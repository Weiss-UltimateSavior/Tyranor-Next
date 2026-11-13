package com.tyranor.next.ui.common

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropdownSlideMotionTest {
    @Test fun selectingAnotherMotionModeStillClosesButReopeningOrPickingAgainInvalidatesTheOldCompletion() {
        val epoch = DropdownSelectionEpoch()
        epoch.invalidate() // Popup opens.
        val selectingOrdinary = epoch.beginSelection()
        // Replacing the slide state for a motion-mode/options change is not another popup opening.
        DropdownSlideState().reset()
        assertTrue("An already delivered selection must still finish closing", epoch.isCurrent(selectingOrdinary))
        epoch.invalidate() // User closes the popup before the deferred completion.
        epoch.invalidate() // User opens a genuinely new session.
        assertFalse("An old completion must never close the reopened popup", epoch.isCurrent(selectingOrdinary))
        val firstPick = epoch.beginSelection()
        val secondPick = epoch.beginSelection()
        assertFalse(epoch.isCurrent(firstPick))
        assertTrue(epoch.isCurrent(secondPick))
    }

    @Test fun closingClearsGeometryButStartingAnotherGestureRetainsTheOpenPopupBounds() {
        val state = DropdownSlideState()
        state.itemBounds[0] = Rect(0f, 100f, 200f, 160f)
        state.active = true
        state.hovered = 0
        state.fingerY = 130f
        assertNotNull(state.pillTarget(1).center)
        state.resetGesture()
        assertEquals(1, state.itemBounds.size)
        assertNull(state.pillTarget(1).center)
        state.active = true
        state.hovered = 0
        state.itemBounds[0] = Rect(0f, 300f, 200f, 360f)
        state.fingerY = 330f
        assertEquals(330f, state.pillTarget(1).center!!, 0f)
        state.reset()
        assertTrue(state.itemBounds.isEmpty())
        state.active = true
        state.hovered = 0
        assertNull("A reopened popup cannot target last session's row", state.pillTarget(1).center)
    }

    @Test fun replacedOptionsCannotUseAnIndexOutsideTheirNewRange() {
        val state = DropdownSlideState()
        state.itemBounds[3] = Rect(0f, 300f, 200f, 360f)
        state.active = true
        state.hovered = 3
        state.confirmed = 3
        assertNull(state.pillTarget(2).center)
        state.reset()
        assertFalse(state.active)
        assertEquals(-1, state.hovered)
        assertEquals(-1, state.confirmed)
    }

    @Test fun openIdlePopupRequestsNoFramesAndSettledMotionSleepsUntilAnotherInput() = runBlocking {
        val target = mutableStateOf(DropdownPillTarget(null, false))
        val clock = CountingClock()
        var center = 0f
        var alpha = 0f
        var speed = 0f
        val job = launch(clock) {
            runDropdownPillMotion({ target.value }) {
                center = it.center
                alpha = it.alpha
                speed = it.speed
            }
        }
        suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
            while (!condition()) delay(1)
        }
        try {
            delay(20)
            assertEquals("Opening without a finger must not request a frame", 0, clock.frames)
            Snapshot.withMutableSnapshot { target.value = DropdownPillTarget(120f, false) }
            await { alpha == 1f && center == 120f && speed == 0f }
            val firstRest = clock.frames
            assertTrue(firstRest > 0)
            delay(20)
            assertEquals("A resting highlight must stop requesting frames", firstRest, clock.frames)
            Snapshot.withMutableSnapshot { target.value = DropdownPillTarget(260f, true) }
            await { center == 260f && speed == 0f }
            assertTrue(clock.frames > firstRest)
            val secondRest = clock.frames
            delay(20)
            assertEquals(secondRest, clock.frames)
            Snapshot.withMutableSnapshot { target.value = DropdownPillTarget(null, false) }
            await { alpha == 0f }
            val hiddenRest = clock.frames
            delay(20)
            assertEquals("An invisible highlight must also sleep", hiddenRest, clock.frames)
        } finally {
            job.cancelAndJoin()
        }
    }

    private class CountingClock : MonotonicFrameClock {
        var frames = 0
            private set
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(1)
            check(frames < 1_000) { "Highlight failed to settle" }
            frames++
            return onFrame(frames * 16_000_000L)
        }
    }
}
