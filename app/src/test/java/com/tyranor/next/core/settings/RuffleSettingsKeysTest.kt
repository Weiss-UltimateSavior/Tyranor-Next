package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruffle 外置模块设置键/默认值/覆盖解析回归。
 * 键名同时锚定全局 prefs（EngineSettingsStore）与单游戏覆盖键（PerGameSettingsStore）。
 */
class RuffleSettingsKeysTest {

    @Test
    fun perGameKeysShareGlobalLiterals() {
        assertEquals("ruffle_screen_timeout", EngineSettingsStore.KEY_RUFFLE_SCREEN_TIMEOUT)
        assertEquals("ruffle_backend", EngineSettingsStore.KEY_RUFFLE_BACKEND)
        assertEquals("ruffle_quality", EngineSettingsStore.KEY_RUFFLE_QUALITY)
        assertEquals("ruffle_scale_mode", EngineSettingsStore.KEY_RUFFLE_SCALE_MODE)
        assertEquals("ruffle_letterbox", EngineSettingsStore.KEY_RUFFLE_LETTERBOX)
        assertEquals("ruffle_load_behavior", EngineSettingsStore.KEY_RUFFLE_LOAD_BEHAVIOR)

        assertEquals(EngineSettingsStore.KEY_RUFFLE_SCREEN_TIMEOUT, PerGameSettingsStore.F_RUFFLE_SCREEN_TIMEOUT)
        assertEquals(EngineSettingsStore.KEY_RUFFLE_BACKEND, PerGameSettingsStore.F_RUFFLE_BACKEND)
        assertEquals(EngineSettingsStore.KEY_RUFFLE_QUALITY, PerGameSettingsStore.F_RUFFLE_QUALITY)
        assertEquals(EngineSettingsStore.KEY_RUFFLE_SCALE_MODE, PerGameSettingsStore.F_RUFFLE_SCALE_MODE)
        assertEquals(EngineSettingsStore.KEY_RUFFLE_LETTERBOX, PerGameSettingsStore.F_RUFFLE_LETTERBOX)
        assertEquals(EngineSettingsStore.KEY_RUFFLE_LOAD_BEHAVIOR, PerGameSettingsStore.F_RUFFLE_LOAD_BEHAVIOR)
    }

    @Test
    fun defaultsMatchPluginProtocol() {
        assertEquals(
            EngineSettingsStore.Ruffle(
                screenTimeout = 0,
                backend = "opengl",
                quality = "high",
                scaleMode = "showall",
                letterbox = "on",
                loadBehavior = "streaming",
            ),
            EngineSettingsStore.Ruffle(),
        )
    }

    @Test
    fun normalizeFunctionsUseAllowListsAndDefaults() {
        assertEquals(30, EngineSettingsStore.normalizeRuffleScreenTimeout(30))
        assertEquals(0, EngineSettingsStore.normalizeRuffleScreenTimeout(7))
        assertEquals(0, EngineSettingsStore.normalizeRuffleScreenTimeout(null))

        assertEquals("vulkan", EngineSettingsStore.normalizeRuffleBackend(" VULKAN "))
        assertEquals("opengl", EngineSettingsStore.normalizeRuffleBackend("bogus"))
        assertEquals("medium", EngineSettingsStore.normalizeRuffleQuality("Medium"))
        assertEquals("high", EngineSettingsStore.normalizeRuffleQuality("bogus"))
        assertEquals("noborder", EngineSettingsStore.normalizeRuffleScaleMode("NOBORDER"))
        assertEquals("showall", EngineSettingsStore.normalizeRuffleScaleMode("bogus"))
        assertEquals("off", EngineSettingsStore.normalizeRuffleLetterbox("OFF"))
        assertEquals("on", EngineSettingsStore.normalizeRuffleLetterbox("bogus"))
        assertEquals("delayed", EngineSettingsStore.normalizeRuffleLoadBehavior("Delayed"))
        assertEquals("streaming", EngineSettingsStore.normalizeRuffleLoadBehavior("bogus"))
    }

    @Test
    fun toRuffleOverrideReturnsNullWhenNoRelevantKeyExists() {
        assertNull(PerGameSettingsStore.toRuffleOverride(null))
        assertNull(PerGameSettingsStore.toRuffleOverride(JSONObject().put("engine_version", "1.3.9")))
    }

    @Test
    fun toRuffleOverrideReadsOnlyPresentKeys() {
        val partial = PerGameSettingsStore.toRuffleOverride(
            JSONObject().put(PerGameSettingsStore.F_RUFFLE_BACKEND, "vulkan"),
        )
        assertEquals(RuffleOverride(backend = "vulkan"), partial)

        val full = PerGameSettingsStore.toRuffleOverride(
            JSONObject()
                .put(PerGameSettingsStore.F_RUFFLE_SCREEN_TIMEOUT, "45")
                .put(PerGameSettingsStore.F_RUFFLE_BACKEND, "auto")
                .put(PerGameSettingsStore.F_RUFFLE_QUALITY, "best")
                .put(PerGameSettingsStore.F_RUFFLE_SCALE_MODE, "exactfit")
                .put(PerGameSettingsStore.F_RUFFLE_LETTERBOX, "off")
                .put(PerGameSettingsStore.F_RUFFLE_LOAD_BEHAVIOR, "blocking"),
        )
        assertEquals(
            RuffleOverride(
                screenTimeout = "45",
                backend = "auto",
                quality = "best",
                scaleMode = "exactfit",
                letterbox = "off",
                loadBehavior = "blocking",
            ),
            full,
        )
    }

    @Test
    fun toRuffleOverrideNormalizesEnumFieldsButKeepsScreenTimeoutRaw() {
        val override = requireNotNull(
            PerGameSettingsStore.toRuffleOverride(
                JSONObject()
                    .put(PerGameSettingsStore.F_RUFFLE_SCREEN_TIMEOUT, " 45 ")
                    .put(PerGameSettingsStore.F_RUFFLE_BACKEND, " VULKAN ")
                    .put(PerGameSettingsStore.F_RUFFLE_QUALITY, " Best ")
                    .put(PerGameSettingsStore.F_RUFFLE_SCALE_MODE, " ExactFit ")
                    .put(PerGameSettingsStore.F_RUFFLE_LETTERBOX, " OFF ")
                    .put(PerGameSettingsStore.F_RUFFLE_LOAD_BEHAVIOR, " Blocking "),
            ),
        )

        // 枚举字段统一 trim/lowercase，与 mergeRuffle 的比较口径一致
        assertEquals("vulkan", override.backend)
        assertEquals("best", override.quality)
        assertEquals("exactfit", override.scaleMode)
        assertEquals("off", override.letterbox)
        assertEquals("blocking", override.loadBehavior)
        // screenTimeout 保留原样，由 mergeRuffle 自行 trim/toIntOrNull
        assertEquals(" 45 ", override.screenTimeout)
    }

    @Test
    fun allowListSetsCoverEveryOptionToken() {
        assertTrue("auto" in EngineSettingsStore.RUFFLE_BACKENDS)
        assertTrue("opengl" in EngineSettingsStore.RUFFLE_BACKENDS)
        assertTrue("vulkan" in EngineSettingsStore.RUFFLE_BACKENDS)
        assertEquals(setOf("low", "medium", "high", "best"), EngineSettingsStore.RUFFLE_QUALITIES)
        assertEquals(
            setOf("exactfit", "noborder", "noscale", "showall"),
            EngineSettingsStore.RUFFLE_SCALE_MODES,
        )
        assertEquals(setOf("on", "off"), EngineSettingsStore.RUFFLE_LETTERBOXES)
        assertEquals(setOf("streaming", "delayed", "blocking"), EngineSettingsStore.RUFFLE_LOAD_BEHAVIORS)
        assertEquals(setOf(0, 5, 10, 15, 30, 45, 60), EngineSettingsStore.RUFFLE_SCREEN_TIMEOUTS)
    }
}
