package com.tyranor.next.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.archive.archiveProgress
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.engine.rememberStorageSweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProgressClockContinuityDeviceTest {
    private val durationScale = object : MotionDurationScale {
        override val scaleFactor = 1f
    }

    @get:Rule val compose = createComposeRule(effectContext = durationScale)

    @Test
    fun storageSweepResumesOnItsUninterruptedTrajectory() {
        compareDrawnTrajectories(initialTarget = 1f) { target ->
            rememberStorageSweep(target())
        }
    }

    @Test
    fun archiveProgressTracksANewTargetEvenWhileDrawingRawProgress() {
        compareDrawnTrajectories(initialTarget = 0.6f, newTarget = 1f) { target ->
            archiveProgress(target, label = "testArchiveProgress")
        }
    }

    // Replay the same inputs twice on fresh instances. The uninterrupted run is the oracle:
    // comparing several drawn frames after the switch catches both a position jump and lost velocity.
    private fun compareDrawnTrajectories(
        initialTarget: Float,
        newTarget: Float? = null,
        progress: @Composable (() -> Float) -> (() -> Float),
    ) {
        MotionDeviceAcceptance.require(enhanced = null)
        val clock = compose.mainClock
        val previousAutoAdvance = clock.autoAdvance
        var trial by mutableIntStateOf(0)
        var target by mutableFloatStateOf(0f)
        var drawn = Float.NaN
        // 动效中途的取样点：before = 动效进行中，resumed = 之后仍在途中（弹簧没有停摆）
        val beforeFrame = 4
        val resumedFrame = 10
        val retargetFrame = 7

        try {
            clock.autoAdvance = false
            compose.setContent {
                key(trial) {
                    val value = progress { target }
                    Canvas(Modifier.size(32.dp)) {
                        drawn = value()
                        drawRect(Color.Black, size = Size(size.width * drawn, size.height))
                    }
                }
            }

            fun sample(): Float {
                clock.advanceTimeByFrame()
                return compose.runOnIdle { drawn }
            }

            fun trace(): List<Float> {
                compose.runOnIdle {
                    trial++
                    target = 0f
                    drawn = Float.NaN
                }
                repeat(2) { sample() }
                assertEquals("fresh instance", 0f, sample(), 0f)
                compose.runOnIdle { target = initialTarget }
                return (0..16).map { frame ->
                    compose.runOnIdle {
                        if (frame == retargetFrame && newTarget != null) target = newTarget
                    }
                    sample()
                }
            }

            val reference = trace()
            val before = reference[beforeFrame]
            val resumed = reference[resumedFrame]
            val finalTarget = newTarget ?: initialTarget
            assertTrue("motion in flight: $before", before > 0f && before < initialTarget)
            assertTrue("motion is still in flight later: $resumed", resumed > before && resumed < finalTarget)
            assertTrue("later frames must distinguish a stalled spring", reference.last() > resumed)
        } finally {
            clock.autoAdvance = previousAutoAdvance
        }
    }
}
