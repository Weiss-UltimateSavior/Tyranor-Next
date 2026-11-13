package com.tyranor.next.ui.main

import android.util.Log
import android.os.Bundle
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.rememberEntranceState
import com.tyranor.next.ui.common.motion.rememberSheetBackdrop
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainTabPixelParityDeviceTest {
    @get:Rule val compose = createComposeRule()

    private data class Pose(val offset: Float, val alpha: Float) {
        val scale get() = 1f - 0.06f * abs(offset).coerceAtMost(1f)
        fun x(local: Float) = 0.5f + offset + (local - 0.5f) * scale
        fun y(local: Float) = 0.5f + (local - 0.5f) * scale
    }

    private data class Sample(val name: String, val selected: Int, val poses: List<Pose>)

    @Test
    fun groupedColorsAndPositionsMatchAtIdenticalProgress() {
        MotionDeviceAcceptance.require(enhanced = null)
        val previous = MotionMode.enhanced
        val cases = listOf(
            Sample("crossfade", 2, listOf(Pose(-0.22f, 0.72f), Pose(0.38f, 0.38f))),
            Sample("overshoot", 2, listOf(Pose(-1.045f, 0.01f), Pose(-0.07f, 0.94f))),
            Sample("reverse", 1, listOf(Pose(-0.16f, 0.55f), Pose(0.24f, 0.68f))),
        )
        var sample by mutableStateOf(cases.first())
        var wide by mutableStateOf(true)
        var showActual by mutableStateOf(true)
        try {
            compose.runOnUiThread { MotionMode.sync(true) }
            compose.mainClock.autoAdvance = false
            compose.setContent {
                val overlay = rememberSheetBackdrop()
                val transition = updateTransition(sample.selected, label = "parity")
                Box(Modifier.size(if (wide) 144.dp else 80.dp, if (wide) 80.dp else 144.dp)
                    .testTag("viewport").clipToBounds().background(Color.Black)) {
                    // Both scenes stay mounted at the SAME physical origin. Only the final draw is toggled.
                    for (actual in listOf(false, true)) key(actual) {
                        Box(Modifier.fillMaxSize().drawWithContent {
                            if (showActual == actual) {
                                drawContent()
                                // Witness outside the scene: reject a stale readback of the previous branch.
                                drawRect(if (actual) Color.White else Color.Magenta, size = Size(2f, 2f))
                            }
                        }) {
                            for (page in 1..2) key(page) {
                                if (actual) {
                                    val motion = remember(page) {
                                        MainTabMotion(
                                            derivedStateOf { sample.poses[page - 1].offset },
                                            derivedStateOf { sample.poses[page - 1].alpha },
                                        )
                                    }
                                    MainTabPageHost(page, sample.selected == page, transition, motion,
                                        rememberEntranceState(playOnStart = false), overlay,
                                        allowHiddenDraw = true, reportActive = false) { Marks(page) }
                                } else {
                                    val pose = sample.poses[page - 1]
                                    // Independent pre-refactor expression: GROUP alpha after the local transform,
                                    // full-width signed displacement and centered depth. No production transform helper.
                                    Box(Modifier.fillMaxSize().zIndex(if (page == sample.selected) 1f else 0f)
                                        .graphicsLayer { alpha = pose.alpha }
                                        .graphicsLayer {
                                            translationX = pose.offset * size.width
                                            scaleX = pose.scale
                                            scaleY = pose.scale
                                        }) { Marks(page) }
                                }
                            }
                        }
                    }
                }
            }
            for (landscape in listOf(true, false)) for (s in cases) {
                compose.runOnIdle { wide = landscape; sample = s; showActual = false }
                compose.mainClock.advanceTimeByFrame()
                val ref = pixels()
                compose.runOnIdle { showActual = true }
                compose.mainClock.advanceTimeByFrame() // pose is frozen; only presentation switches
                val actual = pixels()
                val id = "${s.name} ${actual.width}x${actual.height}"
                assertEquals(id, ref.width, actual.width)
                assertEquals(id, ref.height, actual.height)
                assertEquals("$id reference witness", Color.Magenta.toArgb(), ref[0, 0].toArgb())
                assertEquals("$id actual witness", Color.White.toArgb(), actual[0, 0].toArgb())
                var max = 0
                var changed = 0
                for (y in 0 until actual.height) for (x in 0 until actual.width) {
                    if (x < 2 && y < 2) continue
                    val delta = rgbDiff(actual[x, y].toArgb(), ref[x, y].toArgb())
                    if (delta != 0) changed++
                    max = maxOf(max, delta)
                }
                assertTrue("$id max channel difference=$max at $changed pixels", max <= 1)
                // Non-vacuous independent oracle: position of the incoming page marker and
                // source-over of opaque overlapping primitives under fractional group alphas.
                val markerRanges = mutableListOf<String>()
                for (page in 1..2) {
                    val p = s.poses[page - 1]
                    val left = p.x(if (page == 1) 0.65f else 0.25f)
                    val row = (p.y(if (page == 1) 0.22f else 0.76f) * actual.height).toInt()
                    val xs = (0 until actual.width).filter { actual[it, row].green > 0.05f }
                    markerRanges += "p$page@y$row=" + (if (xs.isEmpty()) "off-screen" else "${xs.first()}..${xs.last()}")
                    if (p.x(if (page == 1) 0.75f else 0.35f) <= 0f) {
                        assertTrue("$id page $page beyond the left edge", xs.isEmpty())
                    } else {
                        assertTrue("$id page $page marker missing", xs.isNotEmpty())
                        assertTrue("$id page $page marker x=${xs.first()}", abs(xs.first() - left * actual.width) <= 2f)
                        assertTrue("$id page $page marker width=${xs.size}", abs(xs.size - 0.1f * p.scale * actual.width) <= 3f)
                    }
                }
                val x = 0.55f
                var red = 0f
                var blue = 0f
                for (page in listOf(3 - s.selected, s.selected)) {
                    val p = s.poses[page - 1]
                    if (x !in p.x(0f)..p.x(1f)) continue
                    red = red * (1f - p.alpha) + (if (page == 1) p.alpha else 0f)
                    blue = blue * (1f - p.alpha) + (if (page == 2) p.alpha else 0f)
                }
                val at = actual[(x * actual.width).toInt(), actual.height / 2].toArgb()
                val expected = android.graphics.Color.rgb((red * 255).roundToInt(), 0, (blue * 255).roundToInt())
                assertTrue("$id blend: expected ${expected.toUInt().toString(16)}, got ${at.toUInt().toString(16)}",
                    rgbDiff(at, expected) <= 2)
                val result = "$id pixels=${actual.width * actual.height - 4} changed=$changed maxRgbDelta=$max " +
                    "center=${at.toUInt().toString(16)} expected=${expected.toUInt().toString(16)} ${markerRanges.joinToString()}"
                Log.i("TabPixelParity", result)
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("stream", "\nTabPixelParity $result\n")
                })
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { MotionMode.sync(previous) }
        }
    }

    private fun pixels() = compose.onNodeWithTag("viewport").captureToImage().toPixelMap()

    @Composable
    private fun Marks(page: Int) {
        Canvas(Modifier.fillMaxSize()) {
            val ink = if (page == 1) Color.Red else Color.Blue
            drawRect(ink, Offset(0f, 0.4f * size.height), Size(size.width, 0.2f * size.height))
            // Same ink twice: modulating individual draw commands instead of the group changes the intersection.
            drawRect(ink, Offset(0.1f * size.width, 0.45f * size.height), Size(0.8f * size.width, 0.1f * size.height))
            drawRect(Color.Green, Offset((if (page == 1) 0.65f else 0.25f) * size.width,
                (if (page == 1) 0.18f else 0.72f) * size.height), Size(0.1f * size.width, 0.08f * size.height))
        }
    }

    private fun rgbDiff(a: Int, b: Int): Int = maxOf(
        abs(((a shr 16) and 255) - ((b shr 16) and 255)),
        abs(((a shr 8) and 255) - ((b shr 8) and 255)),
        abs((a and 255) - (b and 255)),
    )
}
