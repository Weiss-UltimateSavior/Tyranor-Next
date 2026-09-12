package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpgMakerSettingsKeysTest {

    @Test
    fun perGameOverrideKeysMatchGlobalNamingContract() {
        assertEquals("rpg_use_ruby18", PerGameSettingsStore.F_RPG_USE_RUBY18)
        assertEquals("rpg_smooth_scaling", PerGameSettingsStore.F_RPG_SMOOTH_SCALING)
        assertEquals("rpg_path_cache", PerGameSettingsStore.F_RPG_PATH_CACHE)
        assertEquals("rpg_prebuilt_path_cache", PerGameSettingsStore.F_RPG_PREBUILT_PATH_CACHE)
        assertEquals("rpg_fast_path_enum", PerGameSettingsStore.F_RPG_FAST_PATH_ENUM)
        assertEquals("rpg_use_cjk_font", PerGameSettingsStore.F_RPG_USE_CJK_FONT)
        assertEquals("rpg_custom_font", PerGameSettingsStore.F_RPG_CUSTOM_FONT)
        assertEquals("rpg_vertical_screen_align", PerGameSettingsStore.F_RPG_VERTICAL_SCREEN_ALIGN)
        assertEquals("rpg_window_size", PerGameSettingsStore.F_RPG_WINDOW_SIZE)
        assertEquals("rpg_speed_up", PerGameSettingsStore.F_RPG_SPEED_UP)
        assertEquals("rpg_font_scale", PerGameSettingsStore.F_RPG_FONT_SCALE)
    }

    @Test
    fun rpgMakerDefaultsMatchJoiPlay() {
        val d = EngineSettingsStore.RpgMaker()
        assertTrue(d.useRuby18)
        assertTrue(d.smoothScaling)
        assertTrue(d.prebuiltPathCache)
        assertTrue(d.fastPathEnum)
        assertTrue(d.cheats)
        assertEquals(false, d.debug)
        assertEquals(false, d.vsync)
        assertEquals(false, d.frameSkip)
        assertEquals(false, d.solidFonts)
        assertEquals(false, d.pathCache)
        assertEquals(false, d.copyText)
        assertEquals(false, d.useCJKFont)
        assertEquals(false, d.enablePostloadScripts)
        assertEquals("", d.customFont)
        assertEquals("top-center", d.verticalScreenAlign)
        assertEquals("640x480", d.windowSize)
        assertEquals("1", d.speedUp)
        assertEquals("0.75", d.fontScale)
    }

    @Test
    fun whitelistNormalizationFallsBackToDefaults() {
        assertEquals("640x480", EngineSettingsStore.normalizeRpgWindowSize("bogus"))
        assertEquals("1920x1080", EngineSettingsStore.normalizeRpgWindowSize("1920x1080"))
        assertEquals("1", EngineSettingsStore.normalizeRpgSpeedUp("42"))
        assertEquals("9", EngineSettingsStore.normalizeRpgSpeedUp("9"))
        assertEquals("0.75", EngineSettingsStore.normalizeRpgFontScale("0.73"))
        assertEquals("2.00", EngineSettingsStore.normalizeRpgFontScale("2.00"))
        assertEquals("top-center", EngineSettingsStore.normalizeRpgVerticalAlign("bogus"))
        assertEquals("center", EngineSettingsStore.normalizeRpgVerticalAlign("center"))
    }

    @Test
    fun toRpgMakerOverrideParsesOnlyPresentFields() {
        val json = JSONObject()
            .put(PerGameSettingsStore.F_RPG_VSYNC, true)
            .put(PerGameSettingsStore.F_RPG_WINDOW_SIZE, "1280x720")
            .put(PerGameSettingsStore.F_RPG_CUSTOM_FONT, "")

        val override = PerGameSettingsStore.toRpgMakerOverride(json)

        assertEquals(true, override?.vsync)
        assertEquals("1280x720", override?.windowSize)
        assertEquals("", override?.customFont)
        assertNull(override?.useRuby18)
        assertNull(override?.speedUp)
    }

    @Test
    fun toRpgMakerOverrideReturnsNullWhenNoFieldPresent() {
        assertNull(PerGameSettingsStore.toRpgMakerOverride(null))
        assertNull(PerGameSettingsStore.toRpgMakerOverride(JSONObject()))
    }
}
