package com.tyranor.next.core.engine.runtime

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.sync.DeviceWebDavServer
import com.tyranor.next.core.game.save.sync.SaveActivationResult
import com.tyranor.next.core.game.save.sync.SaveContentHash
import com.tyranor.next.core.game.save.sync.SaveSnapshotStore
import com.tyranor.next.core.game.save.sync.SaveSyncDirection
import com.tyranor.next.core.game.save.sync.SaveSyncService
import com.tyranor.next.core.game.save.sync.SaveSyncMode
import com.tyranor.next.core.game.save.sync.SaveSyncWorker
import com.tyranor.next.core.game.save.sync.WebDavConfig
import com.tyranor.next.core.game.save.sync.WebDavSettingsStore
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real runtime-module capability and loopback WebDAV history acceptance. */
@RunWith(AndroidJUnit4::class)
class RuntimeModuleSaveSyncDeviceTest {
    @Test
    fun declaredSaveDirectorySyncsVersionsWithoutTouchingGameResources() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.tyranor.next.ethornelltest") { "Use the isolated acceptance application" }
        check(InstrumentationRegistry.getArguments().getString("runtime_module_test") == "true") {
            "Pass runtime_module_test=true to opt into the real example-module test"
        }
        check(Environment.isExternalStorageManager()) { "Grant file access to the isolated test application" }

        val oldConfig = WebDavSettingsStore.load(context)
        val oldEnabled = WebDavSettingsStore.isEnabled(context)
        val oldModuleEnabled = MODULE in AppSettingsStore.getEnabledRuntimeModules(context)
        val server = DeviceWebDavServer(username = "runtime-acceptance", password = "loopback-only")
        val exampleParent = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "TyranorRuntimeExample")
        val root = File(exampleParent, "acceptance-${UUID.randomUUID()}")
        val game = ScanGame("Runtime save sync ${root.name}", Uri.fromFile(root).toString(),
            EngineType.RUNTIME_MODULE, "game.tyranor-example", runtimeModulePackage = MODULE)
        var registered = false
        val service = SaveSyncService(context)
        val manager = GameSaveManager(context)
        try {
            check(root.mkdirs())
            val gameResource = File(root, "game.tyranor-example").apply { writeText("module-resource-preserve") }
            AppSettingsStore.setRuntimeModuleEnabled(context, MODULE, true)
            check(RuntimeModuleRegistry.refresh(context) is RuntimeModuleDiscoveryResult.Success)
            val module = requireNotNull(RuntimeModuleRegistry.find(MODULE)) { "Install the standalone example APK first" }
            val saveSpec = requireNotNull(module.saves) { "Example APK must declare its saves directory" }
            assertEquals("saves", saveSpec.directory)

            GameLibraryFacade.registerGames(context) { existing -> existing + game }
            GameLibraryRepository.awaitPendingWrites()
            registered = true

            val capability = manager.runtimeSaveCapability(game)
            assertEquals(GameSaveManager.RuntimeSaveCapability.AVAILABLE, capability.status)
            val saveDirectory = requireNotNull(capability.location?.directory)
            assertEquals(File(root, saveSpec.directory).canonicalPath, saveDirectory.canonicalPath)
            assertFalse("Capability resolution must not create the declared directory", saveDirectory.exists())
            assertTrue(saveDirectory.mkdirs())
            val stateFile = File(saveDirectory, "state.json")

            WebDavSettingsStore.setEnabled(context, true)
            WebDavSettingsStore.save(context, WebDavConfig(
                serverUrl = server.serverUrl,
                username = "runtime-acceptance",
                password = "loopback-only",
                mode = SaveSyncMode.BIDIRECTIONAL,
                showSyncFeedback = false,
                enabled = true,
            ))
            SaveSyncWorker.cancel(context)
            stateFile.writeText("state-one")
            val firstHash = requireNotNull(service.fingerprint(game))
            val firstUpload = service.syncGame(game, SaveSyncDirection.UPLOAD)
            assertTrue(firstUpload.toString(), firstUpload.failures.isEmpty())
            assertEquals(1, firstUpload.uploaded)
            val oldVersion = service.listVersions(game).single { it.contentHash == firstHash }
            assertTrue(oldVersion.runtimeProvenances.any {
                it.packageName == MODULE && it.savesFingerprint == saveSpec.fingerprint &&
                    it.canonicalDirectory == saveDirectory.canonicalPath
            })

            stateFile.writeText("state-two")
            val secondHash = requireNotNull(service.fingerprint(game))
            val secondUpload = service.syncGame(game, SaveSyncDirection.UPLOAD)
            assertTrue(secondUpload.toString(), secondUpload.failures.isEmpty())
            assertEquals(1, secondUpload.uploaded)
            val snapshotStore = SaveSnapshotStore(File(context.noBackupFilesDir, "save_sync/versions"))
            val secondVersion = service.listVersions(game).single { it.contentHash == secondHash }
            assertTrue("The test URI's latest private ZIP must exist",
                snapshotStore.file(SaveContentHash.text(game.uri), secondVersion.id).delete())
            stateFile.writeText("local-unpublished-state")
            val pull = service.syncGame(game, SaveSyncDirection.PULL)
            assertTrue(pull.toString(), pull.failures.isEmpty())
            assertEquals(1, pull.downloaded)
            assertTrue("A missing private cloud version must be fetched from loopback", server.requestCount("GET") > 0)
            assertEquals("local-unpublished-state", stateFile.readText())
            assertEquals("module-resource-preserve", gameResource.readText())

            assertEquals(SaveActivationResult.APPLIED, service.activateVersion(game, oldVersion.id))
            assertEquals("state-one", stateFile.readText())
            assertEquals("module-resource-preserve", gameResource.readText())
            assertTrue("WebDAV should have transferred only loopback requests", server.requestCount("PUT") > 0)
            assertEquals(null, server.failure)
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) { cleanupFailures += failure }
            }
            attempt { WebDavSettingsStore.setEnabled(context, false) }
            attempt { SaveSyncWorker.cancel(context) }
            if (registered) {
                // This URI is generated solely by this test; clear its private snapshots and journals.
                attempt { service.clearGame(game) }
                attempt { PerGameSettingsStore.clear(context, game.uri) }
                attempt { GameLibraryFacade.updateGames(context) { existing -> existing.filterNot { it.uri == game.uri } } }
                attempt { GameLibraryRepository.awaitPendingWrites() }
            }
            attempt { AppSettingsStore.setRuntimeModuleEnabled(context, MODULE, oldModuleEnabled) }
            attempt { WebDavSettingsStore.setEnabled(context, true) }
            attempt {
                if (oldConfig.configured) WebDavSettingsStore.save(context, oldConfig)
                else WebDavSettingsStore.reset(context)
            }
            attempt { WebDavSettingsStore.setEnabled(context, oldEnabled) }
            attempt { RuntimeModuleRegistry.refresh(context) }
            attempt { server.close() }
            attempt { check(!root.exists() || root.deleteRecursively()) { "Could not remove runtime test directory" } }
            cleanupFailures.firstOrNull()?.let { failure ->
                cleanupFailures.drop(1).filter { it !== failure }.forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    private companion object {
        const val MODULE = "com.tyranor.runtime.example"
    }
}
