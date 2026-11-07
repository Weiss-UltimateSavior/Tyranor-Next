package com.tyranor.next.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.RollingNumberText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Isolated motion acceptance only; duration scaling is injected into Compose, never written to system settings. */
class MotionRollingDeviceTest {
    private val durationScale = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(4f)
    }

    @get:Rule val compose = createComposeRule(effectContext = durationScale)

    @Test
    fun slowRollingRemainsVisibleUntilFinishedAndCanRetarget() {
        requireMotionTarget()
        var value by mutableStateOf("1")
        setComparisonContent { value }
        assertEquals(0, differentPixels())
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { value = "8" }
        compose.mainClock.advanceTimeBy(704)
        assertTrue("A slowed roll must not jump to plain text at the former 650ms timeout", differentPixels() > 0)
        compose.runOnIdle { value = "1" }
        compose.mainClock.advanceTimeBy(704)
        assertTrue("Returning to the last settled value must retain the unfinished rolling layer", differentPixels() > 0)
        compose.mainClock.advanceTimeBy(8_000)
        assertEquals("After completion the original full text must be restored", 0, differentPixels())
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun disabledAnimationsShowFinalPlainTextWithoutWaiting() {
        requireMotionTarget()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { durationScale.scaleFactor = 0f }
        var value by mutableStateOf("1")
        setComparisonContent { value }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { value = "8" }
        compose.mainClock.advanceTimeBy(64)
        assertEquals("Zero duration scale must complete without a fixed delay", 0, differentPixels())
        compose.mainClock.autoAdvance = true
    }

    private fun setComparisonContent(value: () -> String) {
        compose.setContent {
            MaterialTheme {
                val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 32.sp)
                Column {
                    Box(Modifier.size(80.dp).background(Color.White).testTag("rolling")) {
                        RollingNumberText(
                            text = value(), modifier = Modifier.align(Alignment.Center),
                            color = Color.Black, style = style,
                        )
                    }
                    Box(Modifier.size(80.dp).background(Color.White).testTag("plain")) {
                        Text(
                            text = value(), modifier = Modifier.align(Alignment.Center),
                            color = Color.Black, style = style,
                        )
                    }
                }
            }
        }
    }

    private fun differentPixels(): Int {
        val actual = compose.onNodeWithTag("rolling").captureToImage().toPixelMap()
        val expected = compose.onNodeWithTag("plain").captureToImage().toPixelMap()
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        var different = 0
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            val a = actual[x, y]
            val e = expected[x, y]
            val delta = maxOf(
                kotlin.math.abs(a.red - e.red), kotlin.math.abs(a.green - e.green),
                kotlin.math.abs(a.blue - e.blue), kotlin.math.abs(a.alpha - e.alpha),
            )
            if (delta > 1f / 255f) different++
        }
        return different
    }

    private fun requireMotionTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(instrumentation.targetContext.packageName == "com.tyranor.next.motionv2")
        assumeTrue(InstrumentationRegistry.getArguments().getString("motion_device_acceptance") == "true")
        instrumentation.runOnMainSync { MotionMode.sync(true) }
    }
}
