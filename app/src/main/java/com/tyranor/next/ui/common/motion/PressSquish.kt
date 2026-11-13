package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
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
import androidx.compose.ui.node.traverseDescendants
import androidx.compose.ui.node.traverseAncestors
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
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
 * - 按下的同一帧就开始反馈（先轻轻落下一点，观望 [PRESS_COMMIT_MILLIS] 后没被滚动接手再压到位）：真实的轻点常常只有
 *   40–80ms，若先观望再反馈，手指按着的整个过程什么也看不到，抬手那一刻才凹一下，和点击结果（弹窗 / 抽屉）挤在同一帧，
 *   完全不「跟手」。手指一动被滚动接手时平静收回；快速轻点抬手时补足按压深度再 Q 弹回去；
 * - 「普通」动效档（[MotionMode]）下不加任何节点。
 *
 * @param enabled false 时不加任何节点（禁用态不反馈）
 * @param scale 按下时缩到多少，见 [PressSquishDefaults]
 * @param follow 跟手偏移上限，0 = 不跟手
 * @param charge 控件有长按动作时传 true：按住约 0.15s 后形变随时间继续加深（蓄力，让人看见「按住会发生什么」），
 *   长按成立（时长与 `combinedClickable` 相同）的瞬间弹回（咔一下），结果面同帧出现；手指移出触摸范围则取消蓄力
 * @param refuse 控件外观与可点控件相同但当前不可点（[enabled] 为 false）时传 true：按下只沉一点点（硬、不 Q 弹，表达「推不动」），
 *   抬起（没有滚动）横向轻摇一下并给拒绝触觉，而不是毫无反应。[enabled] 为 true 时无影响
 */
fun Modifier.pressSquish(
    enabled: Boolean = true,
    scale: Float = PressSquishDefaults.Scale,
    follow: Dp = PressSquishDefaults.Follow,
    charge: Boolean = false,
    refuse: Boolean = false,
): Modifier = when {
    !MotionMode.enhanced -> this
    enabled -> this then PressSquishElement(scale, follow, group = false, charge = charge)
    refuse -> this then PressSquishElement(scale, 0.dp, group = false, charge = false, refuse = true)
    else -> this
}

/**
 * 组件内置的行级按压反馈：调用处传进来的修饰符里已经有行级 [pressSquish]（手写的）就不再重复加，避免同一行叠两层形变；
 * 没有就按 [enabled] / [scale] 加一个。设置类条目组件（开关 / 下拉 / 箭头行）一律用它，所以调用处不用再手写，
 * 每个控件都有按压反馈：单控件卡片整卡反馈、多控件卡片只有被按的那一行形变（见 [pressSquishGroup]）。
 */
fun Modifier.withRowPressSquish(
    enabled: Boolean = true,
    scale: Float = PressSquishDefaults.RowScale,
): Modifier = if (this.any { it is PressSquishElement && !it.group }) this else this.pressSquish(enabled = enabled, scale = scale)

/**
 * 整张卡片 / 整个分组一起按压：手指按在卡片里任何一行上，整张卡片（底色、描边、所有行）以按下点为中心收拢、
 * 松手回弹。卡片内部各行自带的 [pressSquish]（以及嵌套的分组）会自动让位给最外层的分组，不会叠加两层形变。
 */
fun Modifier.pressSquishGroup(
    enabled: Boolean = true,
    scale: Float = PressSquishDefaults.GroupScale,
    follow: Dp = PressSquishDefaults.Follow,
    charge: Boolean = false,
): Modifier = if (enabled && MotionMode.enhanced) this then PressSquishElement(scale, follow, group = true, charge = charge) else this

/**
 * 按下后先朝「浅一档」落下（深度的 [PRESS_SOFT_FRACTION]），过了这么久还没有被滚动 / 拖动接手，才压到完整深度：
 * 在列表里从卡片上起手滑动时，被滚动接手前最多只凹下一小点，随即平静收回，不会「戳一下」。
 */
private const val PRESS_COMMIT_MILLIS = 45L
private const val PRESS_SOFT_FRACTION = 0.5f

/** 蓄力从按下多久后开始（避开普通点按），到长按成立的时刻结束。 */
private const val CHARGE_START_MILLIS = 150L

/** 拒绝按压：沉下的深度占正常按压深度的比例、摇动振幅与初速换算（同 wobble 弹簧的标定）。 */
private const val REFUSE_DEPTH_FRACTION = 0.3f
private val REFUSE_SHAKE = 3.dp
private const val REFUSE_KICK = 26f

/** 蓄力在按压深度之外最多再加深多少（缩放量）。 */
private const val CHARGE_EXTRA = 0.03f
private const val MIN_CHARGE_SCALE = 0.8f
private val OUTSIDE_SLOP = 16.dp

/**
 * 扯动限位：拖出控件范围后，在跟手位移（<= Follow）之外最多再被拉出 [PULL_MAX]，总位移约 9dp、拉伸 <= 4%，
 * 小于相邻控件 / 文字之间的间距，被扯动的控件不会盖住别的文字或控件。
 */
private val PULL_MAX = 3.dp
private const val MAX_STRETCH = 0.04f

/** 整卡按压的卡片最大高度：约一行入口；多行聚合卡片（设置分组、引擎设置卡等）不整块缩放。 */
private val GROUP_MAX_HEIGHT = 112.dp
private const val TRAVERSE_KEY = "com.tyranor.next.motion.PressSquish"

private data class PressSquishElement(
    val scale: Float,
    val follow: Dp,
    val group: Boolean,
    val charge: Boolean = false,
    val refuse: Boolean = false,
) : ModifierNodeElement<PressSquishNode>() {
    override fun create(): PressSquishNode = PressSquishNode(scale, follow, group, charge, refuse)

    override fun update(node: PressSquishNode) {
        node.pressedScale = scale
        node.follow = follow
        node.group = group
        node.charge = charge
        node.refuse = refuse
    }

    /** 在布局检查器中区分整组与单组件按压，并显示实际形变参数。 */
    override fun InspectorInfo.inspectableProperties() {
        name = if (group) "pressSquishGroup" else "pressSquish"
        properties["scale"] = scale
        properties["follow"] = follow
        properties["charge"] = charge
        properties["refuse"] = refuse
    }
}

private class PressSquishNode(
    var pressedScale: Float,
    var follow: Dp,
    var group: Boolean,
    var charge: Boolean,
    var refuse: Boolean,
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
    private var chargeJob: Job? = null

    /** 本次按压已经触发过长按成立的弹回：之后抬手只平静归位，不再补按压。 */
    private var longFired = false
    private var lastShiftTarget = Offset.Zero

    /** 首次反馈时才分配缩放动画，并通过快照持有者让图层开始观察它。 */
    private fun press(): Animatable<Float, AnimationVector1D> =
        pressAnim ?: Animatable(1f).also { pressAnim = it }

    /** 首次跟手时才分配位移动画；未被交互的节点不承担动画对象开销。 */
    private fun shift(): Animatable<Offset, AnimationVector2D> =
        shiftAnim ?: Animatable(Offset.Zero, Offset.VectorConverter).also { shiftAnim = it }

    /** 常驻图层块（只存一份，放置时不再分配）：动画值在这里读取，变化只失效图层。 */
    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val k = pressAnim?.value ?: 1f
        val s = shiftAnim?.value ?: Offset.Zero
        // 朝手指那侧略微拉长：偏移越大拉得越多（最多约 2%）
        val stretchX = 1f + (abs(s.x) / width.coerceAtLeast(1f) * 0.6f).coerceAtMost(MAX_STRETCH)
        val stretchY = 1f + (abs(s.y) / height.coerceAtLeast(1f) * 0.6f).coerceAtMost(MAX_STRETCH)
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
                    /** 标记本次手势已释放，并按点击或取消结果选择回收方式。 */
                    fun finish(bouncy: Boolean) {
                        pressing = false
                        release(bouncy)
                    }
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        MotionIntent.noteTouch()
                        // 多组件聚合的大卡片不做整卡按压（整块缩放太突兀）：由卡片内被按的那一行自己反馈
                        if (group && !groupActive()) return@awaitEachGesture
                        // 外层有整卡按压时让位给卡片（嵌套的分组也让位给最外层），避免两层形变叠加
                        if (hasGroupAncestor()) return@awaitEachGesture
                        // 不可点控件的「拒绝按压」：沉一点点，抬起轻摇 + 拒绝触觉
                        if (refuse) {
                            refuseGesture(down)
                            return@awaitEachGesture
                        }
                        // 按下的同一帧就开始反馈（不再先观望）：被滚动 / 拖动接手时下面的循环会平静收回
                        pressDown(down.position)
                        startCharge(viewConfiguration.longPressTimeoutMillis)
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
                                finish(bouncy = change.isConsumed || (!group && isOutside(change.position)))
                                break
                            }
                            if (change.isConsumed && change.positionChange() != Offset.Zero) {
                                // 滚动 / 拖动接手：平静收回，不回弹
                                finish(bouncy = false)
                                break
                            }
                            // 蓄力中手指移出触摸范围：长按不会成立，取消蓄力并回到普通按压深度
                            if (chargeJob != null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                cancelCharge(retract = true)
                            }
                            if (group && isOutside(change.position)) {
                                // 整卡（单行入口卡片）拖出范围：平静收回，抬手前不再形变
                                finish(bouncy = false)
                                break
                            }
                            // 单个控件拖出范围：被橡皮筋拉向指尖（越拉越吃力），抬手时带过冲弹回（扯动）
                            followFinger(change.position)
                        }
                    } finally {
                        if (pressing && isAttached) release(bouncy = false)
                    }
                }
            },
        )
    }

    /**
     * 拒绝按压：控件当前不可点。按下以平静（临界阻尼、无过冲）的弹簧沉一点点；抬起且没有被滚动 / 拖走时，
     * 横向轻摇（欠阻尼弹簧，振幅约 3dp）并给「拒绝」触觉；被滚动接手或手指移出触摸范围则只平静收回。
     */
    private suspend fun AwaitPointerEventScope.refuseGesture(down: PointerInputChange) {
        val depth = 1f - (1f - pressedScale) * REFUSE_DEPTH_FRACTION
        pressJob?.cancel()
        pressJob = coroutineScope.launch { press().animateTo(depth, MotionTokens.releaseCalm(0.0005f)) }
        var rejected = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                // 不可点控件自己不会消费抬起；祖先（如弹窗卡片的点击吞噬）消费不算「被点了」
                rejected = true
                break
            }
            if (change.isConsumed && change.positionChange() != Offset.Zero) break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
        }
        pressJob?.cancel()
        pressJob = coroutineScope.launch { press().animateTo(1f, MotionTokens.releaseCalm(0.0005f)) }
        if (rejected) {
            val kick = REFUSE_SHAKE.value * requireDensity().density * REFUSE_KICK
            MotionHaptics.perform(MotionHaptics.Kind.Reject)
            shiftJob?.cancel()
            shiftJob = coroutineScope.launch {
                shift().animateTo(Offset.Zero, MotionTokens.wobble(Offset(0.1f, 0.1f)), initialVelocity = Offset(kick, 0f))
            }
        }
    }

    /** 手指是否已移出控件范围（四周放宽 [OUTSIDE_SLOP]）。 */
    private fun isOutside(p: Offset): Boolean {
        val m = OUTSIDE_SLOP.value * requireDensity().density
        return p.x < -m || p.y < -m || p.x > width + m || p.y > height + m
    }

    /**
     * 整卡按压是否生效：卡片高度不超过 [GROUP_MAX_HEIGHT]（约一行）**且**里面只有一个独立的可反馈控件（单控件卡片）时才整卡缩放；
     * 里面有两个及以上控件的聚合卡片让位给行级反馈——按哪个控件，只有那个控件自己形变（不再只按高度判断，两行矮行的多控件卡片也不会被误判成整卡）。
     */
    private fun groupActive(): Boolean =
        height <= GROUP_MAX_HEIGHT.value * requireDensity().density && innerRowCount() <= 1

    /** 本整卡节点内部行级反馈控件的数量。 */
    private fun innerRowCount(): Int {
        var count = 0
        traverseDescendants(TRAVERSE_KEY) { node ->
            if (node is PressSquishNode && !node.group) count++
            TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
        return count
    }

    /** 查找外层整组按压；命中后当前节点让出反馈，避免嵌套形变叠乘。 */
    private fun hasGroupAncestor(): Boolean {
        var found = false
        traverseAncestors(TRAVERSE_KEY) { node ->
            if (node is PressSquishNode && node.group && node.groupActive()) {
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

    /** 将指尖偏离中心的距离映射为有界位移；关闭跟手时返回零位移。 */
    private fun shiftTarget(finger: Offset): Offset {
        val density = requireDensity().density
        val limit = follow.value * density
        if (limit <= 0f) return Offset.Zero
        val halfW = (width / 2f).coerceAtLeast(1f)
        val halfH = (height / 2f).coerceAtLeast(1f)
        val dx = finger.x - width / 2f
        val dy = finger.y - height / 2f
        // 超出控件范围的部分：橡皮筋式拉伸（越拉越吃力，上限随控件大小，小图标拉得少）
        val pullX = min(PULL_MAX.value * density, width * 0.5f)
        val pullY = min(PULL_MAX.value * density, height * 0.5f)
        val overX = (abs(dx) - halfW).coerceAtLeast(0f)
        val overY = (abs(dy) - halfH).coerceAtLeast(0f)
        return Offset(
            limit * tanh(dx / halfW) + sign(dx) * rubber(overX, pullX),
            limit * tanh(dy / halfH) + sign(dy) * rubber(overY, pullY),
        )
    }

    /** iOS 橡皮筋公式：开始近似 1:1，越拉越吃力，渐近 [max]。 */
    private fun rubber(overflow: Float, max: Float): Float =
        if (max <= 0f) 0f else max * (1f - 1f / (overflow * 0.55f / max + 1f))

    /** 整组首次按下时固定指尖处的缩放原点，回弹未结束时沿用旧原点。 */
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

    /**
     * 从当前动画状态转向受尺寸上限约束的按下深度，并启动指尖跟随。立刻起跳（不等下一次调度）：先朝浅一档落下，
     * 过 [PRESS_COMMIT_MILLIS] 还没被滚动 / 拖动接手再压到位——两段是同一条弹簧的接力，速度不断，看上去就是一次连贯的下沉。
     */
    private fun pressDown(finger: Offset) {
        longFired = false
        originAt(finger)
        val target = effectiveScale()
        pressTarget = target
        val soft = 1f - (1f - target) * PRESS_SOFT_FRACTION
        pressJob?.cancel()
        pressJob = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val p = press()
            val spec = MotionSprings.response(0.2f, 0.58f, 0.0005f)
            // 第一段被超时取消时动画状态可能被清掉：逐帧记下速度，第二段显式接力，下沉不会在 45ms 处顿一下
            var carried = 0f
            withTimeoutOrNull(PRESS_COMMIT_MILLIS) { p.animateTo(soft, spec) { carried = velocity } }
            p.animateTo(target, spec, initialVelocity = carried)
        }
        followFinger(finger)
    }

    /**
     * 蓄力：按住 [CHARGE_START_MILLIS] 之后，缩放随时间（线性，输入直驱，不是弹簧）从按压深度继续加深 [CHARGE_EXTRA]，
     * 在长按成立的时刻弹回——手指看得见「再按一会儿会发生什么」，成立那一下有「咔」的落点。
     * 只改绘制图层；手指提前抬起 / 移出范围由 [release] / [cancelCharge] 取消，普通点按完全不出现蓄力。
     */
    private fun startCharge(longPressMillis: Long) {
        if (!charge) return
        chargeJob?.cancel()
        chargeJob = coroutineScope.launch {
            delay(CHARGE_START_MILLIS)
            val ramp = (longPressMillis - CHARGE_START_MILLIS).coerceAtLeast(60L)
            val deeper = (pressTarget - CHARGE_EXTRA).coerceAtLeast(MIN_CHARGE_SCALE)
            press().animateTo(deeper, tween(ramp.toInt(), easing = LinearEasing))
            // 长按成立：弹回（Q 弹一下），结果面此刻同帧出现
            longFired = true
            chargeJob = null
            press().animateTo(1f, MotionSprings.response(0.34f, 0.42f, 0.0005f))
        }
    }

    /** 取消蓄力；[retract] 为 true 时回到普通按压深度（手指还按着），否则交给 [release] 处理。 */
    private fun cancelCharge(retract: Boolean) {
        val running = chargeJob ?: return
        running.cancel()
        chargeJob = null
        if (retract) {
            pressJob?.cancel()
            pressJob = coroutineScope.launch {
                press().animateTo(pressTarget, MotionSprings.response(0.2f, 0.58f, 0.0005f))
            }
        }
    }

    /** 单组件用弹簧跟随指尖；整组不偏移，亚像素目标变化不重启活跃任务。 */
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

    /** 点击释放时补足按下深度再回弹；取消时平稳恢复，已有位移同步归零。 */
    private fun release(bouncy: Boolean) {
        chargeJob?.cancel()
        chargeJob = null
        pressJob?.cancel()
        pressJob = coroutineScope.launch {
            if (longFired) {
                // 长按已经成立并弹回过：抬手只平静归位，不再补按压深度
                press().animateTo(1f, MotionSprings.response(0.3f, 1f, 0.0005f))
            } else if (bouncy) {
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

    /** 复用节点重新挂载时同步归位，避免新列表项继承旧项被取消的形变。 */
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
        chargeJob = null
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
