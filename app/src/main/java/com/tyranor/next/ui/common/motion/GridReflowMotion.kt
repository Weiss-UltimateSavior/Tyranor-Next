package com.tyranor.next.ui.common.motion

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/** Tracks layout changes independently from the page's initial entrance. */
@Stable
class GridReflowState internal constructor() {
    internal val entrance = EntranceState()
    private val snapshot = GridReflowSnapshot()
    private val schedule = GridReflowEntranceSchedule()
    // The caller must provide keys in the desired order (for example, via LinkedHashSet).
    private var visibleKeys: () -> Set<Any> = { emptySet() }
    private var placementDelta: ((Any) -> IntOffset)? = null
    private val nodes = mutableSetOf<StaggeredEntranceNode>()
    private var pendingWindow = false
    internal var isReady by mutableStateOf(true)
        private set

    internal fun dispatchReflow() {
        if (pendingWindow) {
            pendingWindow = false
            entrance.openWindow()
            nodes.toList().forEach { it.onGridReflow() }
        }
        isReady = true
    }

    internal fun attach(node: StaggeredEntranceNode) { nodes += node }
    internal fun detach(node: StaggeredEntranceNode) { nodes -= node }
    internal var animationsEnabled: Boolean = false
        private set

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
        if (snapshot.update(columns, landscape) { visibleKeys().toSet() }) {
            schedule.reset()
            if (enabled) pendingWindow = true
        }
        if (!enabled) entrance.skip()
    }

    internal fun pause() {
        isReady = false
        pendingWindow = false
        animationsEnabled = false
        visibleKeys = { emptySet() }
        entrance.skip()
    }

    internal fun isVisible(key: Any): Boolean =
        Snapshot.withoutReadObservation { key in visibleKeys() }

    internal fun shouldEnter(key: Any): Boolean =
        animationsEnabled && snapshot.layoutGeneration > 0 && entrance.isFresh() && isVisible(key)

    internal fun placementDelta(key: Any): IntOffset =
        Snapshot.withoutReadObservation { placementDelta?.invoke(key) ?: IntOffset.Zero }

    internal fun entranceDelayMillis(key: Any, stepMillis: Long): Long? =
        Snapshot.withoutReadObservation {
            val slot = schedule.slotFor(
                key,
                visibleKeys(),
                maxOf(stepMillis, GridReflowEntranceSchedule.DEFAULT_STEP_MILLIS),
            ) ?: return@withoutReadObservation null
            // Explicit zero preserves callers that need placement without entrance staggering.
            if (stepMillis <= 0L) 0L else slot.delayMillis
        }

    /** Preserve native placement; the longer layout spring remains an optional tuning decision. */
    val placement: ListItemPlacement
        get() = ListItemPlacement.Reflow
}

/** Pure layout identity bookkeeping, separated from Android's animation clock. */
internal class GridReflowSnapshot {
    private var layout: Pair<Int, Boolean>? = null
    var layoutGeneration: Int = 0
        private set
    var previousVisibleKeys: Set<Any> = emptySet()
        private set

    fun update(columns: Int, landscape: Boolean, visibleKeys: () -> Set<Any>): Boolean {
        val next = columns to landscape
        val previous = layout
        if (previous == next) return false
        layout = next
        if (previous == null) return false
        previousVisibleKeys = visibleKeys().toSet()
        layoutGeneration++
        return true
    }
}

/**
 * Assigns all visible keys on the first callback in the provider's iteration order.
 * Slots stay fixed for the generation; later keys append in the provider's current order.
 */
internal class GridReflowEntranceSchedule {
    internal data class Slot(val order: Int, val delayMillis: Long)

    private var hasReflow = false
    private val slots = LinkedHashMap<Any, Slot>()

    fun reset() {
        hasReflow = true
        slots.clear()
    }

    fun slotFor(
        key: Any,
        visibleKeys: Set<Any>,
        preferredStepMillis: Long = DEFAULT_STEP_MILLIS,
    ): Slot? {
        if (!hasReflow || key !in visibleKeys) return null
        val additions = visibleKeys.filter { it !in slots }
        if (additions.isNotEmpty()) {
            val previousDelay = slots.values.lastOrNull()?.delayMillis
            val intervals = if (previousDelay == null) additions.size - 1 else additions.size
            val remaining = STAGGER_BUDGET_MILLIS - (previousDelay ?: 0L)
            // Large batches compress toward one 60 Hz frame per card, rather than sharing a tail delay.
            val step = minOf(
                preferredStepMillis.coerceAtLeast(MIN_STEP_MILLIS),
                (remaining / intervals.coerceAtLeast(1)).coerceAtLeast(MIN_STEP_MILLIS),
            )
            val startDelay = previousDelay?.plus(step) ?: 0L
            additions.forEachIndexed { index, newKey ->
                slots[newKey] = Slot(slots.size, startDelay + index.toLong() * step)
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

/**
 * [visibleKeys] must return a set whose iteration follows list index order, such as
 * `visibleItemsInfo.sortedBy { it.index }.mapTo(LinkedHashSet()) { it.key }`.
 * Callback arrival order does not affect entrance order; unordered sets are not supported.
 */
@Composable
fun rememberGridReflowState(
    columns: Int,
    landscape: Boolean,
    ready: Boolean = true,
    placementDelta: ((Any) -> IntOffset)? = null,
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
