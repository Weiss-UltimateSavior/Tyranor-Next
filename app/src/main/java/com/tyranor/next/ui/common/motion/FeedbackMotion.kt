package com.tyranor.next.ui.common.motion

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// 状态驱动的小动效原语：抖动、呼吸、流光。
// 全部只改绘制图层（不改布局、不改静止外观）；「普通」动效档与系统关闭动画时退化为原版行为。
// ---------------------------------------------------------------------------------------------

/**
 * 抖一下：[trigger] 每变成一个新的、非 null / 非 false 的值，内容水平晃动并衰减回原位（欠阻尼弹簧，
 * 被打断时从当前位置与速度继续）。第一次组合不抖。用于「输入无效 / 操作被拒绝」的即时反馈。
 *
 * 通常传一个失败计数器：`var shake by remember { mutableIntStateOf(0) }`，校验失败时 `shake++`。
 *
 * @param amplitude 第一次摆动的大致幅度
 */
fun Modifier.shakeOnTrigger(trigger: Any?, amplitude: Dp = 8.dp): Modifier =
    if (MotionMode.enhanced) this then ShakeElement(trigger, amplitude) else this

private class ShakeElement(val trigger: Any?, val amplitude: Dp) : ModifierNodeElement<ShakeNode>() {
    override fun create(): ShakeNode = ShakeNode(trigger, amplitude)

    override fun update(node: ShakeNode) {
        node.amplitude = amplitude
        node.onTrigger(trigger)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "shakeOnTrigger"
        properties["trigger"] = trigger
        properties["amplitude"] = amplitude
    }

    /** 触发值与幅度共同决定修饰符身份，参数未变时保留节点及当前动画。 */
    override fun equals(other: Any?): Boolean =
        other is ShakeElement && other.trigger == trigger && other.amplitude == amplitude

    override fun hashCode(): Int = trigger.hashCode() * 31 + amplitude.hashCode()
}

private class ShakeNode(private var last: Any?, var amplitude: Dp) : Modifier.Node(), LayoutModifierNode {
    private val offset = Animatable(0f)
    private var job: Job? = null
    private var absorbNext = false

    override fun onAttach() {
        // 懒列表复用节点：不继承旧元素的半途位移
        if (offset.value != 0f) {
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(0f) }
        }
    }

    override fun onDetach() {
        job = null
    }

    /** 被懒列表回收复用：复用带来的新触发值不是用户可感知的「失败」，不播放。 */
    override fun onReset() {
        absorbNext = true
    }

    fun onTrigger(trigger: Any?) {
        if (trigger == last) return
        last = trigger
        if (absorbNext) return
        if (trigger == null || trigger == false || !isAttached) return
        // 欠阻尼弹簧 + 初速度：目标就是原位，自然来回衰减
        val kick = with(requireDensity()) { amplitude.toPx() } * SHAKE_KICK
        // 拒绝触觉：与抖动同帧；提示条同时触发时由门面去重
        MotionHaptics.perform(MotionHaptics.Kind.Reject)
        job?.cancel()
        job = coroutineScope.launch {
            offset.animateTo(0f, MotionTokens.wobble(0.5f), initialVelocity = kick)
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        absorbNext = false
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { translationX = offset.value }
        }
    }
}

/** 初速度（px/s）与第一次摆幅（px）的换算系数，按 wobble 弹簧的周期与阻尼标定。 */
private const val SHAKE_KICK = 26f

/**
 * 呼吸：[active] 期间透明度在 [minAlpha] 与 1 之间缓缓起伏，给「加载中 / 等待中」的文字、图标一点生命。
 * 无限循环只能用时长动画（弹簧不循环）；系统关闭动画或「普通」档时不动。
 */
@Composable
fun Modifier.breathing(active: Boolean = true, minAlpha: Float = 0.55f): Modifier {
    if (!MotionMode.enhanced || !ValueAnimator.areAnimatorsEnabled()) return this
    // 起伏幅度随 active 弹簧进出：开始时渐渐起伏，结束时平滑回到完全不透明（不会从半透明瞬间跳回 1）
    val strength = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = MotionTokens.fadeOut(0.001f),
        label = "breathingStrength",
    )
    if (!active && strength.value == 0f) return this
    val transition = rememberInfiniteTransition(label = "breathing")
    val cycle = transition.animateFloat(
        initialValue = 1f,
        targetValue = minAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathingAlpha",
    )
    return this.graphicsLayer { this.alpha = 1f + (cycle.value - 1f) * strength.value }
}

/**
 * 流光：[active] 期间在内容之上扫过一道斜向高光（骨架 / 占位 / 加载中的块）。只在绘制阶段读动画值，
 * 内容与布局不变；[active] 为 false、「普通」档或系统关闭动画时不画。
 *
 * 高光只叠在已有内容上，调用方应只对「正在加载的占位」启用，加载完成即关闭。
 */
@Composable
fun Modifier.shimmerSweep(
    active: Boolean = true,
    highlight: Color = Color.White.copy(alpha = 0.22f),
): Modifier {
    if (!active || !MotionMode.enhanced || !ValueAnimator.areAnimatorsEnabled()) return this
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1300, easing = LinearEasing)),
        label = "shimmerPhase",
    )
    return this.drawWithContent {
        drawContent()
        val band = size.width * 0.6f
        val x = -band + (size.width + band) * phase.value
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + band, size.height * 0.35f),
            ),
        )
    }
}
