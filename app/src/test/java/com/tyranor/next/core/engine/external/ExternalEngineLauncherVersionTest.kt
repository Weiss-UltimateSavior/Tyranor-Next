package com.tyranor.next.core.engine.external

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEngineLauncherVersionTest {
    @Test
    fun samePackageVersionsAreNotInterchangeable() {
        assertNull(ExternalEngineLauncher.versionCodeError(10100L, RenPyExternalEngineModule))
        assertEquals(
            ExternalEngineErrorCode.PACKAGE_VERSION_MISMATCH,
            ExternalEngineLauncher.versionCodeError(10200L, RenPyExternalEngineModule),
        )
        assertEquals(
            ExternalEngineErrorCode.PACKAGE_OUTDATED,
            ExternalEngineLauncher.versionCodeError(10100L, RenPy853ExternalEngineModule),
        )
        assertNull(ExternalEngineLauncher.versionCodeError(10200L, RenPy853ExternalEngineModule))
    }

    @Test
    fun oldModuleVersionRangeIncludesBothBoundaries() {
        assertEquals(
            ExternalEngineErrorCode.PACKAGE_OUTDATED,
            ExternalEngineLauncher.versionCodeError(10099L, RenPyExternalEngineModule),
        )
        assertNull(ExternalEngineLauncher.versionCodeError(10199L, RenPyExternalEngineModule))
        assertNull(ExternalEngineLauncher.versionCodeError(0L, RenPy77ExternalEngineModule))
        assertNull(ExternalEngineLauncher.versionCodeError(Long.MAX_VALUE, RenPy77ExternalEngineModule))
    }

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
