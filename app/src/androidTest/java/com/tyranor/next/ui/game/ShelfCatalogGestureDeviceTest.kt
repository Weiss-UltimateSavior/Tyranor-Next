package com.tyranor.next.ui.game

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.core.app.ActivityOptionsCompat
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleFileImportSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleFileImporter
import com.tyranor.next.core.game.model.GameLibraryConditions
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.LocalAppDialogHost
import com.tyranor.next.ui.common.LocalMainOverlayHost
import com.tyranor.next.ui.common.MainOverlayHost
import com.tyranor.next.ui.common.MainOverlayLayer
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.shareTouchesWithSiblings
import com.tyranor.next.ui.runtime.rememberRuntimeModuleFileImport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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

    @Test
    fun productionClosingOverlayLetsLongPressReopenCatalogOnce() {
        val stage = ShelfCatalogStage()
        val session = ShelfCatalogSessionState()
        val overlayHost = MainOverlayHost()
        var shelfLongPresses = 0
        var shelfClicks = 0
        var reopenCount = 0

        compose.setContent {
            TyranorNextTheme {
                CompositionLocalProvider(LocalMainOverlayHost provides overlayHost) {
                    Box(Modifier.fillMaxSize()) {
                        // 直接使用生产 GameShelf（含真实 shelfTouchIntent -> onShelfTouch / interruptExit 与长按）
                        GameShelf(
                            games = games,
                            isInteractive = { true },
                            onGameClick = { shelfClicks++ },
                            onGameLongClick = { target ->
                                shelfLongPresses++
                                if (!session.requestOpen(target.uri)) {
                                    stage.reopenRequested = true
                                    stage.flushDeferredDismiss()
                                }
                            },
                            catalog = stage,
                        )

                        if (session.isOpen) {
                            val openSequence = session.openSequence
                            val activeAnchorUri = session.anchorUri
                            key(openSequence) {
                                val anchor = remember(activeAnchorUri) {
                                    activeAnchorUri?.let { uri -> games.firstOrNull { it.uri == uri } } ?: games.first()
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
                                        stage.requestFocus(picked.uri)
                                    },
                                    onDismiss = {
                                        if (session.dismiss(openSequence) != null) {
                                            reopenCount++
                                        }
                                        stage.reopenRequested = false
                                    },
                                )
                            }
                        }

                        // 直接复用 MainScreen 同款生产宿主层，不在测试里手工安装 shareTouchesWithSiblings()
                        MainOverlayLayer(overlayHost)
                    }
                }
            }
        }

        // 1. 通过真实 GameShelf 长按首次进入藏书阁
        compose.waitUntil(timeoutMillis = 5_000) { stage.visibleOrigins().any { it.isHero } }
        val heroPoint = stage.visibleOrigins().first { it.isHero }.frontRect.center
        compose.onRoot().performTouchInput { longClick(heroPoint) }
        compose.waitUntil(timeoutMillis = 5_000) {
            session.isOpen && session.openSequence == 1L && stage.recedeProgress == 1f
        }
        compose.waitForIdle()
        val overlaySpine1 = compose.onAllNodesWithContentDescription(games[1].title).onLast()
        overlaySpine1.assertIsDisplayed()

        // 2. 冻结帧时钟：点选一本书启动真实退场，确认生产 MainOverlayLayer 已开启触摸共享
        compose.mainClock.autoAdvance = false
        try {
            overlaySpine1.performTouchInput { click() }
            compose.mainClock.advanceTimeBy(32)
            assertTrue("Production MainOverlayLayer must remain mounted and share touches", overlayHost.stack.any { it.sharesTouches })
            assertTrue("Catalog session must still be open while exiting", session.isOpen && session.openSequence == 1L)

            // 3. 退场途中用真实触摸按住下层 GameShelf 英雄封面：退场加速但挂起在 deferredDismiss，不取消下层长按
            compose.onRoot().performTouchInput { down(heroPoint) }
            try {
                compose.mainClock.advanceTimeBy(250)
                assertTrue("Overlay must stay mounted while finger holds GameShelf", overlayHost.active)
                assertEquals("Second long press must not fire prematurely", 1, shelfLongPresses)

                // 4. 继续按住越过长按阈值：GameShelf 触发长按 -> 登记重开 -> flushDeferredDismiss 移除旧覆盖层并重开新实例
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
        compose.onAllNodesWithContentDescription(games[0].title).onLast().assertIsDisplayed()

        compose.runOnIdle {
            assertEquals("Initial open + exit long press must trigger GameShelf long-press twice", 2, shelfLongPresses)
            assertEquals("Production exit + deferred dismiss must reopen catalog exactly once", 1, reopenCount)
            assertEquals(2L, session.openSequence)
            assertEquals("Releasing a long press must not click the shelf", 0, shelfClicks)
            assertEquals(listOf(games[1].uri), picks)
        }
    }

    @Test
    fun recreatingHostDuringExitPromotesPendingReopenAnchorAutomatically() {
        val restorationTester = StateRestorationTester(compose)
        var latestSession: ShelfCatalogSessionState? = null
        var observedAnchorUri: String? = null

        restorationTester.setContent {
            TyranorNextTheme {
                val overlayHost = remember { MainOverlayHost() }
                val stage = remember { ShelfCatalogStage() }
                val session = rememberShelfCatalogSessionState()
                latestSession = session
                CompositionLocalProvider(LocalMainOverlayHost provides overlayHost) {
                    Box(Modifier.fillMaxSize()) {
                        if (session.isOpen) {
                            val openSequence = session.openSequence
                            val activeAnchorUri = session.anchorUri
                            key(openSequence) {
                                val anchor = remember(activeAnchorUri) {
                                    activeAnchorUri?.let { uri -> games.firstOrNull { it.uri == uri } } ?: games.first()
                                }
                                observedAnchorUri = anchor.uri
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
                                    onPickGame = { 0 },
                                    onDismiss = { session.dismiss(openSequence) },
                                )
                            }
                        }
                        MainOverlayLayer(overlayHost)
                    }
                }
            }
        }

        // 1. 打开锚点 A，随后登记退场重开锚点 B（不调用 dismiss）
        compose.runOnIdle {
            val session = latestSession!!
            assertTrue(session.requestOpen(games[0].uri))
            assertEquals(games[0].uri, session.anchorUri)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val session = latestSession!!
            assertEquals(false, session.requestOpen(games[5].uri))
            assertEquals(games[5].uri, session.pendingAnchorUri)
            assertEquals(1L, session.openSequence)
        }

        // 2. 模拟宿主配置变更 / 进程重建：不手动调用 dismiss，恢复后新锚点 B 应自动生效
        restorationTester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.runOnIdle {
            val restored = latestSession!!
            assertTrue("Catalog must stay open after recreation", restored.isOpen)
            assertEquals("Restored session must advance sequence for the promoted reopen", 2L, restored.openSequence)
            assertEquals("Pending anchor B must automatically become active after recreation", games[5].uri, restored.anchorUri)
            assertNull("Promoted pending anchor must be consumed on restore", restored.pendingAnchorUri)
            assertEquals("ShelfCatalogOverlay must open with promoted anchor B", games[5].uri, observedAnchorUri)
        }
    }

    @Test
    fun bookLeafRuntimeFileImportSurvivesHostRecreationAndDeliversOnce() {
        val restorationTester = StateRestorationTester(compose)
        val runtimeGame = ScanGame(
            title = "Runtime Book Game",
            uri = "fixture://runtime-book/0",
            engine = EngineType.RUNTIME_MODULE,
            launchTarget = "entry",
            runtimeModulePackage = "com.example.runtime.fixture",
        )
        val importSpec = RuntimeModuleFileImportSpec(
            key = "patch",
            title = "Import Patch",
            directory = "patch",
            fileName = "patch.xp3",
            multiple = false,
        )
        val pickedUri = Uri.parse("content://fixture/patch.xp3")

        var launchedRequestCode: Int? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                // 拦截系统文件选择器，暂不立即返回结果，留待宿主重建后再派发
                launchedRequestCode = requestCode
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }

        val delivered = mutableListOf<Pair<RuntimeModuleFileImportSpec, List<Uri>>>()
        val observedLeaves = mutableListOf<ShelfBookLeaf>()
        var latestPickAction: ((RuntimeModuleFileImportSpec) -> Unit)? = null

        restorationTester.setContent {
            TyranorNextTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    val pages = rememberShelfBookPages()
                    CompositionLocalProvider(LocalAppDialogHost provides pages) {
                        // 与 RuntimeModuleSettingsView 一致：rememberRuntimeModuleFileImport 位于 key(leaf) 之外的稳定路径
                        val pickFiles = rememberRuntimeModuleFileImport(
                            game = runtimeGame,
                            prepareImport = { _, _, spec, sources ->
                                delivered += spec to sources
                                RuntimeModuleFileImporter.Preparation.Unavailable
                            },
                        )
                        latestPickAction = pickFiles
                        AppAlertDialog(
                            onDismissRequest = {},
                            title = { Text("Runtime Settings") },
                            text = { Text("Leaf body") },
                            confirmButton = {},
                        )
                        // 模拟 ShelfBook.kt 的 key(leaf) 渲染层：每次宿主重建后 ShelfBookLeaf 都是全新对象
                        pages.leaves.forEach { leaf ->
                            key(leaf) {
                                if (leaf !in observedLeaves) observedLeaves += leaf
                                leaf.spec?.text?.invoke()
                            }
                        }
                    }
                }
            }
        }

        compose.waitForIdle()
        assertEquals(1, observedLeaves.size)
        val leafBeforeRecreation = observedLeaves.first()

        // 1. 发起文件选择（系统选择器挂起中）
        compose.runOnIdle {
            latestPickAction!!.invoke(importSpec)
        }
        compose.waitForIdle()
        val requestCode = launchedRequestCode!!

        // 2. 在文件选择器返回前重建宿主：ShelfBookPages 与 ShelfBookLeaf 均为新实例
        restorationTester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("Recreation must allocate a fresh ShelfBookLeaf instance", observedLeaves.size >= 2)
        assertNotSame(leafBeforeRecreation, observedLeaves.last())

        // 3. 选择器返回选中文件：验证恢复的 picking 生效且只交付一次
        compose.runOnUiThread {
            registry.dispatchResult(requestCode, pickedUri)
        }
        compose.waitUntil(timeoutMillis = 3_000) { delivered.size == 1 }
        compose.waitForIdle()

        assertEquals("Selected file must be delivered exactly once after host recreation", 1, delivered.size)
        assertEquals(importSpec, delivered.single().first)
        assertEquals(listOf(pickedUri), delivered.single().second)
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
