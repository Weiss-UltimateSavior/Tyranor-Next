package com.tyranor.next.core.cover

import com.sun.net.httpserver.HttpServer
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 覆盖 Gap：
 * 1. `VndbCoverService.saveCustomCoverFromStream`：原子写临时文件 + 内容哈希命名 + 同名覆盖刷新 URI 并删除旧图 +
 *    失败/取消/空流不破坏既有封面 + 不在库兜底；
 * 2. `CoverImageCache.download`：真实 HTTP 字节流读取（含 `read == 0` 分片与超限 `MAX_COVER_BYTES` 拦截）、
 *    缓存命中与无残留 `.tmp`；
 * 3. `CoverScraperService.searchCandidatesForBinding(..., requireCover = false)` 保留 Bangumi 无封面条目及非法来源拒答；
 * 4. `CoverScrapeTaskManager.start` 重入保护与 `clearFinished` 原子清态。
 */
class CustomCoverAndDownloadTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule(installInMemoryCipher = true)

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var context: InMemoryPreferencesContext

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        cacheDir = tempFolder.newFolder("cache")
        context = InMemoryPreferencesContext(
            filesDirProvider = { filesDir },
            cacheDirProvider = { cacheDir },
        )
        GameLibraryFacade.skipPersistForTest = true
        GameLibraryFacade.seedGamesForTest(emptyList())
    }

    @Test
    fun saveCustomCoverWritesHashedUriAndReplacesSameExtensionWithoutDeletingExistingOnFailure() {
        val baseGame = ScanGame(
            title = "CustomGame",
            uri = "content://games/custom_one",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            openTime = 555L,
        )
        GameLibraryFacade.seedGamesForTest(listOf(baseGame))
        GameLibraryFacade.seedDerivedCachesForTest(
            recent = listOf(baseGame),
            quickLaunch = listOf(baseGame),
        )

        val firstBytes = byteArrayOf(1, 2, 3, 4, 5)
        val firstSaved = VndbCoverService.saveCustomCoverFromStream(
            context = context,
            game = baseGame,
            ext = "jpg",
            openStream = { ByteArrayInputStream(firstBytes) },
            toUriString = { it.toURI().toString() },
        )
        assertNotNull(firstSaved)
        val firstUri = firstSaved!!.coverUri!!
        val firstFile = File(java.net.URI(firstUri).path)
        assertTrue(firstFile.exists())
        assertArrayEquals(firstBytes, firstFile.readBytes())
        assertEquals(AppSettingsStore.COVER_SOURCE_CUSTOM, firstSaved.coverSource)

        // 中途读流抛出 IOException：原有封面文件与库记录必须完整保留，且不留 .tmp 文件
        val failed = VndbCoverService.saveCustomCoverFromStream(
            context = context,
            game = firstSaved,
            ext = "jpg",
            openStream = {
                object : InputStream() {
                    private var count = 0
                    override fun read(): Int {
                        if (count++ > 0) throw IOException("simulated read error")
                        return 0x42
                    }
                }
            },
            toUriString = { it.toURI().toString() },
        )
        assertNull(failed)
        assertTrue("existing cover must survive failed replacement", firstFile.exists())
        assertArrayEquals(firstBytes, firstFile.readBytes())
        assertTrue(persistentCoverDir(context).listFiles { f -> f.name.endsWith(".tmp") }.orEmpty().isEmpty())

        // 空流同样返回 null 且不破坏既有封面
        val emptyResult = VndbCoverService.saveCustomCoverFromStream(
            context = context,
            game = firstSaved,
            ext = "jpg",
            openStream = { ByteArrayInputStream(ByteArray(0)) },
            toUriString = { it.toURI().toString() },
        )
        assertNull(emptyResult)
        assertTrue(firstFile.exists())

        // CancellationException 原样透传，且既有封面保留
        assertThrows(CancellationException::class.java) {
            VndbCoverService.saveCustomCoverFromStream(
                context = context,
                game = firstSaved,
                ext = "jpg",
                openStream = { throw CancellationException("cancelled") },
                toUriString = { it.toURI().toString() },
            )
        }
        assertTrue(firstFile.exists())

        // 同扩展名（.jpg）更换另一张图：生成不同内容哈希的新 URI（刷新缓存键），更新派生缓存并删除旧图
        // 同时验证 InputStream.read(buffer, off, len) 返回 0 分片的续读处理
        val secondBytes = byteArrayOf(9, 8, 7, 6, 5, 4)
        val secondSaved = VndbCoverService.saveCustomCoverFromStream(
            context = context,
            game = firstSaved,
            ext = "jpg",
            openStream = {
                object : InputStream() {
                    private var idx = 0
                    private var emittedZeroChunk = false
                    override fun read(): Int = if (idx < secondBytes.size) secondBytes[idx++].toInt() and 0xFF else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!emittedZeroChunk) {
                            emittedZeroChunk = true
                            return 0
                        }
                        if (idx >= secondBytes.size) return -1
                        val n = minOf(len, secondBytes.size - idx)
                        System.arraycopy(secondBytes, idx, b, off, n)
                        idx += n
                        return n
                    }
                }
            },
            toUriString = { it.toURI().toString() },
        )
        assertNotNull(secondSaved)
        val secondUri = secondSaved!!.coverUri!!
        assertNotEquals(firstUri, secondUri)
        val secondFile = File(java.net.URI(secondUri).path)
        assertTrue(secondFile.exists())
        assertArrayEquals(secondBytes, secondFile.readBytes())
        assertFalse("old same-extension cover must be deleted after replacement", firstFile.exists())
        assertEquals(secondUri, GameLibraryFacade.loadRecentGames(context).single().coverUri)
        assertEquals(555L, GameLibraryFacade.loadRecentGames(context).single().openTime)
        assertEquals(secondUri, GameLibraryFacade.loadQuickLaunch(context).single().coverUri)
    }

    @Test
    fun saveCustomCoverWhenGameNotInLibraryReturnsMemoryCopyAndCleansOldCover() {
        val coversDir = persistentCoverDir(context).apply { mkdirs() }
        val oldFile = File(coversDir, "old_custom.jpg").apply { writeText("old") }
        val detachedGame = ScanGame(
            title = "Detached",
            uri = "content://games/not_in_db",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            coverUri = oldFile.toURI().toString(),
        )
        val saved = VndbCoverService.saveCustomCoverFromStream(
            context = context,
            game = detachedGame,
            ext = "png",
            openStream = { ByteArrayInputStream(byteArrayOf(7, 7, 7)) },
            toUriString = { it.toURI().toString() },
        )
        assertNotNull(saved)
        assertEquals(AppSettingsStore.COVER_SOURCE_CUSTOM, saved!!.coverSource)
        assertFalse(oldFile.exists())
        assertTrue(File(java.net.URI(saved.coverUri!!).path).exists())
    }

    @Test
    fun coverImageCacheDownloadStreamsBytesEnforcesLimitsAndCleansTempFiles() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val payload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        var hits = 0
        server.createContext("/ok.png") { exchange ->
            hits += 1
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/empty.png") { exchange ->
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.close()
        }
        server.createContext("/too-big.png") { exchange ->
            exchange.sendResponseHeaders(200, 21L * 1024L * 1024L)
            exchange.responseBody.close()
        }
        server.createContext("/stream-too-big.png") { exchange ->
            // 分块传输（Content-Length 未知），流式累计字节超过 MAX_COVER_BYTES 后必须中止并清理临时文件
            exchange.sendResponseHeaders(200, 0L)
            runCatching {
                val chunk = ByteArray(1024 * 1024) { 0x5A }
                exchange.responseBody.use { out ->
                    repeat(21) { out.write(chunk) }
                }
            }
        }
        server.createContext("/not-found.png") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val uri = CoverImageCache.download(
                context = context,
                imageUrl = "$base/ok.png",
                prefix = "test_cover",
                toUriString = { it.toURI().toString() },
            )
            assertNotNull(uri)
            assertArrayEquals(payload, File(java.net.URI(uri!!).path).readBytes())
            assertEquals(1, hits)

            // 第二次命中本地文件缓存，不再发 HTTP
            val cachedUri = CoverImageCache.download(
                context = context,
                imageUrl = "$base/ok.png",
                prefix = "test_cover",
                toUriString = { it.toURI().toString() },
            )
            assertEquals(uri, cachedUri)
            assertEquals(1, hits)

            assertNull(
                CoverImageCache.download(
                    context = context,
                    imageUrl = "$base/empty.png",
                    prefix = "test_empty",
                    toUriString = { it.toURI().toString() },
                ),
            )
            assertNull(
                CoverImageCache.download(
                    context = context,
                    imageUrl = "$base/too-big.png",
                    prefix = "test_big",
                    toUriString = { it.toURI().toString() },
                ),
            )
            assertNull(
                CoverImageCache.download(
                    context = context,
                    imageUrl = "$base/stream-too-big.png",
                    prefix = "test_stream_big",
                    toUriString = { it.toURI().toString() },
                ),
            )
            assertNull(
                CoverImageCache.download(
                    context = context,
                    imageUrl = "$base/not-found.png",
                    prefix = "test_404",
                    toUriString = { it.toURI().toString() },
                ),
            )
            assertTrue(persistentCoverDir(context).listFiles { f -> f.name.endsWith(".tmp") }.orEmpty().isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun searchCandidatesForBindingAllowsCoverlessBangumiEntriesAndRejectsInvalidSources() {
        testHttpRoundTrip = { _, _, _, _ ->
            HttpJsonResult(
                200,
                JSONObject().put(
                    "data",
                    JSONArray().put(
                        JSONObject()
                            .put("id", 98765)
                            .put("type", 4)
                            .put("name", "Coverless Title")
                            .put("name_cn", "无封面标题"),
                    ),
                ),
            )
        }

        val bindingHits = CoverScraperService.searchCandidatesForBinding(
            context = context,
            source = AppSettingsStore.COVER_SOURCE_BANGUMI,
            keyword = "Coverless",
            limit = 5,
        )
        assertEquals(listOf("98765"), bindingHits.map { it.id })
        assertEquals("", bindingHits.single().coverUrl)

        val coverSearch = CoverScraperService.searchCoverCandidates(
            context = context,
            source = AppSettingsStore.COVER_SOURCE_BANGUMI,
            keyword = "Coverless",
            limit = 5,
        )
        assertTrue(coverSearch is CoverSearchResult.Success)
        assertTrue((coverSearch as CoverSearchResult.Success).candidates.isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            CoverScraperService.searchCandidatesForBinding(
                context = context,
                source = AppSettingsStore.COVER_SOURCE_STEAM,
                keyword = "Half-Life",
                limit = 5,
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coverScrapeTaskManagerPreventsReentrantStartAndClearsFinishedAtomically() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            AppSettingsStore.DEFAULT_COVER_SCRAPER_SOURCES.forEach { source ->
                AppSettingsStore.setCoverScraperSourceEnabled(context, source, source == AppSettingsStore.COVER_SOURCE_BANGUMI)
            }
            AppSettingsStore.setCoverScraperSourceOrder(context, listOf(AppSettingsStore.COVER_SOURCE_BANGUMI))
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            testHttpRoundTrip = { _, _, _, _ ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                HttpJsonResult(200, JSONObject().put("data", JSONArray()))
            }
            val game = ScanGame(
                title = "BatchTest",
                uri = "content://games/batch_1",
                engine = EngineType.KIRIKIRI,
                launchTarget = "data.xp3",
            )
            GameLibraryFacade.seedGamesForTest(listOf(game))

            assertTrue(CoverScrapeTaskManager.start(context, listOf(game)))
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFalse("second start while running must be rejected", CoverScrapeTaskManager.start(context, listOf(game)))
            } finally {
                release.countDown()
            }

            val finished = runBlocking {
                withTimeout(5_000) {
                    CoverScrapeTaskManager.state.first { !it.running && it.eventId > 0L }
                }
            }
            assertFalse(finished.running)
            assertTrue(finished.eventId > 0L)
            assertNotNull(finished.result)

            CoverScrapeTaskManager.clearFinished(finished.eventId)
            assertNull(CoverScrapeTaskManager.state.value.result)
            assertEquals(finished.eventId, CoverScrapeTaskManager.acknowledgedEventId.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun deleteCachedCoverAndSaveCustomCoverRespectDirectorySchemeSizeAndConcurrentDelete() {
        val coversDir = persistentCoverDir(context).apply { mkdirs() }
        val inDir = File(coversDir, "cached_1.jpg").apply { writeText("img") }
        val outside = File(filesDir, "outside.jpg").apply { writeText("keep") }

        // 不得删除 cacheDir 自身、目录外文件或非 file: scheme
        CoverImageCache.deleteCachedCover(context, coversDir.toURI().toString())
        assertTrue(coversDir.isDirectory)
        CoverImageCache.deleteCachedCover(context, "content://com.example/files/covers_remote/cached_1.jpg")
        assertTrue(inDir.exists())
        CoverImageCache.deleteCachedCover(context, outside.toURI().toString())
        assertTrue(outside.exists())
        CoverImageCache.deleteCachedCover(context, inDir.toURI().toString())
        assertFalse(inDir.exists())

        // 超出 MAX_COVER_BYTES 上限被拦截且不留 .tmp
        val game = ScanGame(
            title = "Oversize",
            uri = "content://games/oversize_1",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )
        GameLibraryFacade.seedGamesForTest(listOf(game))
        val oversizeStream = object : InputStream() {
            private var emitted = 0L
            override fun read(): Int = if (emitted++ < CoverImageCache.MAX_COVER_BYTES + 16L) 0x11 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val remaining = CoverImageCache.MAX_COVER_BYTES + 16L - emitted
                if (remaining <= 0L) return -1
                val chunk = minOf(len.toLong(), remaining).toInt()
                java.util.Arrays.fill(b, off, off + chunk, 0x11.toByte())
                emitted += chunk
                return chunk
            }
        }
        assertNull(
            VndbCoverService.saveCustomCoverFromStream(
                context = context,
                game = game,
                ext = "jpg",
                openStream = { oversizeStream },
                toUriString = { it.toURI().toString() },
            ),
        )
        assertTrue(coversDir.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty().isEmpty())

        // 并发删游（wasInLibrary == true 但落库前已被移出库）：返回 null 且不遗留新建封面文件
        val concurrentDeleteResult = VndbCoverService.saveCustomCoverFromStream(
            context = context,
            game = game,
            ext = "png",
            openStream = {
                GameLibraryFacade.seedGamesForTest(emptyList())
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            },
            toUriString = { it.toURI().toString() },
        )
        assertNull(concurrentDeleteResult)
        assertTrue(coversDir.listFiles().orEmpty().isEmpty())
    }
}
