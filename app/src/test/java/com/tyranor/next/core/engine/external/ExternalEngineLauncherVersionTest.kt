package com.tyranor.next.core.engine.external

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEngineLauncherVersionTest {
    @Test
    fun acceptsOnlyInstalledVersionsAtOrAboveModuleMinimum() {
        assertFalse(ExternalEngineLauncher.isVersionCodeSupported(10_199L, 10_200L))
        assertTrue(ExternalEngineLauncher.isVersionCodeSupported(10_200L, 10_200L))
        assertTrue(ExternalEngineLauncher.isVersionCodeSupported(10_201L, 10_200L))
    }

    @Test
    fun zeroMinimumPreservesAnyInstalledVersionCompatibility() {
        assertTrue(ExternalEngineLauncher.isVersionCodeSupported(1L, 0L))
        assertTrue(ExternalEngineLauncher.isVersionCodeSupported(0L, 0L))
    }
}
