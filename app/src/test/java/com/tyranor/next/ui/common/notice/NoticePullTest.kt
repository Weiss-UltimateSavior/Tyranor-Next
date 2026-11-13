package com.tyranor.next.ui.common.notice

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticePullTest {
    @Test fun sidewaysDragReturningToDownPositionIsNotATap() {
        val pull = NoticePull(slop = 8f, limit = 14f, start = 0f)
        assertEquals(0f, pull.move(Offset(30f, 0f)), 0f)
        pull.move(Offset(-30f, 0f))
        assertFalse(pull.dismissOnUp(24f, 0f))
    }

    @Test fun grabbingAReboundRetainsPositionButDismissUsesOnlyThisGesture() {
        val pull = NoticePull(slop = 8f, limit = 14f, start = -20f)
        assertEquals(-20f, pull.move(Offset.Zero), 0f)
        assertEquals(-30f, pull.move(Offset(0f, -10f)), 0f)
        assertFalse(pull.dismissOnUp(24f, 0f))
        assertTrue(pull.dismissOnUp(24f, -601f))
        pull.move(Offset(0f, -15f))
        assertTrue(pull.dismissOnUp(24f, 0f))
    }

    @Test fun pullingDownAgainKeepsRubberBandBoundAndTapsStillDismiss() {
        val pull = NoticePull(slop = 8f, limit = 14f, start = 10f)
        assertEquals(10f, pull.move(Offset(0f, 1f)), 0f)
        assertTrue(pull.dismissOnUp(24f, 0f))
        val offset = pull.move(Offset(0f, 500f))
        assertTrue(offset > 10f && offset <= 14f)
        assertFalse(pull.dismissOnUp(24f, 0f))
    }

    @Test fun fastReleaseUsesDisplayedVelocityAndCanBeRegrabbedAtItsPeak() {
        val pull = NoticePull(slop = 8f, limit = 14f, start = 0f)
        val start = pull.move(Offset(0f, 90f))
        assertEquals(12.115f, start, 0.001f)
        val speed = pull.releaseVelocity(3000f)
        assertEquals(54.364f, speed, 0.01f)
        assertReboundAndRegrab(start, speed)
    }

    @Test fun shallowFastFlickIsBoundedEvenWhenTheMappedVelocityIsStillHigh() {
        val pull = NoticePull(slop = 8f, limit = 14f, start = 0f)
        val start = pull.move(Offset(0f, 9f))
        assertReboundAndRegrab(start, pull.releaseVelocity(3000f))
    }

    private fun assertReboundAndRegrab(start: Float, velocity: Float) {
        val drag = NoticeDrag().apply { offset = start }
        val frames = mutableListOf<Float>()
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                check(frames.size < 1000) { "Rebound failed to settle" }
                return onFrame((frames.size + 1L) * 4_000_000L).also { frames.add(drag.offset) }
            }
        }
        runBlocking(clock) { drag.settle(this, 14f, velocity) }
        val peak = frames.max()
        assertTrue("Expected outward motion below 14px, got ${peak}px", peak > start && peak < 14f)
        assertEquals(0f, drag.offset, 0f)
        val regrab = NoticePull(slop = 8f, limit = 14f, start = peak)
        assertEquals(peak, regrab.move(Offset.Zero), 0f)
        assertTrue(regrab.move(Offset(0f, 12f)) > peak)
        assertTrue(regrab.move(Offset(0f, -20f)) < peak)
    }
}
