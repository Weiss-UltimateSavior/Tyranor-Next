package com.tyranor.next.ui.common.motion

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 统计生产恢复流程的实际耗时，包括已分发的原生触摸回调。 */
class WaterfallRestoreResponsivenessDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hundredCardsRestoreWithoutBlockingInput() = measure(100)
    @Test fun threeThousandCardsRestoreWithoutBlockingInput() = measure(3000)

    @Test fun overdueTouchProbeIncludesTailBlockAfterRestoreCompletes() {
        val delay = requireNotNull(touchProbeDelay(scheduledAt = 1000L, now = 1900L, completedAt = 1800L))
        assertEquals(800L, delay)
        assertTrue("An 800 ms tail block must exceed the input threshold", delay >= 250L)
        assertEquals(800L, touchProbeDelay(scheduledAt = 1000L, now = 1800L, completedAt = 0L))
    }

    @Test fun touchProbeEligibilityIncludesCompletionButExcludesLaterDeadlines() {
        assertEquals(0L, touchProbeDelay(scheduledAt = 1800L, now = 1900L, completedAt = 1800L))
        assertNull(touchProbeDelay(scheduledAt = 1801L, now = 1900L, completedAt = 1800L))
        assertNull(touchProbeDelay(scheduledAt = 1000L, now = 999L, completedAt = 0L))
        assertEquals(100L, touchProbeDelay(scheduledAt = 1000L, now = 1100L, completedAt = 1800L))
    }

    private fun touchProbeDelay(scheduledAt: Long, now: Long, completedAt: Long): Long? {
        val waitEnd = if (completedAt == 0L) now else minOf(now, completedAt)
        return (waitEnd - scheduledAt).takeIf { it >= 0L }
    }

    private fun measure(count: Int) {
        MotionDeviceAcceptance.require()
        val fixture = Fixture(count)
        val handler = Handler(Looper.getMainLooper())
        var scheduledAt = 0L
        val probe = object : Runnable {
            override fun run() {
                if (touchProbeDelay(scheduledAt, SystemClock.uptimeMillis(), fixture.completed.get()) != null) {
                    val event = MotionEvent.obtain(scheduledAt, scheduledAt, MotionEvent.ACTION_DOWN, 2f, 2f, 0)
                    try {
                        fixture.touchView.dispatchTouchEvent(event)
                    } finally {
                        event.recycle()
                    }
                    val up = MotionEvent.obtain(scheduledAt, scheduledAt, MotionEvent.ACTION_UP, 2f, 2f, 0)
                    try { fixture.touchView.dispatchTouchEvent(up) } finally { up.recycle() }
                }
                if (fixture.completed.get() != 0L) return
                scheduledAt = SystemClock.uptimeMillis() + 8L
                handler.postDelayed(this, 8L)
            }
        }
        try {
            compose.setContent { Content(fixture) }
            compose.waitForIdle()
            compose.runOnIdle {
                fixture.scope.launch {
                    fixture.state.scrollToItem(count - 1)
                    fixture.state.scrollBy(fixture.state.layoutInfo.viewportSize.height.toFloat())
                }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("Setup must reach the final card", !fixture.state.canScrollForward)
                fixture.started.set(SystemClock.uptimeMillis())
                fixture.columns = 3
                scheduledAt = SystemClock.uptimeMillis() + 8L
                handler.postDelayed(probe, 8L)
            }
            compose.waitUntil(timeoutMillis = 120_000L) { fixture.completed.get() > 0L }
            compose.waitForIdle()
            val image = compose.onNodeWithTag("restore-grid").captureToImage().toPixelMap()
            var painted = false
            for (y in 0 until image.height step 8) {
                for (x in 0 until image.width step 8) {
                    val color = image[x, y]
                    if (color.green > 0.4f && color.red < 0.1f) painted = true
                }
            }
            assertTrue("Restored content must actually be drawn", painted)
            assertTrue("A native touch due during restore must be processed", fixture.inputCount.get() > 0L)
            assertTrue("Input callbacks must not wait for the whole restore", fixture.maxInputDelay.get() < 250L)
            assertTrue("A post-restore draw callback must have been recorded",
                fixture.firstPostRestoreDrawCallbackAt.get() >= fixture.started.get())
            assertTrue("Restoration including coarse fallback must finish within 2500 ms",
                fixture.completed.get() - fixture.started.get() < 2500L)
            compose.runOnIdle { assertTrue("Bottom must remain anchored", !fixture.state.canScrollForward) }
            Log.i("WaterfallRestoreMetrics", "count=$count restoreMs=${fixture.completed.get() - fixture.started.get()} maxTouchDispatchDelayMs=${fixture.maxInputDelay.get()} firstPostRestoreDrawCallbackMs=${fixture.firstPostRestoreDrawCallbackAt.get() - fixture.started.get()} touchesDueDuringRestore=${fixture.inputCount.get()}")
        } finally {
            handler.removeCallbacks(probe)
        }
    }

    @Composable
    private fun Content(fixture: Fixture) {
        val columns = fixture.columns
        val holder = rememberWaterfallReflowState(columns, columns == 3)
        fixture.state = holder.gridState
        fixture.scope = rememberCoroutineScope()
        LaunchedEffect(holder.gridState) {
            snapshotFlow { holder.isRestoring }.first { !it }
            if (fixture.started.get() > 0L) fixture.completed.compareAndSet(0L, SystemClock.uptimeMillis())
        }
        Column {
            AndroidView(
                factory = { context ->
                    View(context).also { view ->
                        fixture.touchView = view
                        view.setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                val delay = touchProbeDelay(event.eventTime, SystemClock.uptimeMillis(), fixture.completed.get())
                                if (delay != null) {
                                    fixture.inputCount.incrementAndGet()
                                    fixture.maxInputDelay.updateAndGet { maxOf(it, delay) }
                                }
                            }
                            true
                        }
                    }
                },
                modifier = Modifier.size(32.dp),
            )
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columns),
                state = holder.gridState,
                modifier = Modifier.size(240.dp, 350.dp).testTag("restore-grid")
                    .background(Color.Black).waterfallReflowHost(holder)
                    .drawWithContent {
                        drawContent()
                        if (!holder.isRestoring && fixture.started.get() > 0L) {
                            // 此回调不能证明同一帧中已经绘制出可见内容。
                            fixture.firstPostRestoreDrawCallbackAt.compareAndSet(0L, SystemClock.uptimeMillis())
                        }
                    },
                verticalItemSpacing = 8.dp,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(fixture.count, key = { "metric-$it" }) { index ->
                    Box(Modifier.height((80 + index % 7 * 13).dp).background(Color(0xFF00897B)))
                }
            }
        }
    }

    private class Fixture(val count: Int) {
        var columns by mutableIntStateOf(2)
        lateinit var state: LazyStaggeredGridState
        lateinit var scope: CoroutineScope
        lateinit var touchView: View
        val started = AtomicLong()
        val completed = AtomicLong()
        val firstPostRestoreDrawCallbackAt = AtomicLong()
        val inputCount = AtomicLong()
        val maxInputDelay = AtomicLong()
    }
}
