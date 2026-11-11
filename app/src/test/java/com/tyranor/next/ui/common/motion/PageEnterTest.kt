package com.tyranor.next.ui.common.motion

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 二级页面入场翻折进度 [PageEnter] 的退出倒放契约。
 *
 * 曾经的崩溃：`AppScreenActivity.finish()` 在 `lifecycleScope` 里直接 `animateTo`，而 `lifecycleScope`
 * 没有 MonotonicFrameClock，`Animatable.animateTo` 抛 IllegalStateException，返回时整个 App 进程崩溃并重启
 * （表现为「所有进出界面都要重新加载」）。现在倒放统一走 [PageEnter.playTo]，只在页面组合的作用域（带帧时钟）里跑。
 */
class PageEnterTest {

    @Test
    fun playTo_runsInTheBoundScopeAndReachesTarget() = runBlocking {
        val enter = PageEnter(initial = 1f)
        val job = Job()
        enter.scope = CoroutineScope(coroutineContext + job + ManualFrameClock())
        enter.playTo(0f, durationMillis = 80)
        withTimeout(TimeoutMillis) { while (enter.progress.value > 0f) delay(1) }
        assertEquals(0f, enter.progress.value, 1e-4f)
        job.cancel()
    }

    @Test
    fun playTo_withZeroDuration_snapsWithoutNeedingAFrameClock() = runBlocking {
        val enter = PageEnter(initial = 1f)
        val job = Job()
        // 不带帧时钟的作用域：直接就位（snapTo 不需要帧时钟）也绝不能抛异常
        enter.scope = CoroutineScope(coroutineContext + job)
        enter.playTo(0f, durationMillis = 0)
        withTimeout(TimeoutMillis) { while (enter.progress.value > 0f) delay(1) }
        assertEquals(0f, enter.progress.value, 1e-4f)
        job.cancel()
    }

    @Test
    fun playTo_withoutBoundScope_doesNothing() {
        val enter = PageEnter(initial = 1f)
        enter.playTo(0f, durationMillis = 80)
        assertEquals("页面还没组合 / 已离开组合时不做任何事", 1f, enter.progress.value, 0f)
    }

    /** 人工帧时钟：每次 `withFrameNanos` 前进约 16ms。 */
    private class ManualFrameClock : MonotonicFrameClock {
        private var nanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(1)
            nanos += 16_000_000L
            return onFrame(nanos)
        }
    }

    private companion object {
        const val TimeoutMillis = 5_000L
    }
}
