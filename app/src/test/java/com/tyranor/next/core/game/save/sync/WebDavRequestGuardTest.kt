package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebDavRequestGuardTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun refreshDoesNotMoveAfterGuardInvalidatesDuringVerificationGet() = runBlocking {
        val archive = archive("verification", "verified refresh bytes")
        val archiveBytes = archive.readBytes()
        val hash = SaveContentHash.archive(archive)
        val previousAt = 1_700_000_000_000L
        val publicationAt = previousAt + 60_000L
        val previousName = SaveVersionNames.createRemote(previousAt, hash, ZoneOffset.UTC)
        val publicationName = SaveVersionNames.createRemote(
            publicationAt,
            hash,
            ZoneOffset.UTC,
            restored = true,
            previousCreatedAt = previousAt,
        )
        val folders = concurrentSetOf(
            "/dav/TYN-saves/",
            "/dav/TYN-saves/${GAME.engine}/",
            gameFolder(),
        )
        val files = ConcurrentHashMap<String, ByteArray>().apply {
            put(gameFolder() + previousName, archiveBytes)
        }
        val requests = ConcurrentLinkedQueue<ObservedRequest>()
        val getEntered = CompletableDeferred<Unit>()
        val getResponded = CompletableDeferred<Unit>()
        val releaseGet = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { exchange ->
            val request = observed(exchange)
            requests += request
            when (request.method) {
                "MKCOL" -> send(exchange, if (folders.add(request.path)) 201 else 405)
                "PROPFIND" -> sendListing(exchange, request, folders, files, emptyMap())
                "GET" -> {
                    val bytes = files[request.path]
                    if (bytes == null) {
                        send(exchange, 404)
                    } else {
                        getEntered.complete(Unit)
                        if (!await(releaseGet)) {
                            send(exchange, 503)
                        } else {
                            send(exchange, 200, bytes)
                            getResponded.complete(Unit)
                        }
                    }
                }
                else -> send(exchange, 405)
            }
        }
        server.start()
        try {
            val guardFailure = AtomicReference<Throwable?>()
            WebDavClient(config(server)).use { client ->
                client.setRequestAdmission { dispatch ->
                    guardFailure.get()?.let { throw it }
                    dispatch()
                }
                val publication = SaveVersion(publicationName, hash, publicationAt, archive.length(), restored = true)
                val previous = RemoteSaveVersion(previousName, hash, previousAt, archive.length().toLong(), "\"source\"")
                supervisorScope {
                    val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                        client.refresh(GAME, previous, publication, archive)
                    }

                    withTimeout(5_000) { getEntered.await() }
                    val stale = InjectedStaleRequest()
                    guardFailure.set(stale)
                    releaseGet.countDown()

                    val failure = try {
                        withTimeout(5_000) { refresh.await() }
                        null
                    } catch (caught: Throwable) {
                        caught
                    }
                    assertSame(stale, failure)
                    withTimeout(5_000) { getResponded.await() }
                }
            }

            val seen = requests.toList()
            assertTrue(seen.any { it.method == "GET" && it.path == gameFolder() + previousName })
            assertFalse(seen.any { it.method == "MOVE" })
            assertFalse(seen.any { it.method == "PUT" })
        } finally {
            releaseGet.countDown()
            server.stop(0)
        }
    }

    @Test
    fun refreshDoesNotFallbackToPutAfterGuardInvalidatesDuringMove() = runBlocking {
        val archive = archive("move", "move refresh bytes")
        val archiveBytes = archive.readBytes()
        val hash = SaveContentHash.archive(archive)
        val previousAt = 1_700_000_100_000L
        val publicationAt = previousAt + 60_000L
        val previousName = SaveVersionNames.createRemote(previousAt, hash, ZoneOffset.UTC)
        val publicationName = SaveVersionNames.createRemote(
            publicationAt,
            hash,
            ZoneOffset.UTC,
            restored = true,
            previousCreatedAt = previousAt,
        )
        val folders = concurrentSetOf(
            "/dav/TYN-saves/",
            "/dav/TYN-saves/${GAME.engine}/",
            gameFolder(),
        )
        val files = ConcurrentHashMap<String, ByteArray>().apply {
            put(gameFolder() + previousName, archiveBytes)
        }
        val requests = ConcurrentLinkedQueue<ObservedRequest>()
        val moveEntered = CompletableDeferred<Unit>()
        val moveResponded = CompletableDeferred<Unit>()
        val releaseMove = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { exchange ->
            val request = observed(exchange)
            requests += request
            when (request.method) {
                "MKCOL" -> send(exchange, if (folders.add(request.path)) 201 else 405)
                "PROPFIND" -> sendListing(exchange, request, folders, files, emptyMap())
                "GET" -> files[request.path]?.let { send(exchange, 200, it) } ?: send(exchange, 404)
                "MOVE" -> {
                    moveEntered.complete(Unit)
                    if (!await(releaseMove)) {
                        send(exchange, 503)
                    } else {
                        send(exchange, 405)
                        moveResponded.complete(Unit)
                    }
                }
                else -> send(exchange, 405)
            }
        }
        server.start()
        try {
            val guardFailure = AtomicReference<Throwable?>()
            WebDavClient(config(server)).use { client ->
                client.setRequestAdmission { dispatch ->
                    guardFailure.get()?.let { throw it }
                    dispatch()
                }
                val publication = SaveVersion(publicationName, hash, publicationAt, archive.length(), restored = true)
                val previous = RemoteSaveVersion(previousName, hash, previousAt, archive.length().toLong(), "\"source\"")
                supervisorScope {
                    val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                        client.refresh(GAME, previous, publication, archive)
                    }

                    withTimeout(5_000) { moveEntered.await() }
                    val stale = InjectedStaleRequest()
                    guardFailure.set(stale)
                    releaseMove.countDown()

                    val failure = try {
                        withTimeout(5_000) { refresh.await() }
                        null
                    } catch (caught: Throwable) {
                        caught
                    }
                    assertSame(stale, failure)
                    withTimeout(5_000) { moveResponded.await() }
                }
            }

            val seen = requests.toList()
            assertTrue(seen.any { it.method == "MOVE" && it.path == gameFolder() + previousName })
            assertFalse(seen.any { it.method == "PUT" })
        } finally {
            releaseMove.countDown()
            server.stop(0)
        }
    }

    @Test
    fun uploadDoesNotPutAfterGuardInvalidatesDuringEnsureGame() = runBlocking {
        val archive = archive("ensure-game", "ensure game bytes")
        val hash = SaveContentHash.archive(archive)
        val createdAt = 1_700_000_200_000L
        val version = SaveVersion(
            SaveVersionNames.createRemote(createdAt, hash, ZoneOffset.UTC),
            hash,
            createdAt,
            archive.length(),
        )
        val folders = concurrentSetOf()
        val requests = ConcurrentLinkedQueue<ObservedRequest>()
        val mkcolEntered = CompletableDeferred<Unit>()
        val mkcolResponded = CompletableDeferred<Unit>()
        val releaseMkcol = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { exchange ->
            val request = observed(exchange)
            requests += request
            when (request.method) {
                "MKCOL" -> {
                    if (request.path == "/dav/TYN-saves/") {
                        mkcolEntered.complete(Unit)
                        if (!await(releaseMkcol)) {
                            send(exchange, 503)
                        } else {
                            folders.add(request.path)
                            send(exchange, 201)
                            mkcolResponded.complete(Unit)
                        }
                    } else {
                        send(exchange, if (folders.add(request.path)) 201 else 405)
                    }
                }
                "PROPFIND" -> sendListing(exchange, request, folders, emptyMap(), emptyMap())
                else -> send(exchange, 405)
            }
        }
        server.start()
        try {
            val guardFailure = AtomicReference<Throwable?>()
            WebDavClient(config(server)).use { client ->
                client.setRequestAdmission { dispatch ->
                    guardFailure.get()?.let { throw it }
                    dispatch()
                }
                supervisorScope {
                    val upload = async(start = CoroutineStart.UNDISPATCHED) {
                        client.upload(GAME, version, archive)
                    }

                    withTimeout(5_000) { mkcolEntered.await() }
                    val stale = InjectedStaleRequest()
                    guardFailure.set(stale)
                    releaseMkcol.countDown()

                    val failure = try {
                        withTimeout(5_000) { upload.await() }
                        null
                    } catch (caught: Throwable) {
                        caught
                    }
                    assertSame(stale, failure)
                    withTimeout(5_000) { mkcolResponded.await() }
                }
            }

            assertEquals(listOf("MKCOL"), requests.map { it.method })
        } finally {
            releaseMkcol.countDown()
            server.stop(0)
        }
    }

    @Test
    fun uploadDoesNotRepairPutAfterGuardInvalidatesDuringCollisionVerification() = runBlocking {
        val archive = archive("collision", "repair source bytes")
        val sourceBytes = archive.readBytes()
        val corruptBytes = "corrupt remote payload".toByteArray()
        val hash = SaveContentHash.archive(archive)
        val createdAt = 1_700_000_300_000L
        val version = SaveVersion(
            SaveVersionNames.createRemote(createdAt, hash, ZoneOffset.UTC),
            hash,
            createdAt,
            archive.length(),
        )
        val targetPath = gameFolder() + version.id
        val folders = concurrentSetOf(
            "/dav/TYN-saves/",
            "/dav/TYN-saves/${GAME.engine}/",
            gameFolder(),
        )
        val files = ConcurrentHashMap<String, ByteArray>().apply {
            put(targetPath, corruptBytes)
        }
        val etags = mapOf(targetPath to "\"target-v1\"")
        val requests = ConcurrentLinkedQueue<ObservedRequest>()
        val getEntered = CompletableDeferred<Unit>()
        val getResponded = CompletableDeferred<Unit>()
        val releaseGet = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { exchange ->
            val request = observed(exchange)
            requests += request
            when (request.method) {
                "MKCOL" -> send(exchange, if (folders.add(request.path)) 201 else 405)
                "PROPFIND" -> sendListing(exchange, request, folders, files, etags)
                "PUT" -> {
                    exchange.requestBody.use { it.readBytes() }
                    if (request.path == targetPath && request.ifNoneMatch == "*") {
                        send(exchange, 412)
                    } else {
                        files[request.path] = sourceBytes
                        send(exchange, 201)
                    }
                }
                "GET" -> {
                    val bytes = files[request.path]
                    if (bytes == null) {
                        send(exchange, 404)
                    } else if (request.path == targetPath) {
                        getEntered.complete(Unit)
                        if (!await(releaseGet)) {
                            send(exchange, 503)
                        } else {
                            send(exchange, 200, bytes)
                            getResponded.complete(Unit)
                        }
                    } else {
                        send(exchange, 200, bytes)
                    }
                }
                else -> send(exchange, 405)
            }
        }
        server.start()
        try {
            val guardFailure = AtomicReference<Throwable?>()
            WebDavClient(config(server)).use { client ->
                client.setRequestAdmission { dispatch ->
                    guardFailure.get()?.let { throw it }
                    dispatch()
                }
                supervisorScope {
                    val upload = async(start = CoroutineStart.UNDISPATCHED) {
                        client.upload(GAME, version, archive)
                    }

                    withTimeout(5_000) { getEntered.await() }
                    val stale = InjectedStaleRequest()
                    guardFailure.set(stale)
                    releaseGet.countDown()

                    val failure = try {
                        withTimeout(5_000) { upload.await() }
                        null
                    } catch (caught: Throwable) {
                        caught
                    }
                    assertSame(stale, failure)
                    withTimeout(5_000) { getResponded.await() }
                }
            }

            val puts = requests.filter { it.method == "PUT" }
            assertEquals(1, puts.size)
            assertEquals(targetPath, puts.single().path)
            assertEquals("*", puts.single().ifNoneMatch)
            assertNull(puts.single().ifMatch)
            assertTrue(requests.any { it.method == "GET" && it.path == targetPath })
        } finally {
            releaseGet.countDown()
            server.stop(0)
        }
    }

    @Test
    fun serviceIdentityChangeDuringRefreshVerificationStopsNestedMove() = runBlocking {
        val scenario = stalledServiceRefresh { game, service, _ ->
            service.setIdentity(game, "request-guard-changed")
        }

        assertLocalStateChanged(scenario.report)
        assertEquals(0, scenario.report.uploaded)
        assertEquals("request-guard-changed", scenario.service.identity(scenario.game))
        assertTrue(scenario.requests.any { it.method == "GET" && it.path == scenario.remotePath })
        assertFalse(scenario.requests.any { it.method in setOf("MOVE", "PUT", "DELETE") })
    }

    @Test
    fun serviceConfigRevisionChangeDuringRefreshVerificationStopsNestedMove() = runBlocking {
        val scenario = stalledServiceRefresh { _, _, environment ->
            val current = environment.config()
            environment.updateConfig(
                current.copy(
                    serverUrl = current.serverUrl.trimEnd('/') + "-changed/",
                    username = "other-user",
                    revision = current.revision + 1,
                ),
            )
        }

        assertLocalStateChanged(scenario.report)
        assertEquals(0, scenario.report.uploaded)
        assertEquals(scenario.initialConfigRevision + 1, scenario.environment.config().revision)
        assertEquals("other-user", scenario.environment.config().username)
        assertTrue(scenario.requests.any { it.method == "GET" && it.path == scenario.remotePath })
        assertFalse(scenario.requests.any { it.method in setOf("MOVE", "PUT", "DELETE") })
    }

    private suspend fun stalledServiceRefresh(
        mutate: suspend (ScanGame, SaveSyncService, MinimalServiceEnvironment) -> Unit,
    ): StalledServiceRefresh {
        val root = temp.newFolder("service-sync")
        val cache = temp.newFolder("service-cache")
        val game = ScanGame(
            title = "guardgame",
            uri = "file:///guardgame",
            engine = EngineType.ONS,
            launchTarget = "__game_dir__",
        )
        val activeBeforeSelection = archive("service-active-before", "before selected bytes")
        val selectedArchive = archive("service-selected", "selected bytes")
        val selectedBytes = selectedArchive.readBytes()
        val selectedHash = SaveContentHash.archive(selectedArchive)
        val selectedAt = 1_700_000_400_000L
        val selected = SaveSnapshotStore(File(root, "versions")).add(
            SaveContentHash.text(game.uri),
            selectedArchive,
            selectedAt,
            SaveVersionSource.CLOUD,
        )
        val remoteName = SaveVersionNames.createRemote(selectedAt, selectedHash, ZoneOffset.UTC)
        val remotePath = gameFolder() + remoteName
        val folders = concurrentSetOf(
            "/dav/TYN-saves/",
            "/dav/TYN-saves/${GAME.engine}/",
            gameFolder(),
        )
        val files = ConcurrentHashMap<String, ByteArray>().apply {
            put(remotePath, selectedBytes)
        }
        val etags = mapOf(remotePath to "\"remote-selected\"")
        val requests = ConcurrentLinkedQueue<ObservedRequest>()
        val getEntered = CompletableDeferred<Unit>()
        val getResponded = CompletableDeferred<Unit>()
        val releaseGet = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { exchange ->
            val request = observed(exchange)
            requests += request
            when (request.method) {
                "MKCOL" -> send(exchange, if (folders.add(request.path)) 201 else 405)
                "PROPFIND" -> sendListing(exchange, request, folders, files, etags)
                "GET" -> {
                    val bytes = files[request.path]
                    if (bytes == null) {
                        send(exchange, 404)
                    } else if (request.path == remotePath) {
                        getEntered.complete(Unit)
                        if (!await(releaseGet)) {
                            send(exchange, 503)
                        } else {
                            send(exchange, 200, bytes)
                            getResponded.complete(Unit)
                        }
                    } else {
                        send(exchange, 200, bytes)
                    }
                }
                else -> send(exchange, 405)
            }
        }
        server.start()
        try {
            val syncConfig = config(server).copy(mode = SaveSyncMode.BIDIRECTIONAL, enabled = true)
            val environment = MinimalServiceEnvironment(cache, syncConfig, game, activeBeforeSelection)
            val service = SaveSyncService(root, environment)
            service.setIdentity(game, GAME.identity)
            assertEquals(SaveActivationResult.APPLIED, service.activateVersion(game, selected.id))
            assertEquals(selectedHash, environment.activeHash())
            assertTrue("Activation unexpectedly used HTTP", requests.isEmpty())

            lateinit var report: SaveSyncReport
            supervisorScope {
                val sync = async(start = CoroutineStart.UNDISPATCHED) {
                    service.syncGame(game, SaveSyncDirection.UPLOAD)
                }
                withTimeout(5_000) { getEntered.await() }

                try {
                    mutate(game, service, environment)
                } finally {
                    releaseGet.countDown()
                }

                report = withTimeout(5_000) { sync.await() }
                withTimeout(5_000) { getResponded.await() }
            }
            return StalledServiceRefresh(
                game = game,
                service = service,
                environment = environment,
                report = report,
                requests = requests.toList(),
                remotePath = remotePath,
                initialConfigRevision = syncConfig.revision,
            )
        } finally {
            releaseGet.countDown()
            server.stop(0)
        }
    }

    private fun assertLocalStateChanged(report: SaveSyncReport) {
        assertEquals(0, report.busy)
        assertEquals(listOf(SaveSyncError.LOCAL_STATE_CHANGED), report.failures.map { it.error })
    }

    private fun archive(label: String, content: String): File {
        val file = temp.newFile("$label.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("save.bin").apply { time = 1_700_000_000_000L })
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    private fun gameFolder(): String = "/dav/TYN-saves/${GAME.engine}/${GAME.folderName}/"

    private fun config(server: HttpServer) =
        WebDavConfig("http://127.0.0.1:${server.address.port}/dav/", "user", "password")

    private fun sendListing(
        exchange: HttpExchange,
        request: ObservedRequest,
        folders: Set<String>,
        files: Map<String, ByteArray>,
        etags: Map<String, String>,
    ) {
        if (request.path !in folders) {
            send(exchange, 404)
            return
        }
        val children = if (request.depth == "1") {
            files.entries.sortedBy { it.key }.mapNotNull { (path, bytes) ->
                if (path.substringBeforeLast('/') + "/" != request.path) null
                else response(encodedHref(path), false, bytes.size, etags[path])
            }.joinToString("")
        } else {
            ""
        }
        val body = multistatus(response(encodedHref(request.path), true) + children).toByteArray()
        send(exchange, 207, body)
    }

    private fun observed(exchange: HttpExchange) = ObservedRequest(
        method = exchange.requestMethod,
        path = exchange.requestURI.path,
        depth = exchange.requestHeaders.getFirst("Depth"),
        ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match"),
        ifMatch = exchange.requestHeaders.getFirst("If-Match"),
    )

    private fun await(latch: CountDownLatch): Boolean =
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun send(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0)) {
        try {
            if (body.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        } finally {
            exchange.close()
        }
    }

    private class MinimalServiceEnvironment(
        override val cacheDirectory: File,
        webDavConfig: WebDavConfig,
        private val game: ScanGame,
        initialActive: File,
    ) : SaveSyncEnvironment {
        private val lock = Any()
        private var active = initialActive.copyTo(File(cacheDirectory, "active-initial.zip"), overwrite = true)
        private var currentHash = SaveContentHash.archive(active)
        private var importIndex = 0
        @Volatile
        private var currentConfig = webDavConfig

        override val enabled = true

        override fun config(): WebDavConfig = currentConfig
        override fun games(): List<ScanGame> = listOf(game)
        override fun available(game: ScanGame): Boolean = game.uri == this.game.uri
        override fun running(game: ScanGame): Boolean = false

        override suspend fun fingerprint(game: ScanGame): String? = synchronized(lock) { currentHash }

        override suspend fun export(game: ScanGame, archive: File) {
            synchronized(lock) {
                active.copyTo(archive, overwrite = true)
            }
        }

        override suspend fun import(game: ScanGame, archive: File, activeKey: String, committed: () -> Unit) {
            val destination = synchronized(lock) {
                File(cacheDirectory, "active-import-${importIndex++}.zip")
            }
            archive.copyTo(destination, overwrite = true)
            val hash = SaveContentHash.archive(destination)
            synchronized(lock) {
                active = destination
                currentHash = hash
            }
            committed()
        }

        override fun completeImport(game: ScanGame) = Unit
        override fun needsManualRecovery(game: ScanGame): Boolean = false
        override fun repairManualSelection(game: ScanGame, hasCurrent: Boolean): Boolean = true
        override suspend fun recordRestored(game: ScanGame, hash: String, activeKey: String): Boolean = true

        override fun openClient(config: WebDavConfig): SaveSyncTransport =
            WebDavSaveSyncTransport(WebDavClient(config, cacheDirectory) { enabled })

        fun activeHash(): String = synchronized(lock) { currentHash }
        fun updateConfig(config: WebDavConfig) {
            currentConfig = config
        }
    }

    private class InjectedStaleRequest : RuntimeException("request became stale")

    private data class StalledServiceRefresh(
        val game: ScanGame,
        val service: SaveSyncService,
        val environment: MinimalServiceEnvironment,
        val report: SaveSyncReport,
        val requests: List<ObservedRequest>,
        val remotePath: String,
        val initialConfigRevision: Long,
    )

    private data class ObservedRequest(
        val method: String,
        val path: String,
        val depth: String?,
        val ifNoneMatch: String?,
        val ifMatch: String?,
    )

    companion object {
        private val GAME = RemoteSaveGame("ONS", "request-guard")

        private fun concurrentSetOf(vararg values: String): MutableSet<String> =
            ConcurrentHashMap.newKeySet<String>().apply { addAll(values) }

        private fun encodedHref(path: String): String = URI(null, null, path, null).toASCIIString()

        private fun multistatus(inner: String) = "<d:multistatus xmlns:d=\"DAV:\">$inner</d:multistatus>"

        private fun response(
            href: String,
            collection: Boolean,
            size: Int = 0,
            etag: String? = null,
        ) = "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>" +
            (if (collection) "<d:collection/>" else "") +
            "</d:resourcetype><d:getcontentlength>$size</d:getcontentlength>" +
            (etag?.let { "<d:getetag>${it.replace("\"", "&quot;")}</d:getetag>" }.orEmpty()) +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }
}
