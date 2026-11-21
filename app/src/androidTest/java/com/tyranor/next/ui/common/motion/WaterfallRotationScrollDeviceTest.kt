package com.tyranor.next.ui.common.motion

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 固定且键稳定的夹具，用于隔离列数、滚动与恢复边界。 */
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
    fun forcedTimeoutAlignsTallLastCardToBottom() = withProduction(manualRestore = true) { fixture ->
        compose.runOnIdle {
            fixture.tallLastCard = true
            fixture.scope.launch {
                fixture.state.scrollToItem(99)
                while (fixture.state.canScrollForward) {
                    fixture.state.scrollBy(fixture.state.layoutInfo.viewportSize.height.toFloat())
                }
            }
        }
        settle()
        compose.runOnIdle {
            assertTrue("Setup must reach the tall card's bottom", !fixture.state.canScrollForward)
            fixture.columns = 3
        }
        settle()
        compose.runOnIdle {
            assertTrue("Column change must retain a pending bottom anchor", fixture.holder!!.isRestoring)
            fixture.scope.launch {
                // 零预算直接进入兜底，不依赖设备是否在真实的 1500ms 内完成扫描。
                fixture.holder!!.restore(fixture.state, true, scanBudgetMillis = 0L)
            }
        }
        awaitRestore(fixture)
        compose.runOnIdle {
            val info = fixture.state.layoutInfo
            val last = info.visibleItemsInfo.first { it.index == 99 }
            assertTrue("The final card must be taller than two viewports", last.size.height > info.viewportSize.height * 2)
            assertTrue("Timeout fallback must fully reach bottom", !fixture.state.canScrollForward)
            assertTrue("The tall last card must end at the viewport bottom", kotlin.math.abs(last.offset.y + last.size.height - info.viewportSize.height) <= 1)
        }
    }

    @Test
    fun forcedTimeoutAlignsShortLastRowToPaddedBottom() = withProduction(
        manualRestore = true,
        uniformCards = true,
    ) { fixture ->
        compose.runOnIdle { fixture.bottomPaddingDp = 24 }
        settle()
        val setup = compose.runOnIdle {
            val state = fixture.state
            val height = state.layoutInfo.viewportSize.height.toFloat()
            assertTrue("The padded viewport must be measured", height > 0f && state.layoutInfo.afterContentPadding > 0)
            fixture.scope.launch {
                repeat(MAX_STEPS) {
                    if (!state.canScrollForward || state.scrollBy(height) <= 0f) return@launch
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000L) { setup.isCompleted }
        compose.runOnIdle {
            val state = fixture.state
            assertTrue("Setup must reach the bottom of a scrollable grid", !state.canScrollForward && state.canScrollBackward)
            assertTrue("Setup must expose the last card", state.layoutInfo.visibleItemsInfo.any { it.index == 99 })
            fixture.columns = 3
        }
        settle()
        compose.runOnIdle {
            val holder = checkNotNull(fixture.holder)
            assertTrue("Column change must retain a pending bottom anchor", holder.isRestoring)
            fixture.scope.launch { holder.restore(fixture.state, true, scanBudgetMillis = 0L) }
        }
        awaitRestore(fixture)
        compose.runOnIdle {
            val state = fixture.state
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.first { it.index == 99 }
            val rowBottom = info.visibleItemsInfo.maxOf { it.offset.y + it.size.height }
            val viewportBottom = info.viewportEndOffset - info.afterContentPadding
            assertTrue("The fixture must have bottom padding and a short last card",
                info.afterContentPadding > 0 && last.size.height < info.viewportSize.height)
            assertTrue("The short last row must end at the padded viewport bottom: ${viewportBottom - rowBottom}px",
                kotlin.math.abs(viewportBottom - rowBottom) <= 1)
            // 100 项分成三列时末项所在列未必最长；触底按所有可见列的最底边计算。
            assertTrue("The last card must remain fully visible above the padded bottom",
                last.offset.y >= info.viewportStartOffset && last.offset.y + last.size.height <= viewportBottom)
            assertTrue("Timeout fallback must fully reach bottom", !state.canScrollForward)
        }
    }

    @Test
    fun visibilityReadDoesNotRecomposeCallerAndPublishesDeferredLayout() = withProduction { fixture ->
        val originalState = compose.runOnIdle { fixture.state }
        val beforeHide = compose.runOnIdle { fixture.compositions }
        compose.runOnIdle { fixture.enabled = false }
        settle()
        compose.runOnIdle {
            assertTrue("Visibility alone must not recompose the caller", fixture.compositions == beforeHide)
            fixture.columns = 3
        }
        settle()
        compose.runOnIdle {
            assertTrue("Hidden layout must retain the original grid", fixture.state === originalState)
            assertTrue("Hidden layout must retain its matching columns", fixture.holder!!.layoutColumns == 2)
            fixture.enabled = true
        }
        awaitRestore(fixture)
        compose.runOnIdle {
            assertTrue("Observable layout must publish the replacement grid", fixture.state !== originalState)
            assertTrue("Deferred columns and orientation must publish together", fixture.holder!!.layoutColumns == 3 && fixture.holder!!.layoutLandscape)
        }
        val beforeToggle = compose.runOnIdle { fixture.compositions }
        compose.runOnIdle { fixture.enabled = false }
        settle()
        compose.runOnIdle { fixture.enabled = true }
        settle()
        compose.runOnIdle {
            assertTrue("Visibility without a layout change must stay inside the helper", fixture.compositions == beforeToggle)
        }
    }

    @Test
    fun savedAnchorRebuildsLaneHistoryBeforeRevealingBottom() = withProduction { fixture ->
        compose.runOnIdle {
            fixture.scope.launch {
                fixture.state.scrollToItem(99)
                fixture.state.scrollBy(fixture.state.layoutInfo.viewportSize.height.toFloat())
            }
        }
        settle()
        val oldHolder = compose.runOnIdle {
            assertTrue("Saved anchor setup must reach bottom", !fixture.state.canScrollForward)
            fixture.holder!!
        }
        fixture.restoreSavedState()
        awaitRestore(fixture)
        compose.runOnIdle {
            assertTrue("Saved state must recreate the holder", fixture.holder !== oldHolder)
            assertTrue("Saved bottom must be restored", !fixture.state.canScrollForward)
            assertTrue("Saved bottom must retain the last item", fixture.state.layoutInfo.visibleItemsInfo.any { it.index == 99 })
        }
        // 保存载荷没有完整列历史，不能仅跳到保存的索引就结束恢复。
        assertStableUpwardScroll(fixture, "production-saved-bottom")
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
    fun productionHelperRestoresGameIdentityAfterCoverAndOrderChange() = withProduction(compositeKeys = true) { fixture ->
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
        settle()
        val before = compose.runOnIdle {
            val item = fixture.state.layoutInfo.visibleItemsInfo.first { it.key == fixture.itemKey(50) }
            Item(item.index, item.key, Position(item.offset.x, item.offset.y))
        }
        val beforeLayout = compose.runOnIdle {
            fixture.state.layoutInfo.visibleItemsInfo.map { Triple(it.index, it.key, it.offset) }
        }
        compose.runOnIdle {
            fixture.coverVersion++
            fixture.keyOrder = (100 until 110).toList() + fixture.keyOrder
            fixture.columns = 3
        }
        awaitRestore(fixture)
        compose.runOnIdle {
            val after = fixture.state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == fixture.itemKey(50) }
            assertTrue("Changed cover key must differ from the saved lazy key", fixture.itemKey(50) != before.key)
            assertTrue("Game identity must survive cover and order changes; before=$beforeLayout; after=${fixture.state.layoutInfo.visibleItemsInfo.map { Triple(it.index, it.key, it.offset) }}", after != null)
            checkNotNull(after)
            assertTrue("The original game must restore at its new index", after.index == 60)
            assertTrue("Identity anchor must retain its viewport position", kotlin.math.abs(after.offset.y - before.position.y) < after.size.height)
        }
        assertStableUpwardScroll(fixture, "production-cover-and-order")
    }

    @Test
    fun restoreWaitsForUserInputThenRestoresOriginalAnchor() = withProduction(manualRestore = true) { fixture ->
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
        settle()
        val anchor = compose.runOnIdle {
            fixture.state.layoutInfo.visibleItemsInfo.first { it.index == fixture.state.firstVisibleItemIndex }.key
        }
        compose.runOnIdle { fixture.columns = 3 }
        settle()
        val releaseInput = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        lateinit var input: Job
        lateinit var owner: Job
        compose.runOnIdle {
            assertTrue("Column change must have a pending restore", fixture.holder!!.isRestoring)
            input = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.state.scroll(MutatePriority.UserInput) { releaseInput.await() }
            }
            owner = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.holder!!.restore(fixture.state, true)
                returned.complete(Unit)
            }
        }
        try {
            settle()
            compose.runOnIdle {
                assertTrue("UserInput must keep its scroll mutation", input.isActive)
                assertTrue("The restore owner must wait, not return or be cancelled", owner.isActive && !returned.isCompleted)
                assertTrue("The original anchor must remain pending", fixture.holder!!.isRestoring)
                assertTrue("Restore must not move the grid while UserInput owns it", !fixture.state.canScrollBackward)
                releaseInput.complete(Unit)
            }
            awaitRestore(fixture)
            compose.runOnIdle {
                assertTrue("The original owner must finish normally", returned.isCompleted && !owner.isCancelled)
                assertTrue("The original anchor must be visible", fixture.state.layoutInfo.visibleItemsInfo.any { it.key == anchor })
            }
        } finally {
            input.cancel()
            owner.cancel()
        }
    }

    @Test
    fun backwardScanFindsIdentityMovedBehindTheForwardCursor() = withProduction(
        manualRestore = true,
        uniformCards = true,
    ) { fixture ->
        compose.runOnIdle { fixture.keyOrder = (0 until 150).toList() }
        settle()
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
        settle()
        val anchor = compose.runOnIdle {
            fixture.state.layoutInfo.visibleItemsInfo.first { it.index == fixture.state.firstVisibleItemIndex }
        }
        val anchorId = (anchor.key as String).removePrefix("waterfall-").toInt()
        compose.runOnIdle {
            fixture.keyOrder = fixture.keyOrder.filter { it != anchorId }
            fixture.columns = 3
        }
        settle()
        val originalAutoAdvance = compose.mainClock.autoAdvance
        var owner: Job? = null
        var fallbackResolutions = 0
        try {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                owner = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.holder!!.restore(fixture.state, true) { identity ->
                        fallbackResolutions++
                        fixture.keyOrder.indexOfFirst { fixture.itemKey(it) == identity }.takeIf { it >= 0 }
                    }
                }
            }
            for (frame in 0 until 120) {
                if (compose.runOnIdle { fixture.state.layoutInfo.visibleItemsInfo.maxOf { it.index } >= 80 }) break
                compose.mainClock.advanceTimeByFrame()
            }
            compose.runOnIdle {
                assertTrue("The forward scan must still be in progress beyond the old anchor", owner?.isActive == true && fixture.state.canScrollForward)
                assertTrue("The identity must be inserted behind the measured cursor", fixture.state.layoutInfo.visibleItemsInfo.minOf { it.index } > anchor.index)
                fixture.keyOrder = fixture.keyOrder.toMutableList().apply { add(10, anchorId) }
            }
            compose.mainClock.autoAdvance = true
            awaitRestore(fixture)
            compose.runOnIdle {
                val restored = fixture.state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.key }
                assertTrue("Backtracking must restore the game identity instead of another game at the old index", restored?.index == 10)
                checkNotNull(restored)
                assertTrue("Backtracking must preserve the anchor offset", kotlin.math.abs(restored.offset.y - anchor.offset.y) <= 1)
                assertTrue("The identity must be found by measured backtracking, not timeout fallback", fallbackResolutions == 0)
            }
        } finally {
            owner?.cancel()
            compose.mainClock.autoAdvance = originalAutoAdvance
        }
    }

    @Test
    fun finalForwardScanKeepsIdentityThatReappearsBeyondBackwardCursor() = withProduction(
        manualRestore = true,
        uniformCards = true,
    ) { fixture ->
        val newIndex = 320
        compose.runOnIdle { fixture.keyOrder = (0 until 400).toList() }
        settle()
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(30) } }
        settle()
        val anchor = compose.runOnIdle {
            fixture.state.layoutInfo.visibleItemsInfo.first { it.index == fixture.state.firstVisibleItemIndex }
        }
        val anchorId = (anchor.key as String).removePrefix("waterfall-").toInt()
        compose.runOnIdle {
            fixture.keyOrder = fixture.keyOrder.filter { it != anchorId }
            fixture.columns = 3
        }
        settle()
        val originalAutoAdvance = compose.mainClock.autoAdvance
        var owner: Job? = null
        var resolvedAtOldIndex = false
        try {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                owner = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.holder!!.restore(fixture.state, true, scanBudgetMillis = 60_000L) { identity ->
                        val index = fixture.keyOrder.indexOfFirst { fixture.itemKey(it) == identity }
                        if (index == newIndex && fixture.state.layoutInfo.visibleItemsInfo.any { it.index == anchor.index }) {
                            resolvedAtOldIndex = true
                        }
                        index.takeIf { it >= 0 }
                    }
                }
            }
            var passedNewIndex = false
            var reinserted = false
            for (frame in 0 until 240) {
                reinserted = compose.runOnIdle {
                    val visible = fixture.state.layoutInfo.visibleItemsInfo
                    val minIndex = visible.minOf { it.index }
                    val maxIndex = visible.maxOf { it.index }
                    if (maxIndex >= newIndex) passedNewIndex = true
                    if (passedNewIndex && maxIndex < newIndex && minIndex > anchor.index && owner?.isActive == true) {
                        // 已经经过新位置且正在退回顶部；后向扫描不会再见到刚插回的身份。
                        fixture.keyOrder = fixture.keyOrder.toMutableList().apply { add(newIndex, anchorId) }
                        true
                    } else false
                }
                if (reinserted || owner?.isCompleted == true) break
                compose.mainClock.advanceTimeByFrame()
            }
            assertTrue("The identity must be reinserted beyond the backwards cursor", reinserted)
            compose.mainClock.autoAdvance = true
            awaitRestore(fixture)
            compose.runOnIdle {
                val restored = fixture.state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.key }
                assertTrue("Latest identity must be checked before settling on the old index", resolvedAtOldIndex)
                assertTrue("The final scan must restore the same item at its new index", restored?.index == newIndex)
                checkNotNull(restored)
                assertTrue("The final scan must preserve the anchor's viewport offset", kotlin.math.abs(restored.offset.y - anchor.offset.y) <= 1)
            }
        } finally {
            owner?.cancel()
            compose.mainClock.autoAdvance = originalAutoAdvance
        }
    }

    @Test
    fun longUserInputWaitPreservesScanBudgetAndMovedGameIdentity() = withProduction(
        manualRestore = true,
        compositeKeys = true,
    ) { fixture ->
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
        settle()
        val anchor = compose.runOnIdle {
            val item = fixture.state.layoutInfo.visibleItemsInfo.first { it.index == fixture.state.firstVisibleItemIndex }
            Item(item.index, item.key, Position(item.offset.x, item.offset.y))
        }
        val identity = (anchor.key as String).substringBefore('\u0000')
        compose.runOnIdle {
            fixture.coverVersion++
            fixture.keyOrder = (100 until 140).toList() + fixture.keyOrder
            fixture.columns = 3
        }
        settle()
        val capturedKeys = compose.runOnIdle {
            fixture.holder!!.previousItemOffsets.keys.map { (it as String).replace('\u0000', '|') }
        }
        assertTrue("The column boundary must capture the original URI before the dataset replacement: expected=$identity captured=$capturedKeys", capturedKeys.any { it.substringBefore('|') == identity })
        val releaseInput = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        var fallbackResolutions = 0
        lateinit var input: Job
        lateinit var owner: Job
        compose.runOnIdle {
            input = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.state.scroll(MutatePriority.UserInput) { releaseInput.await() }
            }
            owner = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.holder!!.restore(fixture.state, true) { savedIdentity ->
                    fallbackResolutions++
                    fixture.keyOrder.indexOfFirst {
                        fixture.itemKey(it).substringBefore('\u0000') == savedIdentity
                    }.takeIf { it >= 0 }
                }
                returned.complete(Unit)
            }
        }
        try {
            // Compose 虚拟时钟不会推进 SystemClock.uptimeMillis()。
            val waitStarted = SystemClock.uptimeMillis()
            runBlocking { delay(1_600L) }
            compose.runOnIdle {
                assertTrue("Input must hold the mutation beyond the real scan timeout", SystemClock.uptimeMillis() - waitStarted >= 1_500L)
                assertTrue("Long input must leave the original restore owner pending", owner.isActive && !returned.isCompleted && fixture.holder!!.isRestoring)
                assertTrue("Restore must not move the hidden grid during input", !fixture.state.canScrollBackward)
                releaseInput.complete(Unit)
            }
            awaitRestore(fixture)
            compose.runOnIdle {
                val restored = fixture.state.layoutInfo.visibleItemsInfo.firstOrNull {
                    (it.key as String).substringBefore('\u0000') == identity
                }
                assertTrue("The original owner must complete after the long wait", returned.isCompleted && !owner.isCancelled)
                assertTrue("Waiting for input must not spend the scan budget or enter coarse fallback", fallbackResolutions == 0)
                assertTrue("The same game URI must survive the changed cover key and prepended items", restored != null)
                checkNotNull(restored)
                assertTrue("The game must restore at its current index", restored.index == anchor.index + 40)
                assertTrue("The changed cover must produce a new lazy key", restored.key != anchor.key)
                assertTrue("The identity anchor must keep its viewport offset", kotlin.math.abs(restored.offset.y - anchor.position.y) < restored.size.height)
            }
            assertStableUpwardScroll(fixture, "production-long-input-wait")
        } finally {
            input.cancel()
            owner.cancel()
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun interruptedRestoreRescansFromTopAfterUserInputReleases() = withProduction(manualRestore = true) { fixture ->
        compose.runOnIdle { fixture.keyOrder = (0 until 300).toList() }
        settle()
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(220) } }
        settle()
        val anchor = compose.runOnIdle {
            fixture.state.layoutInfo.visibleItemsInfo.first { it.index == fixture.state.firstVisibleItemIndex }
        }
        compose.runOnIdle {
            fixture.keyOrder = (300 until 340).toList() + fixture.keyOrder
            fixture.columns = 3
        }
        settle()
        val originalAutoAdvance = compose.mainClock.autoAdvance
        val releaseInput = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        var owner: Job? = null
        var input: Job? = null
        try {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                owner = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.holder!!.restore(fixture.state, true)
                    returned.complete(Unit)
                }
            }
            for (frame in 0 until 60) {
                if (compose.runOnIdle { fixture.state.canScrollBackward || owner?.isCompleted == true }) break
                compose.mainClock.advanceTimeByFrame()
            }
            compose.runOnIdle {
                val state = fixture.state
                assertTrue("Restore must have measured a partial forward scan", state.canScrollBackward && state.canScrollForward)
                assertTrue("Restore must be interrupted before reaching the original anchor", state.layoutInfo.visibleItemsInfo.maxOf { it.index } < anchor.index + 40)
                assertTrue("Restore must still own the scroll", owner?.isActive == true && state.isScrollInProgress)
                input = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    state.scroll(MutatePriority.UserInput) {
                        // 先移过稳定键及旧数字索引，再允许恢复重试。
                        LazyLayoutScrollScope(state, this).snapToItem(330, 0)
                        releaseInput.await()
                    }
                }
            }
            compose.mainClock.autoAdvance = true
            compose.waitUntil { compose.runOnIdle { fixture.state.firstVisibleItemIndex > anchor.index + 40 } }
            compose.runOnIdle {
                assertTrue("Input must retain the higher-priority mutation", input?.isActive == true)
                assertTrue("Partial scan must stay hidden and pending", fixture.holder!!.isRestoring)
                assertTrue("Scan interruption must not end its owner", owner?.isActive == true && !returned.isCompleted)
                releaseInput.complete(Unit)
            }
            awaitRestore(fixture)
            compose.runOnIdle {
                val restored = fixture.state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.key }
                assertTrue("The same owner must complete the retry", returned.isCompleted && owner?.isCancelled == false)
                assertTrue("The retry must rescan the moved stable key, not fall back to its old index", restored?.index == anchor.index + 40)
                checkNotNull(restored)
                assertTrue("The retry must also recover the original viewport offset", kotlin.math.abs(restored.offset.y - anchor.offset.y) < restored.size.height)
            }
            assertStableUpwardScroll(fixture, "production-interrupted-retry")
        } finally {
            input?.cancel()
            owner?.cancel()
            compose.mainClock.autoAdvance = originalAutoAdvance
        }
    }

    @Test
    fun restoringHostBlocksTouchAndHidesCardSemanticsUntilRevealed() = withProduction(
        manualRestore = true,
        probeInteractions = true,
    ) { fixture ->
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
        settle()
        compose.runOnIdle { fixture.columns = 3 }
        settle()
        val cardCenter = compose.runOnIdle {
            assertTrue("The host must still be restoring during input injection", fixture.holder!!.isRestoring)
            val item = fixture.state.layoutInfo.visibleItemsInfo.first { it.index == 0 }
            Offset(item.offset.x + item.size.width / 2f, item.offset.y + item.size.height / 2f)
        }
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
        val longClickAction = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        compose.onAllNodes(hidden, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasClickAction() or longClickAction).assertCountEquals(0)
        val host = compose.onNodeWithTag(TOUCH_TARGET_TAG)
        host.performTouchInput { click(cardCenter) }
        host.performTouchInput { longClick(cardCenter) }
        host.performTouchInput { swipeUp(startY = height * 0.75f, endY = height * 0.25f) }
        compose.runOnIdle {
            assertTrue("A hidden card must receive neither taps nor long presses", fixture.taps == 0 && fixture.longPresses == 0)
            assertTrue("A hidden host must also block dragging the grid", !fixture.state.canScrollBackward)
            fixture.scope.launch { fixture.holder!!.restore(fixture.state, true) }
        }
        awaitRestore(fixture)
        compose.onAllNodes(hidden, useUnmergedTree = true).assertCountEquals(0)
        val visibleCard = compose.onAllNodes(hasClickAction() and longClickAction)[0]
        visibleCard.performTouchInput { click() }
        visibleCard.performTouchInput { longClick() }
        compose.runOnIdle {
            assertTrue("Revealed cards must receive taps and long presses again", fixture.taps == 1 && fixture.longPresses == 1)
        }
    }

    @Test
    fun restoringHostBlocksFocusAndKeyboardActivationUntilRevealed() = withProduction(
        manualRestore = true,
        probeInteractions = true,
    ) { fixture ->
        val host = compose.onNodeWithTag(TOUCH_TARGET_TAG)
        val inputMode = fixture.inputModeManager
        val originalInputMode = inputMode.inputMode
        try {
            compose.runOnIdle {
                fixture.probeKeyboard = true
                inputMode.requestInputMode(InputMode.Keyboard)
            }
            settle()
            compose.runOnIdle { fixture.cardFocusRequester.requestFocus() }
            host.performKeyInput { pressKey(Key.Enter); pressKey(Key.DirectionCenter) }
            compose.runOnIdle {
                assertTrue("Visible cards must accept both activation keys: taps=${fixture.taps}, focused=${fixture.cardFocused}", fixture.taps == 2 && fixture.cardFocused)
                fixture.holder!!.update(3, true)
                // 组合应用焦点清理前，旧的已聚焦卡片仍挂在树上。
                for (keyCode in intArrayOf(AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_DPAD_CENTER)) {
                    fixture.hostView.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, keyCode))
                    fixture.hostView.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, keyCode))
                }
                assertTrue("Keys arriving before focus cleanup must not activate hidden cards", fixture.taps == 2)
                fixture.columns = 3
            }
            settle()
            compose.runOnIdle {
                assertTrue("Keyboard probe must run while the host is hidden", fixture.holder!!.isRestoring)
                fixture.cardFocusRequester.requestFocus()
            }
            host.performKeyInput { pressKey(Key.Enter); pressKey(Key.DirectionCenter) }
            compose.runOnIdle {
                assertTrue("Hidden cards must neither keep nor acquire focus", !fixture.cardFocused)
                assertTrue("Activation keys must not reach hidden cards", fixture.taps == 2)
                fixture.scope.launch { fixture.holder!!.restore(fixture.state, true) }
            }
            awaitRestore(fixture)
            compose.runOnIdle { fixture.cardFocusRequester.requestFocus() }
            host.performKeyInput { pressKey(Key.Enter); pressKey(Key.DirectionCenter) }
            compose.runOnIdle {
                assertTrue("Revealed cards must regain focus and keyboard activation", fixture.taps == 4 && fixture.cardFocused)
                // 先前未聚焦时的按键可能已经激活外部同级控件。
                fixture.externalTaps = 0
                fixture.externalFocusRequester.requestFocus()
                fixture.columns = 2
            }
            settle()
            compose.runOnIdle {
                assertTrue("External focus must already be retained while the viewport is hidden", fixture.holder!!.isRestoring && fixture.externalFocused)
            }
            host.performKeyInput { pressKey(Key.Enter); pressKey(Key.DirectionCenter) }
            compose.runOnIdle {
                assertTrue("Restoration must preserve an external control's focus and activation: externalFocused=${fixture.externalFocused}, externalTaps=${fixture.externalTaps}, cardTaps=${fixture.taps}", fixture.externalFocused && fixture.externalTaps == 2 && fixture.taps == 4)
                fixture.scope.launch { fixture.holder!!.restore(fixture.state, true) }
            }
            awaitRestore(fixture)
            compose.runOnIdle { assertTrue("Revealing the grid must not steal external focus", fixture.externalFocused) }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { inputMode.requestInputMode(originalInputMode) }
        }
    }

    @Test
    fun ownerCancellationKeepsPendingRestorationForItsSuccessor() = withProduction(manualRestore = true) { fixture ->
        compose.runOnIdle { fixture.scope.launch { fixture.state.scrollToItem(50) } }
        settle()
        compose.runOnIdle { fixture.columns = 3 }
        settle()
        var owner: Job? = null
        var blocker: Job? = null
        val releaseScroll = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        try {
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
                assertTrue("Restoration must be waiting for the scroll lock", owner?.isActive == true && !returned.isCompleted)
                owner?.cancel()
            }
            compose.waitUntil { owner?.isCompleted == true }
            compose.runOnIdle {
                assertTrue("Cancelled effect must propagate cancellation", owner?.isCancelled == true && !returned.isCompleted)
                assertTrue("Cancelled effect must not clear the pending anchor", fixture.holder!!.isRestoring)
            }
        } finally {
            releaseScroll.complete(Unit)
            blocker?.cancel()
            owner?.cancel()
        }
        settle()
        compose.runOnIdle { fixture.scope.launch { fixture.holder!!.restore(fixture.state, true) } }
        awaitRestore(fixture)
        compose.runOnIdle {
            assertTrue("The replacement effect must restore the original anchor", fixture.state.layoutInfo.visibleItemsInfo.any { it.key == "waterfall-50" })
        }
    }

    private fun withProduction(
        manualRestore: Boolean = false,
        compositeKeys: Boolean = false,
        probeInteractions: Boolean = false,
        uniformCards: Boolean = false,
        check: (Fixture) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = MotionMode.enhanced
        val originalAutoAdvance = compose.mainClock.autoAdvance
        try {
            MotionDeviceAcceptance.require()
            val fixture = Fixture(
                freshState = false,
                buildHistory = false,
                production = true,
                manualRestore = manualRestore,
                compositeKeys = compositeKeys,
                probeInteractions = probeInteractions,
                uniformCards = uniformCards,
            )
            val restoration = StateRestorationTester(compose)
            fixture.restoreSavedState = { restoration.emulateSavedInstanceStateRestore() }
            restoration.setContent {
                if (probeInteractions) {
                    Column {
                        if (fixture.probeKeyboard) {
                            Box(Modifier.size(48.dp)
                                .focusRequester(fixture.externalFocusRequester)
                                .onFocusChanged { fixture.externalFocused = it.isFocused }
                                .combinedClickable(onClick = { fixture.externalTaps++ }))
                        }
                        Box(Modifier.testTag(TOUCH_TARGET_TAG)) { Waterfall(fixture) }
                    }
                } else {
                    Waterfall(fixture)
                }
            }
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
                    // 公共滚动作用域的 scrollBy 同步重测量，此处不挂起也不跳过历史。
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
            deltas.forEach { (key, dy) ->
                if (kotlin.math.abs(dy + consumed) > 1f) {
                    violations += "$transition step=$step key=$key dy=$dy expectedDy=${-consumed} consumed=$consumed residual=${dy + consumed}"
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
        fixture.hostView = LocalView.current
        fixture.inputModeManager = LocalInputModeManager.current
        // 与游戏网格一致：旧懒布局闭包保留其组合时的列表和封面键。
        val keyOrder = fixture.keyOrder
        val coverVersion = fixture.coverVersion
        val compositeKeys = fixture.compositeKeys
        fun itemKey(id: Int): String = if (compositeKeys) "waterfall-$id\u0000cover-$coverVersion\u0000fixture" else "waterfall-$id"
        val columns = fixture.columns
        val holder = if (fixture.manualRestore) {
            remember {
                WaterfallReflowState.create(2, false) { (it as String).substringBefore('\u0000') }
            }.also { holder ->
                Snapshot.withoutReadObservation { holder.update(columns, columns == 3) }
            }
        } else if (fixture.production) rememberWaterfallReflowState(
            columns, columns == 3,
            enabled = { fixture.enabled },
            itemIdentity = { (it as String).substringBefore('\u0000') },
            itemIndex = { identity ->
                fixture.keyOrder.indexOfFirst {
                    fixture.itemKey(it).substringBefore('\u0000') == identity
                }.takeIf { it >= 0 }
            },
        ) else null
        SideEffect { fixture.compositions++ }
        fixture.holder = holder
        val layoutColumns = holder?.layoutColumns ?: columns
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
            columns = layoutColumns,
            landscape = holder?.layoutLandscape ?: (columns == 3),
            gridState = state,
            ready = holder?.isRestoring != true,
        )
        // 更换状态时保留懒布局节点，不使用 key(columns) 包装。
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(layoutColumns),
            state = state,
            contentPadding = PaddingValues(bottom = fixture.bottomPaddingDp.dp),
            modifier = Modifier.size(240.dp, 350.dp)
                .then(if (holder != null) Modifier.waterfallReflowHost(holder) else Modifier)
                .gridReflowHost(reflow),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
        ) {
            items(count = keyOrder.size, key = { itemKey(keyOrder[it]) }) { index ->
                val id = keyOrder[index]
                val coverRatio = if (fixture.uniformCards) 0.70f else 0.60f + (id % 5) * 0.05f
                Column(
                    listItemMotion(ListItemPlacement.Reflow)
                        .staggeredEntrance(index, stepMillis = 0L, reflow = reflow, key = itemKey(id))
                        .fillMaxWidth()
                        .then(
                            if (fixture.probeInteractions) {
                                val focusProbe = if (id == 0) Modifier
                                    .focusRequester(fixture.cardFocusRequester)
                                    .onFocusChanged { fixture.cardFocused = it.isFocused }
                                else Modifier
                                focusProbe.combinedClickable(
                                    onClick = { fixture.taps++ },
                                    onLongClick = { fixture.longPresses++ },
                                )
                            } else Modifier,
                        ),
                ) {
                    Box(Modifier.fillMaxWidth()
                        .then(if (fixture.tallLastCard && id == 99) Modifier.height(1_400.dp) else Modifier.aspectRatio(coverRatio))
                        .background(Color(0xFF00897B)))
                    Box(Modifier.fillMaxWidth().height((if (fixture.uniformCards) 24 else 18 + id % 4 * 9).dp).background(Color(0xFF263238)))
                }
            }
        }
    }

    private class Fixture(
        val freshState: Boolean,
        val buildHistory: Boolean,
        val production: Boolean = false,
        val manualRestore: Boolean = false,
        val compositeKeys: Boolean = false,
        val probeInteractions: Boolean = false,
        val uniformCards: Boolean = false,
    ) {
        var enabled by mutableStateOf(true)
        var tallLastCard by mutableStateOf(false)
        var bottomPaddingDp by mutableIntStateOf(0)
        var compositions = 0
        lateinit var restoreSavedState: () -> Unit
        var taps = 0
        var longPresses = 0
        val cardFocusRequester = FocusRequester()
        var cardFocused = false
        val externalFocusRequester = FocusRequester()
        var externalFocused = false
        var externalTaps = 0
        var probeKeyboard by mutableStateOf(false)
        lateinit var hostView: View
        lateinit var inputModeManager: InputModeManager
        var coverVersion by mutableIntStateOf(0)
        fun itemKey(id: Int): String = if (compositeKeys) "waterfall-$id\u0000cover-$coverVersion\u0000fixture" else "waterfall-$id"
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
        private const val TOUCH_TARGET_TAG = "waterfall-touch-target"
        private const val MAX_STEPS = 800
    }
}
