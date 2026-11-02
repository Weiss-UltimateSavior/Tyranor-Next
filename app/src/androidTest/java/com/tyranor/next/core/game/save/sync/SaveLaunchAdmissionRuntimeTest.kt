package com.tyranor.next.core.game.save.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.launch.LaunchResult
import com.tyranor.next.core.game.save.EthornellSaveCapability
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the real runtime entry point rather than just supportsManagedSave or a fake resolver. */
@RunWith(AndroidJUnit4::class)
class SaveLaunchAdmissionRuntimeTest {
    @Test
    fun realLauncherRejectsPendingSelectionsInEveryModeBeforeDispatchOrBookkeeping() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureId = "launch-admission-${UUID.randomUUID()}"
        val root = File(app.cacheDir, fixtureId).apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        var dispatched = false
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$fixtureId-$name"
                preferences += isolatedName
                return app.getSharedPreferences(isolatedName, mode)
            }
            override fun startActivity(intent: Intent) {
                dispatched = true
                throw AssertionError("A pending save selection must prevent engine dispatch")
            }
        }
        val gameRoot = File(root, "game").apply { mkdirs() }
        val save = File(gameRoot, "save").apply { mkdirs() }
        val game = ScanGame("Admission", Uri.fromFile(gameRoot).toString(), EngineType.YURIS, "")
        val directoryKey = SAVE_DIRECTORY_KEY_PREFIX + save.canonicalPath
        val syncRoot = File(context.noBackupFilesDir, "save_sync")
        val selections = SaveDirectoryActivationStore(syncRoot)
        try {
            WebDavSettingsStore.setEnabled(context, true)
            assertEquals(SaveSyncLaunchAdmission.Directory(directoryKey), SaveSyncRuntime.launchAdmission(context, game))
            for (mode in SaveSyncMode.entries) {
                WebDavSettingsStore.save(context, WebDavConfig("https://example.invalid/saves", mode = mode, enabled = true))
                for (pendingKey in listOf(directoryKey,
                    SAVE_DIRECTORY_KEY_PREFIX + gameRoot.canonicalPath,
                    SAVE_DIRECTORY_KEY_PREFIX + File(save, "nested").canonicalPath)) {
                    // Test both the selecting URI and a different URI sharing the directory.
                    for (owner in listOf(game.uri, "file:///another-game-alias")) {
                        val token = UUID.randomUUID().toString()
                        selections.begin(pendingKey, owner, token)
                        val result = EngineLauncher.launch(context, game)
                        assertEquals("$mode / $pendingKey / $owner",
                            LaunchResult.Failure.SavePreparationFailed(SaveSyncError.SELECTION_INCOMPLETE), result)
                        assertFalse(dispatched)
                        assertTrue(selections.owned(owner).any { it.token == token })
                        assertFalse(File(syncRoot, "sessions").exists())
                        assertFalse(File(syncRoot, "external_launches").exists())
                        selections.complete(pendingKey, owner, token)
                    }
                }
            }
            // An unreadable safety record is also mandatory; it cannot become a null ticket.
            File(syncRoot, "directory_selections/${SaveContentHash.text(directoryKey)}.json").writeText("{")
            assertEquals(LaunchResult.Failure.SavePreparationFailed(SaveSyncError.LOCAL_IO),
                EngineLauncher.launch(context, game))
            assertFalse(dispatched)
        } finally {
            preferences.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
            root.deleteRecursively()
            WebDavSettingsStore.isEnabled(app)
        }
    }

    @Test
    fun ethornellWithoutVerifiedSaveIoBypassesRuntimeBookkeepingAndSaveOperationFence() = runBlocking {
        assertFalse(EthornellSaveCapability.available)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw AssertionError("ETHORNELL admission must not read WebDAV settings")
            override fun getFilesDir(): File =
                throw AssertionError("ETHORNELL admission must not resolve managed save files")
            override fun getNoBackupFilesDir(): File =
                throw AssertionError("ETHORNELL admission must not create save-sync state")
            override fun getExternalFilesDir(type: String?): File? =
                throw AssertionError("ETHORNELL admission must not resolve external save files")
        }
        val game = ScanGame("ETHORNELL admission", "file:///unused-ethornell-admission", EngineType.ETHORNELL, "")
        val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.mutation { awaitCancellation() }
        }
        try {
            assertEquals(SaveSyncLaunchAdmission.Unmanaged, SaveSyncRuntime.launchAdmission(context, game))
            val result = withTimeout(5_000) {
                withSaveSyncLaunchAdmission(
                    resolve = { SaveSyncRuntime.launchAdmission(context, game) },
                    managed = { throw AssertionError("ETHORNELL was admitted to managed save bookkeeping") },
                    unmanaged = { "engine-start-path" },
                )
            }
            assertEquals("engine-start-path", result)
        } finally {
            exclusive.cancelAndJoin()
        }
    }
}
