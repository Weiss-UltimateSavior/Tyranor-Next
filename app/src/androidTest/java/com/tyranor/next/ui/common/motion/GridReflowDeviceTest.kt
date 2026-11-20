package com.tyranor.next.ui.common.motion

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Isolated, synthetic grid: no business screen, storage, or device setting changes. */
@RunWith(Parameterized::class)
class GridReflowDeviceTest(private val staggered: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rotationMovesOldCardsAndEveryCardEntersFromBelow() = withFixture {
        assumeTrue("Enhanced frame assertions require system animations", ValueAnimator.areAnimatorsEnabled())
        val before = capture().card(1)
        compose.runOnIdle { columns.intValue = 3 }
        val frames = List(12) {
            compose.mainClock.advanceTimeByFrame()
            capture()
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        val settled = capture()
        assertResting(settled, 3)
        val target = settled.card(1)

        // Scan rendered pixels: semantic bounds alone do not prove entrance translation.
        val oldTarget = settled.card(0)
        assertTrue("Previously visible card must also enter from below", frames.any {
            val card = it.card(0)
            card.count > 0 && card.top > oldTarget.top + 2
        })
        assertTrue("Old visible card must occupy an intermediate placement", frames.any {
            val card = it.card(1)
            card.count > 0 && card.left > target.left + 1 && card.left < before.left - 1
        })
        val newTarget = settled.card(4)
        assertTrue("Newly visible card must be drawn below its target before settling", frames.any {
            val card = it.card(4)
            card.count > 0 && card.top > newTarget.top + 2
        })
    }

    @Test
    fun oldAndNewCardsEnterOnDistinctFrames() = withFixture(entranceStepMillis = 60L) {
        assumeTrue("Staggered frame assertions require system animations", ValueAnimator.areAnimatorsEnabled())
        compose.runOnIdle { columns.intValue = 3 }
        val frames = List(40) {
            compose.mainClock.advanceTimeByFrame()
            capture()
        }
        val firstFrames = COLORS.indices.map { card ->
            frames.indexOfFirst { it.card(card).count > 0 }
        }
        assertTrue("Every old and newly visible card must enter", firstFrames.all { it >= 0 })
        assertEquals("All cards must start on separate frames", COLORS.size, firstFrames.distinct().size)
        assertTrue("All cards must enter in list order", firstFrames.zipWithNext().all { (a, b) -> a < b })
        assertTrue("The sequence must include a frame showing exactly one card", frames.any { frame ->
            COLORS.indices.count { frame.card(it).count > 0 } == 1
        })
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertResting(capture(), 3)
    }

    @Test
    fun fewerColumnsAlsoReplayExistingCardsOnDistinctFrames() = withFixture(entranceStepMillis = 60L) {
        assumeTrue("Staggered frame assertions require system animations", ValueAnimator.areAnimatorsEnabled())
        compose.runOnIdle { columns.intValue = 3 }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertResting(capture(), 3)
        compose.runOnIdle { columns.intValue = 2 }
        val frames = List(32) {
            compose.mainClock.advanceTimeByFrame()
            capture()
        }
        val firstFrames = (0 until 4).map { card ->
            frames.indexOfFirst { it.card(card).count > 0 }
        }
        assertTrue("Every remaining old card must enter", firstFrames.all { it >= 0 })
        assertEquals("Old cards must start on separate frames", 4, firstFrames.distinct().size)
        assertTrue("Remaining old cards must enter in list order", firstFrames.zipWithNext().all { (a, b) -> a < b })
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertResting(capture(), 2)
    }

    @Test
    fun rapidRotationRoundTripsLeaveEveryVisibleCardFullyDrawn() = withFixture(entranceStepMillis = 60L) {
        assumeTrue("Enhanced round trips require system animations", ValueAnimator.areAnimatorsEnabled())
        for (next in listOf(3, 2, 3, 2, 3)) {
            compose.runOnIdle { columns.intValue = next }
                repeat(2) { compose.mainClock.advanceTimeByFrame() }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        val settled = capture()
        assertResting(settled, 3)
        compose.mainClock.advanceTimeBy(256)
        assertArrayEquals("No stale entrance may hide or move a settled card", settled.pixels, capture().pixels)
    }

    @Test
    fun ordinaryModeIsStableOnFirstRestoredFrame() = withFixture(enhanced = false) {
        compose.runOnIdle { columns.intValue = 3 }
        awaitWaterfallReady()
        compose.mainClock.advanceTimeByFrame()
        val first = capture()
        assertResting(first, 3)
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertArrayEquals("Ordinary mode must have no deferred placement or entrance", first.pixels, capture().pixels)
        compose.runOnIdle { columns.intValue = 2 }
        awaitWaterfallReady()
        compose.mainClock.advanceTimeByFrame()
        assertResting(capture(), 2)
    }

    private fun Fixture.awaitWaterfallReady() {
        if (!staggered) return
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            var ready = false
            compose.runOnIdle { ready = waterfall?.isRestoring == false && reflow?.isReady == true }
            if (ready) return
        }
        error("Waterfall restoration did not become ready within 60 frames")
    }

    private class Fixture(val entranceStepMillis: Long) {
        val columns = mutableIntStateOf(2)
        var waterfall: WaterfallReflowState? = null
        var reflow: GridReflowState? = null
    }

    private fun withFixture(
        enhanced: Boolean = true,
        entranceStepMillis: Long = 0L,
        check: Fixture.() -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = MotionMode.enhanced
        val originalAutoAdvance = compose.mainClock.autoAdvance
        try {
            MotionDeviceAcceptance.require()
            if (!enhanced) instrumentation.runOnMainSync { MotionMode.sync(false) }
            val fixture = Fixture(entranceStepMillis)
            compose.setContent { GridFixture(fixture) }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            // Drain initial lazy measurement/placement before changing the layout identity.
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            assertResting(capture(), 2)
            fixture.check()
        } finally {
            compose.mainClock.autoAdvance = originalAutoAdvance
            instrumentation.runOnMainSync { MotionMode.sync(originalMode) }
        }
    }

    @Composable
    private fun GridFixture(fixture: Fixture) {
        val density = LocalDensity.current
        val grid = rememberLazyGridState()
        val columns = fixture.columns.intValue
        val waterfallReflow = rememberWaterfallReflowState(columns, columns == 3)
        val waterfall = waterfallReflow.gridState
        val reflow = rememberGridReflowState(
            columns = columns,
            landscape = columns == 3,
            ready = !staggered || !waterfallReflow.isRestoring,
            placementDelta = if (staggered) { key ->
                val previous = waterfallReflow.previousItemOffsets[key]
                val current = waterfall.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
                if (previous != null && current != null) previous - current else androidx.compose.ui.unit.IntOffset.Zero
            } else null,
            visibleKeys = {
                if (staggered) waterfall.layoutInfo.visibleItemsInfo.sortedBy { it.index }.mapTo(LinkedHashSet()) { it.key }
                else grid.layoutInfo.visibleItemsInfo.sortedBy { it.index }.mapTo(LinkedHashSet()) { it.key }
            },
        )
        fixture.waterfall = waterfallReflow
        fixture.reflow = reflow
        // Integral pixel geometry keeps screenshots independent of emulator density.
        val frame = Modifier
            .size(with(density) { WIDTH.toDp() }, with(density) { HEIGHT.toDp() })
            .background(Color.Black)
            .then(if (staggered) Modifier.waterfallReflowHost(waterfallReflow) else Modifier)
            .gridReflowHost(reflow)
            .testTag(GRID_TAG)
        val gap = with(density) { GAP.toDp() }
        if (staggered) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columns),
                state = waterfall,
                modifier = frame,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalItemSpacing = gap,
            ) {
                items(COLORS.indices.toList(), key = { "card-$it" }) { index ->
                    FixtureCard(
                        index,
                        listItemMotion(reflow.placement).staggeredEntrance(
                            index,
                            stepMillis = fixture.entranceStepMillis,
                            reflow = reflow,
                            key = "card-$index",
                        ),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier = frame,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(COLORS.indices.toList(), key = { "card-$it" }) { index ->
                    FixtureCard(
                        index,
                        listItemMotion(reflow.placement).staggeredEntrance(
                            index,
                            stepMillis = fixture.entranceStepMillis,
                            reflow = reflow,
                            key = "card-$index",
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun FixtureCard(index: Int, modifier: Modifier) {
        val density = LocalDensity.current
        Box(
            modifier
                .fillMaxWidth()
                .height(with(density) { CARD_HEIGHT.toDp() })
                .background(Color(COLORS[index]))
                .testTag("card-$index"),
        )
    }

    private data class CardPixels(val count: Int, val left: Int, val top: Int)

    private data class Frame(val width: Int, val height: Int, val pixels: IntArray) {
        fun card(index: Int, opaque: Boolean = false): CardPixels {
            val color = COLORS[index]
            var count = 0
            var left = width
            var top = height
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val peak = CHANNEL_SHIFTS.maxOf { shift -> pixel ushr shift and 255 }
                    val matches = peak >= (if (opaque) 250 else 32) && CHANNEL_SHIFTS.all { shift ->
                        val expected = color ushr shift and 255
                        val actual = pixel ushr shift and 255
                        abs(actual * 255 - expected * peak) <= 3 * 255
                    }
                    if (matches) {
                        count++
                        left = minOf(left, x)
                        top = minOf(top, y)
                    }
                }
            }
            return CardPixels(count, left, top)
        }
    }

    private fun capture(): Frame {
        val image = compose.onNodeWithTag(GRID_TAG).captureToImage()
        assertEquals("Fixture width", WIDTH, image.width)
        assertEquals("Fixture height", HEIGHT, image.height)
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val frame = Frame(image.width, image.height, pixels)
        return frame
    }

    private fun assertResting(frame: Frame, columns: Int) {
        val cellWidth = (WIDTH - GAP * (columns - 1)).toFloat() / columns
        for (index in 0 until columns * 2) {
            val card = frame.card(index, opaque = true)
            assertTrue("card-$index must be fully opaque and unscaled", card.count >= (cellWidth - 1) * CARD_HEIGHT * 0.98f)
            assertTrue("card-$index must rest in its target column", abs(card.left - (index % columns) * (cellWidth + GAP)) <= 1f)
            assertEquals("card-$index must rest in its target row", (index / columns) * (CARD_HEIGHT + GAP), card.top)
        }
        if (columns == 2) {
            assertEquals("Offscreen card must not leak into the viewport", 0, frame.card(4).count)
            assertEquals("Offscreen card must not leak into the viewport", 0, frame.card(5).count)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "staggered={0}")
        fun layouts(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))

        private const val GRID_TAG = "reflow-grid"
        private const val WIDTH = 240
        private const val HEIGHT = 136
        private const val CARD_HEIGHT = 64
        private const val GAP = 8
        private const val SETTLE_MILLIS = 2_000L
        private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
        // Chromaticity vertices cannot be reproduced by blending the other fixture colors.
        private val COLORS = intArrayOf(
            0xFFFF4000.toInt(), 0xFF40FF00.toInt(), 0xFF00FF40.toInt(),
            0xFF0040FF.toInt(), 0xFF4000FF.toInt(), 0xFFFF0040.toInt(),
        )
    }
}
