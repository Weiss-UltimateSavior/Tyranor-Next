package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEmulatorRegistryTest {

    @Test
    fun mapsEnginesToTargets() {
        val psp = ExternalEmulatorRegistry.forEngine(EngineType.PSP)
        assertEquals("org.ppsspp.ppsspp", psp?.packageName)
        assertEquals("org.ppsspp.ppsspp.PpssppActivity", psp?.activityName)
        assertEquals("*/*", psp?.mime)
        assertTrue(psp?.grantWrite == true)

        val switch = ExternalEmulatorRegistry.forEngine(EngineType.NINTENDO_SWITCH)
        assertEquals("dev.eden.eden_emulator", switch?.packageName)
        // Eden 为 yuzu 派生：Activity 沿用 yuzu 命名空间
        assertEquals("org.yuzu.yuzu_emu.activities.EmulationActivity", switch?.activityName)
        assertEquals("application/octet-stream", switch?.mime)
        assertFalse(switch?.grantWrite == true)
    }

    @Test
    fun noTargetForBuiltInEngines() {
        assertNull(ExternalEmulatorRegistry.forEngine(EngineType.KIRIKIRI))
        assertNull(ExternalEmulatorRegistry.forEngine(EngineType.RENPY))
        assertNull(ExternalEmulatorRegistry.forEngine(EngineType.UNKNOWN))
    }

    @Test
    fun targetsHaveInstallUrlAndDisplayName() {
        ExternalEmulatorRegistry.targets.forEach { target ->
            assertTrue(target.installUrl.startsWith("http"))
            assertTrue(target.displayNameRes != 0)
        }
    }
}
