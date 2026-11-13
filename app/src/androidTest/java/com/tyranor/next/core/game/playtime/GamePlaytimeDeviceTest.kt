package com.tyranor.next.core.game.playtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.core.engine.LaunchContract
import com.core.tyrano.TyranoActivity
import com.tyranor.next.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real cross-process lifecycle probe; run only against the explicitly isolated debug package. */
@RunWith(AndroidJUnit4::class)
class GamePlaytimeDeviceTest {
    @Test
    fun tyranoForegroundSegmentsCheckpointAndImportIdempotently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Dedicated playtime test package required", context.packageName == TEST_APPLICATION_ID)
        assumeTrue("Explicit playtime device-test opt-in required",
            InstrumentationRegistry.getArguments().getString("playtime_test") == "true")

        val fixtureDirectory = File(context.cacheDir, "playtime_${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        File(fixtureDirectory, "index.html").writeText(
            "<!doctype html><html><body style=\"background:#26384a\"></body></html>",
            Charsets.UTF_8,
        )
        val gameUri = Uri.fromFile(fixtureDirectory).toString()
        val sessionId = "playtime-test-${UUID.randomUUID()}"
        val sessionStartedAt = System.currentTimeMillis()
        val hostIntent = Intent(context, TyranoActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(LaunchContract.PATH, fixtureDirectory.absolutePath)
            putExtra(LaunchContract.GAME_PATH, fixtureDirectory.absolutePath)
            putExtra(LaunchContract.ROOT_URI, gameUri)
            putExtra(LaunchContract.TYPE, "WebOther")
            putExtra(LaunchContract.PLAYTIME_SESSION_ID, sessionId)
            putExtra(LaunchContract.PLAYTIME_SESSION_STARTED_AT, sessionStartedAt)
        }
        val checkpointStore = PlaytimeCheckpointStore(context.noBackupFilesDir)
        val database = GamePlaytimeDatabase.get(context)
        val checkpointRoot = File(context.noBackupFilesDir, "game_playtime")

        try {
            context.startActivity(hostIntent)
            waitFor("Tyrano host foreground") { isTyranoForeground(context).takeIf { it } }
            SystemClock.sleep(1_100L)

            showMainApp(context)
            waitFor("Tyrano host paused") { (!isTyranoForeground(context)).takeIf { it } }
            val firstRows = waitForStoredRows(context, gameUri, minimumCount = 1)
            assertTrue("First real foreground segment should be recorded", firstRows.first().durationMillis > 0L)

            refreshAndImport(context)
            val firstStats = observeStats(context, gameUri)
            assertTrue("Imported playtime should be positive", firstStats.totalMillis > 0L)
            assertTrue("Last session should contain the first segment", firstStats.lastSessionMillis > 0L)
            val firstRecordId = firstRows.first().recordId

            SystemClock.sleep(1_100L)
            refreshAndImport(context)
            assertEquals("Refreshing a closed checkpoint must be idempotent", firstStats, observeStats(context, gameUri))

            // Redispatch this still-running game with the launcher's new UUID. The host must keep
            // the original session identity instead of splitting one active play session.
            val redispatchedIntent = Intent(hostIntent).putExtra(
                LaunchContract.PLAYTIME_SESSION_ID,
                "playtime-test-${UUID.randomUUID()}",
            ).putExtra(LaunchContract.PLAYTIME_SESSION_STARTED_AT, sessionStartedAt + 60_000L)
            context.startActivity(redispatchedIntent)
            waitFor("Tyrano host resumed again") { isTyranoForeground(context).takeIf { it } }
            SystemClock.sleep(1_100L)
            showMainApp(context)
            waitFor("Second Tyrano segment paused") { (!isTyranoForeground(context)).takeIf { it } }
            val resumedRows = waitForStoredRows(context, gameUri, minimumCount = 2)
            assertTrue("Resume should create a distinct checkpoint segment",
                resumedRows.any { it.recordId != firstRecordId && it.durationMillis > 0L })
            assertTrue("Same-game redispatch should retain the original session id and start time",
                resumedRows.all { it.sessionId == sessionId && it.startedAtMillis == sessionStartedAt })

            refreshAndImport(context)
            val resumedStats = observeStats(context, gameUri)
            assertTrue("A resumed segment should increase the total", resumedStats.totalMillis > firstStats.totalMillis)
            assertEquals("Same-game redispatch should append to the original session",
                resumedStats.totalMillis, resumedStats.lastSessionMillis)

            val stableStats = resumedStats
            SystemClock.sleep(1_100L)
            refreshAndImport(context)
            assertEquals("Repeated refresh must not double count imported segments", stableStats, observeStats(context, gameUri))
        } finally {
            // Leave only this isolated host process; never force-stop or kill a user installation.
            runCatching { showMainApp(context) }
            runCatching {
                waitFor("Tyrano host paused for cleanup", 5_000L) {
                    (!isTyranoForeground(context)).takeIf { it }
                }
            }
            killOnlyIsolatedTyranoProcess(context)

            // Remove only this randomized fixture's checkpoints and DB rows.
            checkpointStore.readAll().filter { it.gameUri == gameUri }.forEach { record ->
                AtomicFile(File(checkpointRoot, "${record.recordId}.gpt")).delete()
            }
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM playtime_records WHERE gameUri = ?",
                arrayOf(gameUri),
            )
            fixtureDirectory.deleteRecursively()
        }
    }

    private fun refreshAndImport(context: Context) = runBlocking {
        withTimeout(5_000L) { GamePlaytimeRepository.refresh(context) }
    }

    private fun observeStats(context: Context, gameUri: String): GamePlaytimeStats = runBlocking {
        withTimeout(5_000L) { GamePlaytimeRepository.observe(context, gameUri).first() }
    }

    private fun waitForStoredRows(
        context: Context,
        gameUri: String,
        minimumCount: Int,
    ): List<PlaytimeRecordEntity> = waitFor("$minimumCount imported playtime segment(s)") {
        val rows = runBlocking {
            withTimeout(5_000L) { GamePlaytimeRepository.refresh(context) }
            withTimeout(5_000L) {
                GamePlaytimeDatabase.get(context).records().all().filter { it.gameUri == gameUri }
            }
        }
        rows.takeIf { it.size >= minimumCount && it.any { row -> row.durationMillis > 0L } }
    }

    private fun showMainApp(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun isTyranoForeground(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processName = "${context.packageName}:tyrano"
        return manager.runningAppProcesses.orEmpty().any {
            it.processName == processName && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun killOnlyIsolatedTyranoProcess(context: Context) {
        if (context.packageName != TEST_APPLICATION_ID) return
        val processName = "${TEST_APPLICATION_ID}:tyrano"
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses.orEmpty()
            .firstOrNull { it.processName == processName }
            ?.pid
            ?.takeIf { it > 0 && it != Process.myPid() }
            ?.let(Process::killProcess)
    }

    private fun <T : Any> waitFor(
        description: String,
        timeoutMillis: Long = 15_000L,
        condition: () -> T?,
    ): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            condition()?.let { return it }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private companion object {
        const val TEST_APPLICATION_ID = "com.tyranor.next.playtimetest"
    }
}
