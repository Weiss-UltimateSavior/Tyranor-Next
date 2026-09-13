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
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 透镜运动控制器（本项目独立实现）。
 *
 * 结构：**一个帧循环 + 五个自积分弹簧**，没有 `Animatable`、没有互斥锁、没有 `snapshotFlow`、
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
 * | [index] | 连续索引位置，决定透镜槽位 | 1000 / 1.0 |
 * | [pressure] | 按压进度 0..1，驱动折射、高光与覆盖 | 1000 / 1.0 |
 * | [scaleX] / [scaleY] | 按压体积（横向先起、纵向略慢） | 250 / 0.6、250 / 0.7 |
 * | [velocity] | 索引速度估计（带符号），驱动形变 | 300 / 0.5 |
 * | [glow] | 按压光斑亮度（比体积略滞后） | 300 / 0.5 |
 *
 * 速度不是手指 px/s，而是**位置每帧增量的平滑值**再按 [GlassBottomBarSpec.velocityNormalizationSpan]
 * 归一化——与参考实现的观感一致（快速往返时先拉长再收缩，左右方向形变不对称）。
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

    /** 目标索引：拖动过程中是「连续索引」，松手后四舍五入提交。 */
    var targetIndex by mutableFloatStateOf(startIndex)
        private set

    // ---- 运动参数兜底 ----
    // 这几个值直接参与「是否已收敛」的判定或充当除数：一旦是 NaN/±Inf/0，收敛判定永远不成立
    // （帧循环永不退出）或输出直接变成 NaN，因此在控制器初始化时统一归一化为合法值。
    private val pulseVisibleThreshold =
        spec.pulseVisibleThreshold.safeMotionValue(0f, 0.99f, GlassBottomBarSpec.Default.pulseVisibleThreshold)
    private val velocitySpan =
        spec.velocityNormalizationSpan.safeMotionValue(0.01f, 1_000f, GlassBottomBarSpec.Default.velocityNormalizationSpan)
    private val pressedScale =
        spec.pressedScale.safeMotionValue(1f, 10f, GlassBottomBarSpec.Default.pressedScale)

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
    private val positionSpring = Spring(startIndex, 1000f, 1f, epsilon)
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
    private var dragging = false

    /** 手指按下：进入按压形态，并取消上一轮尚未完成的收材质。 */
    fun beginPress() {
        pulsePending = false
        dragging = false
        pressureSpring.target = 1f
        wideSpring.target = pressedScale
        tallSpring.target = pressedScale
        shrinkWhenSettled = false
        ensureFrameLoop()
    }

    /** 拖动：按「索引增量」移动目标位置（像素→索引由调用方按槽宽换算）。 */
    fun dragBy(indexDelta: Float) {
        if (!indexDelta.isFinite()) return
        if (!dragging) {
            // 拖动起手：把基准对齐到**当前视觉位置**。否则「动画途中抓住透镜再拖」时位移会累加在
            // 上一轮目标上（0→3 途中在 1.5 处抓住左拖一格会提交到 2，而非跟手的 0.5 → 吸附到 1）。
            // 「是否算拖动」由手势层按平台 touch slop 把关（见 detectLensPressDrag），
            // 因此这里收到的第一条位移就是真拖动。
            dragging = true
            targetIndex = index.coerceIn(safeRange)
            positionSpring.target = targetIndex
        }
        targetIndex = (targetIndex + indexDelta).coerceIn(safeRange)
        positionSpring.target = targetIndex
        ensureFrameLoop()
    }

    /**
     * 宿主驱动的选中变化（点击切页 / 拖动取消回位）：把透镜送到目标槽位。
     * [pulse] 为真时顺带做一次按压—回落，让点击也有「被按过」的质感。
     */
    fun settleAt(target: Float, pulse: Boolean = true) {
        // 目标值必须有限：否则弹簧永远「未收敛」，帧循环会一直跑下去
        if (!target.isFinite()) return
        targetIndex = target.coerceIn(safeRange)
        positionSpring.target = targetIndex
        pulsePending = pulse
        dragging = false
        if (pulse) {
            pressureSpring.target = 1f
            wideSpring.target = pressedScale
            tallSpring.target = pressedScale
        }
        shrinkWhenSettled = true
        ensureFrameLoop()
    }

    /**
     * 每帧推进：把这一帧的真实时长切成若干个**固定子步长**再积分。
     *
     * 半隐式欧拉并非无条件稳定：对 `k`、阻尼比 ζ，稳定上限约 `dt < 2/√k`。本控制器的
     * 位置/按压弹簧 `k=1000`（√k≈31.6）时临界步长约 **26ms**——若直接用「本帧时长」积分，
     * 一次 30fps 的帧或一次明显卡顿就会让数值发散（透镜来回乱跳且帧循环永不退出）。
     * 因此：
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
                while (isActive) {
                    val now = withFrameNanos { it }
                    val dt = (now - previous) / 1_000_000_000f
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
        private val stiffness: Float,
        private val dampingRatio: Float,
        private val epsilon: Float,
    ) {
        var target: Float = value
        private var speed = 0f

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
        /** 固定积分子步长：远小于 k=1000 时的稳定上限（约 26ms）。 */
        const val SubStepSeconds = 1f / 240f
        /** 单帧最多补偿的真实时长，避免卡顿后一次推进过多（螺旋死亡）。 */
        const val MaxCompensatedSeconds = 1f / 15f
    }
}

