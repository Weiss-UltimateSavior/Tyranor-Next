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
    private val ready = ExternalEngineSettingsFile.Result.READY
    private val unavailable = ExternalEngineSettingsFile.Result.UNAVAILABLE

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

        val result = RuffleRuntimeEnvironment.reconcileConfigurationFile(
            config,
            EngineSettingsStore.Ruffle(backend = "opengl", quality = "best", screenTimeout = 60),
            writable = true,
        )

        assertEquals(ready, result)
        val json = JSONObject(config.readText())
        assertEquals("opengl", json.getString("backend"))
        assertEquals("best", json.getString("quality"))
        assertEquals(60, json.getInt("screenTimeout"))
        assertEquals(7, json.getInt("legacyKey"))
    }

    @Test
    fun skipsMissingFileWithoutCreatingIt() {
        val missing = temporaryFolder.root.resolve("missing/configuration.json")

        assertEquals(ready, RuffleRuntimeEnvironment.reconcileConfigurationFile(missing, EngineSettingsStore.Ruffle(), true))
        assertFalse(missing.exists())
        assertFalse(missing.parentFile?.exists() == true)
    }

    @Test
    fun skipsInvalidJsonWithoutOverwriting() {
        val config = temporaryFolder.newFile("invalid.json")
        config.writeText("{not-json")

        assertEquals(ready, RuffleRuntimeEnvironment.reconcileConfigurationFile(config, EngineSettingsStore.Ruffle(), true))

        assertEquals("{not-json", config.readText())
    }

    @Test
    fun writesThroughSiblingTempWithoutLeavingItBehind() {
        val config = temporaryFolder.newFile("configuration.json")
        config.writeText("""{"backend":"vulkan"}""")

        assertEquals(ready, RuffleRuntimeEnvironment.reconcileConfigurationFile(config, EngineSettingsStore.Ruffle(backend = "opengl"), true))

        assertEquals("opengl", JSONObject(config.readText()).getString("backend"))
        assertEquals(listOf(config.name), config.parentFile?.list()?.toList())
    }

    @Test
    fun skipsOversizedConfigurationFileWithoutTouchingIt() {
        val config = temporaryFolder.newFile("oversized.json")
        // 超过 1 MiB 上限：不再整体读取/解析
        config.writeText("{\"backend\":\"vulkan\",\"pad\":\"" + "x".repeat(1024 * 1024) + "\"}")
        val before = config.readText()

        assertEquals(unavailable, RuffleRuntimeEnvironment.reconcileConfigurationFile(config, EngineSettingsStore.Ruffle(backend = "opengl"), true))

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
    fun configFileUsesSharedFileForStorageRootItself() {
        val root = File("/storage/emulated/0")

        assertEquals(
            File(root, "configuration.json"),
            RuffleRuntimeEnvironment.configFileFor(root.absolutePath + "/", "game-id", root),
        )
    }

    @Test
    fun configFileMatchesPluginPrefixCheck() {
        val root = File("/storage/emulated/0")

        // /storage/emulated/0abc 与根同前缀但不是根目录下的路径
        assertEquals(
            File("/storage/emulated/0abc/configuration.json"),
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
    fun conflictingSharedConfigIsPreservedAndReported() {
        val root = temporaryFolder.root
        val gameFolder = temporaryFolder.newFolder("games")
        val file = File(gameFolder, "configuration.json")
        val original = """{"backend":"vulkan","legacyKey":7}"""
        file.writeText(original)
        File(gameFolder, "first.swf").createNewFile()
        assertTrue(RuffleRuntimeEnvironment.shouldWriteConfigurationFile(file, gameFolder, root))
        File(gameFolder, "second.swf").createNewFile()
        val writable = RuffleRuntimeEnvironment.shouldWriteConfigurationFile(file, gameFolder, root)
        assertFalse(writable)
        assertFalse(RuffleRuntimeEnvironment.shouldWriteConfigurationFile(File(root, "configuration.json"), root, root))
        assertEquals(
            ExternalEngineSettingsFile.Result.CONFLICT,
            RuffleRuntimeEnvironment.reconcileConfigurationFile(file, EngineSettingsStore.Ruffle(backend = "opengl"), writable),
        )
        assertEquals(
            ready,
            RuffleRuntimeEnvironment.reconcileConfigurationFile(file, EngineSettingsStore.Ruffle(backend = "vulkan"), writable),
        )
        assertEquals(original, file.readText())
    }
}
