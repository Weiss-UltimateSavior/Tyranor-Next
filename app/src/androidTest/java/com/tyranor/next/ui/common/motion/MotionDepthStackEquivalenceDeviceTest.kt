package com.tyranor.next.ui.common.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MotionDepthStackEquivalenceDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enhancedStackMatchesTwoNodes() = compareStacks(enhanced = true)
    @Test fun normalStackStaysAtRest() = compareStacks(enhanced = false)

    private data class Frame(
        val label: String,
        val enter: Float = 1f,
        val push: Float = 0f,
        val sheet: Float = 0f,
        val panel: Float = 0f,
    )

    private fun compareStacks(enhanced: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        val originalMode = compose.runOnIdle { MotionMode.enhanced }
        val enter = PageEnter(initial = 1f)
        val push = PageDepth()
        val sheetCover = mutableFloatStateOf(0f)
        val panelCover = mutableFloatStateOf(0f)
        try {
            compose.runOnIdle { MotionMode.sync(enhanced) }
            compose.setContent {
                val sheet = rememberSheetBackdrop()
                val panel = rememberSheetBackdrop()
                sheet.Bind(strength = { DialogSinkStrength }) { sheetCover.floatValue }
                panel.Bind { panelCover.floatValue }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(Modifier.testTag("depthPair")) {
                        DepthTile(Modifier.pageEnterFold(enter).depthFoldStack(sheet, panel, push))
                        DepthTile(Modifier.depthFoldStack(sheet, panel, push, enter = enter))
                    }
                }
            }

            val frames = listOf(
                Frame("at rest"),
                Frame("enter start", enter = 0f),
                Frame("enter middle", enter = 0.53f),
                Frame("entered"),
                Frame("sheet", sheet = 0.91f),
                Frame("covered by next page", push = 0.83f),
                Frame("pop with sheet", enter = 0.72f, push = 0.41f, sheet = 0.81f),
                Frame("all four layers", enter = 0.34f, push = 0.78f, sheet = 0.93f, panel = 0.62f),
                Frame("panel only", panel = 0.71f),
                Frame("pop middle", enter = 0.53f),
                Frame("pop end", enter = 0f),
                Frame("restored"),
            )
            var neutral = IntArray(0)
            val seen = mutableMapOf<Float, IntArray>()
            for (f in frames) {
                compose.runOnIdle {
                    // snapTo needs no animation clock. Both paths observe the same live handles.
                    runBlocking {
                        enter.progress.snapTo(f.enter)
                        push.progress.snapTo(f.push)
                    }
                    sheetCover.floatValue = f.sheet
                    panelCover.floatValue = f.panel
                }
                // One screenshot per fixed state, not two captures separated by a render.
                val image = compose.onNodeWithTag("depthPair").captureToImage()
                assertEquals(TILE_WIDTH, image.width)
                assertEquals(2 * TILE_HEIGHT, image.height)
                val pixels = IntArray(image.width * image.height)
                image.readPixels(pixels)
                val reference = pixels.copyOfRange(0, pixels.size / 2)
                assertPixels(f.label, reference, pixels, reference.size)
                if (neutral.isEmpty()) {
                    assertTrue("Empty reference fixture", reference.distinct().size >= 5)
                    neutral = reference
                }
                val enterOnly = f.push == 0f && f.sheet == 0f && f.panel == 0f
                if (!enhanced || (enterOnly && f.enter == 1f)) {
                    assertPixels("${f.label}: expected neutral", neutral, reference, 0)
                } else {
                    assertTrue("${f.label}: reference did not move", reference.indices.count {
                        channelDistance(reference[it], neutral[it]) > 1
                    } > 32)
                }
                if (enterOnly) {
                    seen.put(f.enter, reference)?.let { assertPixels("${f.label}: reverse", it, reference, 0) }
                }
            }
        } finally {
            compose.runOnIdle { MotionMode.sync(originalMode) }
        }
    }

    @Composable
    private fun DepthTile(motion: Modifier) {
        val density = LocalDensity.current
        // Each tile rasterizes at its own (0,0), then blits at an integral pixel offset. The
        // offscreen backing is outside BOTH motion paths; the transform layers remain real.
        Box(
            Modifier.size(with(density) { TILE_WIDTH.toDp() }, with(density) { TILE_HEIGHT.toDp() })
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize(0.8f).then(motion)) {
                val w = size.width
                val h = size.height
                drawRect(Color.Magenta)
                drawRect(Color.Yellow, size = Size(w * 0.16f, h))
                drawRect(Color.Cyan, Offset(w * 0.30f, 0f), Size(w * 0.70f, h * 0.23f))
                drawCircle(Color.Green, h * 0.18f, Offset(w * 0.73f, h * 0.60f))
                drawLine(Color.White, Offset(w * 0.12f, h * 0.34f), Offset(w * 0.62f, h * 0.91f), 3f)
            }
        }
    }

    private fun assertPixels(label: String, expected: IntArray, actual: IntArray, start: Int) {
        for (i in expected.indices) {
            val a = actual[start + i]
            val e = expected[i]
            if (a != e && channelDistance(e, a) > 1) {
                throw AssertionError("$label @ (${i % TILE_WIDTH},${i / TILE_WIDTH}): " +
                    "expected ARGB=${e.toUInt().toString(16)}, actual=${a.toUInt().toString(16)}")
            }
        }
    }

    // At most one step in one 8-bit channel in the entire pixel, including alpha.
    private fun channelDistance(a: Int, b: Int): Int =
        abs((a ushr 24) - (b ushr 24)) +
            abs((a ushr 16 and 255) - (b ushr 16 and 255)) +
            abs((a ushr 8 and 255) - (b ushr 8 and 255)) +
            abs((a and 255) - (b and 255))

    private companion object {
        const val TILE_WIDTH = 160
        const val TILE_HEIGHT = 128
    }
}
