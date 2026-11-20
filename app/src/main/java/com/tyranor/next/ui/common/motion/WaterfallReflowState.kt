package com.tyranor.next.ui.common.motion

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.staggeredgrid.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

private const val RESTORE_SLICE_NANOS = 4_000_000L
private const val RESTORE_TIMEOUT_MILLIS = 1500L

/** Rebuilds lane history with public APIs instead of anchoring an unmeasured fresh state. */
class WaterfallReflowState private constructor(
    private var columns: Int,
    private var landscape: Boolean,
    private val itemIdentity: (Any) -> Any,
    private var pendingAnchor: Anchor? = null,
) {
    val layoutColumns: Int get() = columns
    val layoutLandscape: Boolean get() = landscape

    var gridState = LazyStaggeredGridState()
        private set
    var previousItemOffsets: Map<Any, IntOffset> = emptyMap()
        private set
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

    internal fun update(columns: Int, landscape: Boolean) {
        if (this.columns == columns && this.landscape == landscape) return
        if (pendingAnchor == null) {
            previousItemOffsets = gridState.layoutInfo.visibleItemsInfo.associate { it.key to it.offset }
            pendingAnchor = capture()
        }
        this.columns = columns
        this.landscape = landscape
        gridState = LazyStaggeredGridState()
        isRestoring = pendingAnchor != null
    }

    @OptIn(ExperimentalFoundationApi::class)
    internal suspend fun restore(
        state: LazyStaggeredGridState,
        contentReady: Boolean,
        itemIndex: ((Any) -> Int?)? = null,
    ) {
        val anchor = pendingAnchor ?: return
        // Wait for layout and data before taking the scroll mutation.
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
                            RESTORE_TIMEOUT_MILLIS - scanMillis, retry, itemIndex,
                        )
                    } finally {
                        // Higher-priority mutation waits must not consume the scan budget.
                        scanMillis += SystemClock.uptimeMillis() - startedAt
                    }
                }
                break
            } catch (cancelled: CancellationException) {
                context.ensureActive()
                // Do not expose the intermediate scan position after an input mutation.
                retry = true
                // A higher-priority mutator can reserve the mutex before its scrolling flag turns on.
                // Yield a frame so that it can acquire the mutex instead of immediately spinning here.
                withFrameNanos { }
                snapshotFlow { state.isScrollInProgress }.first { !it }
            }
        }
        if (gridState === state) {
            pendingAnchor = null
            isRestoring = false
        }
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
        fun coarseRestore() {
            val currentCount = state.layoutInfo.totalItemsCount
            if (currentCount == 0) return
            val currentTarget = if (anchor.atBottom) currentCount - 1 else {
                (anchor.identity?.let { itemIndex?.invoke(it) }?.takeIf { it >= 0 } ?: anchor.index)
                    .coerceIn(0, currentCount - 1)
            }
            snapToItem(currentTarget, if (anchor.atBottom) 0 else -anchor.y)
            if (anchor.atBottom) scrollBy(state.layoutInfo.viewportSize.height.toFloat())
        }
        if (retry || state.canScrollBackward) {
            // Input or an earlier effect can leave us partway through a scan.
            snapToItem(0, 0)
        }
        var sliceStart = System.nanoTime()
        suspend fun canContinue(): Boolean {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() - sliceStart >= RESTORE_SLICE_NANOS) {
                withFrameNanos { }
                sliceStart = System.nanoTime()
            }
            return SystemClock.uptimeMillis() - startedAt < budgetMillis
        }
        // Forward measurement builds the lane history used by upward scrolling.
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
            // Preserve the measured lanes when falling back after an exhaustive identity scan.
            steps = 0L
            while (steps++ < maxSteps && canContinue()) {
                val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                if (item != null) {
                    scrollBy((item.offset.y - anchor.y).toFloat())
                    return@with
                }
                if (!state.canScrollBackward ||
                    scrollBy(-(state.layoutInfo.viewportSize.height / 2f).coerceAtLeast(1f)) >= 0f
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
                val anchor = it.pendingAnchor ?: it.capture()
                val identity = anchor?.identity?.takeIf { value -> canBeSaved(value) }
                listOf(it.columns, it.landscape, anchor?.index ?: -1, anchor?.y ?: 0,
                    anchor?.atBottom ?: false, identity != null, identity ?: "")
            },
            restore = {
                val index = it[2] as Int
                WaterfallReflowState(
                    it[0] as Int,
                    it[1] as Boolean,
                    itemIdentity,
                    if (index < 0) null else Anchor(index, if (it[5] as Boolean) it[6] else null,
                        it[3] as Int, it[4] as Boolean),
                )
            },
        )
    }
}

/**
 * [itemIdentity] maps a Lazy key to a stable, saveable anchor identity across layouts.
 * [itemIndex] resolves that identity in the latest data when the measured scan times out.
 */
@Composable
fun rememberWaterfallReflowState(
    columns: Int,
    landscape: Boolean,
    contentReady: Boolean = true,
    itemIdentity: (Any) -> Any = { it },
    enabled: Boolean = true,
    itemIndex: ((Any) -> Int?)? = null,
): WaterfallReflowState {
    val currentItemIndex by rememberUpdatedState(itemIndex)
    val state = rememberSaveable(saver = WaterfallReflowState.saver(itemIdentity)) {
        WaterfallReflowState.create(columns, landscape, itemIdentity)
    }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (enabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        Snapshot.withoutReadObservation { state.update(columns, landscape) }
        val gridState = state.gridState
        LaunchedEffect(gridState, contentReady) {
            state.restore(gridState, contentReady) { identity -> currentItemIndex?.invoke(identity) }
        }
    }
    return state
}

/** Keep the intermediate viewport out of drawing, hit actions and accessibility. */
fun Modifier.waterfallReflowHost(state: WaterfallReflowState): Modifier = composed {
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
    gate.graphicsLayer { alpha = if (state.isRestoring) 0f else 1f }
}
