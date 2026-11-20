package com.tyranor.next.ui.common.motion

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.staggeredgrid.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

private const val RESTORE_SLICE_NANOS = 4_000_000L

/** Rebuilds lane history with public APIs instead of anchoring an unmeasured fresh state. */
class WaterfallReflowState private constructor(
    private var columns: Int,
    private var landscape: Boolean,
    private val itemIdentity: (Any) -> Any,
    private var pendingAnchor: Anchor? = null,
) {
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
    internal suspend fun restore(state: LazyStaggeredGridState, contentReady: Boolean) {
        val anchor = pendingAnchor ?: return
        // Wait for layout and data before taking the scroll mutation.
        snapshotFlow {
            state.layoutInfo.viewportSize.height to
                (state.layoutInfo.totalItemsCount > 0 || contentReady)
        }.first { it.first > 0 && it.second }
        val context = currentCoroutineContext()
        try {
            state.scroll {
                val scope = LazyLayoutScrollScope(state, this)
                with(scope) {
                    val count = state.layoutInfo.totalItemsCount
                    if (count == 0) return@with
                    val target = anchor.index.coerceIn(0, count - 1)
                    // Foundation 1.11.2 needs forward measurement history for stable upward lanes.
                    // Pause between measured steps without releasing the scroll mutation.
                    var sliceStart = System.nanoTime()
                    suspend fun awaitBudget() {
                        if (System.nanoTime() - sliceStart >= RESTORE_SLICE_NANOS) {
                            withFrameNanos { }
                            sliceStart = System.nanoTime()
                        }
                    }
                    val maxSteps = count.toLong() * 4L + 1024L
                    var steps = 0L
                    var anchorFound = false
                    while (steps++ < maxSteps) {
                        context.ensureActive()
                        awaitBudget()
                        val before = state.layoutInfo
                        val visible = before.visibleItemsInfo
                        if (anchor.atBottom) {
                            if (!state.canScrollForward) break
                        } else {
                            val item = if (anchor.identity != null) {
                                visible.firstOrNull { itemIdentity(it.key) == anchor.identity }
                            } else {
                                visible.firstOrNull { it.index == target }
                            }
                            if (item != null) {
                                anchorFound = true
                                scrollBy((item.offset.y - anchor.y).toFloat())
                                break
                            }
                        }
                        if (!state.canScrollForward) break
                        val lastIndex = visible.maxOfOrNull { it.index } ?: break
                        val firstIndex = state.firstVisibleItemIndex
                        val firstOffset = state.firstVisibleItemScrollOffset
                        val consumed = scrollBy((before.viewportSize.height / 2f).coerceAtLeast(1f))
                        if (!consumed.isFinite() || consumed <= 0f) break
                        val after = state.layoutInfo.visibleItemsInfo
                        if ((after.maxOfOrNull { it.index } ?: -1) <= lastIndex &&
                            state.firstVisibleItemIndex == firstIndex &&
                            state.firstVisibleItemScrollOffset == firstOffset
                        ) break
                    }
                    // A missing identity falls back only after the forward scan has exhausted it.
                    if (!anchor.atBottom && !anchorFound && anchor.identity != null) {
                        steps = 0L
                        while (steps++ < maxSteps) {
                            context.ensureActive()
                            awaitBudget()
                            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                            if (item != null) {
                                scrollBy((item.offset.y - anchor.y).toFloat())
                                break
                            }
                            if (!state.canScrollBackward ||
                                scrollBy(-(state.layoutInfo.viewportSize.height / 2f).coerceAtLeast(1f)) >= 0f
                            ) break
                        }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // A higher-priority user scroll may preempt the history rebuild.
            // Preserve cancellation of the owning effect, but let input take over the viewport.
            context.ensureActive()
        }
        if (gridState === state) {
            pendingAnchor = null
            isRestoring = false
        }
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

/** [itemIdentity] maps a Lazy key to a stable, saveable anchor identity across layouts. */
@Composable
fun rememberWaterfallReflowState(
    columns: Int,
    landscape: Boolean,
    contentReady: Boolean = true,
    itemIdentity: (Any) -> Any = { it },
): WaterfallReflowState {
    val state = rememberSaveable(saver = WaterfallReflowState.saver(itemIdentity)) {
        WaterfallReflowState.create(columns, landscape, itemIdentity)
    }
    Snapshot.withoutReadObservation { state.update(columns, landscape) }
    val gridState = state.gridState
    LaunchedEffect(gridState, contentReady) { state.restore(gridState, contentReady) }
    return state
}

/** The first layout establishes constraints; only the restored viewport may be drawn. */
fun Modifier.waterfallReflowHost(state: WaterfallReflowState): Modifier = graphicsLayer {
    alpha = if (state.isRestoring) 0f else 1f
}
