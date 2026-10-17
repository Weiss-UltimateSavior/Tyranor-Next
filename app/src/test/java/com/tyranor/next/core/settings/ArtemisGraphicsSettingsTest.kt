package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtemisGraphicsSettingsTest {

    @Test
    fun artemisModesExcludeOffWithoutChangingKirikiriModes() {
        assertTrue(EngineSettingsStore.ANIME4K_OFF in EngineSettingsStore.ANIME4K_MODES)
        assertFalse(EngineSettingsStore.ANIME4K_OFF in EngineSettingsStore.ART_ANIME4K_MODES)
        assertEquals(7, EngineSettingsStore.ART_ANIME4K_MODES.size)
        for (mode in EngineSettingsStore.ART_ANIME4K_MODES) {
            assertEquals(mode, EngineSettingsStore.normalizeArtAnime4kMode(" $mode "))
        }
        assertEquals("s", EngineSettingsStore.normalizeArtAnime4kMode(null))
        assertEquals("s", EngineSettingsStore.normalizeArtAnime4kMode("off"))
        assertEquals("s", EngineSettingsStore.normalizeArtAnime4kMode("unknown"))
    }

    @Test
    fun corruptOrOffModeOverrideFollowsGlobalMode() {
        for (override in listOf(null, "", "off", "unknown")) {
            assertEquals(
                "deblur",
                EffectiveEngineSettings.resolveAllowed(
                    override, "deblur", EngineSettingsStore.ART_ANIME4K_MODES,
                    EngineSettingsStore.ART_ANIME4K_DEFAULT,
                ),
            )
        }
        assertEquals(
            "soft_m",
            EffectiveEngineSettings.resolveAllowed(
                " soft_m ", "s", EngineSettingsStore.ART_ANIME4K_MODES,
                EngineSettingsStore.ART_ANIME4K_DEFAULT,
            ),
        )
        assertEquals(
            "s",
            EffectiveEngineSettings.resolveAllowed(
                "off", "invalid", EngineSettingsStore.ART_ANIME4K_MODES,
                EngineSettingsStore.ART_ANIME4K_DEFAULT,
            ),
        )
    }

    @Test
    fun invalidBooleanJsonFollowsGlobalAndExplicitFalseWins() {
        val keys = listOf(
            PerGameSettingsStore.F_ART_ANIME4K_ENABLED,
        )
        for (key in keys) {
            for (invalid in listOf(JSONObject.NULL, "false", 0, JSONObject())) {
                val override = GraphicsSettingsCodec.legacyOverride(JSONObject().put(key, invalid), GraphicsFamily.ARTEMIS).enabled
                assertNull(override)
                assertTrue(EffectiveEngineSettings.resolveBool(override, true))
            }
            assertNull(GraphicsSettingsCodec.legacyOverride(JSONObject(), GraphicsFamily.ARTEMIS).enabled)
            assertFalse(
                EffectiveEngineSettings.resolveBool(
                    GraphicsSettingsCodec.legacyOverride(JSONObject().put(key, false), GraphicsFamily.ARTEMIS).enabled, true,
                ),
            )
            assertTrue(
                EffectiveEngineSettings.resolveBool(
                    GraphicsSettingsCodec.legacyOverride(JSONObject().put(key, true), GraphicsFamily.ARTEMIS).enabled, false,
                ),
            )
        }
    }
}
