package com.tyranor.next.ui.common.glass

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 透镜运动控制器的行为测试：用人工帧时钟驱动自写的弹簧积分器，验证方案要求的状态机契约。
 *
 * 覆盖点（方案 §测试计划 · 控制器单元测试）：
 * 1. 当前槽 DOWN：同帧进入 PressedTracking 并开始膨胀；
 * 2. 远距 DOWN：进入 PressJump，pressure/scale/glow 保持静止、速度形变强制为 0，且位置立即推进；
 * 3. 最远三槽在约 5 帧内进入到位阈值，且不越界；
 * 4. Jump 中 MOVE 用绝对目标更新，不累积误差；
 * 5. 到位前 UP：永不晚到膨胀，以正常尺寸吸附；
 * 6. 到位后 UP：吸附最近槽后恢复静止材质；
 * 7. CANCEL：回权威选中项且不提交；
 * 8. 快速 DOWN-UP-DOWN：旧会话不影响新会话；
 * 9. 外部 selected 变化：取消手势并同步权威项；
 * 10. 40ms 卡顿帧与非法参数：输出有限、最终收敛。
 */
class LensMotionControllerTest {

    @Test
    fun tapCurrentSlot_entersPressedTrackingImmediately() = runBlocking {
        withController { controller ->
            controller.beginPressAt(0f)
            assertEquals(LensInteractionState.PressedTracking, controller.state)
            withTimeout(SettleTimeoutMillis) {
                while (controller.pressure < 0.5f) delay(1)
            }
            assertTrue("原地按下应当立刻膨胀", controller.pressure > 0.5f)
            controller.endPress(0)
            awaitSettled(controller)
            assertTrue("松手后材质归零", controller.pressure < 0.05f)
        }
    }

    @Test
    fun distantDown_startsPressJumpKeepingRestSize() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            assertEquals(LensInteractionState.PressJump, controller.state)
            assertEquals("赴按阶段不得有按压力度", 0f, controller.pressure, 1e-3f)
            assertEquals("赴按阶段保持正常尺寸", 1f, controller.scaleX, 1e-3f)
            assertEquals("赴按阶段不得有光斑", 0f, controller.glow, 1e-3f)
            withTimeout(SettleTimeoutMillis) {
                while (controller.index <= 0.01f) delay(1)
            }
            assertTrue("位置应已开始移动（首帧修正）", controller.index > 0.01f)
        }
    }

    @Test
    fun farthestJump_reachesArrivalQuickly() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            var frames = 0
            withTimeout(SettleTimeoutMillis) {
                while (controller.state == LensInteractionState.PressJump) {
                    delay(1)
                    frames++
                }
            }
            assertTrue("最远三槽应很快到位（此处计的是 1ms 轮询次数，非渲染帧数），实测 $frames 次", frames <= 8)
            assertTrue("赴按途中不得越界", controller.index in 0f..3f)
        }
    }

    @Test
    fun moveDuringJump_updatesAbsoluteTargetWithoutDrift() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            controller.updatePressTarget(1f)
            controller.updatePressTarget(2f)
            assertEquals("绝对目标应立即生效", 2f, controller.targetIndex, 1e-3f)
            withTimeout(SettleTimeoutMillis) {
                while (controller.state == LensInteractionState.PressJump) delay(1)
            }
            assertEquals(LensInteractionState.PressedTracking, controller.state)
            controller.endPress(2)
            awaitSettled(controller)
            assertEquals("应精确停在绝对目标", 2f, controller.index, 1e-2f)
        }
    }

    /**
     * 回归：按住不松手时，连续 MOVE 必须让滑块一路跟到手指最后位置。
     *
     * 这条覆盖「按下能赴按、但移动不跟手」那类回归：手势层把绝对坐标交给
     * [LensMotionController.updatePressTarget]，控制器必须逐帧跟随，而不是只在 DOWN 生效。
     */
    @Test
    fun holdingAndMoving_followsFingerContinuously() = runBlocking {
        withController { controller ->
            controller.beginPressAt(0f)
            // 手指从槽 0 连续滑到槽 3（每次 MOVE 都给绝对目标）
            for (step in 1..30) {
                controller.updatePressTarget(step / 10f)
                delay(1)
            }
            assertEquals("目标必须等于手指最后位置", 3f, controller.targetIndex, 1e-3f)
            withTimeout(SettleTimeoutMillis) {
                while (abs(controller.index - 3f) > 0.02f) delay(1)
            }
            assertTrue("滑块必须跟到手指下", abs(controller.index - 3f) <= 0.02f)
            assertTrue("跟手期间应处于按压形态", controller.state == LensInteractionState.PressedTracking)
        }
    }

    /** 回归：跟手途中反向滑回，也必须跟随（不因方向变化而卡住）。 */
    @Test
    fun holdingAndMovingBack_followsFingerBackwards() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            withTimeout(SettleTimeoutMillis) {
                while (controller.state == LensInteractionState.PressJump) delay(1)
            }
            for (step in 0..20) {
                controller.updatePressTarget(3f - step / 10f)
                delay(1)
            }
            assertEquals(1f, controller.targetIndex, 1e-3f)
            withTimeout(SettleTimeoutMillis) {
                while (abs(controller.index - 1f) > 0.02f) delay(1)
            }
            assertTrue(abs(controller.index - 1f) <= 0.02f)
        }
    }

    /**
     * 回归：**轻点**别的槽位必须与 PR 81 最新版的 `settleAt(pulse = true)` 行为一致 ——
     * **边飞边变大**（膨胀早于到位），到位 + 压力可见后收回正常大小，整栏随压力放大再回落。
     */
    @Test
    fun tapOnOtherSlot_pulsesWhileFlying_likePr81() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            controller.endPress(3, pulse = true) // 快速轻点：UP 早于到位
            // 飞行途中压力就应该已经起来（PR81 的时序：不是等到位才膨胀）
            withTimeout(SettleTimeoutMillis) {
                while (controller.pressure < 0.5f) delay(1)
            }
            assertTrue(
                "膨胀必须早于到位（与 PR81 一致），此时 index=${controller.index}",
                abs(controller.index - 3f) > 0.2f,
            )
            var peakScale = 1f
            withTimeout(SettleTimeoutMillis) {
                while (controller.isAnimating) {
                    peakScale = maxOf(peakScale, controller.scaleX)
                    delay(1)
                }
            }
            assertTrue("体积必须被推起过，实测峰值 $peakScale", peakScale > 1.05f)
            assertEquals("最终必须落在目标槽位", 3f, controller.index, 1e-2f)
            assertTrue("最后必须缩回正常大小", controller.pressure < 0.05f)
            assertEquals("体积回到 1", 1f, controller.scaleX, 1e-2f)
        }
    }

    /**
     * 回归：切换动力学时必须清零位置速度。
     *
     * 必须让**目标落在松手点之前**才能暴露过冲：此时若带着 k=6000 的残余速度继续，
     * 滑块会越过目标再被拉回（仿真约 1.97），清零后从松手点平滑收敛（约 1.37）。
     * 注意：目标在松手点**前方**时两种做法结果相同，不能用来判定。
     */
    @Test
    fun releaseWithTargetBehind_doesNotOvershoot() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            // 飞到 1.37 附近（松手点），目标是它之前的槽 1
            withTimeout(SettleTimeoutMillis) {
                while (controller.index < 1.37f) delay(1)
            }
            val releaseIndex = controller.index
            controller.endPress(1)
            var peak = releaseIndex
            withTimeout(SettleTimeoutMillis) {
                while (controller.isAnimating) {
                    peak = maxOf(peak, controller.index)
                    delay(1)
                }
            }
            assertTrue(
                "目标在松手点之前时不得过冲（松手 $releaseIndex → 峰值 $peak，目标 1）",
                peak < releaseIndex + 0.15f,
            )
            assertEquals("最终停在目标槽位", 1f, controller.index, 1e-2f)
        }
    }

    /**
     * 回归：点击反馈**不做任何拉伸**，只有与手指按压同幅度的放大（用户要求）。
     *
     * 早前速度形变公式有方向性（索引增大被横向拉长），导致「点左边正常扩大、点右边左右拉伸」。
     * 现在形变只由按压弹簧产生：静止为 1，按压后为 spec 的按压缩放（两轴相同）。
     */
    @Test
    fun clickFeedback_enlargesLikeFingerPress_withoutStretch() = runBlocking {
        withController { controller ->
            assertEquals("静止时两轴都是 1", 1f, controller.scaleX, 1e-4f)
            assertEquals(1f, controller.scaleY, 1e-4f)
            controller.beginPressAt(3f)
            controller.endPress(3, pulse = true)
            var peakX = 1f
            var peakY = 1f
            withTimeout(SettleTimeoutMillis) {
                while (controller.isAnimating) {
                    peakX = maxOf(peakX, controller.scaleX)
                    peakY = maxOf(peakY, controller.scaleY)
                    delay(1)
                }
            }
            // 与手指按压同一档：0.78/0.56 ≈ 1.393（允许弹簧略有过冲前的差值）
            assertTrue("两轴都应按压放大，实测 X=$peakX Y=$peakY", peakX > 1.2f && peakY > 1.2f)
            // 关键：两轴比例必须接近 1（速度形变会让 X 远大于 Y；这里测得出拉伸）。
            // 容差 0.05：wide(250/0.6) 与 tall(250/0.7) 阻尼不同，峰值本就略有差异（PR81 原生特性）。
            assertEquals(
                "两轴不得出现拉伸差（X=$peakX Y=$peakY）",
                1f,
                peakX / peakY,
                0.05f,
            )
            assertEquals("扩大后两轴必须回到 1", 1f, controller.scaleX, 1e-2f)
            assertEquals(1f, controller.scaleY, 1e-2f)
        }
    }

    /** 回归：拖动途中松手**不得**补膨胀（避免「松手后又被撑大」）。 */
    @Test
    fun dragReleaseBeforeArrival_doesNotPulse() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            controller.endPress(3, pulse = false)
            var peakPressure = 0f
            withTimeout(SettleTimeoutMillis) {
                while (controller.isAnimating) {
                    peakPressure = maxOf(peakPressure, controller.pressure)
                    delay(1)
                }
            }
            assertTrue("拖动松手不应补膨胀，实测峰值 $peakPressure", peakPressure < 0.5f)
            assertEquals(3f, controller.index, 1e-2f)
        }
    }

    @Test
    fun upBeforeArrival_neverExpandsLate() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            controller.endPress(3)
            var maxPressure = 0f
            withTimeout(SettleTimeoutMillis) {
                while (controller.isAnimating) {
                    maxPressure = maxOf(maxPressure, controller.pressure)
                    delay(1)
                }
            }
            assertTrue("到位前松手不得再膨胀，实测峰值 $maxPressure", maxPressure < 0.5f)
            assertEquals("正常尺寸吸附到目标", 3f, controller.index, 1e-2f)
            assertEquals("尺寸回到 1", 1f, controller.scaleX, 1e-2f)
        }
    }

    @Test
    fun upAfterArrival_settlesAndCollapses() = runBlocking {
        withController { controller ->
            controller.beginPressAt(2f)
            withTimeout(SettleTimeoutMillis) {
                while (controller.state == LensInteractionState.PressJump) delay(1)
            }
            withTimeout(SettleTimeoutMillis) {
                while (controller.pressure < 0.5f) delay(1)
            }
            controller.endPress(2)
            awaitSettled(controller)
            assertTrue("松手后压力归零", controller.pressure < 0.05f)
            assertEquals(2f, controller.index, 1e-2f)
        }
    }

    @Test
    fun cancel_returnsToAuthoritativeIndexWithoutCommit() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            withTimeout(SettleTimeoutMillis) {
                while (controller.index <= 0.01f) delay(1)
            }
            controller.cancelPress(0f)
            awaitSettled(controller)
            assertEquals("CANCEL 必须回到权威选中项", 0f, controller.index, 1e-2f)
            assertTrue("CANCEL 后不得有按压力度", controller.pressure < 0.05f)
        }
    }

    @Test
    fun rapidDownUpDown_oldSessionDoesNotAffectNewOne() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            withTimeout(SettleTimeoutMillis) {
                while (controller.index <= 0.01f) delay(1)
            }
            controller.endPress(3)
            controller.beginPressAt(1f)
            assertEquals(LensInteractionState.PressJump, controller.state)
            withTimeout(SettleTimeoutMillis) {
                while (controller.state == LensInteractionState.PressJump) delay(1)
            }
            assertEquals("新会话目标必须是 1", 1f, controller.targetIndex, 1e-3f)
            controller.endPress(1)
            awaitSettled(controller)
            assertEquals(1f, controller.index, 1e-2f)
        }
    }

    /**
     * 外部权威选中项变化：走与 PR81 一致的 `settleAt`（带脉冲），权威值必须获胜。
     */
    @Test
    fun externalSelectionChange_winsAndPulses() = runBlocking {
        withController { controller ->
            controller.beginPressAt(3f)
            withTimeout(SettleTimeoutMillis) {
                while (controller.index <= 0.01f) delay(1)
            }
            controller.settleAt(1f) // 外部（宿主）驱动的选中变化
            var peak = 0f
            withTimeout(SettleTimeoutMillis) {
                while (controller.isAnimating) {
                    peak = maxOf(peak, controller.pressure)
                    delay(1)
                }
            }
            assertEquals("外部权威项必须获胜", 1f, controller.index, 1e-2f)
            assertTrue("外部变化同样带脉冲（与 PR81 一致），实测峰值 $peak", peak > 0.5f)
            assertTrue("最终材质归零", controller.pressure < 0.05f)
        }
    }

    @Test
    fun jankyFrames_stillConverge() = runBlocking {
        withController(frameNanos = JANK_FRAME_NANOS) { controller ->
            controller.beginPressAt(3f)
            controller.updatePressTarget(2f)
            controller.endPress(2)
            awaitSettled(controller)
            assertEquals(2f, controller.index, 1e-2f)
            assertTrue("输出必须有限", controller.index.isFinite() && controller.pressure.isFinite())
            assertFalse(controller.isAnimating)
        }
    }

    @Test
    fun malformedMotionParams_stillSettle() = runBlocking {
        val specs = listOf(
            GlassBottomBarSpec.Default.copy(visibilityThreshold = Float.NaN),
            GlassBottomBarSpec.Default.copy(visibilityThreshold = 0f),
            GlassBottomBarSpec.Default.copy(releaseThreshold = Float.NaN),
            GlassBottomBarSpec.Default.copy(releaseThreshold = -1f),
            GlassBottomBarSpec.Default.copy(pressArriveThreshold = Float.NaN),
            GlassBottomBarSpec.Default.copy(pressArriveThreshold = -1f),
            GlassBottomBarSpec.Default.copy(pressJumpStiffness = Float.NaN),
            GlassBottomBarSpec.Default.copy(pressTrackingStiffness = 0f),
            GlassBottomBarSpec.Default.copy(pressedScaleX = Float.NaN),
            GlassBottomBarSpec.Default.copy(pressedScaleY = Float.POSITIVE_INFINITY),
        )
        for (spec in specs) {
            withController(spec = spec) { controller ->
                controller.beginPressAt(3f)
                controller.endPress(3)
                awaitSettled(controller)
                assertTrue("输出必须有限", controller.index.isFinite() && controller.pressure.isFinite())
                assertTrue("材质必须收起", controller.pressure < 0.05f)
            }
        }
    }

    @Test
    fun malformedConstructorArgs_areNormalized() = runBlocking {
        val cases = listOf(
            0f to (0f..3f),
            Float.NaN to (0f..3f),
            Float.POSITIVE_INFINITY to (0f..3f),
            -5f to (0f..3f),
            9f to (0f..3f),
            1f to (Float.NaN..3f),
            1f to (3f..0f),
        )
        for ((initial, range) in cases) {
            withController(initialIndex = initial, indexRange = range) { controller ->
                assertTrue(
                    "初值 $initial / 范围 $range 归一化后必须有限",
                    controller.index.isFinite() && controller.targetIndex.isFinite(),
                )
                controller.beginPressAt(controller.targetIndex)
                controller.endPress(controller.targetIndex.toInt())
                awaitSettled(controller)
                assertTrue("输出必须有限", controller.index.isFinite())
            }
        }
    }

    @Test
    fun idleController_staysStill() = runBlocking {
        withController { controller ->
            assertFalse("刚构造时不应有帧循环", controller.isAnimating)
            assertEquals(0f, controller.index, 1e-4f)
            controller.beginPressAt(0f)
            assertTrue("按下后帧循环应当启动", controller.isAnimating)
            controller.endPress(0)
            awaitSettled(controller)
            assertFalse("收敛后帧循环应当自行退出", controller.isAnimating)
            assertTrue("松手后材质归零", controller.pressure < 0.05f)
        }
    }

    // ---- 测试脚手架 ----

    private suspend fun withController(
        frameNanos: Long = NANOS_60HZ,
        spec: GlassBottomBarSpec = GlassBottomBarSpec.Default,
        initialIndex: Float = 0f,
        indexRange: ClosedRange<Float> = 0f..3f,
        block: suspend (LensMotionController) -> Unit,
    ) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + ManualFrameClock(frameNanos))
        val controller = LensMotionController(
            scope = scope,
            initialIndex = initialIndex,
            indexRange = indexRange,
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

    /** 人工帧时钟：每次 `withFrameNanos` 前进 [frameNanos]。 */
    private class ManualFrameClock(private val frameNanos: Long) : MonotonicFrameClock {
        private var nanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(1)
            nanos += frameNanos
            return onFrame(nanos)
        }
    }

    private companion object {
        const val NANOS_60HZ = 16_000_000L
        const val JANK_FRAME_NANOS = 40_000_000L
        const val SettleTimeoutMillis = 10_000L
    }
}
