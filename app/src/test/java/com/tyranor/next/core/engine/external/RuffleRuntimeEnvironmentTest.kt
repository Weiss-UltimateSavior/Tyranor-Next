package com.tyranor.next.core.engine.external

import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuffleRuntimeEnvironmentTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun applyManagedKeysWritesFlatKeys() {
        val json = JSONObject()
        val settings = EngineSettingsStore.Ruffle(
            screenTimeout = 15,
            backend = "vulkan",
            quality = "medium",
            scaleMode = "exactfit",
            letterbox = "off",
            loadBehavior = "delayed",
        )

        RuffleRuntimeEnvironment.applyManagedKeys(json, settings)

        assertEquals(15, json.getInt("screenTimeout"))
        assertEquals("vulkan", json.getString("backend"))
        assertEquals("medium", json.getString("quality"))
        assertEquals("exactfit", json.getString("scaleMode"))
        assertEquals("off", json.getString("letterbox"))
        assertEquals("delayed", json.getString("loadBehavior"))
    }

    @Test
    fun applyManagedKeysPreservesUnknownKeys() {
        val json = JSONObject().put("customUnmanagedKey", "keep-me").put("backend", "vulkan")

        RuffleRuntimeEnvironment.applyManagedKeys(
            json,
            EngineSettingsStore.Ruffle(backend = "opengl"),
        )

        assertEquals("keep-me", json.getString("customUnmanagedKey"))
        assertEquals("opengl", json.getString("backend"))
    }

    @Test
    fun syncsExistingJsonFileAndPreservesUnmanagedKeys() {
        val config = temporaryFolder.newFile("configuration.json")
        config.writeText("""{"backend":"vulkan","legacyKey":7}""")

        val written = RuffleRuntimeEnvironment.syncConfigurationFile(
            config,
            EngineSettingsStore.Ruffle(backend = "opengl", quality = "best", screenTimeout = 60),
        )

        assertTrue(written)
        val json = JSONObject(config.readText())
        assertEquals("opengl", json.getString("backend"))
        assertEquals("best", json.getString("quality"))
        assertEquals(60, json.getInt("screenTimeout"))
        assertEquals(7, json.getInt("legacyKey"))
    }

    @Test
    fun skipsMissingFileWithoutCreatingIt() {
        val missing = temporaryFolder.root.resolve("missing/configuration.json")

        assertFalse(RuffleRuntimeEnvironment.syncConfigurationFile(missing, EngineSettingsStore.Ruffle()))
        assertFalse(missing.exists())
        assertFalse(missing.parentFile?.exists() == true)
    }

    @Test
    fun skipsInvalidJsonWithoutOverwriting() {
        val config = temporaryFolder.newFile("invalid.json")
        config.writeText("{not-json")

        assertFalse(RuffleRuntimeEnvironment.syncConfigurationFile(config, EngineSettingsStore.Ruffle()))

        assertEquals("{not-json", config.readText())
    }

    @Test
    fun writesThroughSiblingTempWithoutLeavingItBehind() {
        val config = temporaryFolder.newFile("configuration.json")
        config.writeText("""{"backend":"vulkan"}""")

        assertTrue(RuffleRuntimeEnvironment.syncConfigurationFile(config, EngineSettingsStore.Ruffle(backend = "opengl")))

        assertEquals("opengl", JSONObject(config.readText()).getString("backend"))
        assertFalse(File(config.parentFile, "${config.name}.tmp").exists())
    }

    @Test
    fun skipsOversizedConfigurationFileWithoutTouchingIt() {
        val config = temporaryFolder.newFile("oversized.json")
        // 超过 1 MiB 上限：不再整体读取/解析
        config.writeText("{\"backend\":\"vulkan\",\"pad\":\"" + "x".repeat(1024 * 1024) + "\"}")
        val before = config.readText()

        assertFalse(RuffleRuntimeEnvironment.syncConfigurationFile(config, EngineSettingsStore.Ruffle(backend = "opengl")))

        assertEquals(before, config.readText())
    }

    @Test
    fun configFileUsesFolderForGameInsideExternalRoot() {
        val root = File("/storage/emulated/0")

        assertEquals(
            File("/storage/emulated/0/Games/foo/configuration.json"),
            RuffleRuntimeEnvironment.configFileFor("/storage/emulated/0/Games/foo", "game-id", root),
        )
    }

    @Test
    fun configFileUsesPerGamePathForStorageRootItself() {
        val root = File("/storage/emulated/0")

        assertEquals(
            File(root, "JoiPlay/games/game-id/configuration.json"),
            RuffleRuntimeEnvironment.configFileFor(root.absolutePath + "/", "game-id", root),
        )
    }

    @Test
    fun configFileDoesNotTreatPrefixNeighborAsStorageRoot() {
        val root = File("/storage/emulated/0")

        // /storage/emulated/0abc 与根同前缀但不是根目录下的路径
        assertEquals(
            File(root, "JoiPlay/games/game-id/configuration.json"),
            RuffleRuntimeEnvironment.configFileFor("/storage/emulated/0abc", "game-id", root),
        )
    }

    @Test
    fun configFileUsesPerGamePathForRemovableStorage() {
        val root = File("/storage/emulated/0")

        assertEquals(
            File(root, "JoiPlay/games/game-id/configuration.json"),
            RuffleRuntimeEnvironment.configFileFor("/storage/1A2B-3C4D/Games/foo", "game-id", root),
        )
    }

    @Test
    fun syncSkipsSharedFoldersAndStorageRoot() {
        val root = File("/storage/emulated/0")
        val gameFolder = "/storage/emulated/0/Games/foo"

        assertTrue(RuffleRuntimeEnvironment.shouldSyncConfigurationFile(gameFolder, swfFileCount = 1, externalRoot = root))
        // 同一目录多个 SWF：目录级配置会串游戏，保留 intent settings
        assertFalse(RuffleRuntimeEnvironment.shouldSyncConfigurationFile(gameFolder, swfFileCount = 2, externalRoot = root))
        // 存储根本身同样不写目录级配置
        assertFalse(
            RuffleRuntimeEnvironment.shouldSyncConfigurationFile(
                root.absolutePath,
                swfFileCount = 1,
                externalRoot = root,
            ),
        )
    }
}
