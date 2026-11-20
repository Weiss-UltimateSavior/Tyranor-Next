package com.tyranor.next.ui.common.motion

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.staggeredgrid.LazyLayoutScrollScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Frozen, stable-key data: only column count and scroll position change. */
class WaterfallRotationScrollDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun retainedStateReportsLaneChangesDuringBottomReflowAndFullUpwardScroll() = runScenario(false)

    @Test
    fun rebuiltStatePreservesLanesDuringBottomReflowAndFullUpwardScroll() = runScenario(true)

    @Test
    fun forwardHistoryPreservesLanesDuringBottomReflowAndFullUpwardScroll() = runScenario(true, true)

    private fun requireDiagnostics() {
        org.junit.Assume.assumeTrue(
            "Enable waterfall_diagnostics=true to run historical hypotheses",
            InstrumentationRegistry.getArguments().getString("waterfall_diagnostics") == "true",
        )
    }

    @Test
    fun productionHelperKeepsBottomAndLanesAcrossBothColumnDirections() = withProduction { fixture ->
        for (columns in listOf(3, 2)) {
            compose.runOnIdle {
                fixture.scope.launch {
                    fixture.state.scrollToItem(99)
                    fixture.state.scrollBy(fixture.state.layoutInfo.viewportSize.height.toFloat())
                }
            }
            settle()
            compose.runOnIdle {
                assertTrue("Setup must reach bottom before columns=$columns", !fixture.state.canScrollForward)
                fixture.columns = columns
            }
            awaitRestore(fixture)
            compose.runOnIdle {
                assertTrue("Column change must preserve bottom", !fixture.state.canScrollForward)
                assertTrue("Bottom must contain item 99", fixture.state.layoutInfo.visibleItemsInfo.any { it.index == 99 })
            }
            assertStableUpwardScroll(fixture, "production-bottom-$columns")
        }
    }

    @Test
    fun productionHelperPreservesMiddleAnchorThenScrollsStablyToTop() = withProduction { fixture ->
        for (columns in listOf(3, 2)) {
            compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
            settle()
            val before = compose.runOnIdle {
                val item = fixture.state.layoutInfo.visibleItemsInfo.first { it.index == 50 }
                Item(item.index, item.key, Position(item.offset.x, item.offset.y))
            }
            compose.runOnIdle { fixture.columns = columns }
            awaitRestore(fixture)
            compose.runOnIdle {
                val after = fixture.state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == before.key }
                assertTrue("Middle stable key must remain visible", after != null)
                checkNotNull(after)
                assertTrue("Anchor drift must be smaller than one card height", kotlin.math.abs(after.offset.y - before.position.y) < after.size.height)
            }
            assertStableUpwardScroll(fixture, "production-middle-$columns")
        }
    }

    @Test
    fun productionHelperFinishesRapidColumnChangesWithValidData() = withProduction { fixture ->
        compose.runOnIdle {
            fixture.scope.launch {
                fixture.state.scrollToItem(99)
                fixture.state.scrollBy(fixture.state.layoutInfo.viewportSize.height.toFloat())
            }
        }
        settle()
        for (columns in listOf(3, 2, 3, 2, 3)) {
            compose.runOnIdle { fixture.columns = columns }
            compose.mainClock.advanceTimeByFrame()
        }
        awaitRestore(fixture)
        compose.runOnIdle {
            assertTrue("Final restore must finish", !fixture.holder!!.isRestoring)
            val info = fixture.state.layoutInfo
            assertTrue("All frozen data must remain present", info.totalItemsCount == 100)
            assertTrue("Final viewport must contain cards", info.visibleItemsInfo.isNotEmpty())
            assertTrue("Final keys must match indices", info.visibleItemsInfo.all { it.key == "waterfall-${it.index}" })
            assertTrue("Rapid changes must preserve bottom", !fixture.state.canScrollForward)
        }
        assertStableUpwardScroll(fixture, "production-rapid")
    }

    @Test
    fun productionHelperRestoresStableKeyAfterPrependingItems() = withProduction { fixture ->
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
        settle()
        val before = compose.runOnIdle {
            val item = fixture.state.layoutInfo.visibleItemsInfo.first { it.key == "waterfall-50" }
            Item(item.index, item.key, Position(item.offset.x, item.offset.y))
        }
        compose.runOnIdle {
            fixture.keyOrder = (100 until 110).toList() + fixture.keyOrder
            assertTrue("Stable key must move beyond its old index", fixture.keyOrder.indexOf(50) > before.index)
            fixture.columns = 3
        }
        awaitRestore(fixture)
        compose.runOnIdle {
            val after = fixture.state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == before.key }
            assertTrue("Prepending must preserve the stable anchor key", after != null)
            checkNotNull(after)
            assertTrue("Restored key must now be at index 60", after.index == 60)
            assertTrue("Stable key drift must stay below one card height", kotlin.math.abs(after.offset.y - before.position.y) < after.size.height)
        }
        assertStableUpwardScroll(fixture, "production-prepend-key")
    }

    @Test
    fun userInputPreemptionRevealsViewportWithoutRestoringOldAnchor() = withProduction(manualRestore = true) { fixture ->
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
        settle()
        compose.runOnIdle { fixture.columns = 3 }
        settle()
        val ownerCompleted = CompletableDeferred<Unit>()
        lateinit var inputJob: Job
        lateinit var restoreJob: Job
        compose.runOnIdle {
            assertTrue("Column change must have a pending restore", fixture.holder!!.isRestoring)
            inputJob = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.state.scroll(MutatePriority.UserInput) { awaitCancellation() }
            }
            restoreJob = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.holder!!.restore(fixture.state, true)
                ownerCompleted.complete(Unit)
            }
        }
        try {
            compose.waitUntil { restoreJob.isCompleted }
            compose.runOnIdle {
                assertTrue("Input preemption must not cancel the restoration owner", ownerCompleted.isCompleted && !restoreJob.isCancelled)
                assertTrue("Preempted restoration must reveal the viewport", !fixture.holder!!.isRestoring)
                assertTrue("Preemption must keep the user viewport rather than apply the old anchor", fixture.state.firstVisibleItemIndex == 0)
                assertTrue("Input must retain its higher-priority scroll", inputJob.isActive)
            }
        } finally {
            compose.runOnIdle { inputJob.cancel(); restoreJob.cancel() }
        }
        settle()
        compose.runOnIdle {
            fixture.scope.launch { fixture.holder!!.restore(fixture.state, true) }
        }
        settle()
        compose.runOnIdle {
            assertTrue("Cleared restoration must not retry the old anchor", fixture.state.firstVisibleItemIndex == 0)
        }
    }

    @Test
    fun ownerCancellationKeepsPendingRestorationForItsSuccessor() = withProduction(manualRestore = true) { fixture ->
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
        settle()
        compose.runOnIdle { fixture.columns = 3 }
        settle()
        lateinit var owner: Job
        lateinit var blocker: Job
        val releaseScroll = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        compose.runOnIdle {
            blocker = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.state.scroll {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { releaseScroll.await() }
                    }
                }
            }
            owner = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.holder!!.restore(fixture.state, true)
                returned.complete(Unit)
            }
            assertTrue("Restoration must be waiting for the scroll lock", owner.isActive && !returned.isCompleted)
            owner.cancel()
        }
        try {
            compose.waitUntil { owner.isCompleted }
            compose.runOnIdle {
                assertTrue("Cancelled effect must propagate cancellation", owner.isCancelled && !returned.isCompleted)
                assertTrue("Cancelled effect must not clear the pending anchor", fixture.holder!!.isRestoring)
            }
        } finally {
            compose.runOnIdle { releaseScroll.complete(Unit); blocker.cancel(); owner.cancel() }
        }
        settle()
        compose.runOnIdle { fixture.scope.launch { fixture.holder!!.restore(fixture.state, true) } }
        awaitRestore(fixture)
        compose.runOnIdle {
            assertTrue("The replacement effect must restore the original anchor", fixture.state.layoutInfo.visibleItemsInfo.any { it.key == "waterfall-50" })
        }
    }

    private fun withProduction(manualRestore: Boolean = false, check: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = MotionMode.enhanced
        val originalAutoAdvance = compose.mainClock.autoAdvance
        try {
            MotionDeviceAcceptance.require()
            val fixture = Fixture(freshState = false, buildHistory = false, production = true, manualRestore = manualRestore)
            compose.setContent { Waterfall(fixture) }
            settle()
            check(fixture)
        } finally {
            compose.mainClock.autoAdvance = originalAutoAdvance
            instrumentation.runOnMainSync { MotionMode.sync(originalMode) }
        }
    }

    private fun awaitRestore(fixture: Fixture) {
        settle()
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.runOnIdle { !fixture.holder!!.isRestoring }
        }
        settle()
    }

    private fun assertStableUpwardScroll(fixture: Fixture, phase: String) {
        val changes = scrollUpAndTrace(fixture, phase)
        changes.forEach { Log.e(TAG, it) }
        assertTrue("Production helper changed stable layout: ${changes.joinToString("\n")}", changes.isEmpty())
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun runScenario(freshState: Boolean, buildHistory: Boolean = false) {
        requireDiagnostics()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = MotionMode.enhanced
        val originalAutoAdvance = compose.mainClock.autoAdvance
        try {
            MotionDeviceAcceptance.require()
            val fixture = Fixture(freshState, buildHistory)
            compose.setContent { Waterfall(fixture) }
            compose.waitForIdle()
            if (buildHistory) buildForwardHistory(fixture, "initial", "waterfall-99", 0)
            val violations = ArrayList<String>()
            for (columns in listOf(3, 2)) {
                if (buildHistory) {
                    buildForwardHistory(fixture, "before-$columns", "waterfall-99", 0)
                } else {
                    compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(99) } }
                }
                settle()
                compose.runOnIdle {
                    val visible = fixture.state.layoutInfo.visibleItemsInfo
                    assertTrue("Bottom setup must expose item 99", visible.any { it.index == 99 })
                    fixture.anchorIndex = fixture.state.firstVisibleItemIndex
                    fixture.anchorOffset = fixture.state.firstVisibleItemScrollOffset
                    val anchor = visible.first { it.index == fixture.anchorIndex }
                    fixture.anchorKey = anchor.key
                    fixture.anchorY = anchor.offset.y
                    fixture.columns = columns
                }
                settle()
                if (buildHistory) {
                    buildForwardHistory(fixture, "after-$columns", fixture.anchorKey, fixture.anchorY)
                    settle()
                }
                violations += scrollUpAndTrace(fixture, if (columns == 3) "2->3" else "3->2")
            }
            val mode = if (buildHistory) "forward-history" else if (freshState) "fresh-state" else "retained-state"
            Log.i(TAG, "$mode changes=${violations.size}")
            violations.forEach { Log.e(TAG, "$mode $it") }
            if (freshState) assertTrue("Fresh state is not a proven fix: ${violations.joinToString("\n")}", violations.isEmpty())
        } finally {
            compose.mainClock.autoAdvance = originalAutoAdvance
            instrumentation.runOnMainSync { MotionMode.sync(originalMode) }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun buildForwardHistory(fixture: Fixture, phase: String, key: Any, savedY: Int) {
        compose.runOnIdle {
            fixture.scope.launch {
                val started = SystemClock.elapsedRealtime()
                var steps = 0
                fixture.state.scroll {
                    val layoutScope = LazyLayoutScrollScope(fixture.state, this)
                    // Public scope scrollBy remeasures synchronously: never suspend or jump history here.
                    with(layoutScope) {
                        while (fixture.state.layoutInfo.visibleItemsInfo.none { it.key == key } && steps < MAX_STEPS) {
                            val distance = fixture.state.layoutInfo.viewportSize.height / 2f
                            check(distance > 0f) { "Viewport is not measured" }
                            if (scrollBy(distance) <= 0f) break
                            steps++
                        }
                        val item = fixture.state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                        check(item != null) { "Forward history did not reach key=$key phase=$phase steps=$steps" }
                        scrollBy((item.offset.y - savedY).toFloat())
                    }
                }
                Log.i(TAG, "forward-history phase=$phase key=$key savedY=$savedY steps=$steps elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
        }
        compose.waitForIdle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    private fun scrollUpAndTrace(fixture: Fixture, transition: String): List<String> {
        var previous = emptyMap<Any, Position>()
        val violations = ArrayList<String>()
        var reachedTop = false
        var consumed = 0f
        for (step in 0 until MAX_STEPS) {
            val frame = compose.runOnIdle {
                Frame(
                    fixture.state.layoutInfo.visibleItemsInfo.sortedBy { it.index }.map {
                        Item(it.index, it.key, Position(it.offset.x, it.offset.y))
                    },
                    !fixture.state.canScrollBackward,
                )
            }
            Log.i(TAG, "fresh=${fixture.freshState} $transition step=$step consumed=$consumed " + frame.items.joinToString { "index=${it.index},key=${it.key},x=${it.position.x},y=${it.position.y}" })
            val deltas = frame.items.mapNotNull { item ->
                previous[item.key]?.let { old ->
                    if (old.x != item.position.x) violations += "$transition step=$step key=${item.key} x=${old.x}->${item.position.x}"
                    item.key to (item.position.y - old.y)
                }
            }
            val expectedDy = deltas.firstOrNull()?.second
            deltas.forEach { (key, dy) ->
                if (expectedDy != null && kotlin.math.abs(dy - expectedDy) > 1) {
                    violations += "$transition step=$step key=$key dy=$dy commonDy=$expectedDy consumed=$consumed residual=${dy + consumed}"
                }
            }
            previous = frame.items.associate { it.key to it.position }
            if (frame.atTop) {
                reachedTop = true
                assertTrue("Reached top must expose item 0", frame.items.any { it.index == 0 })
                break
            }
            compose.runOnIdle { fixture.scope.launch { consumed = fixture.state.scrollBy(-80f) } }
            compose.waitForIdle()
        }
        assertTrue("$transition must scroll all the way to the top within $MAX_STEPS steps", reachedTop)
        return violations
    }

    @Composable
    private fun Waterfall(fixture: Fixture) {
        val columns = fixture.columns
        val holder = if (fixture.manualRestore) {
            remember { WaterfallReflowState.create(2, false) }.also { holder ->
                Snapshot.withoutReadObservation { holder.update(columns, columns == 3) }
            }
        } else if (fixture.production) rememberWaterfallReflowState(columns, columns == 3) else null
        fixture.holder = holder
        val state = if (holder != null) {
            holder.gridState
        } else if (fixture.freshState) {
            remember(columns) {
                if (fixture.buildHistory) LazyStaggeredGridState()
                else LazyStaggeredGridState(fixture.anchorIndex, fixture.anchorOffset)
            }
        } else {
            remember { LazyStaggeredGridState() }
        }
        fixture.state = state
        fixture.scope = rememberCoroutineScope()
        val reflow = rememberGridReflowState(
            columns = columns,
            landscape = columns == 3,
            ready = holder?.isRestoring != true,
            visibleKeys = { state.layoutInfo.visibleItemsInfo.sortedBy { it.index }.mapTo(LinkedHashSet()) { it.key } },
        )
        // Keep the lazy node alive when replacing its state; no key(columns) wrapper.
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            state = state,
            modifier = Modifier.size(240.dp, 350.dp)
                .then(if (holder != null) Modifier.waterfallReflowHost(holder) else Modifier)
                .gridReflowHost(reflow),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
        ) {
            items(count = fixture.keyOrder.size, key = { "waterfall-${fixture.keyOrder[it]}" }) { index ->
                val id = fixture.keyOrder[index]
                val coverRatio = 0.60f + (id % 5) * 0.05f
                Column(
                    listItemMotion(reflow.placement)
                        .staggeredEntrance(index, stepMillis = 0L, reflow = reflow, key = "waterfall-$id")
                        .fillMaxWidth(),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(coverRatio).background(Color(0xFF00897B)))
                    Box(Modifier.fillMaxWidth().height((18 + id % 4 * 9).dp).background(Color(0xFF263238)))
                }
            }
        }
    }

    private class Fixture(
        val freshState: Boolean,
        val buildHistory: Boolean,
        val production: Boolean = false,
        val manualRestore: Boolean = false,
    ) {
        var holder: WaterfallReflowState? = null
        var keyOrder by mutableStateOf((0 until 100).toList())
        var columns by mutableIntStateOf(2)
        var anchorIndex = 0
        var anchorOffset = 0
        var anchorKey: Any = "waterfall-0"
        var anchorY = 0
        lateinit var state: LazyStaggeredGridState
        lateinit var scope: CoroutineScope
    }

    private data class Position(val x: Int, val y: Int)
    private data class Item(val index: Int, val key: Any, val position: Position)
    private data class Frame(val items: List<Item>, val atTop: Boolean)

    companion object {
        private const val TAG = "WaterfallRotationScroll"
        private const val MAX_STEPS = 800
    }
}
