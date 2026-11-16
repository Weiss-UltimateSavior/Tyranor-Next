package com.tyranor.next.core.engine.runtime

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.launch.LaunchResult
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveException
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.SaveErrorCode
import com.tyranor.next.core.game.save.sync.SaveSyncRuntime
import com.tyranor.next.core.game.save.sync.WebDavSettingsStore
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real PackageManager, Room, filesystem, explicit APK handoff and durable exit-fence acceptance. */
@RunWith(AndroidJUnit4::class)
class RuntimeModuleDeviceTest {
    @Test fun perGameConfigurationAndSaveTransactionsAreIsolated() = withFixture { context, first, second ->
        val module = requireNotNull(RuntimeModuleRegistry.find(MODULE))
        val spec = requireNotNull(module.settings)
        val overrides = JSONObject().put("vsync", false).put("renderer", "software")
            .put("player_name", "Fixture").put("fps_limit", 90)
        assertEquals(PerGameSettingsStore.RuntimeModuleSettingsSaveResult.Saved,
            PerGameSettingsStore.saveRuntimeModuleSettings(context, first.uri, MODULE, spec, overrides))
        assertEquals(PerGameSettingsStore.RuntimeModuleSettingsSaveResult.DescriptorChanged,
            PerGameSettingsStore.saveRuntimeModuleSettings(context, first.uri, MODULE,
                spec.copy(fingerprint = "stale-descriptor"), overrides))
        assertFalse(PerGameSettingsStore.getRuntimeModuleSettings(context, first.uri, MODULE).getBoolean("vsync"))
        assertEquals(0, PerGameSettingsStore.getRuntimeModuleSettings(context, second.uri, MODULE).length())
        PerGameSettingsStore.setStr(context, first.uri, "device_test_unrelated", "keep")
        GameLibraryRepository.awaitPendingWrites()
        assertEquals(90, PerGameSettingsStore.getRuntimeModuleSettings(context, first.uri, MODULE).getInt("fps_limit"))

        val manager = GameSaveManager(context)
        val directory = requireNotNull(manager.resolveSaveLocation(first).directory)
        assertFalse("Resolving an empty save must not create it", directory.exists())
        assertTrue(directory.mkdirs())
        File(directory, "state.json").writeText("{\"chapter\":7}")
        val resource = File(directory.parentFile, "game-resource.dat").apply { writeText("resource") }
        val archive = File(context.cacheDir, "runtime-${UUID.randomUUID()}.zip")
        try {
            assertEquals(1, manager.withManualSaveAccess(first) { manager.exportToZip(first, Uri.fromFile(archive)) })
            assertEquals(1, manager.withManualSaveAccess(first) { manager.deleteSaves(first) })
            assertFalse(File(directory, "state.json").exists())
            assertEquals(1, manager.withManualSaveAccess(first) { manager.importFromZip(first, Uri.fromFile(archive)) })
            assertEquals("{\"chapter\":7}", File(directory, "state.json").readText())
            assertEquals("resource", resource.readText())

            val unowned = File(directory.parentFile, directory.name + ".import_backup").apply { mkdirs() }
            File(unowned, "resource").writeText("do not delete")
            try {
                manager.withManualSaveAccess(first) { manager.deleteSaves(first) }
                fail("Unowned sidecar must reject deletion")
            } catch (failure: GameSaveException) {
                assertEquals(SaveErrorCode.RESOURCE_RESTORE_CONFLICT, failure.code)
            }
            assertEquals("do not delete", File(unowned, "resource").readText())
        } finally { archive.delete() }

        assertEquals(PerGameSettingsStore.RuntimeModuleSettingsSaveResult.Saved,
            PerGameSettingsStore.clearRuntimeModuleSettings(context, first.uri, MODULE, spec))
        assertEquals(0, PerGameSettingsStore.getRuntimeModuleSettings(context, first.uri, MODULE).length())
        assertEquals("keep", PerGameSettingsStore.getStr(context, first.uri, "device_test_unrelated"))
    }

    @Test fun realModuleReceivesSettingsAndExitFenceWorksWithoutWebDav() = withFixture { context, game, _ ->
        val spec = requireNotNull(RuntimeModuleRegistry.find(MODULE)?.settings)
        assertEquals(PerGameSettingsStore.RuntimeModuleSettingsSaveResult.Saved,
            PerGameSettingsStore.saveRuntimeModuleSettings(context, game.uri, MODULE, spec,
                JSONObject().put("player_name", "Fixture").put("fps_limit", 90)))
        WebDavSettingsStore.setEnabled(context, false)
        assertEquals(LaunchResult.Success, EngineLauncher.launch(context, game))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        waitUntil("example activity receives settings") {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitUntil false
            nodeTexts(root).any { it.contains("Fixture") && it.contains("fps_limit") }
        }
        val manager = GameSaveManager(context)
        try {
            manager.withManualSaveAccess(game) { manager.deleteSaves(game) }
            fail("External game must fence manual saves with WebDAV disabled")
        } catch (failure: GameSaveException) {
            assertEquals(SaveErrorCode.ENGINE_RUNNING, failure.code)
        }
        val moduleResources = context.packageManager.getResourcesForApplication(MODULE)
        val writeLabel = moduleResources.getString(moduleResources.getIdentifier("write_save", "string", MODULE))
        clickText(writeLabel)
        val save = File(requireNotNull(manager.resolveSaveLocation(game).directory), "state.json")
        waitUntil("example module writes its declared save") { save.isFile }
        assertEquals("Fixture", JSONObject(save.readText()).getJSONObject("settings").getString("player_name"))
        val finishLabel = moduleResources.getString(moduleResources.getIdentifier("finish", "string", MODULE))
        clickText(finishLabel)
        assertTrue(SaveSyncRuntime.pendingExternalExitConfirmations(context).any { it.uri == game.uri })
        val confirmation = requireNotNull(SaveSyncRuntime.externalExitConfirmation(context, game))
        SaveSyncRuntime.confirmExternalExit(context, game, confirmation)
        assertFalse(SaveSyncRuntime.pendingExternalExitConfirmations(context).any { it.uri == game.uri })
        assertEquals(1, manager.withManualSaveAccess(game) { manager.deleteSaves(game) })
    }

    private fun withFixture(action: suspend (Context, ScanGame, ScanGame) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.tyranor.next.ethornelltest") { "Isolated application required" }
        check(InstrumentationRegistry.getArguments().getString("runtime_module_test") == "true") { "Explicit opt-in required" }
        check(Environment.isExternalStorageManager()) { "Grant file access to the isolated test application" }
        val originalEnabled = MODULE in AppSettingsStore.getEnabledRuntimeModules(context)
        val oldWebDav = WebDavSettingsStore.isEnabled(context)
        val parent = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TyranorRuntimeExample")
        check(parent.isDirectory || parent.mkdirs())
        val roots = List(2) { File(parent, "acceptance-${UUID.randomUUID()}").apply { check(mkdir()) } }
        val games = roots.mapIndexed { index, root ->
            File(root, "game.tyranor-example").writeText("")
            ScanGame("Runtime acceptance $index", Uri.fromFile(root).toString(), EngineType.RUNTIME_MODULE,
                "game.tyranor-example", runtimeModulePackage = MODULE)
        }
        try {
            AppSettingsStore.setRuntimeModuleEnabled(context, MODULE, true)
            check(RuntimeModuleRegistry.refresh(context) is RuntimeModuleDiscoveryResult.Success)
            check(RuntimeModuleRegistry.find(MODULE)?.settings != null) { "Install the standalone example APK first" }
            GameLibraryFacade.registerGames(context) { it + games }
            GameLibraryRepository.awaitPendingWrites()
            action(context, games[0], games[1])
        } finally {
            // Release only this randomized fixture's sessions, never unrelated games.
            games.forEach { game ->
                runCatching {
                    val confirmation = SaveSyncRuntime.externalExitConfirmation(context, game)
                    if (confirmation != null) SaveSyncRuntime.confirmExternalExit(context, game, confirmation)
                }
                PerGameSettingsStore.clear(context, game.uri)
            }
            GameLibraryFacade.updateGames(context) { existing -> existing.filterNot { it.uri in games.map(ScanGame::uri) } }
            GameLibraryRepository.awaitPendingWrites()
            AppSettingsStore.setRuntimeModuleEnabled(context, MODULE, originalEnabled)
            WebDavSettingsStore.setEnabled(context, oldWebDav)
            RuntimeModuleRegistry.refresh(context)
            roots.forEach(File::deleteRecursively)
        }
    }

    private fun clickText(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun click(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (node.text?.toString()?.equals(text, ignoreCase = true) == true && node.isClickable &&
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true
            for (index in 0 until node.childCount) {
                if (node.getChild(index)?.let(::click) == true) return true
            }
            return false
        }
        waitUntil("clickable example control: $text") {
            automation.rootInActiveWindow?.let(::click) == true
        }
    }

    private fun nodeTexts(node: android.view.accessibility.AccessibilityNodeInfo): List<String> = buildList {
        node.text?.toString()?.let(::add)
        for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(nodeTexts(it)) }
    }

    private fun waitUntil(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }

    private companion object { const val MODULE = "com.tyranor.runtime.example" }
}
