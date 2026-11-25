package com.tyranor.next.ui.common.motion

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitScrimTapTrackerTest {
    private fun tracker(
        downPosition: Offset = Offset(100f, 200f),
        downUptimeMillis: Long = 1_000L,
        tapSlopPx: Float = 24f,
        longPressMillis: Long = 400L,
    ) = ExitScrimTapTracker(
        downPosition = downPosition,
        downUptimeMillis = downUptimeMillis,
        tapSlopPx = tapSlopPx,
        longPressMillis = longPressMillis,
    )

    @Test fun quickSingleFingerLiftWithinSlopAndTimeoutReplaysAsTap() {
        val tracker = tracker()
        assertTrue(tracker.onPointerEvent(1, Offset(110f, 205f), targetPressed = true, eventUptimeMillis = 1_040L))
        assertFalse(tracker.onPointerEvent(1, Offset(112f, 206f), targetPressed = false, eventUptimeMillis = 1_080L))
        assertTrue(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun cancellingBeforeLiftNeverReplaysAHoldAsATap() {
        val tracker = tracker()
        assertTrue(tracker.onPointerEvent(1, Offset(100f, 200f), targetPressed = true, eventUptimeMillis = 1_150L))
        assertFalse(tracker.shouldReplayTap(cancelled = true))
    }

    @Test fun cancellingImmediatelyAfterDownWithoutMoveEventsDoesNotReplayAsTap() {
        val tracker = tracker()
        assertFalse(tracker.shouldReplayTap(cancelled = true))
    }

    @Test fun holdingPastLongPressTimeoutBeforeLiftDoesNotReplayAsTap() {
        val tracker = tracker()
        assertFalse(tracker.onPointerEvent(1, Offset(100f, 200f), targetPressed = false, eventUptimeMillis = 1_401L))
        assertFalse(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun liftingAtExactLongPressTimeoutBoundaryStillCountsAsTap() {
        val tracker = tracker()
        assertFalse(tracker.onPointerEvent(1, Offset(100f, 200f), targetPressed = false, eventUptimeMillis = 1_400L))
        assertTrue(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun draggingBeyondTapSlopDisqualifiesTapEvenWhenReturningNearOrigin() {
        val tracker = tracker(tapSlopPx = 24f)
        assertTrue(tracker.onPointerEvent(1, Offset(125f, 200f), targetPressed = true, eventUptimeMillis = 1_030L))
        assertFalse(tracker.onPointerEvent(1, Offset(102f, 200f), targetPressed = false, eventUptimeMillis = 1_070L))
        assertFalse(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun movementAtExactTapSlopBoundaryRemainsATap() {
        val tracker = tracker(tapSlopPx = 24f)
        assertFalse(tracker.onPointerEvent(1, Offset(124f, 200f), targetPressed = false, eventUptimeMillis = 1_060L))
        assertTrue(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun multiTouchDuringGestureDisqualifiesTapReplay() {
        val tracker = tracker()
        assertTrue(tracker.onPointerEvent(2, Offset(100f, 200f), targetPressed = true, eventUptimeMillis = 1_020L))
        assertFalse(tracker.onPointerEvent(1, Offset(100f, 200f), targetPressed = false, eventUptimeMillis = 1_060L))
        assertFalse(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun missingTargetPointerStopsTrackingAndDoesNotReplay() {
        val tracker = tracker()
        assertFalse(tracker.onPointerEvent(1, targetPosition = null, targetPressed = false, eventUptimeMillis = 1_050L))
        assertFalse(tracker.shouldReplayTap(cancelled = false))
    }
}
