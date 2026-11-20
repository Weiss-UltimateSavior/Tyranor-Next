package com.tyranor.next.core.game

import android.content.Context
import com.tyranor.next.core.cover.persistentCoverDir
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameDeletionCleanupTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var noBackupDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        cacheDir = tempFolder.newFolder("cache")
        noBackupDir = tempFolder.newFolder("no_backup")
        context = InMemoryPreferencesContext(
            filesDirProvider = { filesDir },
            cacheDirProvider = { cacheDir },
            noBackupFilesDirProvider = { noBackupDir },
        )
        GameLibraryFacade.skipPersistForTest = true
    }

    @Test
    fun cleanupDeletedGameRemovesManagedCoversAndDerivedCachesWithoutDuplicatingSideEffects() = runBlocking {
        val coverDir = persistentCoverDir(context).apply { mkdirs() }
        val liveCover = File(coverDir, "live_cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val staleCover = File(coverDir, "stale_cover.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val outsideCover = tempFolder.newFile("outside.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }

        val target = ScanGame(
            title = "CleanupTest",
            uri = "file:///games/cleanup_test",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            coverUri = liveCover.toURI().toString(),
            openTime = 1234L,
        )
        val other = ScanGame(
            title = "KeepMe",
            uri = "file:///games/keep_me",
            engine = EngineType.ONS,
            launchTarget = "arc.nsa",
            openTime = 5678L,
        )
        GameLibraryFacade.seedGamesForTest(listOf(target, other))
        GameLibraryFacade.seedDerivedCachesForTest(recent = listOf(target, other), quickLaunch = listOf(target, other))

        val saveClearCalls = AtomicInteger(0)
        val settingsClearCalls = AtomicInteger(0)

        cleanupDeletedGame(
            context = context,
            target = target,
            extraCoverUri = staleCover.toURI().toString(),
            clearSaveSync = { saveClearCalls.incrementAndGet() },
            clearPerGameSettings = { settingsClearCalls.incrementAndGet() },
        )

        assertEquals(1, saveClearCalls.get())
        assertEquals(1, settingsClearCalls.get())
        assertFalse(liveCover.exists())
        assertFalse(staleCover.exists())
        assertEquals(listOf(other), GameLibraryFacade.loadRecentGames(context))
        assertEquals(listOf(other), GameLibraryFacade.loadQuickLaunch(context))

        val outsideTarget = target.copy(coverUri = outsideCover.toURI().toString())
        cleanupDeletedGame(
            context = context,
            target = outsideTarget,
            clearSaveSync = { saveClearCalls.incrementAndGet() },
            clearPerGameSettings = { settingsClearCalls.incrementAndGet() },
        )
        assertTrue(outsideCover.exists())
    }

    @Test
    fun cleanupDeletedGameAggregatesFailuresAndRethrowsCancellationException() = runBlocking {
        val target = ScanGame(
            title = "ErrorTest",
            uri = "file:///games/error_test",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )
        GameLibraryFacade.seedGamesForTest(listOf(target))
        GameLibraryFacade.seedDerivedCachesForTest(recent = listOf(target), quickLaunch = listOf(target))

        try {
            cleanupDeletedGame(
                context = context,
                target = target,
                clearSaveSync = { throw IOException("save fail") },
                clearPerGameSettings = { throw IllegalStateException("prefs fail") },
            )
            fail("expected aggregated exception")
        } catch (expected: IOException) {
            assertEquals("save fail", expected.message)
            assertEquals(1, expected.suppressed.size)
            assertEquals("prefs fail", expected.suppressed[0].message)
            assertTrue(GameLibraryFacade.loadRecentGames(context).isEmpty())
            assertTrue(GameLibraryFacade.loadQuickLaunch(context).isEmpty())
        }

        try {
            cleanupDeletedGame(
                context = context,
                target = target,
                clearSaveSync = { throw CancellationException("cancelled") },
                clearPerGameSettings = { fail("must not run after cancellation") },
            )
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun cleanupDeletedGameRunsAllStepsEvenWhenCallerCancelledMidway() = runBlocking {
        // LOGIC-7：清理步骤（含 clearSaveSync）统一纳入 NonCancellable——调用方协程在清理
        // 途中被取消（VM 销毁窗口）不得让后续步骤被跳过而残留孤儿数据；真实 clearGame 会
        // 观察取消（ensureActive），此处以挂起 + ensureActive 桩还原该行为形状
        val target = ScanGame(
            title = "CancelWindow",
            uri = "file:///games/cancel_window",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )
        val coverFile = File(persistentCoverDir(context).apply { mkdirs() }, "cancel_window_cover.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val coveredTarget = target.copy(coverUri = coverFile.toURI().toString())
        GameLibraryFacade.seedGamesForTest(listOf(coveredTarget))
        GameLibraryFacade.seedDerivedCachesForTest(recent = listOf(coveredTarget), quickLaunch = listOf(coveredTarget))

        val gate = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) {
            cleanupDeletedGame(
                context = context,
                target = coveredTarget,
                clearSaveSync = {
                    gate.await()
                    currentCoroutineContext().ensureActive()
                    ran += "saveSync"
                },
                clearPerGameSettings = { ran += "perGameSettings" },
            )
        }
        job.cancel()
        gate.complete(Unit)
        job.join()

        // 取消窗口内全链完成：存档同步 + 其余清理（含封面文件回收）不得被跳过
        assertEquals(listOf("saveSync", "perGameSettings"), ran)
        assertFalse(coverFile.exists())
        assertTrue(GameLibraryFacade.loadRecentGames(context).isEmpty())
        assertTrue(GameLibraryFacade.loadQuickLaunch(context).isEmpty())
    }
}
