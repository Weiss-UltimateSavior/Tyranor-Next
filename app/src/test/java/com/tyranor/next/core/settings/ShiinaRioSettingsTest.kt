package com.tyranor.next.core.settings

import com.tyranor.next.core.game.storage.GameOverridePartitions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiinaRioSettingsTest {
    @Test
    fun serializedDefaultsMatchTheHostProtocol() {
        val json = ShiinaRioSettings.Defaults.toJson()

        assertEquals(11, json.length())
        assertEquals("Gpu", json.getString("renderer"))
        assertEquals("Sharp", json.getString("scaling"))
        assertEquals(8, json.getInt("fsrSharpness"))
        assertEquals("Game", json.getString("framePacing"))
        assertEquals("Auto", json.getString("fileCache"))
        assertTrue(json.getBoolean("translateX86"))
        assertTrue(json.getBoolean("pauseInBackground"))
        assertTrue(json.getBoolean("gameController"))
        assertFalse(json.getBoolean("showFrameRate"))
        assertEquals("Shiina", json.getString("theme"))
        assertEquals("", json.getString("language"))
    }

    @Test
    fun overridesRoundTripThroughGameStorageAndFollowGlobalPerField() {
        val overrides = JSONObject("""{"renderer":"cpu","language":"","gameController":false,"fsrSharpness":99,"theme":"bad"}""")
        val blob = JSONObject().put(GameOverridePartitions.KEY_SHIINARIO_SETTINGS, overrides)
        val reassembled = GameOverridePartitions.assemble(GameOverridePartitions.split("game", blob, 1L))
        val global = ShiinaRioSettings.Defaults.copy(language = "ja", theme = "Graphite", scaling = "Fsr")
        val resolved = PerGameSettingsStore.toShiinaRioOverride(reassembled).withFallback(global)

        assertEquals("Cpu", resolved.renderer)
        assertEquals("", resolved.language)
        assertEquals(false, resolved.gameController)
        assertEquals(20, resolved.fsrSharpness)
        assertEquals("Graphite", resolved.theme)
        assertEquals("Fsr", resolved.scaling)
        assertEquals(0, ShiinaRioSettings().toJson().length())
    }
}
