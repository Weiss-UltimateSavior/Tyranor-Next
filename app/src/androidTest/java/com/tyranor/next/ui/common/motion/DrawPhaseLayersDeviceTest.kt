package com.tyranor.next.ui.common.motion

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DrawPhaseLayersDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun middleLayerEntersAndLeavesInOrderWithoutRecordingContent() {
        MotionDeviceAcceptance.require(enhanced = null)
        var scale by mutableFloatStateOf(1f)
        var effect by mutableStateOf<RenderEffect?>(null)
        var color by mutableStateOf(Color.Red)
        val draws = AtomicInteger()
        compose.setContent {
            Box(
                Modifier.size(96.dp).testTag("page").background(Color.Black)
                    .drawPhaseLayers(
                        Unit,
                        LayerSource { p, _ -> p.translationX = p.width / 8f },
                        LayerSource { p, _ ->
                            p.renderEffect = effect
                            if (scale != 1f) {
                                p.scaleX = scale
                                p.pivotFracX = 0f
                            }
                        },
                        LayerSource { p, _ -> p.translationX = p.width / 4f },
                    )
                    .drawBehind {
                        draws.incrementAndGet()
                        drawRect(color, size = Size(size.width / 4f, size.height))
                    },
            )
        }

        assertStripe(0.375f, 0.625f, Color.Red)
        val initial = draws.get()
        assertTrue(initial > 0)
        for (s in listOf(0.5f, 0.75f, 1f, 0.5f)) {
            compose.runOnIdle { scale = s }
            assertStripe(0.25f + s / 8f, 0.25f + s * 3f / 8f, Color.Red)
            assertEquals("Only the transform changed at scale=$s", initial, draws.get())
        }
        // While the middle layer is active, a real content invalidation must still reach the screen.
        compose.runOnIdle { color = Color.Green }
        assertStripe(0.3125f, 0.4375f, Color.Green)
        assertTrue(draws.get() > initial)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val beforeEffect = draws.get()
            compose.runOnIdle {
                scale = 1f
                val filter = PorterDuffColorFilter(Color.Blue.toArgb(), PorterDuff.Mode.SRC_IN)
                effect = android.graphics.RenderEffect.createColorFilterEffect(filter).asComposeRenderEffect()
            }
            // An effect-only layer must not be bypassed as an identity transform.
            assertStripe(0.375f, 0.625f, Color.Blue)
            compose.runOnIdle { effect = null }
            assertStripe(0.375f, 0.625f, Color.Green)
            assertEquals(beforeEffect, draws.get())
        }
    }

    @Test
    fun sameKeyReplacementResizeAndReattachUseTheLatestSource() {
        MotionDeviceAcceptance.require(enhanced = null)
        var oldOffset by mutableFloatStateOf(0f)
        var newOffset by mutableFloatStateOf(0.5f)
        val newReads = AtomicInteger()
        val old = LayerSource { p, _ -> p.translationX = oldOffset * p.width }
        val replacement = LayerSource { p, _ ->
            newReads.incrementAndGet()
            p.translationX = newOffset * p.width
        }
        var source by mutableStateOf(old)
        var enabled by mutableStateOf(true)
        var present by mutableStateOf(true)
        var width by mutableStateOf(96.dp)
        val draws = AtomicInteger()
        val stripe = Modifier.drawBehind {
            draws.incrementAndGet()
            drawRect(Color.Red, size = Size(size.width / 4f, size.height))
        }
        compose.setContent {
            ReusableContentHost(active = present) {
                val page = Modifier.size(width, 32.dp).testTag("page").background(Color.Black)
                val motion = if (enabled) page.drawPhaseLayers(Unit, source) else page.drawPhaseLayers(Unit)
                Box(motion.then(stripe))
            }
        }

        assertStripe(0f, 0.25f, Color.Red)
        val initial = draws.get()
        compose.runOnIdle { source = replacement }
        assertStripe(0.5f, 0.75f, Color.Red)
        assertEquals(initial, draws.get())
        val reads = newReads.get()
        compose.runOnIdle { oldOffset = 0.25f }
        compose.runOnIdle { assertEquals("Old state must no longer wake the node", reads, newReads.get()) }
        compose.runOnIdle { width = 64.dp }
        assertStripe(0.5f, 0.75f, Color.Red)
        compose.runOnIdle { enabled = false }
        assertStripe(0f, 0.25f, Color.Red)
        compose.runOnIdle { enabled = true }
        assertStripe(0.5f, 0.75f, Color.Red)
        compose.runOnIdle { present = false }
        compose.onNodeWithTag("page").assertDoesNotExist()
        compose.runOnIdle {
            newOffset = 0.25f
            present = true
        }
        assertStripe(0.25f, 0.5f, Color.Red)
    }

    @Test
    fun replacingObservedSourcesInOneSnapshotDoesNotWakeTheNewBinding() {
        MotionDeviceAcceptance.require(enhanced = null)
        var oldOffset by mutableFloatStateOf(0f)
        var newOffset by mutableFloatStateOf(0.5f)
        val reads = AtomicInteger()
        val draws = AtomicInteger()
        @Suppress("UNCHECKED_CAST")
        fun element(vararg sources: LayerSource) =
            Modifier.drawPhaseLayersInOwnedLayer(Unit, *sources) as ModifierNodeElement<Modifier.Node>
        val first = element(LayerSource { p, _ -> p.translationX = oldOffset * p.width })
        val second = element(LayerSource { p, _ ->
            reads.incrementAndGet()
            p.translationX = newOffset * p.width
        })
        // Drive the real attached node directly so both update calls see exactly the same snapshot.
        lateinit var node: Modifier.Node
        val mount = object : ModifierNodeElement<Modifier.Node>() {
            override fun create() = first.create().also { node = it }
            override fun update(node: Modifier.Node) = Unit
            override fun InspectorInfo.inspectableProperties() { name = "mountDrawPhaseNode" }
            override fun equals(other: Any?) = this === other
            override fun hashCode() = System.identityHashCode(this)
        }
        compose.setContent {
            Box(Modifier.size(96.dp).testTag("page").background(Color.Black)
                .graphicsLayer {}
                .then(mount).drawBehind {
                    draws.incrementAndGet()
                    drawRect(Color.Red, size = Size(size.width / 4f, size.height))
                })
        }
        assertStripe(0f, 0.25f, Color.Red)
        val initialDraws = draws.get()
        compose.runOnIdle {
            Snapshot.withMutableSnapshot {
                first.update(node)
                second.update(node)
            }
        }
        assertStripe(0.5f, 0.75f, Color.Red)
        val boundReads = reads.get()
        compose.runOnIdle { oldOffset = 0.75f }
        compose.runOnIdle { assertEquals(boundReads, reads.get()) }
        compose.runOnIdle { newOffset = 0.25f }
        assertStripe(0.25f, 0.5f, Color.Red)
        assertTrue(reads.get() > boundReads)
        assertEquals(initialDraws, draws.get())
        compose.runOnIdle {
            Snapshot.withMutableSnapshot {
                second.update(node)
                element().update(node)
            }
        }
        assertStripe(0f, 0.25f, Color.Red)
        val unboundReads = reads.get()
        compose.runOnIdle { newOffset = 0.75f }
        compose.runOnIdle { assertEquals(unboundReads, reads.get()) }
    }

    private fun assertStripe(left: Float, right: Float, color: Color) {
        val pixels = compose.onNodeWithTag("page").captureToImage().toPixelMap()
        val start = left * pixels.width
        val end = right * pixels.width
        for (x in 0 until pixels.width) {
            // Fractional transforms can anti-alias the edge. Check the solid span and both margins.
            if (kotlin.math.abs(x - start) < 2 || kotlin.math.abs(x - end) < 2) continue
            val expected = if (x > start && x < end) color else Color.Black
            assertEquals(
                "x=$x, expected span [$start,$end)",
                expected.toArgb(),
                pixels[x, pixels.height / 2].toArgb(),
            )
        }
    }
}
