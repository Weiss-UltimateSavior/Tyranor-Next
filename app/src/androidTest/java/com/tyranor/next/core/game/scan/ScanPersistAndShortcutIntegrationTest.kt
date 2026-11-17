package com.tyranor.next.core.game.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.cover.VndbCoverService
import com.tyranor.next.core.cover.persistentCoverDir
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.shortcut.GameShortcutManager
import com.tyranor.next.core.game.storage.GameLibraryDatabase
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.AppStorageNames
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 扫描合并 + 真实 Room 持久化 + 自定义封面切换 + 桌面快捷方式集成链路回归：
 * 1. `EngineScanner.rescanLibrary` 在真实目录与隔离 Room 库中扫描入库；
 * 2. 写入四源远端绑定 ID 与自定义封面后再重扫，验证四源绑定与自定义封面完整保留；
 * 3. 切换新自定义封面时旧图回收、Room 落库一致；
 * 4. `GameShortcutManager` 从持久自定义封面解码生成方形快捷方式图标并执行 pin 请求。
 */
@RunWith(AndroidJUnit4::class)
class ScanPersistAndShortcutIntegrationTest {

    private var context: Context? = null
    private var testRootDir: File? = null
    private var tempImage1: File? = null
    private var tempImage2: File? = null
    private var previousDb: GameLibraryDatabase? = null
    private var testDb: GameLibraryDatabase? = null
    private var dbSwapped = false
    private val testDbName = "test_scan_persist_shortcut_integration.db"
    private val createdCoverFiles = mutableListOf<File>()

    private var prevRoots: String? = null
    private var prevGames: String? = null
    private var prevRecent: String? = null
    private var prevQuick: String? = null
    private var prevFlag: Boolean = false
    private var prevMigrationDone: Boolean = false
    private var prevSkipPersist: Boolean = false
    private var prefsSaved = false

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        context = ctx
        // 先排空 Application.onCreate() 的初始化与残留防抖 mirrorJob，避免读写 prefs 或替换 DB 时发生交错污染
        runBlocking { GameLibraryRepository.flushPrefsMirrorForTest(ctx) }
        val prefs = ctx.getSharedPreferences(AppStorageNames.GAME_SCANNER_PREFS, Context.MODE_PRIVATE)
        prevRoots = prefs.getString("scan_roots", null)
        prevGames = prefs.getString("scan_games", null)
        prevRecent = prefs.getString("recent_games", null)
        prevQuick = prefs.getString("quick_launch", null)
        prevFlag = prefs.getBoolean("storage_migration_v1_done", false)
        prevMigrationDone = GameLibraryRepository.isMigrationDoneForTest()
        prevSkipPersist = GameLibraryFacade.skipPersistForTest
        prefsSaved = true

        prefs.edit()
            .remove("scan_roots")
            .remove("scan_games")
            .remove("recent_games")
            .remove("quick_launch")
            .putBoolean("storage_migration_v1_done", true)
            .commit()

        ctx.deleteDatabase(testDbName)
        val db = GameLibraryDatabase.buildWithProductionConfig(ctx, testDbName)
        testDb = db
        previousDb = GameLibraryDatabase.replaceInstanceForTest(db)
        dbSwapped = true
        GameLibraryRepository.resetMigrationForTest(true)
        GameLibraryFacade.skipPersistForTest = false
        GameLibraryFacade.resetCachesForTest()

        val rootDir = File(ctx.filesDir, "scan_integration_root_${System.nanoTime()}").apply { mkdirs() }
        testRootDir = rootDir
        val gameDir = File(rootDir, "SakuraGame").apply { mkdirs() }
        File(gameDir, "root.pfs").writeBytes(byteArrayOf(0x70, 0x66, 0x38))

        tempImage1 = File(ctx.cacheDir, "integration_cover_1.png").also { writePng(it, 0xFF336699.toInt()) }
        tempImage2 = File(ctx.cacheDir, "integration_cover_2.png").also { writePng(it, 0xFF993366.toInt()) }
    }

    @After
    fun tearDown() {
        val ctx = context ?: return
        try {
            runCatching {
                runBlocking {
                    // 冲洗并取消防抖 mirrorJob，避免 1.5s 后回写覆盖已恢复的 GAME_SCANNER_PREFS
                    GameLibraryRepository.flushPrefsMirrorForTest(ctx)
                }
            }
            createdCoverFiles.forEach { runCatching { it.delete() } }
            createdCoverFiles.clear()
            testRootDir?.let { runCatching { it.deleteRecursively() } }
            tempImage1?.let { runCatching { it.delete() } }
            tempImage2?.let { runCatching { it.delete() } }
            if (prefsSaved) {
                val prefs = ctx.getSharedPreferences(AppStorageNames.GAME_SCANNER_PREFS, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    if (prevRoots == null) remove("scan_roots") else putString("scan_roots", prevRoots)
                    if (prevGames == null) remove("scan_games") else putString("scan_games", prevGames)
                    if (prevRecent == null) remove("recent_games") else putString("recent_games", prevRecent)
                    if (prevQuick == null) remove("quick_launch") else putString("quick_launch", prevQuick)
                    putBoolean("storage_migration_v1_done", prevFlag)
                }.commit()
            }
        } finally {
            if (dbSwapped) {
                GameLibraryDatabase.replaceInstanceForTest(previousDb)
            }
            testDb?.let { runCatching { it.close() } }
            runCatching { ctx.deleteDatabase(testDbName) }
            if (prefsSaved) {
                GameLibraryRepository.resetMigrationForTest(prevMigrationDone)
                GameLibraryFacade.skipPersistForTest = prevSkipPersist
            }
            GameLibraryFacade.resetCachesForTest()
        }
    }

    @Test
    fun rescanPreservesFourSourceBindingsAndCustomCoverThroughShortcutFlow() = runBlocking {
        val ctx = context!!
        val rootDir = testRootDir!!
        val img1 = tempImage1!!
        val img2 = tempImage2!!

        GameLibraryFacade.saveRoot(ctx, rootDir.absolutePath)
        val firstScan = EngineScanner.rescanLibrary(ctx)
        val scanned = firstScan.single { it.title == "SakuraGame" }

        // 写入四源绑定 ID 并设置首个自定义封面
        GameLibraryFacade.updateGameCoverMetadata(ctx, scanned.uri) { current ->
            current.copy(
                vndbId = "v17",
                bangumiId = "4154",
                hikarinagiId = "hkn-17",
                nextmoeId = "207379",
                metadataTitle = "SakuraMeta",
            )
        }
        val withBindings = GameLibraryFacade.loadGames(ctx).single { it.uri == scanned.uri }
        val custom1 = VndbCoverService.saveCustomCover(ctx, withBindings, Uri.fromFile(img1))
        assertNotNull(custom1)
        val cover1File = File(Uri.parse(custom1!!.coverUri!!).path!!).also { createdCoverFiles += it }
        assertTrue(cover1File.exists())
        assertTrue(cover1File.parentFile == persistentCoverDir(ctx))

        // 重扫游戏库：保留四源绑定与自定义封面
        EngineScanner.rescanLibrary(ctx)
        GameLibraryRepository.awaitPendingWrites()
        GameLibraryFacade.resetCachesForTest()
        val afterRescan = GameLibraryFacade.loadGames(ctx).single { it.uri == scanned.uri }
        assertEquals("v17", afterRescan.vndbId)
        assertEquals("4154", afterRescan.bangumiId)
        assertEquals("hkn-17", afterRescan.hikarinagiId)
        assertEquals("207379", afterRescan.nextmoeId)
        assertEquals("SakuraMeta", afterRescan.metadataTitle)
        assertEquals(AppSettingsStore.COVER_SOURCE_CUSTOM, afterRescan.coverSource)
        assertEquals(custom1.coverUri, afterRescan.coverUri)

        // 切换为第二张自定义封面：旧封面文件被回收、新封面持久化并可生成快捷方式图标
        val custom2 = VndbCoverService.saveCustomCover(ctx, afterRescan, Uri.fromFile(img2))
        assertNotNull(custom2)
        assertNotEquals(custom1.coverUri, custom2!!.coverUri)
        val cover2File = File(Uri.parse(custom2.coverUri!!).path!!).also { createdCoverFiles += it }
        assertTrue(cover2File.exists())
        assertFalse(cover1File.exists())

        GameLibraryRepository.awaitPendingWrites()
        GameLibraryFacade.resetCachesForTest()
        val reloaded: ScanGame = GameLibraryFacade.loadGames(ctx).single { it.uri == scanned.uri }
        assertEquals(custom2.coverUri, reloaded.coverUri)

        val shortcutBitmap = GameShortcutManager.decodeSquareIcon(ctx, reloaded.coverUri!!, 192)
        assertNotNull(shortcutBitmap)
        try {
            assertEquals(192, shortcutBitmap!!.width)
            assertEquals(192, shortcutBitmap.height)
        } finally {
            shortcutBitmap?.recycle()
        }

        val pinResult = GameShortcutManager.requestPinShortcut(
            context = ctx,
            game = reloaded,
            launchIntent = Intent("com.tyranor.next.TEST_LAUNCH"),
        )
        assertNotEquals(GameShortcutManager.RequestResult.FAILED, pinResult)
    }

    private fun writePng(file: File, color: Int) {
        val bmp = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        try {
            FileOutputStream(file).use { out ->
                assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bmp.recycle()
        }
    }
}
