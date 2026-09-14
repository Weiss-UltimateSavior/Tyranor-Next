package com.tyranor.next.ui.common.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 透镜交互状态机（方案 §运动状态机）。
 *
 * - [Idle]：停在权威选中槽位，静止光学常驻；
 * - [PressJump]：任意位置按下后**以正常尺寸高速赴按**（pressure/scale 保持静止，速度形变为 0）；
 * - [PressedTracking]：到达阈值后进入按压形态，并以绝对目标连续跟手；
 * - [Settling]：松手或取消后吸附整数槽，材质按需收回。
 */
internal enum class LensInteractionState { Idle, PressJump, PressedTracking, Settling }

/**
 * 透镜运动控制器（本项目独立实现）。
 *
 * 结构：**一个帧循环 + 六个自积分弹簧**，没有 `Animatable`、没有互斥锁、没有 `snapshotFlow`、
 * 也没有 `VelocityTracker`。整块状态由自己按帧推进，好处是：
 * - 所有输出的时间基准一致（同一个 `withFrameNanos` 时钟），不会出现各属性各自动画导致的相位差；
 * - 「松手后先吸附、再收材质」变成循环里的一个普通条件，不需要挂起的等待流程，
 *   也就不存在旧释放流程抢在新按下之后把透镜缩回去的竞争（参考实现需要额外机制处理这一点）；
 * - 状态本身就是 Compose 快照态，绘制层直接读，动画期间不触发重组。
 *
 * 输出与弹簧参数（数值取自分析报告 §6.3 记录的参考默认档，见 [GlassBottomBarSpec]）：
 *
 * | 输出 | 含义 | 刚度 / 阻尼比 |
 * |---|---|---|
 * | [index] | 连续索引位置，决定透镜槽位 | 赴按 6000 / 跟手 2400 / 吸附 1000，阻尼比均为 1.0 |
 * | [pressure] | 按压进度 0..1，驱动折射、高光与覆盖 | 1000 / 1.0 |
 * | [scaleX] / [scaleY] | 按压体积（横向先起、纵向略慢） | 250 / 0.6、250 / 0.7 |
 * | [velocity] | 索引速度估计（带符号），驱动形变 | 300 / 0.5 |
 * | [glow] | 按压光斑亮度（比体积略滞后） | 300 / 0.5 |
 *
 * 速度不是手指 px/s，而是**位置每帧增量的平滑值**再按 [GlassBottomBarSpec.velocityNormalizationSpan]
 * 归一化——与参考实现的观感一致（快速往返时先拉长再收缩，左右方向形变不对称）。
 * [LensInteractionState.PressJump] 阶段速度形变强制为 0（见 [effectiveVelocity]），
 * 保证快速赴按时滑块保持正常尺寸与正常轮廓。
 */
internal class LensMotionController(
    private val scope: CoroutineScope,
    initialIndex: Float,
    indexRange: ClosedRange<Float>,
    private val spec: GlassBottomBarSpec,
) {
    /**
     * 构造边界归一化后的索引范围：起止必须有限且有序。
     *
     * 逆序或含 NaN 的范围会让 `coerceIn` 直接抛 `IllegalArgumentException`，
     * 因此这里退回单点范围而不是把非法值带进运行期。
     */
    private val safeRange: ClosedRange<Float> =
        indexRange.takeIf {
            it.start.isFinite() && it.endInclusive.isFinite() && it.start <= it.endInclusive
        } ?: 0f..0f

    /**
     * 归一化后的初始索引：非有限值退回范围起点，越界值夹进范围。
     *
     * 若放任非有限初值进入 [lastIndex] 与速度链路，`rawSpeed` 会变成 NaN、
     * 速度弹簧的目标也变成 NaN，`settled` 便永远不成立（帧循环不再结束）。
     */
    private val startIndex: Float =
        if (initialIndex.isFinite()) initialIndex.coerceIn(safeRange) else safeRange.start

    // ---- 对外输出（Compose 快照态，供绘制/手势阶段读取）----
    var index by mutableFloatStateOf(startIndex)
        private set
    var pressure by mutableFloatStateOf(0f)
        private set
    var scaleX by mutableFloatStateOf(1f)
        private set
    var scaleY by mutableFloatStateOf(1f)
        private set
    var velocity by mutableFloatStateOf(0f)
        private set

    /** 按压光斑亮度：比体积弹簧更慢一档，复刻参考实现「光斑略滞后于形变」的观感。 */
    var glow by mutableFloatStateOf(0f)
        private set

    /** 目标索引：赴按/跟手期间是「连续索引」，松手后四舍五入提交。 */
    var targetIndex by mutableFloatStateOf(startIndex)
        private set

    /** 当前交互状态（供手势层与绘制层判断是否处于赴按阶段）。 */
    var state by mutableStateOf(LensInteractionState.Idle)
        private set

    /**
     * 绘制用速度：赴按阶段恒为 0。
     *
     * 方案硬性要求：PressJump 期间 scale = 1、速度形变 = 0，避免高速移动时被拉成畸形轮廓。
     */
    val effectiveVelocity: Float
        get() = if (state == LensInteractionState.PressJump) 0f else velocity

    // ---- 运动参数兜底 ----
    // 这几个值直接参与「是否已收敛」的判定或充当除数：一旦是 NaN/±Inf/0，收敛判定永远不成立
    // （帧循环永不退出）或输出直接变成 NaN，因此在控制器初始化时统一归一化为合法值。
    private val pulseVisibleThreshold =
        spec.pulseVisibleThreshold.safeMotionValue(0f, 0.99f, GlassBottomBarSpec.Default.pulseVisibleThreshold)
    private val velocitySpan =
        spec.velocityNormalizationSpan.safeMotionValue(0.01f, 1_000f, GlassBottomBarSpec.Default.velocityNormalizationSpan)
    private val pressedScaleX =
        spec.pressedScaleX.safeMotionValue(1f, 10f, GlassBottomBarSpec.Default.pressedScaleX)
    private val pressedScaleY =
        spec.pressedScaleY.safeMotionValue(1f, 10f, GlassBottomBarSpec.Default.pressedScaleY)
    private val jumpStiffness =
        spec.pressJumpStiffness.safeMotionValue(200f, 50_000f, GlassBottomBarSpec.Default.pressJumpStiffness)
    private val jumpDamping =
        spec.pressJumpDampingRatio.safeMotionValue(0.05f, 5f, GlassBottomBarSpec.Default.pressJumpDampingRatio)
    private val trackingStiffness =
        spec.pressTrackingStiffness.safeMotionValue(100f, 50_000f, GlassBottomBarSpec.Default.pressTrackingStiffness)
    private val trackingDamping =
        spec.pressTrackingDampingRatio.safeMotionValue(0.05f, 5f, GlassBottomBarSpec.Default.pressTrackingDampingRatio)
    private val arriveThreshold =
        spec.pressArriveThreshold.safeMotionValue(1e-4f, 2f, GlassBottomBarSpec.Default.pressArriveThreshold)

    // ---- 内部弹簧与积分状态 ----
    private val epsilon =
        spec.visibilityThreshold.safeMotionValue(1e-5f, 0.5f, GlassBottomBarSpec.Default.visibilityThreshold)

    /**
     * 「位置已足够接近目标」的收起阈值。
     *
     * 下限取 1e-5 而不是 0：收起条件是**严格小于**，等于 0 会永远不成立，帧循环便不再退出。
     * 这里刻意不取 `max(阈值, epsilon)`——那会让收起提前到「离目标还有 epsilon」时发生，
     * 而 epsilon 同时是弹簧的静止判据，等于让透镜停在半路。
     */
    private val releaseThreshold =
        spec.releaseThreshold.safeMotionValue(1e-5f, 10f, GlassBottomBarSpec.Default.releaseThreshold)
    private val positionSpring = Spring(startIndex, SettleStiffness, SettleDamping, epsilon)
    private val pressureSpring = Spring(0f, 1000f, 1f, epsilon)
    private val wideSpring = Spring(1f, 250f, 0.6f, epsilon)
    private val tallSpring = Spring(1f, 250f, 0.7f, epsilon)
    private val speedSpring = Spring(0f, 300f, 0.5f, epsilon * 10f)
    private val glowSpring = Spring(0f, 300f, 0.5f, epsilon)

    /**
     * 是否仍在推进动画（静止后帧循环自动退出）。
     *
     * 这是控制器的对外状态：本仓库的控制器测试用它作为「已收敛」的权威判据，
     * 宿主也可据此判断当前是否需要等待（生产代码目前不依赖它，属有意保留的公共契约）。
     */
    var isAnimating by mutableStateOf(false)
        private set

    private var shrinkWhenSettled = false
    private var lastIndex = startIndex
    private var loopActive = false
    private var pulsePending = false

    /**
     * 会话编号：每次按下 / 松手 / 取消 / 外部同步都递增。
     *
     * 用于丢弃「上一轮会话的收尾意图」——例如松手瞬间的到位判定不得再让滑块膨胀。
     */
    private var sessionId = 0

    /**
     * 按下并用**绝对目标**启动一次会话（方案 §开始按压）。
     *
     * 与旧 [beginPress] 的区别：不从旧 target 或 selectedIndex 起跳，而是从**当前视觉位置**
     * 出发；距离超过 [GlassBottomBarSpec.pressArriveThreshold] 时先进入 [LensInteractionState.PressJump]，
     * 保持正常尺寸高速赴按，到达后才进入按压形态；已在手指附近则同帧进入按压。
     */
    fun beginPressAt(target: Float) {
        if (!target.isFinite()) return
        val clamped = target.coerceIn(safeRange)
        sessionId++
        pulsePending = false
        shrinkWhenSettled = false
        targetIndex = clamped
        // 位置：从当前视觉位置起跳，清零速度，换用赴按动力学
        positionSpring.setDynamics(jumpStiffness, jumpDamping)
        positionSpring.resetVelocity()
        positionSpring.target = clamped
        // 材质：明确复位到静止目标（赴按阶段不得膨胀）
        pressureSpring.target = 0f
        wideSpring.target = 1f
        tallSpring.target = 1f
        glowSpring.target = 0f
        if (abs(index - clamped) <= arriveThreshold) {
            enterPressedTracking()
        } else {
            state = LensInteractionState.PressJump
        }
        ensureFrameLoop()
    }

    /**
     * 跟手：用**绝对索引**更新目标（不累加位移，避免误差累积）。
     *
     * 仅在赴按/跟手阶段有效；其他状态忽略（例如松手后的吸附过程不被手指继续拖动）。
     */
    fun updatePressTarget(target: Float) {
        if (!target.isFinite()) return
        if (state != LensInteractionState.PressJump && state != LensInteractionState.PressedTracking) return
        targetIndex = target.coerceIn(safeRange)
        positionSpring.target = targetIndex
        ensureFrameLoop()
    }

    /**
     * 松手：吸附到 [destinationIndex] 并收起材质。
     *
     * 若松手发生在赴按到位**之前**，则以正常尺寸吸附——绝不允许「晚到的到位逻辑」再把它膨胀起来。
     */
    fun endPress(destinationIndex: Int) {
        sessionId++
        pulsePending = false
        val destination = destinationIndex.toFloat().coerceIn(safeRange)
        targetIndex = destination
        positionSpring.setDynamics(SettleStiffness, SettleDamping)
        positionSpring.target = destination
        if (state == LensInteractionState.PressedTracking) {
            // 已进入按压形态：沿用「接近目标后再收材质」的既有手感
            shrinkWhenSettled = true
        } else {
            // 赴按途中松手：保持正常尺寸直接吸附
            pressureSpring.target = 0f
            wideSpring.target = 1f
            tallSpring.target = 1f
            glowSpring.target = 0f
            shrinkWhenSettled = false
        }
        state = LensInteractionState.Settling
        ensureFrameLoop()
    }

    /**
     * 取消：不提交、不补震，回到外部权威选中项并恢复静止尺寸。
     */
    fun cancelPress(authoritativeIndex: Float) {
        if (!authoritativeIndex.isFinite()) return
        sessionId++
        pulsePending = false
        shrinkWhenSettled = false
        val authoritative = authoritativeIndex.coerceIn(safeRange)
        targetIndex = authoritative
        positionSpring.setDynamics(SettleStiffness, SettleDamping)
        positionSpring.target = authoritative
        pressureSpring.target = 0f
        wideSpring.target = 1f
        tallSpring.target = 1f
        glowSpring.target = 0f
        state = LensInteractionState.Settling
        ensureFrameLoop()
    }

    /**
     * 外部权威选中项变化：取消当前手势并同步过去，避免 `LaunchedEffect` 与手指争夺位置。
     */
    fun syncSelection(authoritativeIndex: Float) {
        cancelPress(authoritativeIndex)
    }

    /**
     * 宿主驱动的选中变化（点击切页 / 拖动取消回位）：把透镜送到目标槽位。
     * [pulse] 为真时顺带做一次按压—回落，让点击也有「被按过」的质感。
     */
    fun settleAt(target: Float, pulse: Boolean = true) {
        // 目标值必须有限：否则弹簧永远「未收敛」，帧循环会一直跑下去
        if (!target.isFinite()) return
        sessionId++
        targetIndex = target.coerceIn(safeRange)
        positionSpring.setDynamics(SettleStiffness, SettleDamping)
        positionSpring.target = targetIndex
        pulsePending = pulse
        if (pulse) {
            pressureSpring.target = 1f
            wideSpring.target = pressedScaleX
            tallSpring.target = pressedScaleY
        }
        shrinkWhenSettled = true
        state = LensInteractionState.Settling
        ensureFrameLoop()
    }

    /** 进入按压形态：赴按到位后调用（同帧切换动力学并推起材质）。 */
    private fun enterPressedTracking() {
        state = LensInteractionState.PressedTracking
        positionSpring.setDynamics(trackingStiffness, trackingDamping)
        pressureSpring.target = 1f
        wideSpring.target = pressedScaleX
        tallSpring.target = pressedScaleY
        glowSpring.target = 1f
    }

    /**
     * 每帧推进：把这一帧的真实时长切成若干个**固定子步长**再积分。
     *
     * 半隐式欧拉并非无条件稳定：对 `k`、阻尼比 ζ，稳定上限约 `dt < 2/√k`。赴按弹簧 `k=6000`
     * （√k≈77.5）时临界步长约 **26ms**——若直接用「本帧时长」积分，一次 30fps 的帧或一次明显卡顿
     * 就会让数值发散（透镜来回乱跳且帧循环永不退出）。因此：
     * 1. 子步长固定为 [SubStepSeconds]（1/240s），远小于临界值；
     * 2. 单帧补偿的总时长限幅到 [MaxCompensatedSeconds]（1/15s），避免卡顿后一次补太多时间。
     */
    private fun step(dt: Float) {
        if (!dt.isFinite() || dt <= 0f) return
        var remaining = dt.coerceIn(0f, MaxCompensatedSeconds)
        while (remaining > 0f) {
            val sub = if (remaining > SubStepSeconds) SubStepSeconds else remaining
            integrate(sub)
            remaining -= sub
        }
    }

    /** 单个子步的积分：推进六个弹簧并同步对外输出。 */
    private fun integrate(dt: Float) {
        positionSpring.step(dt)
        index = positionSpring.value.coerceIn(safeRange)

        // 速度 = 位置每帧增量 / 时间，再除以归一化跨度（与参考实现的观感一致）
        val rawSpeed = ((index - lastIndex) / dt) / velocitySpan
        lastIndex = index
        speedSpring.target = rawSpeed
        speedSpring.step(dt)
        velocity = speedSpring.value

        // 赴按到位：切换为按压形态并开始膨胀（在帧循环内判定，不用 delay 或独立到位协程）
        if (state == LensInteractionState.PressJump && abs(index - targetIndex) <= arriveThreshold) {
            enterPressedTracking()
        }

        // 本次还欠一个「按压脉冲」时，先等压力涨到可见阈值再收（目标本来就在位也能看见按压）
        if (pulsePending && pressureSpring.value >= pulseVisibleThreshold) {
            pulsePending = false
        }
        // 松手后：位置足够接近目标才收材质，避免「还没吸附就缩回去」
        if (shrinkWhenSettled && !pulsePending && abs(index - targetIndex) < releaseThreshold) {
            pressureSpring.target = 0f
            wideSpring.target = 1f
            tallSpring.target = 1f
            shrinkWhenSettled = false
        }
        pressureSpring.step(dt)
        wideSpring.step(dt)
        tallSpring.step(dt)
        // 光斑跟随按压的目标值，但用自己的弹簧推进（因此略滞后于体积）
        glowSpring.target = pressureSpring.target
        glowSpring.step(dt)

        glow = glowSpring.value.coerceIn(0f, 1f)
        pressure = pressureSpring.value.coerceIn(0f, 1f)
        scaleX = wideSpring.value
        scaleY = tallSpring.value
    }

    /** 是否所有输出都已稳定（用于结束帧循环，静止时不再占用帧回调）。 */
    private fun allSettled(): Boolean =
        state != LensInteractionState.PressJump &&
            !shrinkWhenSettled &&
            positionSpring.settled &&
            pressureSpring.settled &&
            wideSpring.settled &&
            tallSpring.settled &&
            speedSpring.settled &&
            glowSpring.settled

    private fun ensureFrameLoop() {
        if (loopActive) return
        loopActive = true
        isAnimating = true
        scope.launch {
            try {
                var previous = withFrameNanos { it }
                var firstFrame = true
                while (isActive) {
                    val now = withFrameNanos { it }
                    // 首帧修正：否则第一次 withFrameNanos 只记录基准，运动要等到第二帧才开始，
                    // 赴按会凭空晚一帧。这里用受限的名义步长推进一次，之后恢复真实帧差。
                    val dt = if (firstFrame) {
                        firstFrame = false
                        NominalFirstFrameSeconds
                    } else {
                        ((now - previous) / 1_000_000_000f)
                    }
                    previous = now
                    step(dt)
                    if (allSettled()) break
                }
            } finally {
                loopActive = false
                isAnimating = false
            }
        }
    }

    /**
     * 单个弹簧：半隐式欧拉积分 `a = -k(x - target) - 2ζ√k·v`。
     * 与 Compose 的 `Animatable` 采用同一套物理参数（刚度 / 阻尼比），曲线形态一致，
     * 但积分器与状态管理完全由本类自己完成。
     */
    private class Spring(
        var value: Float,
        private var stiffness: Float,
        private var dampingRatio: Float,
        private val epsilon: Float,
    ) {
        var target: Float = value
        private var speed = 0f

        /** 切换动力学参数（赴按 / 跟手 / 吸附三段使用不同刚度）。 */
        fun setDynamics(newStiffness: Float, newDampingRatio: Float) {
            stiffness = newStiffness
            dampingRatio = newDampingRatio
        }

        /** 清零速度：起跳时不能让上一段运动的速度带进来。 */
        fun resetVelocity() {
            speed = 0f
        }

        fun step(dt: Float) {
            val accel = -stiffness * (value - target) - 2f * dampingRatio * sqrt(stiffness) * speed
            speed += accel * dt
            value += speed * dt
            // 兜底：任何非有限值都直接回到目标（同时让 settled 成立，帧循环不会卡死）
            if (!value.isFinite() || !speed.isFinite()) {
                value = target
                speed = 0f
            }
        }

        val settled: Boolean
            get() = abs(value - target) < epsilon && abs(speed) < epsilon * 10f
    }

    private companion object {
        /** 常规吸附弹簧（参考实现的手感）。 */
        const val SettleStiffness = 1000f
        const val SettleDamping = 1f

        /** 固定积分子步长：远小于 k 最大 6000 时的稳定上限。 */
        const val SubStepSeconds = 1f / 240f
        /** 单帧最多补偿的真实时长，避免卡顿后一次推进过多（螺旋死亡）。 */
        const val MaxCompensatedSeconds = 1f / 15f
        /** 首帧名义步长（60Hz 一帧），用于消除帧循环的起步空帧。 */
        const val NominalFirstFrameSeconds = 1f / 60f
    }
}
