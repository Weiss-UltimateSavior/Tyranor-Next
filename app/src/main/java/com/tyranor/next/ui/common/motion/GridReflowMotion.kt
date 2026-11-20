package com.tyranor.next.ui.common.motion

import android.animation.ValueAnimator
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemInfo
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/** Tracks layout changes independently from the page's initial entrance. */
@Stable
class GridReflowState internal constructor() {
    internal val entrance = EntranceState()
    private val snapshot = GridReflowSnapshot()
    private val schedule = GridReflowEntranceSchedule()
    private var visibleKeys: (() -> Set<Any>)? = null
    private var placementDelta: ((Any) -> IntOffset)? = null
    private val nodes = mutableSetOf<StaggeredEntranceNode>()
    private var pendingWindow = false
    private var animationsEnabled = false
    internal var isReady by mutableStateOf(true)
        private set

    internal fun dispatchReflow() {
        if (pendingWindow) {
            pendingWindow = false
            schedule.reset()
            entrance.openWindow()
            nodes.toList().forEach { it.onGridReflow() }
        }
        isReady = true
    }

    internal fun attach(node: StaggeredEntranceNode) { nodes += node }
    internal fun detach(node: StaggeredEntranceNode) { nodes -= node }

    internal fun update(
        columns: Int,
        landscape: Boolean,
        enabled: Boolean,
        visibleKeys: () -> Set<Any>,
        placementDelta: ((Any) -> IntOffset)? = null,
    ) {
        animationsEnabled = enabled
        this.visibleKeys = visibleKeys
        this.placementDelta = placementDelta
        if (snapshot.update(columns, landscape)) pendingWindow = enabled
        if (!enabled) entrance.skip()
    }

    internal fun pause() {
        isReady = false
        pendingWindow = false
        animationsEnabled = false
        visibleKeys = null
        entrance.skip()
    }

    internal fun placementDelta(key: Any): IntOffset =
        Snapshot.withoutReadObservation { placementDelta?.invoke(key) ?: IntOffset.Zero }

    internal fun entranceDelayMillis(key: Any, stepMillis: Long): Long? {
        if (!animationsEnabled || pendingWindow || !entrance.isFresh()) return null
        return Snapshot.withoutReadObservation {
            if (!MotionMode.enhanced) return@withoutReadObservation null
            val keys = visibleKeys?.invoke() ?: return@withoutReadObservation null
            val slot = schedule.slotFor(
                key,
                keys,
                maxOf(stepMillis, GridReflowEntranceSchedule.DEFAULT_STEP_MILLIS),
            ) ?: return@withoutReadObservation null
            // Explicit zero preserves callers that need placement without entrance staggering.
            if (stepMillis <= 0L) 0L else slot.delayMillis
        }
    }
}

/** Orders each visible-items list once, after an active entrance actually requests it. */
internal class GridReflowVisibleKeys<T>(
    private val items: () -> List<T>,
    private val index: (T) -> Int,
    private val key: (T) -> Any,
) : () -> Set<Any> {
    private var previousItems: List<T>? = null
    private var keys: Set<Any> = emptySet()

    override fun invoke(): Set<Any> {
        val current = items()
        if (current !== previousItems) {
            keys = current.sortedBy(index).mapTo(LinkedHashSet(current.size), key)
            previousItems = current
        }
        return keys
    }
}

/** Pure layout identity bookkeeping, separated from Android's animation clock. */
internal class GridReflowSnapshot {
    private var columns = 0
    private var landscape = false
    private var initialized = false
    var layoutGeneration: Int = 0
        private set

    fun update(columns: Int, landscape: Boolean): Boolean {
        if (initialized && this.columns == columns && this.landscape == landscape) return false
        this.columns = columns
        this.landscape = landscape
        if (!initialized) {
            initialized = true
            return false
        }
        layoutGeneration++
        return true
    }
}

/**
 * Assigns visible keys on the first eligible callback in the ordered snapshot's iteration order.
 * Slots stay fixed for the generation; later keys append in the new snapshot's order.
 */
internal class GridReflowEntranceSchedule {
    internal data class Slot(val order: Int, val delayMillis: Long)

    private var hasReflow = false
    private val slots = HashMap<Any, Slot>()
    private var scheduledKeys: Set<Any>? = null
    private var lastDelay = 0L

    fun reset() {
        hasReflow = true
        slots.clear()
        scheduledKeys = null
        lastDelay = 0L
    }

    fun slotFor(
        key: Any,
        visibleKeys: Set<Any>,
        preferredStepMillis: Long = DEFAULT_STEP_MILLIS,
    ): Slot? {
        if (!hasReflow || key !in visibleKeys) return null
        if (scheduledKeys !== visibleKeys) {
            scheduledKeys = visibleKeys
            val additions = visibleKeys.count { it !in slots }
            if (additions > 0) {
                val intervals = if (slots.isEmpty()) additions - 1 else additions
                val remaining = STAGGER_BUDGET_MILLIS - lastDelay
                // Large batches compress toward one 60 Hz frame per card, rather than sharing a tail delay.
                val step = minOf(
                    preferredStepMillis.coerceAtLeast(MIN_STEP_MILLIS),
                    (remaining / intervals.coerceAtLeast(1)).coerceAtLeast(MIN_STEP_MILLIS),
                )
                for (visibleKey in visibleKeys) {
                    if (visibleKey in slots) continue
                    if (slots.isNotEmpty()) lastDelay += step
                    slots[visibleKey] = Slot(slots.size, lastDelay)
                }
            }
        }
        return slots[key]
    }

    companion object {
        const val DEFAULT_STEP_MILLIS = 60L
        const val MIN_STEP_MILLIS = 17L
        const val STAGGER_BUDGET_MILLIS = 720L
    }
}

/** Tracks reflow entrance for a lazy grid, ordering visible items by their list index. */
@Composable
fun rememberGridReflowState(
    columns: Int,
    landscape: Boolean,
    gridState: LazyGridState,
    ready: Boolean = true,
    placementDelta: ((Any) -> IntOffset)? = null,
): GridReflowState = rememberGridReflowState(
    columns,
    landscape,
    ready,
    placementDelta,
    remember(gridState) {
        GridReflowVisibleKeys(
            { gridState.layoutInfo.visibleItemsInfo },
            LazyGridItemInfo::index,
            LazyGridItemInfo::key,
        )
    },
)

/** Tracks reflow entrance for a lazy staggered grid, ordering visible items by their list index. */
@Composable
fun rememberGridReflowState(
    columns: Int,
    landscape: Boolean,
    gridState: LazyStaggeredGridState,
    ready: Boolean = true,
    placementDelta: ((Any) -> IntOffset)? = null,
): GridReflowState = rememberGridReflowState(
    columns,
    landscape,
    ready,
    placementDelta,
    remember(gridState) {
        GridReflowVisibleKeys(
            { gridState.layoutInfo.visibleItemsInfo },
            LazyStaggeredGridItemInfo::index,
            LazyStaggeredGridItemInfo::key,
        )
    },
)

@Composable
private fun rememberGridReflowState(
    columns: Int,
    landscape: Boolean,
    ready: Boolean,
    placementDelta: ((Any) -> IntOffset)?,
    visibleKeys: () -> Set<Any>,
): GridReflowState {
    val state = remember { GridReflowState() }
    val enabled = MotionMode.enhanced && ValueAnimator.areAnimatorsEnabled()
    Snapshot.withoutReadObservation {
        if (ready) state.update(columns, landscape, enabled, visibleKeys, placementDelta) else state.pause()
    }
    if (ready) SideEffect { state.dispatchReflow() }
    return state
}

@Composable
fun Modifier.gridReflowHost(state: GridReflowState): Modifier =
    skipEntranceOnDrag(state.entrance).graphicsLayer {
        alpha = if (state.isReady) 1f else 0f
    }
