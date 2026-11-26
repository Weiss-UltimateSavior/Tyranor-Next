package com.tyranor.next.ui.game

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.zIndex
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.GameLibraryConditions
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.LocalMainOverlayHost
import com.tyranor.next.ui.common.MainOverlayHost
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.shareTouchesWithSiblings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ShelfCatalogGestureDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val games = List(180) { index ->
        ScanGame(
            title = "Gesture book $index",
            uri = "fixture://shelf-gesture/$index",
            engine = EngineType.KIRIKIRI,
            launchTarget = "fixture-$index",
        )
    }
    private val picks = mutableListOf<String>()

    @Before
    fun requireIsolatedTarget() = MotionDeviceAcceptance.require()

    @Test
    fun diagonalDragPeeksBeforeReleaseWithoutScrollingOrPicking() {
        showCatalog()
        val first = spine(0).fetchSemanticsNode().boundsInRoot
        val second = spine(1).fetchSemanticsNode().boundsInRoot
        val distance = second.center.x - first.center.x
        val restingGap = secondToThirdSpacing()
        val scrollBefore = wallScroll()

        compose.mainClock.autoAdvance = false
        try {
            compose.onRoot().performTouchInput {
                down(first.center)
                repeat(5) { moveBy(Offset(distance / 5f, -distance * 1.25f / 5f)) }
            }
            try {
                compose.mainClock.advanceTimeBy(144)
                assertTrue(
                    "The book under the moving finger must make room for its front cover",
                    secondToThirdSpacing() > restingGap + second.width * 0.25f,
                )
                assertEquals("Peeking must not scroll the wall", scrollBefore, wallScroll(), 0f)
            } finally {
                compose.onRoot().performTouchInput { up() }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertEquals("The cover must fold back on release", restingGap, secondToThirdSpacing(), 1f)
        compose.runOnIdle { assertTrue("Peeking must not select a book", picks.isEmpty()) }
    }

    @Test
    fun nearVerticalDragFromASpineScrollsTheWallWithoutPicking() {
        showCatalog()
        val scrollBefore = wallScroll()
        val start = spine(1).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(start.center)
            repeat(8) { moveBy(Offset(start.height * 0.005f, -start.height * 0.055f)) }
            up()
        }
        assertTrue("A near-vertical drag on a book must scroll the list", wallScroll() > scrollBefore)
        compose.runOnIdle { assertTrue("Scrolling must not select a book", picks.isEmpty()) }
    }

    @Test
    fun tapPicksOnlyTheTouchedSpine() {
        showCatalog()
        spine(1).performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(games[1].uri), picks) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun closingBookLetsOneLongPressThroughToTheShelf() {
        var closing by mutableStateOf(false)
        var bookClicks = 0
        var shelfClicks = 0
        var shelfLongPresses = 0
        compose.setContent {
            TyranorNextTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize().combinedClickable(
                            onLongClick = { shelfLongPresses++ },
                            onClick = { shelfClicks++ },
                        ),
                    )
                    Box(Modifier.fillMaxSize().then(if (closing) Modifier.shareTouchesWithSiblings() else Modifier)) {
                        // The exiting wall still has pointer nodes even after its book stops handling clicks.
                        Box(
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent() }
                            }.catalogBookClick(closing) { bookClicks++ },
                        )
                    }
                }
            }
        }

        compose.onRoot().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, bookClicks)
            assertEquals(0, shelfClicks)
            closing = true
        }
        compose.onRoot().performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals("The first long press during exit must reach the shelf exactly once", 1, shelfLongPresses)
            assertEquals("Releasing a long press must not tap the shelf", 0, shelfClicks)
            assertEquals("The exiting book must not be selected", 1, bookClicks)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun productionClosingOverlayLetsLongPressReopenCatalogOnce() {
        val stage = ShelfCatalogStage()
        val session = ShelfCatalogSessionState(initialOpen = true, initialSequence = 1L)
        val overlayHost = MainOverlayHost()
        var anchorUri by mutableStateOf(games.first().uri)
        var shelfLongPresses = 0
        var shelfClicks = 0
        var reopenCount = 0

        compose.setContent {
            TyranorNextTheme {
                CompositionLocalProvider(LocalMainOverlayHost provides overlayHost) {
                    Box(Modifier.fillMaxSize()) {
                        // 模拟书架容器：按下时追踪手指、触发 interruptExit；长按时走与 GameScreen 相同的会话重开链路。
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(stage) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val pressed = event.changes.any { it.pressed }
                                            stage.onShelfTouch(pressed)
                                            if (pressed) stage.interruptExit()
                                        }
                                    }
                                }
                                .combinedClickable(
                                    onLongClick = {
                                        shelfLongPresses++
                                        val targetUri = games[2].uri
                                        if (session.requestOpen(targetUri)) {
                                            anchorUri = targetUri
                                        } else {
                                            stage.reopenRequested = true
                                            stage.flushDeferredDismiss()
                                        }
                                    },
                                    onClick = { shelfClicks++ },
                                ),
                        )

                        if (session.isOpen) {
                            val openSequence = session.openSequence
                            key(openSequence) {
                                val anchor = remember(anchorUri) {
                                    games.firstOrNull { it.uri == anchorUri } ?: games.first()
                                }
                                ShelfCatalogOverlay(
                                    allGames = games,
                                    filteredGames = games,
                                    anchorGame = anchor,
                                    currentFocusGame = anchor,
                                    stage = stage,
                                    query = "",
                                    conditions = GameLibraryConditions(),
                                    onQueryChange = {},
                                    onOpenFilter = {},
                                    onResetAll = {},
                                    onPickGame = { picked ->
                                        picks.add(picked.uri)
                                        0
                                    },
                                    onDismiss = {
                                        session.dismiss(openSequence)?.let { reopenAnchor ->
                                            reopenCount++
                                            anchorUri = reopenAnchor
                                        }
                                        stage.reopenRequested = false
                                    },
                                )
                            }
                        }

                        // 与 MainScreen 相同的宿主渲染层：不手工安装 shareTouchesWithSiblings，完全由生产 MainOverlayShareTouches 驱动。
                        if (overlayHost.active) {
                            overlayHost.stack.forEach { entry ->
                                key(entry.id) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .zIndex(10f)
                                            .then(if (entry.sharesTouches) Modifier.shareTouchesWithSiblings() else Modifier),
                                    ) {
                                        entry.Content()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) { stage.recedeProgress == 1f }
        compose.waitForIdle()
        spine(1).assertIsDisplayed()

        // 1. 冻结自动推进：点选一本书启动真实生产退场，确认覆盖层仍挂载且已经由 MainOverlayShareTouches 共享触摸
        val holdPoint = spine(1).fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        try {
            spine(1).performTouchInput { click() }
            compose.mainClock.advanceTimeBy(32)
            assertTrue("Production closing overlay must remain mounted and share touches", overlayHost.stack.any { it.sharesTouches })
            assertTrue("Catalog session must still be open while exiting", session.isOpen && session.openSequence == 1L)

            // 2. 退场途中用真实触摸按住下层书架：退场被 interruptExit 加速收尾但因手指按住而挂起，不得提前移除覆盖层取消长按
            compose.onRoot().performTouchInput { down(holdPoint) }
            try {
                compose.mainClock.advanceTimeBy(250)
                assertTrue("Overlay must stay mounted while finger holds the shelf", overlayHost.active)
                assertEquals("Long press must not fire prematurely", 0, shelfLongPresses)

                // 3. 继续按住越过长按阈值：书架长按成立 -> 登记重开 -> flushDeferredDismiss 移除旧覆盖层并重开新实例
                compose.mainClock.advanceTimeBy(450)
            } finally {
                compose.onRoot().performTouchInput { up() }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            session.isOpen && session.openSequence == 2L && stage.recedeProgress == 1f
        }
        compose.waitForIdle()
        spine(0).assertIsDisplayed()

        compose.runOnIdle {
            assertEquals("Long press during production exit must trigger shelf long-press once", 1, shelfLongPresses)
            assertEquals("Production exit + deferred dismiss must reopen catalog exactly once", 1, reopenCount)
            assertEquals(2L, session.openSequence)
            assertEquals(games[2].uri, anchorUri)
            assertEquals("Releasing a long press must not click the shelf", 0, shelfClicks)
            assertEquals(listOf(games[1].uri), picks)
        }
    }

    private fun showCatalog() {
        val stage = ShelfCatalogStage()
        compose.setContent {
            TyranorNextTheme {
                ShelfCatalogOverlay(
                    allGames = games,
                    filteredGames = games,
                    anchorGame = games.first(),
                    currentFocusGame = games.first(),
                    stage = stage,
                    query = "",
                    conditions = GameLibraryConditions(),
                    onQueryChange = {},
                    onOpenFilter = {},
                    onResetAll = {},
                    onPickGame = { picked ->
                        picks.add(picked.uri)
                        0
                    },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { stage.recedeProgress == 1f }
        compose.waitForIdle()
        spine(0).assertIsDisplayed()
    }

    private fun spine(index: Int) = compose.onNodeWithContentDescription(games[index].title)

    private fun secondToThirdSpacing(): Float =
        spine(2).fetchSemanticsNode().boundsInRoot.left - spine(1).fetchSemanticsNode().boundsInRoot.left

    private fun wallScroll(): Float {
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return compose.runOnIdle { range.value() }
    }
}
