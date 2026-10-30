package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeLaunchGraceRegistryTest {
    @Test
    fun failedSecondLaunchCannotClearFirst() {
        val registry = NativeLaunchGraceRegistry(maxEntries = 4)

        registry.mark("game", generation = 1L, token = "first", nowMillis = 0L, durationMillis = 100L)
        registry.mark("game", generation = 1L, token = "second", nowMillis = 0L, durationMillis = 100L)
        registry.clear("game", token = "second", generation = 1L)

        assertTrue(registry.active("game", generation = 1L, nowMillis = 1L))
    }

    @Test
    fun multipleTokensForOneUriAreIndependent() {
        val registry = NativeLaunchGraceRegistry(maxEntries = 4)

        registry.mark("game", generation = 1L, token = "first", nowMillis = 0L, durationMillis = 100L)
        registry.mark("game", generation = 2L, token = "second", nowMillis = 0L, durationMillis = 100L)

        assertTrue(registry.active("game", generation = 2L, nowMillis = 1L))
        registry.clear("game", token = "second", generation = 2L)
        assertFalse(registry.active("game", generation = 2L, nowMillis = 1L))

        registry.clear("game", token = "first", generation = 1L)
        assertFalse(registry.active("game", generation = 1L, nowMillis = 1L))
    }

    @Test
    fun clearWithGenerationRetiresOnlyTheExactTokenGeneration() {
        val registry = NativeLaunchGraceRegistry(maxEntries = 4)

        registry.mark("game", generation = 1L, token = "shared", nowMillis = 0L, durationMillis = 100L)
        registry.mark("game", generation = 2L, token = "shared", nowMillis = 0L, durationMillis = 100L)
        registry.clear("game", token = "shared", generation = 2L)

        assertTrue(registry.active("game", generation = 1L, nowMillis = 1L))
        assertFalse(registry.active("game", generation = 2L, nowMillis = 1L))
    }

    @Test
    fun clearWithoutGenerationRetiresEveryGenerationOfTheToken() {
        val registry = NativeLaunchGraceRegistry(maxEntries = 4)

        registry.mark("game", generation = 1L, token = "shared", nowMillis = 0L, durationMillis = 100L)
        registry.mark("game", generation = 2L, token = "shared", nowMillis = 0L, durationMillis = 100L)
        registry.clear("game", token = "shared")

        assertFalse(registry.active("game", generation = 1L, nowMillis = 1L))
        assertFalse(registry.active("game", generation = 2L, nowMillis = 1L))
    }

    @Test
    fun staleSnapshotRespectsNewerGeneration() {
        val registry = NativeLaunchGraceRegistry(maxEntries = 4)
        registry.mark("game", generation = 2L, token = "newer", nowMillis = 100L, durationMillis = 50L)

        assertTrue(registry.active("game", generation = 1L, nowMillis = 149L))
        assertFalse(registry.active("game", generation = 3L, nowMillis = 149L))
    }

    @Test
    fun expirationIsHalfOpen() {
        val registry = NativeLaunchGraceRegistry(maxEntries = 4)
        registry.mark("game", generation = 1L, token = "launch", nowMillis = 1_000L, durationMillis = 10L)

        assertTrue(registry.active("game", generation = 1L, nowMillis = 1_009L))
        assertFalse(registry.active("game", generation = 1L, nowMillis = 1_010L))
    }

    @Test
    fun capacityIsCountedPerTokenAndEvictsTheEarliestExpiry() {
        val registry = NativeLaunchGraceRegistry(maxEntries = 2)
        registry.mark("game", generation = 1L, token = "first", nowMillis = 0L, durationMillis = 100L)
        registry.mark("game", generation = 2L, token = "second", nowMillis = 0L, durationMillis = 200L)
        registry.mark("game", generation = 3L, token = "third", nowMillis = 0L, durationMillis = 300L)

        registry.clear("game", token = "second", generation = 2L)
        registry.clear("game", token = "third", generation = 3L)
        assertFalse(registry.active("game", generation = 1L, nowMillis = 1L))
    }

    @Test
    fun capacityMustBePositive() {
        try {
            NativeLaunchGraceRegistry(maxEntries = 0)
            fail("Zero capacity was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
