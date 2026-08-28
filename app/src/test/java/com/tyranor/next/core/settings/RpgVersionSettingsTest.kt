package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RpgVersionSettingsTest {
    @Test
    fun rpgMvAndMzVersionConstantsAreV0() {
        assertEquals("v0", EngineSettingsStore.RPG_MV_V0)
        assertEquals("v0", EngineSettingsStore.RPG_MZ_V0)
    }

    @Test
    fun perGameRpgVersionKeysExist() {
        assertEquals("rpg_mv_engine_version", PerGameSettingsStore.F_RPG_MV_VERSION)
        assertEquals("rpg_mz_engine_version", PerGameSettingsStore.F_RPG_MZ_VERSION)
    }
}
