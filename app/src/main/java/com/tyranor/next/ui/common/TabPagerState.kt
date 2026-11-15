package com.tyranor.next.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import com.tyranor.next.ui.common.glass.GlassBottomBarSpec
import com.tyranor.next.ui.common.glass.LensInteractionState
import com.tyranor.next.ui.common.glass.LensMotionController
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.TabPageMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GRAB_PENDING = 0
private const val GRAB_ABORT = 1
private const val GRAB_DRAG = 2

/** 长按多久唤出透镜（按在选中块以外时；按在选中块上立刻抓取）。比系统长按（400ms）略短；期间移动超过 touch slop 即视为普通滑动，原样放行。 */
private const val LENS_LONG_PRESS_MILLIS = 260L

/**
 * 与底栏「液态玻璃 · 透镜」**同一套**运动参数：赴按 / 跟手 / 吸附三段刚度统一为 1000、阻尼比 1（临界阻尼，无过冲），
 * 取法与 `EnhancedLiquidGlassNavigation` 完全一致（默认赴按 6000 → 1000、跟手 2400 → 1000）。
 */
private val LensSpec: GlassBottomBarSpec = GlassBottomBarSpec.Default.let { d ->
    d.copy(
        pressJumpStiffness = d.pressJumpStiffness / 6f,
        pressTrackingStiffness = d.pressTrackingStiffness * (1000f / 2400f),
        // 分类栏的透镜不放大：运动参数与底栏完全一致，只是按压时选中块和里面的文字都保持原大小（底栏才有放大）
        pressedScaleX = 1f,
        pressedScaleY = 1f,
    )
}

/**
 * 分类栏 + 分页内容共用的状态：
 *
 * - **透镜 [lens]**：直接复用底栏液态玻璃透镜的 [LensMotionController]（逐帧积分的弹簧 + 同一个状态机），
 *   所以分类栏的选中块与底栏的透镜**运动完全一致**：点选时带按压脉冲滑过去（边飞边变大、到位收回），
 *   在分类栏 / 标题区**长按**后先原地按压、同帧朝指尖移动（不放大），按住时连续跟手，松手吸附到最近的标签并请求选中，
 *   不做任何速度拉伸；
 * - **分页**（[pageOffset] / [pageAlpha]）：与主界面底栏切页**同一套效果**——每个分页各有一个「离开 / 进入」偏移（整宽水平位移，
 *   `MotionTokens.page` 弹簧）和一个透明度（`MotionTokens.fadeIn` 弹簧），宿主再叠 [tabPageTransform][com.tyranor.next.ui.common.motion.tabPageTransform] 景深缩放；
 *   选中变化时**只有旧页和新页**移动——从第 1 个跳到第 4 个，中间的分页不会闪过；透镜选择中分页完全不动，松手那一刻才一次性切换。
 *
 * 所有动画值都在布局 / 图层 / 绘制阶段读取（不触发重组）。
 */
@Stable
class TabPagerState(initialIndex: Int, val count: Int, scope: CoroutineScope) {
    /** 与底栏同款的透镜运动控制器：`index` 是透镜的连续位置（标签下标），`pressure` / `scaleX` / `scaleY` / `glow` 是按压形变。 */
    internal val lens = LensMotionController(
        scope = scope,
        initialIndex = initialIndex.toFloat(),
        indexRange = 0f..(count - 1).coerceAtLeast(0).toFloat(),
        spec = LensSpec,
    )

    /** 手指正按着透镜（赴按或跟手阶段）。 */
    val lensPressed: Boolean
        get() = lens.state == LensInteractionState.PressJump || lens.state == LensInteractionState.PressedTracking

    /** 手指换算出的目标标签：只用来在越过标签时给触觉反馈。 */
    internal var lensHover by mutableIntStateOf(initialIndex)

    /** 手势请求的选中项：随后的外部选中变化是它引起的，不再补一次按压脉冲（与底栏的 `pendingRequestedIndex` 相同）。 */
    internal var pendingRequested: Int? = null
    private var lastSynced: Int? = null

    // 标签栏在窗口坐标里的几何（由 SlidingTabRow 上报），用来把手指 x 换算成位置
    internal var rowLeftPx: Float = 0f
    internal var rowWidthPx: Float = 0f
    internal var spacingPx: Float = 0f

    private val tabWidthPx: Float
        get() = if (count > 0) (rowWidthPx - spacingPx * (count - 1)) / count else 0f

    /** 窗口坐标 [x] 对应的透镜位置（以该点为透镜中心，夹在首尾标签之间）。 */
    internal fun positionForWindowX(x: Float): Float {
        val w = tabWidthPx
        val pitch = w + spacingPx
        if (pitch <= 0f) return lens.index
        return (((x - rowLeftPx) - w / 2f) / pitch).coerceIn(0f, (count - 1).toFloat())
    }

    /**
     * 透镜「静下来」的程度（0..1）：离目标不到 0.15 个标签时为 1，超过 0.6 个标签为 0（平滑过渡）。
     * 透镜里文字的「选中样式」乘上它——飞速经过标签时所有字都保持常规，到位时才平滑变成选中样式。
     */
    fun lensSettle(): Float {
        val t = (1f - (abs(lens.index - lens.targetIndex) - 0.15f) / 0.45f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 透镜里标签 [index] 的「选中样式」强度（0..1，离透镜中心越近越强）：透镜里每个标签叠常规 + 加粗两份文字，
     * 按它在绘制阶段交叉淡入淡出——滑过标签时不触发任何重组 / 文字重新排版；
     * 再乘 [lensSettle]：透镜飞速经过时所有字都保持常规，静下来才平滑地变成选中样式（不会随经过快速闪粗体）。
     */
    fun lensEmphasis(index: Int): Float {
        val t = (1f - abs(lens.index - index) * 2f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t) * lensSettle()
    }

    // ---------------- 分页 ----------------

    private var shownPage = initialIndex

    /** 每页相对「当前页」的偏移：0 = 在场，-1 / +1 = 在左 / 右侧退场（不可见）。 */
    private val pageOffsets = Array(count) { Animatable(if (it < initialIndex) -1f else if (it > initialIndex) 1f else 0f) }

    /** 分页 [page] 的偏移（-1..1，弹簧过冲时略超出）。 */
    fun pageOffset(page: Int): Float = if (page in 0 until count) pageOffsets[page].value else 0f

    /** 每页的透明度：当前页 1，其余 0（与主界面切页相同，用独立的淡入淡出弹簧，不绑在位移上）。 */
    private val pageAlphas = Array(count) { Animatable(if (it == initialIndex) 1f else 0f) }

    /** 分页 [page] 的透明度（0..1）。 */
    fun pageAlpha(page: Int): Float = if (page in 0 until count) pageAlphas[page].value.coerceIn(0f, 1f) else 0f

    /**
     * 选中项变化：同步透镜（外部驱动的变化走带脉冲的 `settleAt`，与底栏一致；手势请求的那次已由 `endPress` 处理，不重复），
     * 分页**只动旧页和新页**——新页从切换方向的一侧弹入，旧页朝反方向退出，其余分页留在原处不可见。
     */
    internal suspend fun select(index: Int, enhanced: Boolean) = coroutineScope {
        val to = index.coerceIn(0, count - 1)
        val previous = lastSynced
        lastSynced = to
        val requested = pendingRequested == to
        pendingRequested = null
        when {
            previous == null -> Unit // 首次组合只记录，避免启动时一次假按压
            requested -> Unit
            else -> lens.settleAt(to.toFloat(), pulse = enhanced)
        }
        val from = shownPage
        if (from == to) return@coroutineScope
        shownPage = to
        val dir = if (to > from) 1f else -1f
        // 与主界面底栏切页完全相同的弹簧：位移 MotionTokens.page（约 0.4s、几乎不过冲），透明度 MotionTokens.fadeIn
        val pageSpec: FiniteAnimationSpec<Float> = if (enhanced) TabPageMotion.slide else snap()
        val alphaSpec: FiniteAnimationSpec<Float> = if (enhanced) TabPageMotion.fade else snap()
        val enter = pageOffsets[to]
        // 新页本来在场外：先放到切换方向那一侧；若还在淡出半途（被打断）就从当前位置接着走，不跳变
        if (abs(enter.value) >= 0.95f) enter.snapTo(dir)
        launch { enter.animateTo(0f, pageSpec) }
        launch { pageOffsets[from].animateTo(-dir, pageSpec) }
        launch { pageAlphas[to].animateTo(1f, alphaSpec) }
        launch { pageAlphas[from].animateTo(0f, alphaSpec) }
        // 其它页：被打断时可能还有一点可见，顺着它自己的方向退出场外
        for (i in 0 until count) {
            if (i == to || i == from) continue
            val o = pageOffsets[i]
            if (abs(o.value) < 0.95f) launch { o.animateTo(if (o.value < 0f) -1f else 1f, pageSpec) }
            if (pageAlphas[i].value > 0f) launch { pageAlphas[i].animateTo(0f, alphaSpec) }
        }
    }
}

/**
 * 创建并随选中项变化驱动透镜与分页的动画。选中项以 lambda 传入、在协程里用 snapshotFlow 读取：
 * 调用方的组合作用域不会因为选中项变化而重组（否则切页那一帧整个页面顶层都要重新执行）。
 */
@Composable
fun rememberTabPagerState(selectedIndex: () -> Int, count: Int): TabPagerState {
    val last = (count - 1).coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val state = remember(count, scope) {
        TabPagerState(Snapshot.withoutReadObservation(selectedIndex).coerceIn(0, last), count, scope)
    }
    LaunchedEffect(state) {
        // collectLatest：连续切换时取消上一段，动画值与速度由各 Animatable / 控制器保留，从当前状态接着走
        snapshotFlow(selectedIndex).collectLatest { state.select(it, MotionMode.enhanced) }
    }
    return state
}

/**
 * 在被修饰区域内唤出玻璃透镜，按住左右拖动选择标签，**松手**才切换（一次，只切到所选的那一页）。
 * 抓取：分类栏按下即抓取（与底栏透镜一致，滑块马上朝手指飞去，不等长按）；标题区则长按（约 0.26s）或开始横向拖动后抓取。
 * 手势与运动接线和底栏透镜一致：长按成立后先原地按压、同帧朝指尖移动（不放大）；按住期间以绝对位置连续跟手；
 * 松手吸附到最近标签并请求选中；被取消则回到当前选中项。
 * 长按未满时的点按 / 滑动原样交给子内容（点标签、纵向滑动、左缘右滑唤出侧栏都不受影响）；长按触发后接管事件。
 * 可同时挂在多个区域（标题区、分类栏），它们共用同一个 [state]。
 */
@Composable
fun Modifier.tabDragSelect(
    state: TabPagerState,
    enabled: Boolean,
    selectedIndex: () -> Int,
    onSelect: (Int) -> Unit,
    /**
     * 按下就立刻抓取（与底栏透镜完全一致：DOWN 同帧起跳，滑块马上朝手指飞去，不等长按）。分类栏传 true；
     * 标题区等没有标签可按的区域传 false，长按或开始横向拖动才抓取。
     */
    grabImmediately: Boolean = false,
): Modifier {
    val haptic = LocalHapticFeedback.current
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selectedIndex)
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(state, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                var finger = down.position
                // 1) 抓取时机：分类栏按下即抓取（与底栏透镜一致）；
                //    不立刻抓取的区域 → 长按成立、或开始横向拖动，才抓取。中途抬起 / 纵向滑动 / 被别处消费（滚动等）都视为普通手势，放行
                var byLongPress = false
                if (!grabImmediately) {
                    val verdict: Int? = withTimeoutOrNull(LENS_LONG_PRESS_MILLIS) {
                        var result = GRAB_PENDING
                        while (result == GRAB_PENDING) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.isConsumed) {
                                result = GRAB_ABORT
                            } else {
                                finger = change.position
                                val d = change.position - down.position
                                if (d.getDistance() > slop) {
                                    result = if (abs(d.x) > abs(d.y)) GRAB_DRAG else GRAB_ABORT
                                }
                            }
                        }
                        result
                    }
                    if (verdict == GRAB_ABORT) return@awaitEachGesture
                    byLongPress = verdict == null
                }

                // 2) 抓取成立：与底栏透镜一致——先原地按压，再同帧设置移动目标（边移动，不等到位）
                SidePanelGestureExclusions.suppressed = true
                haptic.performHapticFeedback(if (byLongPress) HapticFeedbackType.LongPress else HapticFeedbackType.SegmentTick)
                var target = state.positionForWindowX(origin.x + finger.x)
                state.lensHover = target.roundToInt().coerceIn(0, state.count - 1)
                state.lens.beginPressAt(state.lens.index)
                state.lens.updatePressTarget(target)

                // 3) 按住拖动（绝对位置，不累加位移）→ 松手
                var committed = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        target = state.positionForWindowX(origin.x + change.position.x)
                        state.lens.updatePressTarget(target)
                        val hover = target.roundToInt().coerceIn(0, state.count - 1)
                        if (hover != state.lensHover) {
                            state.lensHover = hover
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        }
                        if (!change.pressed) {
                            committed = true
                            break
                        }
                    }
                } finally {
                    SidePanelGestureExclusions.suppressed = false
                    val selected = currentSelected()
                    if (committed) {
                        // 松手：吸附到最近标签（不再补膨胀），若与当前选中项不同则请求一次选中——页面只在此刻切换一次
                        val index = target.roundToInt().coerceIn(0, state.count - 1)
                        state.lens.endPress(index, pulse = false)
                        if (index != selected) {
                            state.pendingRequested = index
                            currentOnSelect(index)
                        }
                    } else {
                        // 被取消：不提交，回到当前选中项
                        state.lens.cancelPress(selected.toFloat())
                    }
                }
            }
        }
}
