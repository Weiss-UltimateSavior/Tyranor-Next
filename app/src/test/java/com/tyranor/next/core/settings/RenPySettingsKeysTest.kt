package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenPySettingsKeysTest {

    @Test
    fun legacy85VersionNormalizesTo853WithoutChangingOtherValues() {
        assertEquals(EngineSettingsStore.RENPY_853, EngineSettingsStore.normalizeRenpyVersion(EngineSettingsStore.RENPY_85))
        assertEquals(EngineSettingsStore.RENPY_853, EngineSettingsStore.normalizeRenpyVersion(" 8.5 "))
        assertEquals(EngineSettingsStore.RENPY_853, EngineSettingsStore.normalizeRenpyVersion(EngineSettingsStore.RENPY_853))
        assertEquals(EngineSettingsStore.RENPY_77, EngineSettingsStore.normalizeRenpyVersion(EngineSettingsStore.RENPY_77))
        assertNull(EngineSettingsStore.normalizeRenpyVersion(null))
    }

    @Test
    fun globalAndPerGameKeysMatchLiteralContract() {
        assertEquals("renpy_cheats", EngineSettingsStore.KEY_RENPY_CHEATS)
        assertEquals("renpy_hw_video", EngineSettingsStore.KEY_RENPY_HW_VIDEO)
        assertEquals("renpy_autosave", EngineSettingsStore.KEY_RENPY_AUTOSAVE)
        assertEquals("renpy_phonesmallvariant", EngineSettingsStore.KEY_RENPY_PHONE_SMALL_VARIANT)
        assertEquals("renpy_vsync", EngineSettingsStore.KEY_RENPY_VSYNC)
        assertEquals("renpy_less_memory", EngineSettingsStore.KEY_RENPY_LESS_MEMORY)
        assertEquals("renpy_less_updates", EngineSettingsStore.KEY_RENPY_LESS_UPDATES)
        assertEquals("renpy_dont_use_gl2", EngineSettingsStore.KEY_RENPY_DONT_USE_GL2)
        assertEquals("renpy_recompile", EngineSettingsStore.KEY_RENPY_RECOMPILE)

        assertEquals(EngineSettingsStore.KEY_RENPY_CHEATS, PerGameSettingsStore.F_RENPY_CHEATS)
        assertEquals(EngineSettingsStore.KEY_RENPY_HW_VIDEO, PerGameSettingsStore.F_RENPY_HW_VIDEO)
        assertEquals(EngineSettingsStore.KEY_RENPY_AUTOSAVE, PerGameSettingsStore.F_RENPY_AUTOSAVE)
        assertEquals(EngineSettingsStore.KEY_RENPY_PHONE_SMALL_VARIANT, PerGameSettingsStore.F_RENPY_PHONE_SMALL_VARIANT)
        assertEquals(EngineSettingsStore.KEY_RENPY_VSYNC, PerGameSettingsStore.F_RENPY_VSYNC)
        assertEquals(EngineSettingsStore.KEY_RENPY_LESS_MEMORY, PerGameSettingsStore.F_RENPY_LESS_MEMORY)
        assertEquals(EngineSettingsStore.KEY_RENPY_LESS_UPDATES, PerGameSettingsStore.F_RENPY_LESS_UPDATES)
        assertEquals(EngineSettingsStore.KEY_RENPY_DONT_USE_GL2, PerGameSettingsStore.F_RENPY_DONT_USE_GL2)
        assertEquals(EngineSettingsStore.KEY_RENPY_RECOMPILE, PerGameSettingsStore.F_RENPY_RECOMPILE)
    }

    @Test
    fun renPyDefaultsMatchJoiPlay() {
        val d = EngineSettingsStore.RenPy()
        assertTrue(d.cheats)
        assertTrue(d.hwVideo)
        assertEquals(false, d.autosave)
        assertEquals(false, d.phoneSmallVariant)
        assertEquals(false, d.vsync)
        assertEquals(false, d.lessMemory)
        assertEquals(false, d.lessUpdates)
        assertEquals(false, d.dontUseGl2)
        assertEquals(false, d.recompile)
    }

    @Test
    fun toRenPyOverrideParsesOnlyPresentFields() {
        val json = JSONObject()
            .put(PerGameSettingsStore.F_RENPY_AUTOSAVE, true)
            .put(PerGameSettingsStore.F_RENPY_DONT_USE_GL2, true)

        val override = PerGameSettingsStore.toRenPyOverride(json)

        assertEquals(true, override?.autosave)
        assertEquals(true, override?.dontUseGl2)
        assertNull(override?.cheats)
        assertNull(override?.hwVideo)
    }

    @Test
    fun toRenPyOverrideReturnsNullWhenNoFieldPresent() {
        assertNull(PerGameSettingsStore.toRenPyOverride(null))
        assertNull(PerGameSettingsStore.toRenPyOverride(JSONObject()))
    }
}
