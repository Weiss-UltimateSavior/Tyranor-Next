package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayEntranceSpringsTest {
    private class Curve(val name: String, original: AnimationSpec<Float>, replay: AnimationSpec<Float>) {
        val expected = original.vectorize(Float.VectorConverter)
        val actual = replay.vectorize(Float.VectorConverter)
    }

    private val replay = ReplayEntranceSprings()
    private val curves = listOf(
        Curve("alpha", MotionSprings.effects(300), replay.alpha), // Default displacement threshold.
        Curve("progress", MotionSprings.spring(460, 0.28f, 0.0005f), replay.progress),
    )

    private data class Leg(val start: Float, val target: Float, val initialVelocity: Float)
    private val normal = Leg(0f, 1f, 0f)

    private fun checkLeg(curve: Curve, leg: Leg, times: (duration: Long) -> LongArray) {
        val start = AnimationVector1D(leg.start)
        val target = AnimationVector1D(leg.target)
        val velocity = AnimationVector1D(leg.initialVelocity)
        val id = "${curve.name} $leg"
        val duration = curve.expected.getDurationNanos(start, target, velocity)
        assertEquals("$id duration", duration, curve.actual.getDurationNanos(start, target, velocity))
        assertBits("$id end velocity", curve.expected.getEndVelocity(start, target, velocity).value,
            curve.actual.getEndVelocity(start, target, velocity).value)
        for (t in times(duration)) {
            assertBits("$id value at ${t}ns", curve.expected.getValueFromNanos(t, start, target, velocity).value,
                curve.actual.getValueFromNanos(t, start, target, velocity).value)
            assertBits("$id velocity at ${t}ns", curve.expected.getVelocityFromNanos(t, start, target, velocity).value,
                curve.actual.getVelocityFromNanos(t, start, target, velocity).value)
        }
    }

    private fun assertBits(label: String, expected: Float, actual: Float) =
        assertEquals(label, expected.toRawBits(), actual.toRawBits())

    @Test fun replayMatchesEachOriginalSpringThroughItsOwnEndTime() {
        for (curve in curves) checkLeg(curve, normal) { duration ->
            longArrayOf(0L, 8_333_333L, 83_333_333L, duration / 2, duration - 1, duration, duration + 1)
        }
    }

    @Test fun sameMillisecondAndCollidingSamplesCanBeRevisitedInAnyOrder() {
        val t = 8_123_456L
        // t and t+1 share a millisecond; t and t+64ms share a slot but have different spring values.
        val collision = t + 64_000_000L
        for (curve in curves) checkLeg(curve, normal) {
            longArrayOf(t, t, t + 1, t, collision, collision + 1, t, collision, -1L, t)
        }
    }

    @Test fun interruptedLegsDoNotBorrowOrOverwriteNormalReplaySamples() {
        val interrupted = listOf(
            Leg(0.37f, 1f, 0f), Leg(0f, 0.65f, 0f), Leg(0f, 1f, 2.9f),
            Leg(1.013f, 1f, -1.7f), Leg(1f, 0f, 0f),
            Leg(-0f, 1f, 0f), Leg(0f, 1f, -0f),
        )
        for (curve in curves) {
            // At 9 ms, affinely rescaling the unit progress spring for 0.37 → 1 loses one ULP.
            val times = { d: Long -> longArrayOf(0L, 8_333_333L, 9_000_000L, 33_333_333L, d) }
            checkLeg(curve, normal, times)
            for (leg in interrupted) {
                checkLeg(curve, leg, times)
                checkLeg(curve, normal, times)
            }
        }
    }
}
