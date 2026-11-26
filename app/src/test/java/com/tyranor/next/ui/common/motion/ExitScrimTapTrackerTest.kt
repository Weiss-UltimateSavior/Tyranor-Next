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
        assertTrue(tracker.onPointerEvent(1, Offset(110f, 205f), targetPressed = true, eventUptimeMillis = 1_040L, genuineRelease = false))
        assertFalse(tracker.onPointerEvent(1, Offset(112f, 206f), targetPressed = false, eventUptimeMillis = 1_080L, genuineRelease = true))
        assertTrue(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun cancellingBeforeLiftNeverReplaysAHoldAsATap() {
        val tracker = tracker()
        assertTrue(tracker.onPointerEvent(1, Offset(100f, 200f), targetPressed = true, eventUptimeMillis = 1_150L, genuineRelease = false))
        assertFalse(tracker.shouldReplayTap(cancelled = true))
    }

    @Test fun cancellingImmediatelyAfterDownWithoutMoveEventsDoesNotReplayAsTap() {
        val tracker = tracker()
        assertFalse(tracker.shouldReplayTap(cancelled = true))
    }

    @Test fun holdingPastLongPressTimeoutBeforeLiftDoesNotReplayAsTap() {
        val tracker = tracker()
        assertFalse(tracker.onPointerEvent(1, Offset(100f, 200f), targetPressed = false, eventUptimeMillis = 1_401L, genuineRelease = true))
        assertFalse(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun liftingAtExactLongPressTimeoutBoundaryStillCountsAsTap() {
        val tracker = tracker()
        assertFalse(tracker.onPointerEvent(1, Offset(100f, 200f), targetPressed = false, eventUptimeMillis = 1_400L, genuineRelease = true))
        assertTrue(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun draggingBeyondTapSlopDisqualifiesTapEvenWhenReturningNearOrigin() {
        val tracker = tracker(tapSlopPx = 24f)
        assertTrue(tracker.onPointerEvent(1, Offset(125f, 200f), targetPressed = true, eventUptimeMillis = 1_030L, genuineRelease = false))
        assertFalse(tracker.onPointerEvent(1, Offset(102f, 200f), targetPressed = false, eventUptimeMillis = 1_070L, genuineRelease = true))
        assertFalse(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun movementAtExactTapSlopBoundaryRemainsATap() {
        val tracker = tracker(tapSlopPx = 24f)
        assertFalse(tracker.onPointerEvent(1, Offset(124f, 200f), targetPressed = false, eventUptimeMillis = 1_060L, genuineRelease = true))
        assertTrue(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun multiTouchDuringGestureDisqualifiesTapReplay() {
        val tracker = tracker()
        assertTrue(tracker.onPointerEvent(2, Offset(100f, 200f), targetPressed = true, eventUptimeMillis = 1_020L, genuineRelease = false))
        assertFalse(tracker.onPointerEvent(1, Offset(100f, 200f), targetPressed = false, eventUptimeMillis = 1_060L, genuineRelease = true))
        assertFalse(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun synthesizedCancelThatLooksLikeAReleaseNeverReplaysAsTap() {
        // 窗口退场末段改成不拦触摸时，系统取消手势，Compose 合成出一个「pressed=false」的事件（没有 ACTION_UP）：
        // 它很快、没位移，满足单击的时间与位移条件，但手指其实还按着，不能补发单击
        val tracker = tracker()
        assertFalse(tracker.onPointerEvent(1, Offset(100f, 200f), targetPressed = false, eventUptimeMillis = 1_132L, genuineRelease = false))
        assertFalse(tracker.shouldReplayTap(cancelled = false))
    }

    @Test fun missingTargetPointerStopsTrackingAndDoesNotReplay() {
        val tracker = tracker()
        assertFalse(tracker.onPointerEvent(1, targetPosition = null, targetPressed = false, eventUptimeMillis = 1_050L, genuineRelease = false))
        assertFalse(tracker.shouldReplayTap(cancelled = false))
    }
}
