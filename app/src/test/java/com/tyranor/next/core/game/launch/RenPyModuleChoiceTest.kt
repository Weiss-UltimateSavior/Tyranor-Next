package com.tyranor.next.core.game.launch

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenPyModuleChoiceTest {
    @Test
    fun autoWithoutScriptVersionIsUnverified() {
        assertTrue(renpyModuleChoiceUnverified(EngineType.RENPY, EngineSettingsStore.RENPY_AUTO, null))
    }

    @Test
    fun readableScriptVersionIsVerified() {
        assertFalse(
            renpyModuleChoiceUnverified(EngineType.RENPY, EngineSettingsStore.RENPY_AUTO, EngineSettingsStore.RENPY_86),
        )
    }

    @Test
    fun explicitVersionIsNeverUnverified() {
        assertFalse(renpyModuleChoiceUnverified(EngineType.RENPY, EngineSettingsStore.RENPY_86, null))
        assertFalse(renpyModuleChoiceUnverified(EngineType.RENPY, EngineSettingsStore.RENPY_853, null))
    }

    @Test
    fun otherEnginesAreNeverUnverified() {
        assertFalse(renpyModuleChoiceUnverified(EngineType.RUFFLE, EngineSettingsStore.RENPY_AUTO, null))
    }
}
