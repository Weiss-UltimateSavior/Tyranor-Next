package com.tyranor.next.ui.common.glass

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 运动控制器的行为测试：用人工帧时钟驱动自写的弹簧积分器，验证最容易出错的那条链路。
 *
 * 覆盖点：
 * 1. 松手后必须**吸附**到四舍五入后的槽位（而不是停在手指松开的小数位置）；
 * 2. 同一次输入批次里的多次位移要累加；
 * 3. 拖动目标被限制在索引范围内；
 * 4. 材质只在位置靠近目标后收起，最终所有输出收敛（帧循环能退出）。
 */
class LensMotionControllerTest {

    @Test
    fun dragThenRelease_snapsToRoundedSlot() = runBlocking {
        withController { controller ->
            controller.beginPress()
            controller.dragBy(1.6f)
            // 松手：吸附到四舍五入后的槽位 2，并收起材质（不额外脉冲）
            controller.settleAt(2f, pulse = false)
            awaitSettled(controller)

            assertEquals(2f, controller.index, 1e-3f)
            assertEquals(2f, controller.targetIndex, 1e-3f)
            assertTrue("松手后材质应收起", controller.pressure < 0.05f)
            assertTrue("体积应回到 1", abs(controller.scaleX - 1f) < 0.05f)
        }
    }

    @Test
    fun batchedDrags_accumulateBeforeRelease() = runBlocking {
        withController { controller ->
            controller.beginPress()
            // 同一次输入批次里的两次位移必须都算进去（不能被动画目标值吃掉）
            controller.dragBy(0.4f)
            controller.dragBy(0.4f)
            assertEquals(0.8f, controller.targetIndex, 1e-4f)
            controller.settleAt(1f, pulse = false)
            awaitSettled(controller)
            assertEquals(1f, controller.index, 1e-3f)
        }
    }

    @Test
    fun dragIsClampedToIndexRange() = runBlocking {
        withController { controller ->
            controller.beginPress()
            controller.dragBy(99f)
            assertEquals(3f, controller.targetIndex, 1e-4f)
            controller.dragBy(-99f)
            assertEquals(0f, controller.targetIndex, 1e-4f)
        }
    }

    @Test
    fun settleAtWithPulse_pressesThenCollapses() = runBlocking {
        withController { controller ->
            controller.settleAt(2f)
            // 采样整个动画过程：必须出现一次明显按压，然后收起
            var peakPressure = 0f
            withTimeout(SettleTimeoutMillis) {
                while (controller.isAnimating) {
                    peakPressure = maxOf(peakPressure, controller.pressure)
                    delay(1)
                }
            }
            assertEquals(2f, controller.index, 1e-3f)
            assertTrue("点击应有一次按压脉冲，实测峰值 $peakPressure", peakPressure > 0.3f)
            assertTrue(controller.pressure < 0.05f)
        }
    }

    /**
     * 回归：掉帧/30Hz 下积分器必须仍然收敛。
     *
     * 半隐式欧拉对 k=1000 的位置/按压弹簧稳定上限约 26ms，早期实现把「本帧时长」直接当步长、
     * 上限又放到 1/30s，导致 33ms 的帧会让弹簧发散：透镜来回乱跳且帧循环永不退出。
     * 现在改为固定子步长积分，这里用 40ms 的「卡顿帧」验证仍能收敛并收起。
     */
    @Test
    fun jankyFrames_stillConverge() = runBlocking {
        withController(frameNanos = JankFrameNanos) { controller ->
            controller.beginPress()
            controller.dragBy(1.6f)
            controller.settleAt(2f, pulse = false)
            awaitSettled(controller)

            assertEquals(2f, controller.index, 1e-3f)
            assertTrue("卡顿帧后材质也必须收起", controller.pressure < 0.05f)
            assertTrue("输出必须有限", controller.index.isFinite() && controller.pressure.isFinite())
            assertTrue("动画结束后不应继续占用帧循环", !controller.isAnimating)
        }
    }

    /**
     * 回归：目标索引本来就在位时，`settleAt(pulse = true)` 仍必须产生可见按压。
     * 早期实现在第一帧就把压力目标改回 0（目标已足够接近），这次脉冲完全看不见。
     */
    @Test
    fun settleAtWithPulse_onAlreadyNearTarget_stillShowsPress() = runBlocking {
        withController { controller ->
            controller.settleAt(0f) // 目标 = 当前位置，且 pulse 默认为 true
            var peak = 0f
            withTimeout(SettleTimeoutMillis) {
                while (controller.isAnimating) {
                    peak = maxOf(peak, controller.pressure)
                    delay(1)
                }
            }
            assertTrue("近目标也必须出现可见按压，实测峰值 $peak", peak > 0.5f)
            assertTrue("收完后材质要归零", controller.pressure < 0.05f)
        }
    }

    /**
     * 回归：非法运动参数不得把帧循环钉死。
     *
     * `pulseVisibleThreshold` 若为 NaN/±Inf/>1，`pressure >= threshold` 永远不成立，
     * `pulsePending` 与 `shrinkWhenSettled` 就永远清不掉，`allSettled()` 恒为 false
     * → 帧循环一直跑下去。控制器初始化时必须把这些值归一化。
     */
    @Test
    fun malformedPulseThreshold_stillSettles() = runBlocking {
        val bad = listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            1f,
            2f,
            -1f,
        )
        for (value in bad) {
            withController(spec = GlassBottomBarSpec.Default.copy(pulseVisibleThreshold = value)) { controller ->
                controller.settleAt(0f) // 目标已在位 + 需要脉冲：最容易卡住的组合
                awaitSettled(controller)
                assertTrue(
                    "阈值 $value 下材质必须收起",
                    controller.pressure < 0.05f,
                )
                assertTrue("输出必须有限（阈值 $value）", controller.index.isFinite())
            }
        }
    }

    /** 回归：其它参与收敛判定/作除数的运动参数同样要兜底。 */
    @Test
    fun malformedMotionParams_stillSettle() = runBlocking {
        val specs = listOf(
            GlassBottomBarSpec.Default.copy(visibilityThreshold = Float.NaN),
            GlassBottomBarSpec.Default.copy(visibilityThreshold = 0f),
            GlassBottomBarSpec.Default.copy(releaseThreshold = Float.NaN),
            GlassBottomBarSpec.Default.copy(releaseThreshold = -1f),
            GlassBottomBarSpec.Default.copy(velocityNormalizationSpan = 0f),
            GlassBottomBarSpec.Default.copy(velocityNormalizationSpan = Float.NaN),
            GlassBottomBarSpec.Default.copy(pressedScale = Float.NaN),
            GlassBottomBarSpec.Default.copy(pressedScale = Float.POSITIVE_INFINITY),
        )
        for (spec in specs) {
            withController(spec = spec) { controller ->
                controller.beginPress()
                controller.dragBy(1.5f)
                controller.settleAt(2f, pulse = false)
                awaitSettled(controller)
                assertTrue("输出必须有限", controller.index.isFinite() && controller.pressure.isFinite())
                assertTrue("材质必须收起", controller.pressure < 0.05f)
            }
        }
    }

    @Test
    fun idleController_staysStill() = runBlocking {
        withController { controller ->
            delay(20)
            assertTrue("静止时不应维持帧循环", !controller.isAnimating)
            assertEquals(0f, controller.index, 1e-4f)
            assertEquals(0f, controller.pressure, 1e-4f)
            assertEquals(1f, controller.scaleX, 1e-4f)
        }
    }

    /** 人工帧时钟：每次 `withFrameNanos` 前进 [frameNanos]，让积分器以最快速度跑完动画。 */
    private class ManualFrameClock(private val frameNanos: Long = FrameNanos) : MonotonicFrameClock {
        private var nanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            // 让出事件循环，测试才能观察到动画的中间状态
            delay(1)
            nanos += frameNanos
            return onFrame(nanos)
        }
    }

    private suspend fun withController(
        frameNanos: Long = FrameNanos,
        spec: GlassBottomBarSpec = GlassBottomBarSpec.Default,
        block: suspend (LensMotionController) -> Unit,
    ) = coroutineScope {
            val scope = CoroutineScope(coroutineContext + ManualFrameClock(frameNanos))
            val controller = LensMotionController(
                scope = scope,
                initialIndex = 0f,
                indexRange = 0f..3f,
                spec = spec,
            )
            block(controller)
        }

    /** 等到帧循环自己结束：这正是「动画已收敛」的权威条件。 */
    private suspend fun awaitSettled(controller: LensMotionController) {
        withTimeout(SettleTimeoutMillis) {
            while (controller.isAnimating) delay(1)
        }
    }

    private companion object {
        const val FrameNanos = 16_000_000L

        /** 40ms 一帧（约 25fps）：曾会让积分器发散的卡顿区间。 */
        const val JankFrameNanos = 40_000_000L
        const val SettleTimeoutMillis = 10_000L
    }
}
