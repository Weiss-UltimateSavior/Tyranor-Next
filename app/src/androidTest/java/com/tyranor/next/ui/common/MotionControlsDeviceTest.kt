package com.tyranor.next.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.bounceOnChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator

/** Requires explicit opt-in and the isolated motion acceptance package; never runs against the official app. */
class MotionControlsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun accessibilitySliderChangeDeliversCompletion() {
        requireMotionTarget()
        var value by mutableFloatStateOf(2f)
        var saved = 2f
        var completions = 0
        compose.setContent {
            MiuixSettingsTheme {
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { saved = value; completions++ },
                    valueRange = 1f..5f,
                    keyPoints = listOf(1f, 2f, 3f, 4f, 5f),
                    modifier = Modifier.width(240.dp).testTag("slider"),
                )
            }
        }
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle {
            assertEquals(4f, value)
            assertEquals(4f, saved)
            assertEquals(1, completions)
        }
        compose.runOnIdle {
            MotionMode.sync(false)
            value = 2f
            saved = 2f
            completions = 0
        }
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle {
            assertEquals(4f, value)
            assertEquals(4f, saved)
            assertEquals(1, completions)
        }
    }

    @Test
    fun progressFramesMatchPreviousMiuixRendering() {
        requireMotionTarget()
        var progress by mutableFloatStateOf(0.35f)
        compose.setContent {
            MiuixSettingsTheme {
                Column(Modifier.width(240.dp)) {
                    MotionLinearProgressIndicator(progress, Modifier.testTag("motionProgress"))
                    // 原实现：相同 spring 在组合期读取，作为迁移到绘制阶段后的像素参照。
                    val reference by animateFloatAsState(progress, MotionTokens.value(), label = "referenceProgress")
                    LinearProgressIndicator(progress = reference, modifier = Modifier.testTag("libraryProgress"))
                }
            }
        }
        assertSamePixels("motionProgress", "libraryProgress")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { progress = 0.85f }
        // 每个 16ms 动画帧都比对新旧实现，覆盖起步、运动、尾段和停稳，不只比较最终状态。
        repeat(64) {
            compose.mainClock.advanceTimeByFrame()
            assertSamePixels("motionProgress", "libraryProgress")
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun reusedBounceDoesNotKeepInterruptedScale() {
        requireMotionTarget()
        var active by mutableStateOf(true)
        var trigger by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.size(80.dp).background(Color.Black).testTag("frame")) {
                ReusableContentHost(active) {
                    Box(Modifier.bounceOnChange(trigger).size(80.dp).background(Color.White))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { trigger = true }
        compose.mainClock.advanceTimeBy(64)
        val interrupted = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
        assertTrue("The bounce must be interrupted while scaled", interrupted[2, 2].red < 0.1f)
        compose.runOnIdle { active = false }
        compose.mainClock.advanceTimeByFrame()
        val inactive = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
        assertTrue("The content must actually be deactivated before reuse", inactive[2, 2].red < 0.1f)
        compose.runOnIdle { active = true }
        compose.mainClock.advanceTimeByFrame()
        val restored = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
        assertTrue("Reused content must regain its full static size", restored[2, 2].red > 0.99f)
        compose.mainClock.autoAdvance = true
    }

    private fun assertSamePixels(actualTag: String, expectedTag: String) {
        val actual = compose.onNodeWithTag(actualTag).captureToImage().toPixelMap()
        val expected = compose.onNodeWithTag(expectedTag).captureToImage().toPixelMap()
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            val a = actual[x, y]
            val e = expected[x, y]
            val delta = maxOf(kotlin.math.abs(a.red - e.red), kotlin.math.abs(a.green - e.green), kotlin.math.abs(a.blue - e.blue), kotlin.math.abs(a.alpha - e.alpha))
            assertTrue("$actualTag differs at ($x,$y), delta=$delta", delta <= 1f / 255f)
        }
    }

    private fun requireMotionTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(instrumentation.targetContext.packageName == "com.tyranor.next.motionv2")
        assumeTrue(InstrumentationRegistry.getArguments().getString("motion_device_acceptance") == "true")
        instrumentation.runOnMainSync { MotionMode.sync(true) }
    }

}
