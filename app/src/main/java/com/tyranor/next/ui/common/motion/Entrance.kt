package com.tyranor.next.ui.common.motion

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 一屏内容的「入场」节拍：[replay] 之后的一小段时间（[WINDOW_MILLIS]）内首次组合的元素依次弹入；
 * 窗口之外才组合的元素（例如之后滚动进来的懒列表项）直接显示，不会边滚边播放。
 */
@Stable
class EntranceState internal constructor() {
    /**
     * 本轮入场的代数。不是 Compose 状态：[replay] 直接通知已挂载的节点（见 [attach]），
     * 不再靠「组合里读它 → 整屏重组 → 重启 LaunchedEffect」来传递。
     */
    internal var generation: Int = 0
        private set
    internal var startedAt: Long = 0L
        private set

    /** 只属于这份入场节拍的跳过信号；其他页面或浮层的拖动不会影响本轮。 */
    private var skipCounter by mutableIntStateOf(0)

    /** 本轮入场开始时的「跳过」计数：用户一拖动（[skip]），本轮未完成的入场立即收尾。 */
    internal var skipMark: Int = 0
        private set

    /** 本轮是否为「快速」入场（切回已看过的标签页）：错开更短，内容不会在页面滑入时空着。 */
    internal var quick: Boolean = false
        private set

    private var slotGeneration = -1
    private var nextSlot = 0

    /**
     * 本轮入场的屏幕次序：按元素启动入场的先后发放（与组合顺序一致，即屏幕上从上到下）。
     * 不能只用调用方传的列表下标：列表滚到中下部时，屏幕上第一行的下标已经很大，
     * 按下标算延迟会让整屏内容一起空等到封顶延迟。
     */
    internal fun takeSlot(forGeneration: Int): Int {
        if (slotGeneration != forGeneration) {
            slotGeneration = forGeneration
            nextSlot = 0
        }
        return nextSlot++
    }

    /** 已挂载的 [staggeredEntrance] 节点：挂载时加入、卸载时移除（只在主线程访问）。 */
    private val nodes = ArrayList<StaggeredEntranceNode>()

    /** 登记已挂载节点，使下一次 [replay] 能直接通知它；与 [detach] 成对调用。 */
    internal fun attach(node: StaggeredEntranceNode) {
        nodes.add(node)
    }

    /** 移除节点最后一次登记；重复卸载或已被移除时保持无操作。 */
    internal fun detach(node: StaggeredEntranceNode) {
        val i = nodes.lastIndexOf(node)
        if (i >= 0) nodes.removeAt(i)
    }

    /**
     * 重新播放一次入场（例如主界面切回该标签页时）。
     *
     * @param quick 快速入场：错开更短、封顶更低
     */
    fun replay(quick: Boolean = false) {
        startedAt = SystemClock.uptimeMillis()
        skipMark = skipCounter
        this.quick = quick
        generation++
        // 已挂载的元素按挂载顺序（= 组合顺序）依次领号并（重新）开始入场；不再触发任何重组
        var i = 0
        while (i < nodes.size) {
            nodes[i].onReplay(this)
            i++
        }
    }

    private var nextOrder = 0

    /** 按首次组合顺序发放入场次序（同一屏内唯一递增，见 [rememberEntranceOrder]）。 */
    internal fun takeOrder(): Int = nextOrder++

    /** 入场窗口内且用户还没开始拖动：此时新组合的元素才参与入场。 */
    internal fun isFresh(): Boolean =
        generation > 0 && SystemClock.uptimeMillis() - startedAt < WINDOW_MILLIS && !isSkipped()

    /** 判断本轮开始后是否收到过拖动跳过信号，供动画协程观察。 */
    internal fun isSkipped(): Boolean = skipCounter != skipMark

    /** 只让本作用域中未完成的入场快速收尾，之后新出现的元素直接显示。 */
    fun skip() {
        skipCounter++
    }

    internal companion object {
        const val WINDOW_MILLIS = 500L
    }
}

/**
 * 放在 [ProvideEntrance] 内的页面根部：观察（不消费）拖动，超过触摸阈值就让当前 [state] 收尾。
 * 不影响其他页面或浮层；没有提供者或「普通」动效档时不加任何节点。
 */
@Composable
fun Modifier.skipEntranceOnDrag(state: EntranceState? = LocalEntranceState.current): Modifier =
    if (!MotionMode.enhanced || state == null) this else pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var total = Offset.Zero
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                total += change.positionChange()
                if (total.getDistance() > viewConfiguration.touchSlop) {
                    state.skip()
                    break
                }
            }
        }
    }

private val LocalEntranceState = staticCompositionLocalOf<EntranceState?> { null }

/** 创建并记住入场节拍。[playOnStart] = true 时首帧即开始一次入场（独立页面）。 */
@Composable
fun rememberEntranceState(playOnStart: Boolean = true): EntranceState =
    remember { EntranceState().also { if (playOnStart) it.replay() } }

/**
 * 按首次组合顺序取一个入场次序：适合由同一个组件渲染、调用方不方便逐个传下标的卡片（如设置分组卡）。
 * 没有入场节拍提供者时返回 0。
 */
@Composable
fun rememberEntranceOrder(): Int {
    val state = LocalEntranceState.current
    return remember(state) { state?.takeOrder() ?: 0 }
}

/** 给子树提供入场节拍；子树里的 [staggeredEntrance] 才会生效（没有提供者时完全无动画）。 */
@Composable
fun ProvideEntrance(state: EntranceState = rememberEntranceState(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEntranceState provides state, content = content)
}

/**
 * 依次弹入：从透明、下方 [distance]、略小处带回弹地升到原位。只改绘制图层，停稳后与原样完全一致。
 *
 * 实现是修饰符节点（不再是 `composed`）：不播放的元素（入场已过 / 「普通」档 / 已被拖动跳过）
 * 不开协程、不建动画、不挂图层；播放的元素在挂载时同步决定，首帧即按隐藏绘制。
 *
 * @param index 在本屏中的次序（0 起），与屏幕次序取较小者决定延迟；超过 10 的按 10 算，避免长列表最后一项等太久
 */
fun Modifier.staggeredEntrance(
    index: Int,
    distance: Dp = 22.dp,
    stepMillis: Long = 30L,
): Modifier = this then StaggeredEntranceElement(index, distance, stepMillis)

private class StaggeredEntranceElement(
    val index: Int,
    val distance: Dp,
    val stepMillis: Long,
) : ModifierNodeElement<StaggeredEntranceNode>() {
    override fun create(): StaggeredEntranceNode = StaggeredEntranceNode(index, distance, stepMillis)

    override fun update(node: StaggeredEntranceNode) {
        node.index = index
        node.stepMillis = stepMillis
        // 下一个动画帧图层块重跑时即读到新值；静止时 distance 不参与绘制
        node.distance = distance
    }

    /** 按完整入场参数比较，让参数未变的修饰符继续复用现有节点。 */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StaggeredEntranceElement) return false
        return index == other.index && distance == other.distance && stepMillis == other.stepMillis
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + distance.hashCode()
        result = 31 * result + stepMillis.hashCode()
        return result
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "staggeredEntrance"
        properties["index"] = index
        properties["distance"] = distance
        properties["stepMillis"] = stepMillis
    }
}

/** 一次入场用到的动画与「是否隐藏」标记；只有真正要播放的节点才会创建。 */
private class EntranceAnim {
    val progress = Animatable(0f)
    val alpha = Animatable(0f)

    /** [MODE_HIDDEN] 按隐藏画（等价旧版的 armed）；[MODE_RUN] 读动画值；[MODE_DONE] 不再有任何形变。 */
    var mode by mutableIntStateOf(MODE_HIDDEN)

    /** 每次（重新）开始 / 终止都自增：过期协程的收尾不得再碰这份动画。 */
    var token = 0
}

internal class StaggeredEntranceNode(
    var index: Int,
    var distance: Dp,
    var stepMillis: Long,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode {

    private var state: EntranceState? = null
    private var anim by mutableStateOf<EntranceAnim?>(null)
    private var playJob: Job? = null

    /** 一旦播放过就一直走图层（避免动画结束时图层抖动）；从未播放过则不挂图层。 */
    private var layerActive = false

    /** 只存一份的图层块：动画值在块里读取，变化只失效图层，放置时不分配。 */
    private val layerBlock: GraphicsLayerScope.() -> Unit = block@{
        val an = anim ?: return@block
        val p: Float
        val a: Float
        when (an.mode) {
            MODE_HIDDEN -> {
                p = 0f
                a = 0f
            }
            MODE_RUN -> {
                p = an.progress.value
                a = an.alpha.value
            }
            else -> return@block
        }
        if (p != 1f || a != 1f) {
            this.alpha = a.coerceIn(0f, 1f)
            translationY = (1f - p) * distance.toPx()
            val s = 0.96f + 0.04f * p
            scaleX = s
            scaleY = s
            transformOrigin = ENTRANCE_ORIGIN
        }
    }

    /** 挂载时订阅最近的入场作用域，并在有效窗口内准备首帧隐藏态。 */
    override fun onAttach() {
        observeEntranceState(relayout = false)
    }

    /** 提供者替换后重新绑定作用域，必要时请求放置以启用图层。 */
    override fun onObservedReadsChanged() {
        observeEntranceState(relayout = true)
    }

    /** 切换作用域时撤销旧动画与登记；只有增强档且新节拍仍有效时才播放。 */
    private fun observeEntranceState(relayout: Boolean) {
        var next: EntranceState? = null
        observeReads { next = currentValueOf(LocalEntranceState) }
        val st = next
        if (st === state) return
        state?.detach(this)
        playJob?.cancel()
        playJob = null
        anim?.let {
            it.token++
            it.mode = MODE_DONE
        }
        state = st
        if (st == null) return
        st.attach(this)
        // 与旧版「组合时 isFresh」同一时刻决定：入场窗口内、未被拖动跳过、「增强」档才播放
        if (MotionMode.enhanced && st.isFresh()) beginPlay(st, relayout)
    }

    /** 卸载时解除节拍登记并丢弃动画，避免懒列表复用继承半透明状态。 */
    override fun onDetach() {
        state?.detach(this)
        state = null
        playJob?.cancel()
        playJob = null
        // 卸载 = 旧版整个组合被销毁：丢掉动画；重新挂载（懒列表复用）按新元素重新决定，不会停在半透明
        anim = null
        layerActive = false
    }

    /** 懒列表回收节点时作废当前动画，让旧内容立即恢复完整显示。 */
    override fun onReset() {
        // 懒列表回收：中途的入场立刻作废，内容以原样显示
        playJob?.cancel()
        playJob = null
        anim?.let {
            it.token++
            it.mode = MODE_DONE
        }
    }

    /** [EntranceState.replay]：已挂载的元素重新（或首次）播放。 */
    fun onReplay(st: EntranceState) {
        if (!MotionMode.enhanced) return
        beginPlay(st, relayout = true)
    }

    /** 为本轮领取屏幕顺序并替换旧任务；协程启动前先隐藏，防止首帧闪现。 */
    private fun beginPlay(st: EntranceState, relayout: Boolean) {
        val an = anim ?: EntranceAnim().also { anim = it }
        an.token++
        val token = an.token
        // 立刻按隐藏画：协程还没来得及运行的那几帧也不会先以原样闪一下
        an.mode = MODE_HIDDEN
        val generation = st.generation
        // 屏幕次序：按元素启动入场的先后发放（与组合 / 挂载顺序一致）
        val slot = st.takeSlot(generation)
        val newLayer = !layerActive
        layerActive = true
        playJob?.cancel()
        playJob = coroutineScope.launch {
            // 协程开跑时才读 index / stepMillis：懒列表复用时节点先于元素更新挂载，此刻读到的才是新元素的值
            val quick = st.quick
            val order = minOf(index, slot).coerceIn(0, if (quick) QUICK_MAX_ORDER else MAX_ORDER)
            val step = if (quick) minOf(stepMillis, QUICK_STEP_MILLIS) else stepMillis
            play(st, an, generation, token, order * step)
        }
        // 挂载时不用手动失效：节点随布局节点一起挂载，首次测量 / 放置本来就会发生
        if (newLayer && relayout) invalidatePlacement()
    }

    /** 播放错峰入场并响应拖动跳过；取消时只收尾仍属于本轮的动画值。 */
    private suspend fun play(st: EntranceState, an: EntranceAnim, generation: Int, token: Int, delayMillis: Long) {
        val progress = an.progress
        val alpha = an.alpha
        if (!st.isFresh()) {
            // 协程真正开跑前窗口已过 / 用户已拖动：直接收尾到原样
            if (progress.value != 1f) progress.snapTo(1f)
            if (alpha.value != 1f) alpha.snapTo(1f)
            if (an.token == token) an.mode = MODE_RUN
            return
        }
        progress.snapTo(0f)
        alpha.snapTo(0f)
        if (an.token == token) an.mode = MODE_RUN
        try {
            coroutineScope {
                val play = launch {
                    delay(delayMillis)
                    launch { alpha.animateTo(1f, MotionSprings.effects(300)) }
                    progress.animateTo(1f, MotionSprings.spring(460, 0.28f, 0.0005f))
                }
                // 用户开始拖动：未完成的入场立即快速收尾（约 150ms），不让内容在滚动时空着
                val watcher = launch {
                    snapshotFlow { st.isSkipped() }.first { it }
                    play.cancel()
                    launch { alpha.animateTo(1f, MotionSprings.effects(140)) }
                    progress.animateTo(1f, MotionSprings.smooth(180, 0.0005f))
                }
                play.join()
                // 正常播完才停止监听；被拖动打断时让快速收尾跑完
                if (!play.isCancelled) watcher.cancel()
            }
        } finally {
            // 兜底：无论以何种方式结束（包括被取消），都不会停在半透明的中间态。
            // 但若是被新一轮 replay 取代，新一轮已经接管了这两个值，此时收尾会把新一轮的动画顶掉，必须跳过
            if (st.generation == generation && an.token == token) {
                withContext(NonCancellable) {
                    if (alpha.value != 1f) alpha.snapTo(1f)
                    if (progress.value != 1f) progress.snapTo(1f)
                }
            }
        }
    }

    /** 保留内容原始测量尺寸，仅为曾经播放过入场的节点附加绘制图层。 */
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            if (layerActive) {
                placeable.placeWithLayer(0, 0, layerBlock = layerBlock)
            } else {
                placeable.place(0, 0)
            }
        }
    }
}

private const val MODE_HIDDEN = 0
private const val MODE_RUN = 1
private const val MODE_DONE = 2
private val ENTRANCE_ORIGIN = TransformOrigin(0.5f, 0f)

private const val MAX_ORDER = 10
private const val QUICK_MAX_ORDER = 6
private const val QUICK_STEP_MILLIS = 18L
