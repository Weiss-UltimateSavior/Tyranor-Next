package com.tyranor.next.ui.common.motion

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRotationTest {
    private val portrait = IntSize(1200, 1920)
    private val landscape = IntSize(1920, 1200)

    @Test
    fun captureAtTheDeadlineIsAcceptedButLateSuccessIsNot() {
        assertTrue(rotationCaptureWithinBudget(finished = true, startedAt = 1_000L, checkedAt = 1_080L))
        assertFalse(rotationCaptureWithinBudget(finished = false, startedAt = 1_000L, checkedAt = 1_080L))
        assertFalse(rotationCaptureWithinBudget(finished = true, startedAt = 1_000L, checkedAt = 1_081L))
    }

    @Test
    fun missingSnapshotWithoutAPreviousTransitionSettlesImmediately() {
        assertNull(rotationRetarget(portrait, active = null, drawn = null, hasFreshSnapshot = false))
    }

    @Test
    fun freshSnapshotKeepsVirtualLayout() {
        val leg = requireNotNull(rotationRetarget(portrait, active = null, drawn = null, hasFreshSnapshot = true))

        assertTrue(leg.usesVirtualLayout)
        assertFalse(leg.redirected)
        assertEquals(portrait, leg.virtualSize(landscape, 0f))
        assertEquals(landscape, leg.virtualSize(landscape, 1f))
    }

    @Test
    fun missingSnapshotDuringRotationContinuesFromLastDrawButUsesFinalLayout() {
        val active = requireNotNull(rotationRetarget(portrait, active = null, drawn = null, hasFreshSnapshot = true))
        val painted = rotationViewport(landscape, IntSize(1600, 1600))
        val leg = requireNotNull(rotationRetarget(landscape, active, painted, hasFreshSnapshot = false))

        assertTrue(leg.redirected)
        assertFalse(leg.usesVirtualLayout)
        assertEquals(painted.virtualSize, leg.virtualSize(portrait, 0f))
        val start = rotationFinalLayoutViewport(portrait, rotationViewport(portrait, leg.virtualSize(portrait, 0f)))
        assertEquals(painted.virtualSize, start.virtualSize)
        assertEquals(IntRect(225, 360, 975, 1560), start.bounds)
        val end = rotationFinalLayoutViewport(portrait, rotationViewport(portrait, leg.virtualSize(portrait, 1f)))
        assertEquals(IntRect(0, 0, portrait.width, portrait.height), end.bounds)
        assertEquals(1f, end.scale, 0f)
    }

    @Test
    fun redirectBeforeFirstDrawStartsAtThePreviousLegOrigin() {
        val active = requireNotNull(rotationRetarget(portrait, active = null, drawn = null, hasFreshSnapshot = true))
        val leg = requireNotNull(rotationRetarget(landscape, active, drawn = null, hasFreshSnapshot = false))

        assertTrue(leg.redirected)
        assertEquals(portrait, leg.virtualSize(portrait, 0f))
    }

    @Test
    fun freshSnapshotAfterAFailedRedirectRestoresVirtualLayout() {
        val active = requireNotNull(rotationRetarget(portrait, active = null, drawn = null, hasFreshSnapshot = true))
        val failed = requireNotNull(rotationRetarget(landscape, active, drawn = null, hasFreshSnapshot = false))
        val virtualSize = IntSize(1600, 1600)
        val painted = rotationFinalLayoutViewport(portrait, rotationViewport(portrait, virtualSize))
        val plan = requireNotNull(rotationCapturePlan(portrait, portrait, painted.bounds, liveNotice = true, snapshotNotice = false))
        val recovered = requireNotNull(rotationRetarget(portrait, failed, painted, hasFreshSnapshot = true))

        assertEquals(IntRect(225, 360, 975, 1560), plan.crop)
        assertTrue(plan.mayContainNotice)
        assertTrue(recovered.usesVirtualLayout)
        assertTrue(recovered.redirected)
        assertEquals(virtualSize, recovered.virtualSize(landscape, 0f))
    }

    @Test
    fun failedRedirectRetainsOnlyTheVisibleSnapshotAndItsNotice() {
        val alpha = rotationSnapshotAlpha(1f, 0.14f)
        val inherited = requireNotNull(rotationFallbackAlpha(startAlpha = 1f, drawnAlpha = alpha))

        assertEquals(0.5f, alpha, 0.0001f)
        assertEquals(alpha, rotationSnapshotAlpha(inherited, 0f), 0f)
        assertTrue(rotationSnapshotOwnsNotice(hasNotice = true, alpha = inherited))
        assertNull(rotationFallbackAlpha(startAlpha = 1f, drawnAlpha = rotationSnapshotAlpha(1f, 0.28f)))
        assertFalse(rotationSnapshotOwnsNotice(hasNotice = true, alpha = 0f))
    }

    @Test
    fun failedRedirectBeforeNextDrawDoesNotDropOrRebrightenTheSnapshot() {
        assertEquals(0.35f, requireNotNull(rotationFallbackAlpha(startAlpha = 0.35f, drawnAlpha = null)), 0f)
        assertNull(rotationFallbackAlpha(startAlpha = 0.35f, drawnAlpha = 0f))
    }
}
