package com.tyranor.next.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropdownSlideMotionTest {
    @Test fun reopeningOrPickingAgainInvalidatesTheOldCompletion() {
        val epoch = DropdownSelectionEpoch()
        epoch.invalidate()
        val first = epoch.beginSelection()
        assertTrue(epoch.isCurrent(first))
        epoch.invalidate()
        assertFalse(epoch.isCurrent(first))
        val next = epoch.beginSelection()
        val latest = epoch.beginSelection()
        assertFalse(epoch.isCurrent(next))
        assertTrue(epoch.isCurrent(latest))
    }

    @Test fun cancellingThePreviewRestoresCommittedHighlightWithoutForgettingOpenPopupBounds() {
        val state = DropdownSlideState()
        state.itemBounds[0] = Rect(0f, 100f, 200f, 160f)
        val gesture = state.beginGesture(selected = 0)
        state.hovered = 1
        assertEquals(1, state.highlightedIndex(0))
        state.cancelGesture(gesture)
        assertEquals(0, state.highlightedIndex(0))
        assertFalse(state.active)
        assertEquals(1, state.itemBounds.size)
        state.reset()
        assertTrue(state.itemBounds.isEmpty())
    }

    @Test fun anOldGestureCannotCancelOrCommitANewerPreview() {
        val state = DropdownSlideState()
        val old = state.beginGesture()
        val current = state.beginGesture()
        state.hovered = 2
        state.cancelGesture(old)
        assertFalse(state.isCurrent(old))
        assertTrue(state.isCurrent(current))
        assertTrue(state.active)
        assertEquals(2, state.highlightedIndex(0))
    }

    @Test fun releaseConfirmationSurvivesGestureCleanupUntilThePopupCloses() {
        val state = DropdownSlideState()
        val gesture = state.beginGesture()
        state.confirmed = 2
        state.active = false
        state.closing = true
        state.cancelGesture(gesture)
        assertEquals(2, state.highlightedIndex(0))
        assertTrue(state.closing)
        state.reset()
        assertEquals(0, state.highlightedIndex(0))
        assertFalse(state.closing)
    }

    @Test fun hitRequiresAnEnabledOptionAndBothCoordinatesInsideItsVisibleBounds() {
        val state = DropdownSlideState()
        state.itemBounds[0] = Rect(100f, 100f, 200f, 150f)
        state.itemBounds[1] = Rect(100f, 150f, 200f, 200f)
        state.itemBounds[2] = Rect(100f, 200f, 200f, 250f)
        val enabled = listOf(true, false, true)
        assertEquals(0, state.hit(Offset(120f, 125f), enabled))
        assertEquals(-1, state.hit(Offset(120f, 175f), enabled))
        assertEquals(2, state.hit(Offset(120f, 225f), enabled))
        assertEquals(-1, state.hit(Offset(99f, 125f), enabled))
        assertEquals(-1, state.hit(Offset(200f, 125f), enabled))
        assertEquals(-1, state.hit(Offset(120f, 250f), enabled))
        assertEquals(-1, state.hit(Offset(Float.NaN, 125f), enabled))
    }

    @Test fun replacedOptionsAndFullyClippedRowsCannotBeSelected() {
        val state = DropdownSlideState()
        state.itemBounds[0] = Rect(100f, 100f, 200f, 100f)
        state.itemBounds[3] = Rect(100f, 200f, 200f, 250f)
        assertEquals(-1, state.hit(Offset(120f, 100f), listOf(true, true)))
        assertEquals(-1, state.hit(Offset(120f, 225f), listOf(true, true)))
    }
}
