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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** Rebuilds lane history with public APIs instead of anchoring an unmeasured fresh state. */
class WaterfallReflowState private constructor(
    private var columns: Int,
    private var landscape: Boolean,
    private var pendingAnchor: Anchor? = null,
) {
    var gridState = LazyStaggeredGridState()
        private set
    var previousItemOffsets: Map<Any, IntOffset> = emptyMap()
        private set
    var isRestoring by mutableStateOf(pendingAnchor != null)
        private set

    private data class Anchor(val index: Int, val key: Any?, val y: Int, val atBottom: Boolean)

    private fun capture(): Anchor? {
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == gridState.firstVisibleItemIndex }
            ?: visible.minByOrNull { it.index }
            ?: return null
        return Anchor(
            item.index,
            item.key,
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
        // Wait outside the uninterrupted scroll transaction until layout and data are resolved.
        snapshotFlow {
            state.layoutInfo.viewportSize.height to
                (state.layoutInfo.totalItemsCount > 0 || contentReady)
        }.first { it.first > 0 && it.second }
        val context = currentCoroutineContext()
        state.scroll {
            val scope = LazyLayoutScrollScope(state, this)
            with(scope) {
                val count = state.layoutInfo.totalItemsCount
                if (count == 0) return@with
                val target = anchor.index.coerceIn(0, count - 1)
                // Foundation 1.11.2 needs forward measurement history for stable upward lanes.
                // This is O(anchorIndex), or O(itemCount) at the bottom; a large library can
                // block the UI thread. Do not replace these measured steps with teleportation.
                val maxSteps = count.toLong() * 4L + 1024L
                var steps = 0L
                var anchorFound = false
                while (steps++ < maxSteps) {
                    context.ensureActive()
                    val before = state.layoutInfo
                    val visible = before.visibleItemsInfo
                    val item = if (anchor.key != null) visible.firstOrNull { it.key == anchor.key }
                        else visible.firstOrNull { it.index == target }
                    if (anchor.atBottom) {
                        if (!state.canScrollForward) break
                    } else if (item != null) {
                        anchorFound = true
                        scrollBy((item.offset.y - anchor.y).toFloat())
                        break
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
                if (!anchor.atBottom && !anchorFound && anchor.key != null) {
                    steps = 0L
                    while (steps++ < maxSteps) {
                        context.ensureActive()
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
        if (gridState === state) {
            pendingAnchor = null
            isRestoring = false
        }
    }

    companion object {
        internal fun create(columns: Int, landscape: Boolean) = WaterfallReflowState(columns, landscape)

        internal val Saver = listSaver<WaterfallReflowState, Any>(
            save = {
                val anchor = it.pendingAnchor ?: it.capture()
                val key = anchor?.key?.takeIf { value -> canBeSaved(value) }
                listOf(it.columns, it.landscape, anchor?.index ?: -1, anchor?.y ?: 0,
                    anchor?.atBottom ?: false, key != null, key ?: "")
            },
            restore = {
                val index = it[2] as Int
                WaterfallReflowState(
                    it[0] as Int,
                    it[1] as Boolean,
                    if (index < 0) null else Anchor(index, if (it[5] as Boolean) it[6] else null,
                        it[3] as Int, it[4] as Boolean),
                )
            },
        )
    }
}

@Composable
fun rememberWaterfallReflowState(
    columns: Int,
    landscape: Boolean,
    contentReady: Boolean = true,
): WaterfallReflowState {
    val state = rememberSaveable(saver = WaterfallReflowState.Saver) {
        WaterfallReflowState.create(columns, landscape)
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
