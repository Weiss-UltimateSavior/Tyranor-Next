package com.tyranor.next.ui.common.motion

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.staggeredgrid.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

private const val RESTORE_SLICE_NANOS = 4_000_000L
private const val RESTORE_TIMEOUT_MILLIS = 1500L

// 保存态落回网格列数时的合法范围直接取设置侧常量，避免两处各写一份数字后漂移。
// 0 会流向 StaggeredGridCells.Fixed 与列宽整除，越界值只可能来自被篡改或跨版本的载荷。
// 坏载荷没有可信方向，回落按竖屏默认列数，交由下一次真实布局立即改写。
private const val MIN_SAVED_COLUMNS = AppSettingsStore.MIN_GAME_CARD_COLUMNS
private const val MAX_SAVED_COLUMNS = AppSettingsStore.MAX_GAME_CARD_COLUMNS
private const val DEFAULT_SAVED_COLUMNS = AppSettingsStore.DEFAULT_GAME_WATERFALL_PORTRAIT_COLUMNS

// 锚点纵向偏移来自懒布局的真实滚动量，正常不会超过若干屏高；
// 限幅只为挡住 Int.MIN_VALUE 之类的坏载荷在取负后仍然为负。
private const val MAX_SAVED_OFFSET_Y = 1 shl 20

/** 从头测量以重建列历史，避免直接把未测量的新状态跳到锚点。 */
class WaterfallReflowState private constructor(
    columns: Int,
    landscape: Boolean,
    private val itemIdentity: (Any) -> Any,
    private var pendingAnchor: Anchor? = null,
) {
    // 列数、方向和懒布局状态必须原子发布，隐藏页恢复后调用方才能读到同一代布局。
    private data class Layout(
        val columns: Int,
        val landscape: Boolean,
        val gridState: LazyStaggeredGridState = LazyStaggeredGridState(),
    )
    private var layout by mutableStateOf(Layout(columns, landscape))
    val layoutColumns: Int get() = layout.columns
    val layoutLandscape: Boolean get() = layout.landscape
    val gridState: LazyStaggeredGridState get() = layout.gridState
    var previousItemOffsets: Map<Any, IntOffset> = emptyMap()
        private set
    // 进程或宿主重建后同样必须重建列历史，期间由宿主隐藏中间位置并拦截交互。
    var isRestoring by mutableStateOf(pendingAnchor != null)
        private set

    private data class Anchor(val index: Int, val identity: Any?, val y: Int, val atBottom: Boolean)

    private fun capture(): Anchor? {
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == gridState.firstVisibleItemIndex }
            ?: visible.minByOrNull { it.index }
            ?: return null
        return Anchor(
            item.index,
            itemIdentity(item.key),
            item.offset.y,
            !gridState.canScrollForward && gridState.canScrollBackward,
        )
    }

    private var inactiveAnchor: Anchor? = null
    private var inactiveOffsets: Map<Any, IntOffset> = emptyMap()

    internal fun preserveInactiveAnchor() {
        if (inactiveAnchor != null || pendingAnchor != null) return
        inactiveAnchor = capture()
        inactiveOffsets = gridState.layoutInfo.visibleItemsInfo.associate { it.key to it.offset }
    }

    internal fun update(columns: Int, landscape: Boolean) {
        if (layoutColumns == columns && layoutLandscape == landscape) {
            inactiveAnchor = null
            inactiveOffsets = emptyMap()
            return
        }
        if (pendingAnchor == null) {
            previousItemOffsets = if (inactiveAnchor != null) inactiveOffsets else
                gridState.layoutInfo.visibleItemsInfo.associate { it.key to it.offset }
            pendingAnchor = inactiveAnchor ?: capture()
        }
        inactiveAnchor = null
        inactiveOffsets = emptyMap()
        layout = Layout(columns, landscape)
        isRestoring = pendingAnchor != null
    }

    @OptIn(ExperimentalFoundationApi::class)
    internal suspend fun restore(
        state: LazyStaggeredGridState,
        contentReady: Boolean,
        scanBudgetMillis: Long = RESTORE_TIMEOUT_MILLIS,
        itemIndex: ((Any) -> Int?)? = null,
    ) {
        val anchor = pendingAnchor ?: return
        try {
            // 等待视口高度与数据就绪后再获取滚动修改权。
            snapshotFlow {
                state.layoutInfo.viewportSize.height to
                    (state.layoutInfo.totalItemsCount > 0 || contentReady)
            }.first { it.first > 0 && it.second }
            val context = currentCoroutineContext()
            var scanMillis = 0L
            var retry = false
            while (true) {
                try {
                    state.scroll(MutatePriority.Default) {
                        val startedAt = SystemClock.uptimeMillis()
                        try {
                            restoreAnchor(
                                state, anchor, LazyLayoutScrollScope(state, this),
                                scanBudgetMillis - scanMillis, retry, itemIndex,
                            )
                        } finally {
                            // 等待更高优先级滚动期间不消耗扫描预算。
                            scanMillis += SystemClock.uptimeMillis() - startedAt
                        }
                    }
                    break
                } catch (cancelled: CancellationException) {
                    context.ensureActive()
                    // 输入打断后仍隐藏中间扫描位置。
                    retry = true
                    // 更高优先级的修改者可能先预约互斥锁，稍后才开启滚动标记。
                    // 让出一帧供其取得互斥锁，避免此处立即重试而空转。
                    withFrameNanos { }
                    snapshotFlow { state.isScrollInProgress }.first { !it }
                }
            }
            finishRestore(state)
        } catch (error: Throwable) {
            // 取消（自身作用域销毁或被更高优先级滚动打断后未恢复）保留锚点与隐藏状态，
            // 由 enabled()/生命周期重新进入本流程时接续；其它异常必须整体收尾：
            // 否则宿主会永久透明、吞掉触摸并退出无障碍树，且组合键不变时不会重启恢复。
            // 这里用 finishRestore 而不是只清 isRestoring，避免留下「有锚点却未在恢复」的
            // 不一致状态，让下一次换布局跳过重新捕获、拿旧列宽的位置去滚动。
            if (error is CancellationException) throw error
            // 非取消异常在生产路径上未知，但一旦发生必须留下痕迹：
            // 这条路径会把整表的遮蔽、吞输入与无障碍移除一并收尾，静默的话无从定位。
            android.util.Log.w(
                "WaterfallReflow",
                "Restore aborted before reaching the anchor; layout is released without it",
                error,
            )
            finishRestore(state)
            // Error 同样会逃出本协程，收尾后原样重抛，既不压掉崩溃报告也不留永久遮蔽。
            if (error is Error) throw error
        }
    }

    private fun finishRestore(state: LazyStaggeredGridState) {
        if (gridState !== state) return
        pendingAnchor = null
        isRestoring = false
    }

    @OptIn(ExperimentalFoundationApi::class)
    private suspend fun restoreAnchor(
        state: LazyStaggeredGridState,
        anchor: Anchor,
        scope: androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope,
        budgetMillis: Long,
        retry: Boolean,
        itemIndex: ((Any) -> Int?)?,
    ) = with(scope) {
        val startedAt = SystemClock.uptimeMillis()
        val count = state.layoutInfo.totalItemsCount
        if (count == 0) return@with
        val target = if (anchor.atBottom) count - 1 else anchor.index.coerceIn(0, count - 1)
        var sliceStart = System.nanoTime()
        suspend fun canContinue(): Boolean {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() - sliceStart >= RESTORE_SLICE_NANOS) {
                withFrameNanos { }
                sliceStart = System.nanoTime()
            }
            return SystemClock.uptimeMillis() - startedAt < budgetMillis
        }
        suspend fun coarseRestore() {
            val currentCount = state.layoutInfo.totalItemsCount
            if (currentCount == 0) return
            val currentTarget = if (anchor.atBottom) currentCount - 1 else {
                (anchor.identity?.let { itemIndex?.invoke(it) }?.takeIf { it >= 0 } ?: anchor.index)
                    .coerceIn(0, currentCount - 1)
            }
            snapToItem(currentTarget, if (anchor.atBottom) 0 else -anchor.y)
            // Foundation 1.11.2 会在尾部不足一屏时同步回填；高于视口的尾卡仍可能继续滚动。
            // 只按实际可滚距离补齐，不能假定再滚一屏就已到底。
            // 这段补齐故意不受扫描预算限制：预算耗尽后仍要真正落到尾部，否则超时兜底会停在
            // 半途的中间位置（WaterfallRotationScrollDeviceTest 的超时用例守着这一点）；
            // 它的代价由三处约束兜住——每 4ms 让出一帧、每步检查取消、补齐步数按列表规模设上限。
            var coarseSteps = 0L
            val coarseMaxSteps = currentCount.toLong() * 4L + 1024L
            while (anchor.atBottom && state.canScrollForward && coarseSteps++ < coarseMaxSteps) {
                currentCoroutineContext().ensureActive()
                val consumed = scrollBy(state.layoutInfo.viewportSize.height.toFloat().coerceAtLeast(1f))
                if (!consumed.isFinite() || consumed <= 0f) break
                if (System.nanoTime() - sliceStart >= RESTORE_SLICE_NANOS) {
                    withFrameNanos { }
                    sliceStart = System.nanoTime()
                }
            }
        }
        if (retry || state.canScrollBackward) {
            // 输入或上一代效果可能把状态留在扫描中途。
            snapToItem(0, 0)
        }
        // 向前测量重建回滚所需的列历史；保存的锚点只有位置，没有每项的列归属。
        val maxSteps = count.toLong() * 4L + 1024L
        var steps = 0L
        while (steps++ < maxSteps) {
            if (!canContinue()) {
                coarseRestore()
                return@with
            }
            val before = state.layoutInfo
            if (anchor.atBottom) {
                if (!state.canScrollForward) return@with
            } else {
                val item = before.visibleItemsInfo.firstOrNull {
                    if (anchor.identity == null) it.index == target else itemIdentity(it.key) == anchor.identity
                }
                if (item != null) {
                    scrollBy((item.offset.y - anchor.y).toFloat())
                    return@with
                }
            }
            if (!state.canScrollForward) break
            val consumed = scrollBy((before.viewportSize.height / 2f).coerceAtLeast(1f))
            if (!consumed.isFinite() || consumed <= 0f) break
        }
        if (!anchor.atBottom && anchor.identity != null) {
            // 让出帧期间数据可能把目标标识移动到向前扫描游标的后方。
            steps = 0L
            while (steps++ < maxSteps && canContinue()) {
                val item = state.layoutInfo.visibleItemsInfo.firstOrNull { itemIdentity(it.key) == anchor.identity }
                if (item != null) {
                    scrollBy((item.offset.y - anchor.y).toFloat())
                    return@with
                }
                if (!state.canScrollBackward ||
                    scrollBy(-(state.layoutInfo.viewportSize.height / 2f).coerceAtLeast(1f)) >= 0f
                ) break
            }
            // 后向扫描期间，目标也可能重新出现在已扫过的一侧。
            steps = 0L
            while (steps++ < maxSteps && canContinue()) {
                val visible = state.layoutInfo.visibleItemsInfo
                val sameItem = visible.firstOrNull { itemIdentity(it.key) == anchor.identity }
                val oldIndexItem = visible.firstOrNull { it.index == target }
                val item = sameItem ?: oldIndexItem?.takeIf {
                    // 只有即将按索引完成时才查询最新数据，避免每步线性查找。
                    itemIndex?.invoke(anchor.identity)?.let { it >= 0 } != true
                }
                if (item != null) {
                    scrollBy((item.offset.y - anchor.y).toFloat())
                    return@with
                }
                if (!state.canScrollForward ||
                    scrollBy((state.layoutInfo.viewportSize.height / 2f).coerceAtLeast(1f)) <= 0f
                ) break
            }
        }
        coarseRestore()
    }

    companion object {
        internal fun create(columns: Int, landscape: Boolean, itemIdentity: (Any) -> Any = { it }) =
            WaterfallReflowState(columns, landscape, itemIdentity)

        internal fun saver(itemIdentity: (Any) -> Any) = listSaver<WaterfallReflowState, Any>(
            save = {
                val anchor = it.pendingAnchor ?: it.inactiveAnchor ?: it.capture()
                val identity = anchor?.identity?.takeIf { value -> canBeSaved(value) }
                listOf(it.layoutColumns, it.layoutLandscape, anchor?.index ?: -1, anchor?.y ?: 0,
                    anchor?.atBottom ?: false, identity != null, identity ?: "")
            },
            // 坏载荷或跨版本格式不符时回落到新状态：Compose 不包裹 saver.restore，
            // 直接强转会把崩溃抛进恢复阶段。
            restore = {
                val values = it
                val restored = if (values.size == 7) runCatching {
                    val columns = (values[0] as Int).coerceIn(MIN_SAVED_COLUMNS, MAX_SAVED_COLUMNS)
                    val landscape = values[1] as Boolean
                    val index = values[2] as Int
                    val offsetY = (values[3] as Int).coerceIn(-MAX_SAVED_OFFSET_Y, MAX_SAVED_OFFSET_Y)
                    val anchor = if (index < 0) null else Anchor(
                        index,
                        if (values[5] as Boolean) values[6] else null,
                        offsetY,
                        values[4] as Boolean,
                    )
                    WaterfallReflowState(columns, landscape, itemIdentity, anchor)
                }.getOrNull() else null
                restored ?: WaterfallReflowState(DEFAULT_SAVED_COLUMNS, false, itemIdentity)
            },
        )
    }
}

/**
 * [itemIdentity] 把懒布局键映射到跨布局稳定、可保存的锚点标识。
 * [itemIndex] 在测量扫描超时时从最新数据解析该标识的位置。
 * [enabled] 在独立组合范围内读取，使显隐变化不订阅持有本状态的整个页面。
 */
@Composable
fun rememberWaterfallReflowState(
    columns: Int,
    landscape: Boolean,
    contentReady: Boolean = true,
    itemIdentity: (Any) -> Any = { it },
    enabled: () -> Boolean = { true },
    itemIndex: ((Any) -> Int?)? = null,
): WaterfallReflowState {
    val state = rememberSaveable(saver = WaterfallReflowState.saver(itemIdentity)) {
        WaterfallReflowState.create(columns, landscape, itemIdentity)
    }
    UpdateWaterfallReflow(state, columns, landscape, contentReady, enabled, itemIndex)
    return state
}

@Composable
private fun UpdateWaterfallReflow(
    state: WaterfallReflowState,
    columns: Int,
    landscape: Boolean,
    contentReady: Boolean,
    enabled: () -> Boolean,
    itemIndex: ((Any) -> Int?)?,
) {
    val currentItemIndex by rememberUpdatedState(itemIndex)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (enabled() && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        // 同步发布布局，调用方在本轮组合就能读取匹配的列数、方向和状态；不订阅更新时的内部读。
        Snapshot.withoutReadObservation { state.update(columns, landscape) }
        val gridState = state.gridState
        LaunchedEffect(gridState, contentReady) {
            state.restore(gridState, contentReady) { identity -> currentItemIndex?.invoke(identity) }
        }
    } else {
        Snapshot.withoutReadObservation { state.preserveInactiveAnchor() }
    }
}

/** 恢复期间隔离中间视口的绘制、焦点、触摸与无障碍操作。 */
fun Modifier.waterfallReflowHost(state: WaterfallReflowState): Modifier = composed {
    var hostHasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    if (state.isRestoring && hostHasFocus) {
        SideEffect { focusManager.clearFocus(force = true) }
    }
    val gate = if (state.isRestoring) {
        Modifier.clearAndSetSemantics { hideFromAccessibility() }
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
    } else Modifier
    Modifier.onFocusChanged { hostHasFocus = it.hasFocus }
        .focusProperties { onEnter = { if (state.isRestoring) cancelFocusChange() } }
        .focusGroup()
        .onPreviewKeyEvent { state.isRestoring }
        .then(gate)
        .graphicsLayer { alpha = if (state.isRestoring) 0f else 1f }
}
