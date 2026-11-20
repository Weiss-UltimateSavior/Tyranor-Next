package com.tyranor.next.ui.common.motion

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewTreeObserver
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
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
    fun secondRotationReplaysCardsWhilePreviousEntranceIsRunning() = withFixture(entranceStepMillis = 60L) {
        assumeTrue("Enhanced repeated entrances require system animations", ValueAnimator.areAnimatorsEnabled())
        compose.runOnIdle { columns.intValue = 3 }
        awaitWaterfallReady()
        repeat(6) { compose.mainClock.advanceTimeByFrame() }
        val firstEntrance = capture()
        assertTrue("The first entrance must have started before interrupting it", firstEntrance.card(0).count > 0)
        assertEquals("A scheduled later card must still be waiting when interrupted", 0, firstEntrance.card(5).count)

        compose.runOnIdle { columns.intValue = 2 }
        val frames = List(32) {
            compose.mainClock.advanceTimeByFrame()
            capture()
        }
        val firstFrames = (0 until 4).map { card ->
            frames.indexOfFirst { it.card(card).count > 0 }
        }
        assertTrue("Every card must replay for the second rotation", firstFrames.all { it >= 0 })
        assertTrue("The second entrance must restart in list order", firstFrames.zipWithNext().all { (a, b) -> a < b })
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
        assumeTrue("Frame commit callbacks require API 29", Build.VERSION.SDK_INT >= 29)
        for (next in listOf(3, 2)) {
            val first = captureFirstRestoredDraw(next)
            assertResting(first, next)
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
            assertArrayEquals(
                "Ordinary mode must have no deferred placement or entrance ($next columns)",
                first.pixels,
                captureSettledWindow().pixels,
            )
        }
    }

    private fun Fixture.captureFirstRestoredDraw(next: Int): Frame {
        val request = RestoredDraw(next)
        compose.runOnIdle {
            restoredDraw = request
            columns.intValue = next
        }
        try {
            repeat(60) {
                val registered = compose.runOnIdle {
                    // Both the check and the clock step run on the main thread.
                    if (request.registered) true else {
                        compose.mainClock.advanceTimeByFrame()
                        false
                    }
                }
                if (registered) return request.result.get(5, TimeUnit.SECONDS)
            }
            check(compose.runOnIdle { request.registered }) {
                "No restored draw for $next columns within 60 clock frames"
            }
            return request.result.get(5, TimeUnit.SECONDS)
        } finally {
            compose.runOnUiThread { restoredDraw = null }
        }
    }

    private fun Fixture.captureSettledWindow(): Frame {
        val result = CompletableFuture<Frame>()
        compose.runOnIdle {
            copyViewport(checkNotNull(window), checkNotNull(viewportBounds), result)
        }
        return result.get(5, TimeUnit.SECONDS)
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

    private class RestoredDraw(val columns: Int) {
        var registered = false
        val result = CompletableFuture<Frame>()
    }

    private class Fixture(val entranceStepMillis: Long, val captureRestoredDraws: Boolean) {
        val columns = mutableIntStateOf(2)
        var restoredDraw: RestoredDraw? = null
        var appliedColumns = 0
        var window: Window? = null
        var viewportBounds: Rect? = null
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
            val fixture = Fixture(entranceStepMillis, captureRestoredDraws = !enhanced)
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
        val reflow = if (staggered) {
            rememberGridReflowState(
                columns = columns,
                landscape = columns == 3,
                gridState = waterfall,
                ready = !waterfallReflow.isRestoring,
                placementDelta = { key ->
                    val previous = waterfallReflow.previousItemOffsets[key]
                    val current = waterfall.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
                    if (previous != null && current != null) previous - current else androidx.compose.ui.unit.IntOffset.Zero
                },
            )
        } else {
            rememberGridReflowState(columns, columns == 3, grid)
        }
        fixture.waterfall = waterfallReflow
        fixture.reflow = reflow
        // Integral pixel geometry keeps screenshots independent of emulator density.
        val viewport = Modifier
            .size(with(density) { WIDTH.toDp() }, with(density) { HEIGHT.toDp() })
            .then(
                if (fixture.captureRestoredDraws && Build.VERSION.SDK_INT >= 29) {
                    windowFrameObserver(fixture, columns, waterfallReflow, reflow)
                } else Modifier,
            )
            .background(Color.Black)
            .testTag(GRID_TAG)
        val frame = Modifier.fillMaxSize()
            .then(if (staggered) Modifier.waterfallReflowHost(waterfallReflow) else Modifier)
            .gridReflowHost(reflow)
        val gap = with(density) { GAP.toDp() }
        // Capture outside the hidden accessibility subtree, including its preparation frames.
        Box(viewport) {
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
                            listItemMotion(ListItemPlacement.Reflow).staggeredEntrance(
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
                            listItemMotion(ListItemPlacement.Reflow).staggeredEntrance(
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
    }

    @RequiresApi(29)
    @Composable
    private fun windowFrameObserver(
        fixture: Fixture,
        columns: Int,
        waterfall: WaterfallReflowState,
        reflow: GridReflowState,
    ): Modifier {
        val view = LocalView.current
        val window = checkNotNull(LocalActivity.current).window
        SideEffect {
            fixture.appliedColumns = columns
            fixture.window = window
        }
        DisposableEffect(view, window, waterfall, reflow) {
            val observer = view.viewTreeObserver
            val listener = ViewTreeObserver.OnPreDrawListener {
                val request = fixture.restoredDraw
                val bounds = fixture.viewportBounds
                if (request != null && !request.registered && bounds != null &&
                    request.columns == fixture.appliedColumns &&
                    (!staggered || !waterfall.isRestoring) && reflow.isReady
                ) {
                    request.registered = true
                    val source = Rect(bounds)
                    // Register before performDraw captures this frame's commit callbacks.
                    observer.registerFrameCommitCallback {
                        copyViewport(window, source, request.result)
                    }
                }
                true
            }
            observer.addOnPreDrawListener(listener)
            onDispose {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
        return Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            fixture.viewportBounds = Rect(
                bounds.left.roundToInt(), bounds.top.roundToInt(),
                bounds.right.roundToInt(), bounds.bottom.roundToInt(),
            )
        }
    }

    private fun copyViewport(window: Window, bounds: Rect, result: CompletableFuture<Frame>) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bounds, bitmap, { status ->
                try {
                    check(status == PixelCopy.SUCCESS) { "Viewport PixelCopy failed: $status" }
                    val pixels = IntArray(WIDTH * HEIGHT)
                    bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    result.complete(Frame(WIDTH, HEIGHT, pixels))
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                } finally {
                    bitmap.recycle()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (failure: Throwable) {
            bitmap.recycle()
            result.completeExceptionally(failure)
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

    private fun capture(): Frame = compose.onNodeWithTag(GRID_TAG).captureToImage().readFrame()

    private fun ImageBitmap.readFrame(): Frame {
        assertEquals("Fixture width", WIDTH, width)
        assertEquals("Fixture height", HEIGHT, height)
        val pixels = IntArray(width * height)
        readPixels(pixels)
        return Frame(width, height, pixels)
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
