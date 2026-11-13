package com.tyranor.next.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.LocalAppDialogContent
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.bounceOnChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator

/** Requires explicit opt-in and the isolated motion acceptance package; never runs against the official app. */
class MotionControlsDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** Checks that accessibility changes save the chosen value and complete exactly once in both modes. */
    @Test
    fun accessibilitySliderChangeDeliversCompletion() {
        MotionDeviceAcceptance.require()
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

    /** Compares every enhanced progress frame with the original animated Miuix rendering. */
    @Test
    fun progressFramesMatchPreviousMiuixRendering() {
        MotionDeviceAcceptance.require()
        var progress by mutableFloatStateOf(0.35f)
        compose.setContent {
            MiuixSettingsTheme {
                Column(Modifier.width(240.dp).testTag("progressComparison")) {
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

    /** Compares ordinary progress frames with the original unanimated Miuix indicator. */
    @Test
    fun ordinaryProgressFramesMatchUnanimatedMiuixRendering() {
        MotionDeviceAcceptance.require()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { MotionMode.sync(false) }
        var progress by mutableFloatStateOf(0.35f)
        compose.setContent {
            MiuixSettingsTheme {
                Column(Modifier.width(240.dp).testTag("progressComparison")) {
                    MotionLinearProgressIndicator(progress, Modifier.testTag("motionProgress"))
                    LinearProgressIndicator(progress = progress, modifier = Modifier.testTag("libraryProgress"))
                }
            }
        }
        assertSamePixels("motionProgress", "libraryProgress")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { progress = 0.85f }
        repeat(64) {
            compose.mainClock.advanceTimeByFrame()
            assertSamePixels("motionProgress", "libraryProgress")
        }
        compose.mainClock.autoAdvance = true
    }

    /** Verifies continuous and stepped accessibility updates when no custom ticks are supplied. */
    @Test
    fun emptyKeyPointsSupportContinuousAndSteppedAccessibilityChanges() {
        MotionDeviceAcceptance.require()
        var value by mutableFloatStateOf(0.25f)
        var steps by mutableStateOf(0)
        var completions = 0
        compose.setContent {
            MiuixSettingsTheme {
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { completions++ },
                    keyPoints = emptyList(),
                    steps = steps,
                    modifier = Modifier.width(240.dp).testTag("emptyTicksSlider"),
                )
            }
        }
        compose.onNodeWithTag("emptyTicksSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle {
            assertEquals(0.75f, value)
            assertEquals(1, completions)
            value = 0.25f
            steps = 3
        }
        compose.onNodeWithTag("emptyTicksSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle {
            assertEquals(0.75f, value)
            assertEquals(2, completions)
        }
    }

    /** Samples the indicator beneath each selected label with fixed and scrollable LTR/RTL tabs. */
    @Test
    fun tabIndicatorFollowsSelectedLabelInBothLayoutDirections() {
        MotionDeviceAcceptance.require()
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var scrollable by mutableStateOf(false)
        var selected by mutableStateOf(0)
        val labels = listOf("One", "Two", "Three")
        compose.setContent {
            MiuixSettingsTheme {
                Column(Modifier.width(240.dp).background(Color.Black)) {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides direction,
                        LocalAppDialogContent provides true,
                    ) {
                        SlidingTabRow(labels, selected, {}, Modifier.testTag("tabs"), scrollable)
                    }
                    Box(Modifier.size(20.dp).background(DialogItemSurface).testTag("indicatorReference"))
                }
            }
        }
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (useScrolling in listOf(false, true)) {
                for (index in labels.indices) {
                    compose.runOnIdle {
                        direction = layoutDirection
                        scrollable = useScrolling
                        selected = index
                    }
                    // runOnIdle executes the mutation after idling; wait for its resulting layout and animation too.
                    compose.waitForIdle()
                    val tabs = compose.onNodeWithTag("tabs")
                    val tabBounds = tabs.fetchSemanticsNode().boundsInRoot
                    val labelBounds = compose.onNodeWithText(labels[index]).fetchSemanticsNode().boundsInRoot
                    val tabImage = tabs.captureToImage()
                    val pixels = tabImage.toPixelMap()
                    val expected = compose.onNodeWithTag("indicatorReference").captureToImage().toPixelMap()
                    val x = (labelBounds.center.x - tabBounds.left).toInt().coerceIn(0, pixels.width - 1)
                    val actualColor = pixels[x, pixels.height * 5 / 6]
                    val expectedColor = expected[expected.width / 2, expected.height / 2]
                    if (expectedColor != actualColor) {
                        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "motion-tabs-failure.png")
                        file.outputStream().use { tabImage.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    assertEquals("$layoutDirection scrolling=$useScrolling index=$index", expectedColor, actualColor)
                }
            }
        }
    }

    /** Deactivates an unfinished bounce and checks that reused content regains its static size. */
    @Test
    fun reusedBounceDoesNotKeepInterruptedScale() {
        MotionDeviceAcceptance.require()
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

    /** Captures both progress references in one draw frame and compares their corresponding pixels. */
    private fun assertSamePixels(actualTag: String, expectedTag: String) {
        val actual = compose.onNodeWithTag(actualTag).fetchSemanticsNode()
        val expected = compose.onNodeWithTag(expectedTag).fetchSemanticsNode()
        val row = compose.runOnIdle {
            expected.size.also {
                assertEquals("Measured indicator sizes", it, actual.size)
                assertTrue("Empty indicator: $it", it.width > 0 && it.height > 0)
            }
        }
        // The fixture is a Column with exactly two rows and no spacing. captureToImage reads
        // its own crop: don't mix that image with boundsInRoot sampled before the capture.
        val pixels = compose.onNodeWithTag("progressComparison").captureToImage().toPixelMap()
        assertEquals("Capture must include the full row width", row.width, pixels.width)
        assertEquals("Capture must include both complete rows", 2 * row.height, pixels.height)
        for (y in 0 until row.height) for (x in 0 until row.width) {
            val a = pixels[x, y]
            val e = pixels[x, y + row.height]
            val delta = maxOf(kotlin.math.abs(a.red - e.red), kotlin.math.abs(a.green - e.green), kotlin.math.abs(a.blue - e.blue), kotlin.math.abs(a.alpha - e.alpha))
            assertTrue("$actualTag differs at ($x,$y), delta=$delta", delta <= 1f / 255f)
        }
    }
}
