package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.FloatAnimationSpec
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.SpringSpec

/** One mounted entrance scope, on its UI thread. Never share mutable animation vectors or node state. */
internal class ReplayEntranceSprings {
    val alpha: FloatAnimationSpec = ReplaySpring(MotionSprings.effects(300))
    val progress: FloatAnimationSpec = ReplaySpring(MotionSprings.spring(460, 0.28f, 0.0005f))
}

/** Only the ordinary 0 → 1 leg of a quick replay is shared. The frame clock belongs to each Animatable. */
private class ReplaySpring(spec: SpringSpec<Float>) : FloatAnimationSpec {
    private val spring = spec.visibilityThreshold?.let { FloatSpringSpec(spec.dampingRatio, spec.stiffness, it) }
        ?: FloatSpringSpec(spec.dampingRatio, spec.stiffness)
    private val duration = spring.getDurationNanos(0f, 1f, 0f)
    private val values = Samples()
    private val velocities = Samples()

    private fun standard(start: Float, end: Float, velocity: Float) =
        start.toRawBits() == 0 && end == 1f && velocity.toRawBits() == 0

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: Float, targetValue: Float, initialVelocity: Float): Float =
        if (standard(initialValue, targetValue, initialVelocity))
            values.at(playTimeNanos) { spring.getValueFromNanos(playTimeNanos, 0f, 1f, 0f) }
        else spring.getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: Float, targetValue: Float, initialVelocity: Float): Float =
        if (standard(initialValue, targetValue, initialVelocity))
            velocities.at(playTimeNanos) { spring.getVelocityFromNanos(playTimeNanos, 0f, 1f, 0f) }
        else spring.getVelocityFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)

    override fun getDurationNanos(initialValue: Float, targetValue: Float, initialVelocity: Float): Long =
        if (standard(initialValue, targetValue, initialVelocity)) duration
        else spring.getDurationNanos(initialValue, targetValue, initialVelocity)

    override fun getEndVelocity(initialValue: Float, targetValue: Float, initialVelocity: Float): Float =
        spring.getEndVelocity(initialValue, targetValue, initialVelocity)

    private class Samples {
        // Milliseconds select a bucket only; a hit requires the ORIGINAL nanosecond timestamp.
        private val times = LongArray(64) { -1L }
        private val results = FloatArray(times.size)

        inline fun at(nanos: Long, compute: () -> Float): Float {
            if (nanos < 0L) return compute()
            val i = (nanos / 1_000_000L).toInt() and times.lastIndex
            if (times[i] != nanos) {
                results[i] = compute()
                times[i] = nanos
            }
            return results[i]
        }
    }
}
