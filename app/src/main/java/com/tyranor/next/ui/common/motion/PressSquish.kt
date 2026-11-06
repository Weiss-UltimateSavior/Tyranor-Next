package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.traverseAncestors
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/** 按压形变默认值。 */
object PressSquishDefaults {
    /** 按钮、图标、标签等小控件按下时缩到多少。 */
    const val Scale: Float = 0.85f

    /** 卡片、宫格、列表项等中等面积控件。 */
    const val CardScale: Float = 0.92f

    /** 整行（卡片内平铺的一行，或没有卡片外框的设置行）。 */
    const val RowScale: Float = 0.95f

    /** 整张分组卡片（含多行）：以手指为中心收拢，按下的那一行留在指尖下。 */
    const val GroupScale: Float = 0.94f

    /** 跟手偏移上限。 */
    val Follow: Dp = 6.dp

    /** 大面积控件每边最多收进多少（防止整屏大小的卡片缩得过猛）。 */
    val MaxInset: Dp = 20.dp
}

/**
 * Q 弹按压反馈：按下明显缩小并朝手指方向偏移、略微拉长（跟手形变），松手带过冲地弹回。
 *
 * - 只改绘制图层（不重组、不重新布局），静止时完全等于原样；
 * - 不消费任何触摸事件，可与 `clickable` / Miuix 组件叠用；放在修饰符链最外层，让背景、描边与内容整体一起形变；
 * - 按下先等一小会儿：手指一动被滚动接手时平静收回，不会滚动时闪一下；快速轻点也补一次完整的按下 + 回弹；
 * - 「普通」动效档（[MotionMode]）下不加任何节点。
 *
 * @param enabled false 时不加任何节点（禁用态不反馈）
 * @param scale 按下时缩到多少，见 [PressSquishDefaults]
 * @param follow 跟手偏移上限，0 = 不跟手
 */
fun Modifier.pressSquish(
    enabled: Boolean = true,
    scale: Float = PressSquishDefaults.Scale,
    follow: Dp = PressSquishDefaults.Follow,
): Modifier = if (enabled && MotionMode.enhanced) this then PressSquishElement(scale, follow, group = false) else this

/**
 * 整张卡片 / 整个分组一起按压：手指按在卡片里任何一行上，整张卡片（底色、描边、所有行）以按下点为中心收拢、
 * 松手回弹。卡片内部各行自带的 [pressSquish]（以及嵌套的分组）会自动让位给最外层的分组，不会叠加两层形变。
 */
fun Modifier.pressSquishGroup(
    enabled: Boolean = true,
    scale: Float = PressSquishDefaults.GroupScale,
    follow: Dp = PressSquishDefaults.Follow,
): Modifier = if (enabled && MotionMode.enhanced) this then PressSquishElement(scale, follow, group = true) else this

private const val PRESS_DELAY_MILLIS = 55L
private const val TAP_PULSE_MILLIS = 150L
private val OUTSIDE_SLOP = 16.dp
private const val TRAVERSE_KEY = "com.tyranor.next.motion.PressSquish"

private data class PressSquishElement(
    val scale: Float,
    val follow: Dp,
    val group: Boolean,
) : ModifierNodeElement<PressSquishNode>() {
    override fun create(): PressSquishNode = PressSquishNode(scale, follow, group)

    override fun update(node: PressSquishNode) {
        node.pressedScale = scale
        node.follow = follow
        node.group = group
    }

    override fun InspectorInfo.inspectableProperties() {
        name = if (group) "pressSquishGroup" else "pressSquish"
        properties["scale"] = scale
        properties["follow"] = follow
    }
}

private class PressSquishNode(
    var pressedScale: Float,
    var follow: Dp,
    var group: Boolean,
) : DelegatingNode(), LayoutModifierNode, TraversableNode {

    override val traverseKey: Any = TRAVERSE_KEY

    // 两个 Animatable 在第一次按压 / 轻点时才创建（绝大多数节点从不被按到）。
    // 持有它们的是快照状态：图层块读到后，创建时会自动重跑并开始观察动画值
    // （invalidateLayer 只重绘图层、不重跑图层块，稳定的图层块也不会因重新放置而重跑）
    private var pressAnim by mutableStateOf<Animatable<Float, AnimationVector1D>?>(null)
    private var shiftAnim by mutableStateOf<Animatable<Offset, AnimationVector2D>?>(null)
    private var width = 1f
    private var height = 1f
    private var origin = TransformOrigin.Center
    private var pressJob: Job? = null
    private var shiftJob: Job? = null
    private var lastShiftTarget = Offset.Zero

    private fun press(): Animatable<Float, AnimationVector1D> =
        pressAnim ?: Animatable(1f).also { pressAnim = it }

    private fun shift(): Animatable<Offset, AnimationVector2D> =
        shiftAnim ?: Animatable(Offset.Zero, Offset.VectorConverter).also { shiftAnim = it }

    /** 常驻图层块（只存一份，放置时不再分配）：动画值在这里读取，变化只失效图层。 */
    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val k = pressAnim?.value ?: 1f
        val s = shiftAnim?.value ?: Offset.Zero
        // 朝手指那侧略微拉长：偏移越大拉得越多（最多约 2%）
        val stretchX = 1f + abs(s.x) / width.coerceAtLeast(1f) * 0.6f
        val stretchY = 1f + abs(s.y) / height.coerceAtLeast(1f) * 0.6f
        scaleX = k * stretchX
        scaleY = k * stretchY
        translationX = s.x
        translationY = s.y
        transformOrigin = origin
    }

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    // 手势被异常中断（指针流取消）时，释放代码不会跑到：用 finally 兜底收回
                    var pressing = false
                    fun finish(bouncy: Boolean) {
                        pressing = false
                        release(bouncy)
                    }
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // 外层有整卡按压时让位给卡片（嵌套的分组也让位给最外层），避免两层形变叠加
                        if (hasGroupAncestor()) return@awaitEachGesture
                        var finger = down.position
                        // 先观望一小会儿：松手 = 轻点；移动被上层消费 = 滚动接手
                        val early: Int? = withTimeoutOrNull(PRESS_DELAY_MILLIS) {
                            var verdict = OUTCOME_CANCEL
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    verdict = OUTCOME_CANCEL
                                    break
                                }
                                if (!change.pressed) {
                                    // 抬起被点击处理消费 = 真正的轻点；未被消费（取消、点在不可点处）不补回弹
                                    verdict = if (change.isConsumed) OUTCOME_TAP else OUTCOME_CANCEL
                                    break
                                }
                                if (change.isConsumed && change.positionChange() != Offset.Zero) {
                                    verdict = OUTCOME_CANCEL
                                    break
                                }
                                finger = change.position
                            }
                            verdict
                        }
                        when (early) {
                            OUTCOME_TAP -> tapPulse(finger)
                            OUTCOME_CANCEL -> Unit
                            else -> {
                                pressDown(finger)
                                pressing = true
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        finish(bouncy = false)
                                        break
                                    }
                                    if (!change.pressed) {
                                        // 点击生效才 Q 弹回弹；手势被取消时平静收回
                                        finish(bouncy = change.isConsumed)
                                        break
                                    }
                                    if (change.isConsumed && change.positionChange() != Offset.Zero) {
                                        // 滚动 / 拖动接手：平静收回，不回弹
                                        finish(bouncy = false)
                                        break
                                    }
                                    if (isOutside(change.position)) {
                                        // 手指拖出控件范围（与 iOS 一致）：平静收回，抬手前不再形变
                                        finish(bouncy = false)
                                        break
                                    }
                                    followFinger(change.position)
                                }
                            }
                        }
                    } finally {
                        if (pressing && isAttached) release(bouncy = false)
                    }
                }
            },
        )
    }

    /** 手指是否已移出控件范围（四周放宽 [OUTSIDE_SLOP]）。 */
    private fun isOutside(p: Offset): Boolean {
        val m = OUTSIDE_SLOP.value * requireDensity().density
        return p.x < -m || p.y < -m || p.x > width + m || p.y > height + m
    }

    private fun hasGroupAncestor(): Boolean {
        var found = false
        traverseAncestors(TRAVERSE_KEY) { node ->
            if (node is PressSquishNode && node.group) {
                found = true
                false
            } else {
                true
            }
        }
        return found
    }

    /** 按下时的缩放：大面积控件按「每边最多收进 MaxInset」封顶。 */
    private fun effectiveScale(): Float {
        val inset = PressSquishDefaults.MaxInset.value * requireDensity().density
        val dim = max(width, height).coerceAtLeast(1f)
        val minScale = 1f - 2f * inset / dim
        return max(pressedScale, minScale).coerceAtMost(1f)
    }

    private fun shiftTarget(finger: Offset): Offset {
        val limit = follow.value * requireDensity().density
        if (limit <= 0f) return Offset.Zero
        val dx = (finger.x - width / 2f) / (width / 2f).coerceAtLeast(1f)
        val dy = (finger.y - height / 2f) / (height / 2f).coerceAtLeast(1f)
        return Offset(limit * tanh(dx), limit * tanh(dy))
    }

    private fun originAt(finger: Offset) {
        if (!group) return
        // 分组卡片：以按下点为中心收拢（回弹途中不换原点，避免跳动）
        if ((pressAnim?.value ?: 1f) != 1f) return
        origin = TransformOrigin(
            (finger.x / width.coerceAtLeast(1f)).coerceIn(0f, 1f),
            (finger.y / height.coerceAtLeast(1f)).coerceIn(0f, 1f),
        )
    }

    /** 本次按下的目标缩放（松手时据此判断是否已按到位）。 */
    private var pressTarget = 1f

    private fun pressDown(finger: Offset) {
        originAt(finger)
        val target = effectiveScale()
        pressTarget = target
        pressJob?.cancel()
        pressJob = coroutineScope.launch {
            press().animateTo(target, MotionSprings.response(0.2f, 0.58f, 0.0005f))
        }
        followFinger(finger)
    }

    private fun followFinger(finger: Offset) {
        if (group) return
        val target = shiftTarget(finger)
        // 目标几乎没变（< 0.5px）时不重启动画，避免每个移动事件都新建协程
        if (shiftJob?.isActive == true && (target - lastShiftTarget).getDistance() < 0.5f) return
        lastShiftTarget = target
        shiftJob?.cancel()
        // 跟手：更紧的弹簧，且立即开始（不晚一帧）
        shiftJob = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            shift().animateTo(target, MotionSprings.response(0.16f, 0.82f, Offset(0.1f, 0.1f)))
        }
    }

    private fun release(bouncy: Boolean) {
        pressJob?.cancel()
        pressJob = coroutineScope.launch {
            if (bouncy) {
                // 单点：手指抬起时形变往往还没压到位（典型点按只有 80–150ms）。先快速补完按压深度，
                // 再带明显过冲地弹回，保证每一次点按都有完整的「压下 → Q 弹」
                val depth = 1f - pressTarget
                val p = press()
                val reached = if (depth > 0f) (1f - p.value) / depth else 1f
                if (reached < 0.85f) {
                    p.animateTo(pressTarget, MotionSprings.response(0.08f, 1f, 0.0005f))
                }
                p.animateTo(1f, MotionSprings.response(0.46f, 0.36f, 0.0005f))
            } else {
                press().animateTo(1f, MotionSprings.response(0.3f, 1f, 0.0005f))
            }
        }
        shiftJob?.cancel()
        // 从未形变过的位移（分组卡片不跟手）没有东西可回弹：不创建 Animatable、也不开协程
        val sh = shiftAnim
        shiftJob = if (sh == null) null else coroutineScope.launch {
            sh.animateTo(Offset.Zero, MotionSprings.response(0.36f, if (bouncy) 0.55f else 1f, Offset(0.1f, 0.1f)))
        }
    }

    private fun tapPulse(finger: Offset) {
        originAt(finger)
        val target = effectiveScale()
        pressJob?.cancel()
        pressJob = coroutineScope.launch {
            val p = press()
            p.animateTo(target, MotionSprings.response(0.08f, 1f, 0.0005f))
            delay(TAP_PULSE_MILLIS / 4)
            p.animateTo(1f, MotionSprings.response(0.46f, 0.36f, 0.0005f))
        }
        if (!group) {
            shiftJob?.cancel()
            shiftJob = coroutineScope.launch {
                val sh = shift()
                sh.animateTo(shiftTarget(finger), MotionSprings.response(0.2f, 0.8f, Offset(0.1f, 0.1f)))
                sh.animateTo(Offset.Zero, MotionSprings.response(0.36f, 0.55f, Offset(0.1f, 0.1f)))
            }
        }
    }

    override fun onAttach() {
        // 懒列表复用节点时，上一次的回弹可能在半途被取消：重新挂载时同步回到原样
        val p = pressAnim
        val sh = shiftAnim
        if ((p != null && p.value != 1f) || (sh != null && sh.value != Offset.Zero)) {
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                p?.snapTo(1f)
                sh?.snapTo(Offset.Zero)
            }
        }
    }

    override fun onDetach() {
        pressJob = null
        shiftJob = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        width = placeable.width.toFloat()
        height = placeable.height.toFloat()
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }
}

private const val OUTCOME_TAP = 1
private const val OUTCOME_CANCEL = 2
