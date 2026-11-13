package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenPyExternalEngineModuleTest {
    @Test
    fun buildsRenPyGameJsonPayload() {
        val request = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "测试 RenPy",
                uri = "content://com.android.externalstorage.documents/tree/primary%3AGames%2FRenPy",
                engine = EngineType.RENPY,
                launchTarget = "[游戏目录]",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/RenPy/",
        )

        val json = parseFlatJson(RenPyExternalEngineModule.buildGameJson(request))

        assertEquals("测试 RenPy", json["title"])
        assertEquals("/storage/emulated/0/Games/RenPy", json["folder"])
        assertEquals("", json["execFile"])
        assertEquals("renpy", json["type"])
        assertTrue(json["id"].orEmpty().isNotBlank())
    }

    @Test
    fun escapesJsonStringValues() {
        val request = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "引号\"与换行\n",
                uri = "file:///storage/emulated/0/Games/RenPy",
                engine = EngineType.RENPY,
                launchTarget = "[游戏目录]",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/RenPy",
        )

        val payload = RenPyExternalEngineModule.buildGameJson(request)

        assertTrue(payload.contains("\"title\":\"引号\\\"与换行\\n\""))
    }

    @Test
    fun buildsNestedSettingsWithTypeWrappers() {
        val json = JSONObject(RenPyExternalEngineModule.buildSettingsJson())
        val app = json.getJSONObject("app")
        val renpy = json.getJSONObject("renpy")

        assertTrue(app.getJSONObject("cheats").getBoolean("boolean"))
        assertTrue(renpy.getJSONObject("renpy_hw_video").getBoolean("boolean"))
        assertEquals(false, renpy.getJSONObject("renpy_autosave").getBoolean("boolean"))
        assertEquals(false, renpy.getJSONObject("renpy_phonesmallvariant").getBoolean("boolean"))
        assertEquals(false, renpy.getJSONObject("renpy_vsync").getBoolean("boolean"))
        assertEquals(false, renpy.getJSONObject("renpy_less_memory").getBoolean("boolean"))
        assertEquals(false, renpy.getJSONObject("renpy_less_updates").getBoolean("boolean"))
        assertEquals(false, renpy.getJSONObject("renpy_dont_use_gl2").getBoolean("boolean"))
        assertEquals(false, renpy.getJSONObject("renpy_recompile").getBoolean("boolean"))
        assertEquals(8, renpy.length())
    }

    @Test
    fun passesResolvedSettingsIntoPayload() {
        val settings = EngineSettingsStore.RenPy(
            cheats = false,
            hwVideo = false,
            autosave = true,
            phoneSmallVariant = true,
            vsync = true,
            lessMemory = true,
            lessUpdates = true,
            dontUseGl2 = true,
            recompile = true,
        )

        val json = JSONObject(RenPyExternalEngineModule.buildSettingsJson(settings))

        assertEquals(false, json.getJSONObject("app").getJSONObject("cheats").getBoolean("boolean"))
        val renpy = json.getJSONObject("renpy")
        assertEquals(false, renpy.getJSONObject("renpy_hw_video").getBoolean("boolean"))
        assertTrue(renpy.getJSONObject("renpy_autosave").getBoolean("boolean"))
        assertTrue(renpy.getJSONObject("renpy_phonesmallvariant").getBoolean("boolean"))
        assertTrue(renpy.getJSONObject("renpy_vsync").getBoolean("boolean"))
        assertTrue(renpy.getJSONObject("renpy_less_memory").getBoolean("boolean"))
        assertTrue(renpy.getJSONObject("renpy_less_updates").getBoolean("boolean"))
        assertTrue(renpy.getJSONObject("renpy_dont_use_gl2").getBoolean("boolean"))
        assertTrue(renpy.getJSONObject("renpy_recompile").getBoolean("boolean"))
    }

    @Test
    fun renpy77SharesSameSettingsProtocol() {
        val settings = EngineSettingsStore.RenPy(lessMemory = true)
        val payload = JSONObject(RenPy77ExternalEngineModule.buildSettingsJson(settings))
        assertTrue(payload.getJSONObject("renpy").getJSONObject("renpy_less_memory").getBoolean("boolean"))
        assertEquals("cyou.joiplay.runtime.renpy.run", RenPy77ExternalEngineModule.action)
    }

    @Test
    fun renpy853UsesExisting85SettingsProtocol() {
        val settings = EngineSettingsStore.RenPy(lessMemory = true)
        val payload = JSONObject(RenPyExternalEngineModule.buildSettingsJson(settings))
        assertTrue(payload.getJSONObject("renpy").getJSONObject("renpy_less_memory").getBoolean("boolean"))
        assertEquals("cyou.joiplay.runtime.renpy.v8d4d1", RenPyExternalEngineModule.packageName)
        assertEquals(10200L, RenPyExternalEngineModule.minimumVersionCode)
    }

    private fun parseFlatJson(payload: String): Map<String, String> {
        val body = payload.removePrefix("{").removeSuffix("}")
        return body.split(',')
            .associate { entry ->
                val pair = entry.split("\":\"", limit = 2)
                pair[0].removePrefix("\"") to pair[1].removeSuffix("\"")
            }
    }
}
