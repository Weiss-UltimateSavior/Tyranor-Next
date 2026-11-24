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
import com.tyranor.next.ui.common.motion.MotionMode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** 统计生产恢复流程的实际耗时，包括已分发的原生触摸回调。 */
class WaterfallRestoreResponsivenessDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hundredCardsRestoreWithoutBlockingInput() = measure(100)
    @Test fun threeThousandCardsRestoreWithoutBlockingInput() = measure(3000)

    @Test fun overdueTouchProbeIncludesTailBlockAfterRestoreCompletes() {
        val delay = requireNotNull(touchProbeDelay(scheduledAt = 1000L, now = 1900L, completedAt = 1800L))
        // 尾段补齐被完整计入：到期时刻在恢复结束之后，只等到结束时为止。
        assertEquals(800L, delay)
        // 恰好等于阈值的那一次仍算「没等超过阈值」，越过一格才算。
        assertEquals(MAX_TOUCH_DISPATCH_DELAY_MILLIS,
            touchProbeDelay(scheduledAt = 1000L, now = 1000L + MAX_TOUCH_DISPATCH_DELAY_MILLIS, completedAt = 0L))
        assertEquals(MAX_TOUCH_DISPATCH_DELAY_MILLIS + 1L,
            touchProbeDelay(scheduledAt = 1000L, now = 1001L + MAX_TOUCH_DISPATCH_DELAY_MILLIS, completedAt = 0L))
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
        // 门禁会临时把动效档位切到增强；记下原值，用例结束时还原，别让后续用例被动继承。
        val originalEnhanced = MotionDeviceAcceptance.require()
        val fixture = Fixture(count)
        // 换列前的完成次数：用来证明这次换列真的触发了一整轮恢复，而不是被挂载信号拦下。
        var restoresBefore = 0
        val handler = Handler(Looper.getMainLooper())
        var scheduledAt = 0L
        val probe = object : Runnable {
            override fun run() {
                // 恢复起点在这里采，而不是在组合里的 snapshotFlow：小列表或快设备上整段恢复
                // 可能在任何收集器读到 isRestoring=true 之前就结束，`first { it }` 会永远等下去，
                // 把用例变成 120s 超时而不是断言失败。错过时只会让 restoreWindow 变成 0，
                // 由下面的窗口守卫跳过，不会挂死。
                if (fixture.started.get() > 0L && fixture.restoreEnteredAt.get() == 0L &&
                    fixture.isRestoring()
                ) {
                    fixture.restoreEnteredAt.compareAndSet(0L, SystemClock.uptimeMillis())
                }
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
                scheduledAt = SystemClock.uptimeMillis() + PROBE_INTERVAL_MILLIS
                handler.postDelayed(this, PROBE_INTERVAL_MILLIS)
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
                restoresBefore = fixture.holder?.completedRestores ?: 0
                fixture.started.set(SystemClock.uptimeMillis())
                fixture.columns = 3
                scheduledAt = SystemClock.uptimeMillis()
                handler.post(probe)
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
            val completedAt = fixture.completed.get()
            val enteredAt = fixture.restoreEnteredAt.get()
            val restoreMs = completedAt - fixture.started.get()
            val restoreWindow = if (enteredAt == 0L) 0L else completedAt - enteredAt
            assertTrue("A post-restore draw callback must have been recorded",
                fixture.firstPostRestoreDrawCallbackAt.get() >= fixture.started.get())
            assertTrue("Restoration including coarse fallback must finish within 2500 ms",
                restoreMs < 2500L)
            // 探针每 8ms 投递一次，只有「到期时刻确实落在恢复区间内」的那几次才算数：
            // 恢复开始前就到的触摸与恢复结束后才到的触摸都不能替恢复期作证。
            assertTrue("Probes must have reached the touch view", fixture.inputCount.get() > 0L)
            // 先证明这一轮恢复真的跑完过：挂载信号若失效，恢复根本不会执行，窗口采不到、
            // 触摸也没被阻塞，下面每条断言都会平凡通过，用例反而显示成「已跳过」。
            assertTrue(
                "Restoration must have completed for this run",
                (fixture.holder?.completedRestores ?: 0) > restoresBefore,
            )
            compose.runOnIdle { assertTrue("Bottom must remain anchored", !fixture.state.canScrollForward) }
            Log.i("WaterfallRestoreMetrics", "count=$count restoreMs=$restoreMs restoreWindowMs=$restoreWindow maxTouchDispatchDelayMs=${fixture.maxInputDelay.get()} firstPostRestoreDrawCallbackMs=${fixture.firstPostRestoreDrawCallbackAt.get() - fixture.started.get()} probes=${fixture.inputCount.get()} touchesDuringRestore=${fixture.touchesDuringRestore.get()}")
            if (enteredAt == 0L) {
                // 起点只由主线程探针在 isRestoring() 为真时采样，所以「没有窗口样本」有两种成因，
                // 必须分开判：恢复短到一次探针都没到期（真跳过），或探针被整段挡住——
                // 后者正是本用例要抓的回归（输入等整段恢复），跳过它就等于把最坏情况变成绿色。
                if (restoreMs > PROBE_INTERVAL_MILLIS * 2) {
                    fail(
                        "Restore took ${restoreMs}ms but no probe sampled its start; " +
                            "the main thread was blocked for the whole window",
                    )
                }
                assumeTrue(
                    "Restore (${restoreMs}ms) finished inside one $PROBE_INTERVAL_MILLIS ms probe " +
                        "interval; touch-window assertions are unobservable",
                    false,
                )
            }
            assertTrue("Input callbacks must not wait for the whole restore",
                fixture.maxInputDelay.get() < MAX_TOUCH_DISPATCH_DELAY_MILLIS)
            assertTrue("A touch due inside the restore window must be processed",
                fixture.touchesDuringRestore.get() > 0L)
        } finally {
            handler.removeCallbacks(probe)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { MotionMode.sync(originalEnhanced) }
        }
    }

    @Composable
    private fun Content(fixture: Fixture) {
        val columns = fixture.columns
        val holder = rememberWaterfallReflowState(columns, columns == 3)
        fixture.state = holder.gridState
        fixture.scope = rememberCoroutineScope()
        fixture.holder = holder
        fixture.isRestoring = { holder.isRestoring }
        LaunchedEffect(holder.gridState) {
            // 只等结束；起点由 8ms 探针采（见 probe），避免整段恢复在收集器读到之前就结束时永久等待。
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
                                // 只统计到期时刻落在恢复窗口内的那几次，避免恢复开始前的
                                // 首次探针把「恢复期间输入可用」平凡地满足掉。
                                val enteredAt = fixture.restoreEnteredAt.get()
                                val completedAt = fixture.completed.get()
                                if (enteredAt != 0L && event.eventTime >= enteredAt &&
                                    (completedAt == 0L || event.eventTime < completedAt)
                                ) {
                                    fixture.touchesDuringRestore.incrementAndGet()
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
        /** 挂载后的状态对象本身，用来读完成整恢复的次数。 */
        var holder: WaterfallReflowState? = null
        // 让探针能在主线程读到宿主当前的恢复状态（探针只跑在主线程，不涉及跨线程可见性）。
        var isRestoring: () -> Boolean = { false }
        val started = AtomicLong()
        val completed = AtomicLong()
        val restoreEnteredAt = AtomicLong()
        val firstPostRestoreDrawCallbackAt = AtomicLong()
        val inputCount = AtomicLong()
        val touchesDuringRestore = AtomicLong()
        val maxInputDelay = AtomicLong()
    }

    private companion object {
        /** 触摸探针的投递间隔。 */
        const val PROBE_INTERVAL_MILLIS = 8L

        /**
         * 触摸回调投递延迟的上限。
         *
         * 推导：恢复扫描每满 4ms 让出一帧，单次连续阻塞的量级是「一次切片（4ms）+ 一次
         * `scrollBy` 的测量成本」；尾段补齐与列历史重建叠在同一帧时再翻几倍。实测大列表
         * 30–90ms，250ms 取该量级的 3–8 倍，仍能把「输入被整段恢复（最长 2500ms）卡住」
         * 这种真回归判红。
         */
        const val MAX_TOUCH_DISPATCH_DELAY_MILLIS = 250L
    }
}
