package com.tyranor.next.core.game.save.sync

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.os.Process
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.core.engine.LaunchContract
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryDatabase
import com.tyranor.next.ui.save.WebDavSettingsActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opt-in Android integration. All saves and HTTP objects are synthetic and owned by this test. */
@RunWith(AndroidJUnit4::class)
class SaveSyncDeviceTest {
    @Test
    fun encryptedSettingsAndRealTransfersPreserveActiveSaveUntilSelection() = runBlocking {
        withFixture { fixture ->
            val service = fixture.service
            val first = fixture.write("source-S")
            assertClean(service.syncGame(fixture.game, SaveSyncDirection.UPLOAD), uploaded = 1)
            val selected = service.listVersions(fixture.game).single { it.contentHash == first }
            val second = fixture.write("second-version")
            assertClean(service.syncGame(fixture.game, SaveSyncDirection.UPLOAD), uploaded = 1)
            fixture.write("current-unpublished")
            val current = fixture.slot.readText()
            assertClean(service.syncGame(fixture.game, SaveSyncDirection.PULL))
            assertEquals(current, fixture.slot.readText())

            assertEquals(SaveActivationResult.APPLIED, service.activateVersion(fixture.game, selected.id))
            assertEquals("source-S", fixture.slot.readText())
            assertClean(service.syncGame(fixture.game, SaveSyncDirection.UPLOAD), uploaded = 1)
            val next = fixture.write("played-N")
            assertClean(service.syncGame(fixture.game, SaveSyncDirection.UPLOAD), uploaded = 1)
            val cloud = fixture.cloudVersions()
            assertTrue(cloud.any { it.contentHash == second })
            assertFalse(cloud.any { it.contentHash == first })
            val promoted = cloud.single { it.contentHash == next }
            assertTrue(promoted.restored)
            assertEquals(selected.createdAt, promoted.previousCreatedAt)
            assertTrue(fixture.server.requests.filter { it.method == "PUT" }
                .all { it.header("If-None-Match") == "*" })
            assertNull(fixture.server.failure)
        }
    }

    @Test
    fun failedUploadRetriesAndEvictedCloudHeadDownloadsAgain() = runBlocking {
        withFixture { fixture ->
            val hash = fixture.write("retryable-content")
            fixture.server.failNext("PUT", 500)
            assertTrue(fixture.service.syncGame(fixture.game, SaveSyncDirection.UPLOAD).failures.isNotEmpty())
            assertClean(fixture.service.syncGame(fixture.game, SaveSyncDirection.UPLOAD), uploaded = 1)
            val remote = fixture.cloudVersions().single { it.contentHash == hash }
            val key = SaveContentHash.text(fixture.game.uri)
            val history = SaveSnapshotStore(File(fixture.context.noBackupFilesDir, "save_sync/versions"))
            val cached = history.list(key).single { it.contentHash == hash }
            assertTrue(history.file(key, cached.id).delete())
            fixture.server.clearRequests()
            assertClean(fixture.service.syncGame(fixture.game, SaveSyncDirection.PULL))
            assertTrue(history.list(key).any { it.contentHash == remote.contentHash && history.isValid(key, it) })
            assertTrue(fixture.server.requestCount("GET") > 0)
            assertEquals("retryable-content", fixture.slot.readText())
            assertNull(fixture.server.failure)
        }
    }

    @Test
    fun corruptActivationJournalRescuesProtectedZipAfterFeatureIsDisabled() = runBlocking {
        withFixture { fixture ->
            val currentHash = fixture.write("current-active")
            val archive = File(fixture.root, "protected.zip")
            ZipOutputStream(archive.outputStream()).use {
                it.putNextEntry(ZipEntry("slot.dat"))
                it.write("protected-source".toByteArray())
                it.closeEntry()
            }
            val hash = SaveContentHash.archive(archive)
            val key = SaveContentHash.text(fixture.game.uri)
            val journalRoot = File(fixture.context.noBackupFilesDir, "save_sync/pending")
            val journal = SaveActivationJournal(journalRoot)
            val time = System.currentTimeMillis()
            journal.prepare(key, SaveVersion(SaveVersionNames.create(time, hash), hash, time, archive.length()),
                archive, currentHash)
            File(journalRoot, "records/$key.json").writeText("{damaged")
            WebDavSettingsStore.setEnabled(fixture.context, false)
            assertTrue(fixture.service.needsSelectionRecovery(fixture.game))
            fixture.service.recoverSelection(fixture.game)
            assertEquals("current-active", fixture.slot.readText())
            assertFalse(fixture.service.needsSelectionRecovery(fixture.game))
            WebDavSettingsStore.setEnabled(fixture.context, true)
            assertTrue(fixture.service.listVersions(fixture.game).any { it.contentHash == hash })
        }
    }

    @Test
    fun launchFailureClearsTicketAndNativeGraceWithoutUploading() = runBlocking {
        withFixture { fixture ->
            fixture.write("launch-baseline")
            fixture.configure(SaveSyncMode.UPLOAD_ONLY)
            val ticket = SaveSyncLocks.active(fixture.game.uri) {
                SaveSyncRuntime.prepareLaunch(fixture.context, fixture.game)
            }
            assertNotNull(ticket)
            assertTrue(SaveSyncRuntime.isGameRunning(fixture.context, fixture.game))
            SaveSyncRuntime.finishLaunch(fixture.context, fixture.game, ticket, success = false)
            assertFalse(SaveSyncRuntime.isGameRunning(fixture.context, fixture.game))
            assertFalse(SaveSyncRuntime.hasPending(fixture.context))
            assertEquals(0, fixture.server.requestCount("PUT"))
        }
    }

    @Test
    fun explicitRestoreCanFlushWithoutEverLaunchingAGameHost() = runBlocking {
        clearOwnedProbeEvidence()
        withFixture { fixture ->
            val hash = fixture.write("selected-before-first-launch")
            assertClean(fixture.service.syncGame(fixture.game, SaveSyncDirection.UPLOAD), uploaded = 1)
            val selected = fixture.service.listVersions(fixture.game).single { it.contentHash == hash }
            fixture.write("current-before-first-launch")
            fixture.configure(SaveSyncMode.UPLOAD_ONLY)
            assertEquals(SaveActivationResult.APPLIED,
                fixture.service.activateVersion(fixture.game, selected.id))
            assertFalse(SaveSyncRuntime.isGameRunning(fixture.context, fixture.game))
            assertClean(SaveSyncRuntime.flushPending(fixture.context))
            assertFalse(SaveSyncRuntime.hasPending(fixture.context))
            assertEquals("selected-before-first-launch", fixture.slot.readText())
        }
    }

    @Test(timeout = 150_000)
    fun realCachedSecondaryHostAndWorkManagerUploadOnlyChangedSession() = runBlocking {
        val context = acceptanceContext()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assumeTrue("Automatic work requires an unmetered network", connectivity
            .getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)
        assumeTrue("Build with -PwebdavDeviceProbe=true", runCatching {
            context.packageManager.getActivityInfo(android.content.ComponentName(context.packageName, PROBE_CLASS), 0)
        }.isSuccess)
        // Return to the actual app after host exit. Some OEMs freeze all test-process timers
        // when no app Activity exists; this makes no changes to global battery settings.
        ActivityScenario.launch<WebDavSettingsActivity>(WebDavSettingsActivity.createIntent(context)).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            withFixture { fixture ->
                stage("worker: fixture ready")
                fixture.write("unchanged-session")
                fixture.configure(SaveSyncMode.UPLOAD_ONLY)
                fixture.launchProbe()
                stage("worker: unchanged host launched")
                await(60_000) { !SaveSyncRuntime.hasPending(fixture.context) }
                stage("worker: unchanged session cleared")
                assertEquals(0, fixture.server.requestCount("PUT"))
                val record = fixture.hostRecord()
                assertEquals(0, record.getInt("activities"))
                assertTrue(File("/proc/${record.getInt("pid")}").exists())
                assertFalse(SaveSyncRuntime.isGameRunning(fixture.context, fixture.game))

                fixture.launchProbe()
                stage("worker: changed host launched")
                fixture.write("changed-session")
                stage("worker: changed bytes written")
                await(60_000) { !SaveSyncRuntime.hasPending(fixture.context) && fixture.server.requestCount("PUT") > 0 }
                stage("worker: changed session uploaded")
                await(15_000) {
                    WorkManager.getInstance(fixture.context)
                        .getWorkInfosForUniqueWork(SaveSyncWorker.WORK_NAME_EXIT_NOW)
                        .get(5, TimeUnit.SECONDS).any { it.state == WorkInfo.State.SUCCEEDED }
                }
                assertEquals(1, fixture.cloudVersions().size)
                assertEquals("changed-session", fixture.slot.readText())
                assertNull(fixture.server.failure)
            }
        }
    }

    private fun stage(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("webdav_stage", message)
        })
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(acceptanceContext())
        try {
            fixture.prepare()
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun acceptanceContext(): Context {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        assumeTrue("Dedicated acceptance package required",
            context.packageName.startsWith("com.tyranor.next.webdavacceptance"))
        assumeTrue("Explicit device acceptance opt-in required",
            InstrumentationRegistry.getArguments().getString("webdav_device_acceptance") == "true")
        return context
    }

    private suspend fun clearOwnedProbeEvidence() {
        val context = acceptanceContext()
        val processName = context.packageName + ":webdav_acceptance_host"
        val hosts = SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/hosts"))
        val key = SaveContentHash.text(processName)
        val processes = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val probe = processes.runningAppProcesses.orEmpty().singleOrNull {
            it.processName == processName && it.uid == Process.myUid()
        }
        if (probe != null) {
            // Other methods may leave this test-only process cached in the same runner.
            Process.killProcess(probe.pid)
            await(5_000) { processes.runningAppProcesses.orEmpty().none { it.pid == probe.pid } }
        }
        hosts.remove(key)
        assertTrue("The first-host regression must start without any host evidence", hosts.keys().isEmpty())
    }

    private fun assertClean(report: SaveSyncReport, uploaded: Int? = null) {
        assertTrue(report.toString(), report.failures.isEmpty())
        assertEquals(report.toString(), 0, report.busy)
        if (uploaded != null) assertEquals(report.toString(), uploaded, report.uploaded)
    }

    private suspend fun await(timeout: Long, condition: () -> Boolean) = withTimeout(timeout) {
        while (!condition()) delay(200)
    }

    private inner class Fixture(val context: Context) {
        val root = File(context.filesDir, "webdav_acceptance_${UUID.randomUUID()}")
        val game = ScanGame("WebDAV acceptance ${root.name}", Uri.fromFile(root).toString(),
            EngineType.ONS, root.absolutePath)
        val manager = GameSaveManager(context)
        val service = SaveSyncService(context)
        val server = DeviceWebDavServer()
        val slot get() = File(requireNotNull(manager.resolveSaveLocation(game).directory), "slot.dat")
        private val oldConfig = WebDavSettingsStore.load(context)
        private var registered = false

        fun prepare() {
            assumeTrue("Use a fresh dedicated acceptance installation",
                GameLibraryFacade.loadGames(context).isEmpty() && !oldConfig.configured)
            require(root.mkdirs())
            GameLibraryFacade.updateGames(context) { it + game }
            registered = true
            SaveSyncWorker.cancel(context)
            configure(SaveSyncMode.MANUAL)
            assertEquals("synthetic-only", WebDavSettingsStore.load(context).password)
            assertEquals(server.serverUrl.trimEnd('/'),
                WebDavSettingsStore.load(context).serverUrl.trimEnd('/'))
        }

        fun configure(mode: SaveSyncMode) {
            WebDavSettingsStore.setEnabled(context, true)
            WebDavSettingsStore.save(context, WebDavConfig(server.serverUrl, "acceptance", "synthetic-only",
                mode = mode, showSyncFeedback = false, enabled = true))
        }

        suspend fun write(value: String): String {
            require(slot.parentFile!!.isDirectory || slot.parentFile!!.mkdirs())
            slot.writeText(value)
            return requireNotNull(service.fingerprint(game))
        }

        suspend fun cloudVersions(): List<RemoteSaveVersion> =
            WebDavClient(WebDavSettingsStore.load(context), context.cacheDir).use {
                it.list(RemoteSaveGame(game.engine.name, service.identity(game)))
            }

        fun hostRecord() = SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/hosts"))
            .load(SaveContentHash.text(context.packageName + ":webdav_acceptance_host"))

        suspend fun launchProbe() {
            stage("probe: preparing ticket")
            val ticket = SaveSyncLocks.active(game.uri) { SaveSyncRuntime.prepareLaunch(context, game) }
            assertNotNull(ticket)
            stage("probe: ticket prepared")
            try {
                context.startActivity(Intent().setClassName(context.packageName, PROBE_CLASS)
                    .putExtra(LaunchContract.ROOT_URI, game.uri)
                    .putExtra("acceptance_duration_ms", 4_000L)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await(5_000) {
                    val record = hostRecord()
                    val uris = record.optJSONArray("uris")
                    record.optInt("activities") > 0 && uris != null &&
                        (0 until uris.length()).any { uris.optString(it) == game.uri }
                }
                stage("probe: active host record observed")
                assertTrue(SaveSyncRuntime.isGameRunning(context, game))
                SaveSyncRuntime.finishLaunch(context, game, ticket, success = true)
                stage("probe: launch finalized")
            } catch (failure: Throwable) {
                SaveSyncRuntime.finishLaunch(context, game, ticket, success = false)
                throw failure
            }
        }

        suspend fun close() {
            if (!registered) {
                server.close()
                return
            }
            stage("fixture: closing")
            val failures = mutableListOf<Throwable>()
            suspend fun attempt(block: suspend () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
            attempt {
                WebDavSettingsStore.setEnabled(context, false)
                stage("fixture: disabled")
            }
            attempt {
                SaveSyncWorker.cancel(context)
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (SaveSyncRuntime.isGameRunning(context, game) && SystemClock.elapsedRealtime() < deadline) {
                    delay(200)
                }
            }
            stage("fixture: clearing private game state")
            attempt { service.clearGame(game) }
            attempt { GameLibraryFacade.removeGame(context, game.uri) }
            attempt {
                withTimeout(5_000) {
                    while (GameLibraryDatabase.get(context).gameLibraryDao().getAllGames().any { it.uri == game.uri } ||
                        context.getSharedPreferences("game_scanner", Context.MODE_PRIVATE)
                            .getString("scan_games", "").orEmpty().contains(game.uri)) delay(50)
                }
                val mirror = context.getSharedPreferences("game_scanner", Context.MODE_PRIVATE)
                check(mirror.edit().putString("scan_games", mirror.getString("scan_games", "")).commit())
            }
            attempt { manager.cleanupAppData(game) }
            attempt { check(root.deleteRecursively()) }
            stage("fixture: closing HTTP fixture")
            attempt { server.close() }
            stage("fixture: restoring settings")
            attempt { WebDavSettingsStore.setEnabled(context, true) }
            attempt {
                if (oldConfig.configured) WebDavSettingsStore.save(context, oldConfig)
                else WebDavSettingsStore.reset(context)
            }
            attempt { WebDavSettingsStore.setEnabled(context, oldConfig.enabled) }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private companion object {
        const val PROBE_CLASS = "com.tyranor.next.webdavprobe.SaveSyncProbeActivity"
    }
}
