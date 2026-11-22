package com.tyranor.next.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSheetController
import com.tyranor.next.ui.common.motion.rememberMotionSheetController
import com.tyranor.next.ui.game.GameFilterSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the real window hosts under the isolated, explicitly opted-in motion target. */
@OptIn(ExperimentalMaterial3Api::class)
class AppBottomSheetDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var originalMotion: Boolean? = null

    @Before fun requireIsolatedTarget() {
        MotionDeviceAcceptance.require(enhanced = null)
        compose.runOnUiThread { originalMotion = MotionMode.enhanced }
    }

    @After fun restoreMotion() {
        originalMotion?.let { compose.runOnUiThread { MotionMode.sync(it) } }
    }

    @Test fun enhancedFilterDropdownConsumesBackBeforeSheet() = filterDropdownConsumesBack(true)
    @Test fun ordinaryFilterDropdownConsumesBackBeforeSheet() = filterDropdownConsumesBack(false)
    @Test fun enhancedGuardBlocksBackAndScrimUntilReleased() = guardBlocksDismissal(true)
    @Test fun ordinaryGuardBlocksBackAndScrimUntilReleased() = guardBlocksDismissal(false)
    @Test fun ordinaryGrowthDuringOpeningKeepsBottomActionVisibleAndClickable() =
        growthDuringOrdinaryOpening()
    @Test fun enhancedGrowthDuringClosingThenReopenKeepsBottomActionVisibleAndClickable() =
        growthDuringEnhancedCloseThenReopen()
    @Test fun ordinaryUnchangedHeightControlKeepsBottomActionAligned() =
        unchangedHeightControl(enhanced = false)
    @Test fun enhancedUnchangedHeightControlKeepsBottomActionAligned() =
        unchangedHeightControl(enhanced = true)

    @Test fun columnSliderAppearsOnlyForGridStylesAndReportsChanges() {
        compose.runOnUiThread { MotionMode.sync(false) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val slider = compose.onNodeWithContentDescription(context.getString(R.string.game_filter_card_columns))
        // 列数按「风格 + 方向」各自保存，测试只动当前方向的那一对键，结束时恢复原值。
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        fun gridColumns() = if (landscape) {
            AppSettingsStore.getGameGridLandscapeColumns(context)
        } else {
            AppSettingsStore.getGameGridPortraitColumns(context)
        }
        fun waterfallColumns() = if (landscape) {
            AppSettingsStore.getGameWaterfallLandscapeColumns(context)
        } else {
            AppSettingsStore.getGameWaterfallPortraitColumns(context)
        }
        fun setGrid(columns: Int) = if (landscape) {
            AppSettingsStore.setGameGridLandscapeColumns(context, columns)
        } else {
            AppSettingsStore.setGameGridPortraitColumns(context, columns)
        }
        fun setWaterfall(columns: Int) = if (landscape) {
            AppSettingsStore.setGameWaterfallLandscapeColumns(context, columns)
        } else {
            AppSettingsStore.setGameWaterfallPortraitColumns(context, columns)
        }
        val originalStyle = AppSettingsStore.getGameCardStyle(context)
        val originalGrid = gridColumns()
        val originalWaterfall = waterfallColumns()
        try {
            compose.runOnUiThread {
                AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW)
                setGrid(3)
                setWaterfall(3)
            }
            compose.setContent {
                TyranorNextTheme {
                    GameFilterSheet(
                        sortMode = AppSettingsStore.GAME_SORT_ALPHA,
                        engineFilter = AppSettingsStore.GAME_FILTER_ENGINE_ALL,
                        playedFilter = AppSettingsStore.GAME_FILTER_PLAYED_ALL,
                        availableEngines = listOf(EngineType.UNKNOWN),
                        searchQuery = "",
                        onSortChange = {}, onEngineChange = {}, onPlayedChange = {},
                        onReset = {}, onDismiss = {},
                    )
                }
            }
            compose.onNodeWithText(context.getString(R.string.game_filter_title)).assertIsDisplayed()
            slider.assertDoesNotExist()
            compose.runOnUiThread { AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_WATERFALL) }
            slider.performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
            compose.runOnIdle { assertEquals(8, waterfallColumns()) }
            compose.runOnUiThread { AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_GRID) }
            slider.assertIsDisplayed().performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
            slider.assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.game_filter_card_columns_value, 5),
            ))
            compose.runOnIdle {
                assertEquals(5, gridColumns())
                // 网格与瀑布各存一份：改网格不影响瀑布已选列数
                assertEquals(8, waterfallColumns())
            }
            // SetProgress 走的是「改值即完成」，测不到草稿语义；真实拖动必须松手才落盘。
            compose.runOnUiThread { AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_WATERFALL) }
            slider.performScrollTo()
            slider.performTouchInput {
                down(center)
                moveTo(Offset(centerLeft.x + 2f, center.y))
            }
            compose.runOnIdle {
                // 拖动中只更新草稿与读数，已保存的列数保持原值。
                assertEquals(8, waterfallColumns())
            }
            slider.performTouchInput { up() }
            compose.runOnIdle {
                // 松手才提交，并且落到滑条最小值。
                assertEquals(AppSettingsStore.MIN_GAME_CARD_COLUMNS, waterfallColumns())
            }
            compose.runOnUiThread { AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW) }
            slider.assertDoesNotExist()
        } finally {
            compose.runOnUiThread {
                setGrid(originalGrid)
                setWaterfall(originalWaterfall)
                AppSettingsStore.setGameCardStyle(context, originalStyle)
            }
        }
    }

    private fun filterDropdownConsumesBack(enhanced: Boolean) {
        compose.runOnUiThread { MotionMode.sync(enhanced) }
        // The enhanced popup intentionally samples its follow spring every frame while open.
        // Drive a finite interval instead of asking autoAdvance to drain that live frame loop.
        // Semantics queries still wait for recomposition/layout with the clock paused, so a
        // frame-independent recomposition loop remains a real failure.
        compose.mainClock.autoAdvance = false
        var visible by mutableStateOf(true)
        var dismissals = 0
        try {
            compose.setContent {
                TyranorNextTheme {
                    if (visible) {
                        GameFilterSheet(
                            sortMode = AppSettingsStore.GAME_SORT_ALPHA,
                            engineFilter = AppSettingsStore.GAME_FILTER_ENGINE_ALL,
                            playedFilter = AppSettingsStore.GAME_FILTER_PLAYED_ALL,
                            availableEngines = listOf(EngineType.UNKNOWN),
                            searchQuery = "",
                            onSortChange = {}, onEngineChange = {}, onPlayedChange = {}, onReset = {},
                            onDismiss = { dismissals++; visible = false },
                        )
                    }
                }
            }
            fun windowCount() = compose.onAllNodes(isDialog()).fetchSemanticsNodes().size
            fun awaitWindows(expected: Int) {
                compose.waitUntil(5_000) {
                    compose.mainClock.advanceTimeBy(100)
                    windowCount() == expected
                }
                compose.waitForIdle()
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val title = context.getString(R.string.game_filter_title)
            val sortLabel = context.getString(R.string.game_filter_sort_section)
            // A platform Dialog can complete its first measure after a single virtual-clock
            // advance. MotionSheetEffects starts only once that measured height is positive.
            // Wait for the real title to enter the visible window while advancing finite frames.
            var previousTitleBounds: Rect? = null
            var stableSamples = 0
            try {
                compose.waitUntil(5_000) {
                    compose.mainClock.advanceTimeBy(100)
                    try {
                        val titleNode = compose.onNodeWithText(title).assertIsDisplayed()
                        compose.onNodeWithText(sortLabel).assertIsDisplayed()
                        val measuredBounds = titleNode.fetchSemanticsNode().boundsInRoot
                        stableSamples = if (measuredBounds == previousTitleBounds) stableSamples + 1 else 0
                        previousTitleBounds = measuredBounds
                        stableSamples >= 2
                    } catch (_: AssertionError) {
                        previousTitleBounds = null
                        stableSamples = 0
                        false
                    }
                }
            } catch (failure: Throwable) {
                // Retain evidence if this is a real short-window layout defect rather than
                // first-measure timing; do not replace the visibility assertion or its timeout.
                runCatching {
                    val windows = compose.onAllNodes(isDialog()).fetchSemanticsNodes()
                    val titleNodes = compose.onAllNodesWithText(title).fetchSemanticsNodes()
                    Log.e("AppBottomSheetTest", "clock=${compose.mainClock.currentTime}, " +
                        "windows=${windows.map { it.boundsInRoot }}, " +
                        "title=${titleNodes.map { it.boundsInRoot }}, " +
                        "screen=${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
                    val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    if (screenshot != null) {
                        val evidence = File(context.externalCacheDir ?: context.cacheDir,
                            "filter-sheet-${if (enhanced) "enhanced" else "ordinary"}-not-displayed.png")
                        evidence.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        screenshot.recycle()
                        Log.e("AppBottomSheetTest", "Visibility failure screenshot: ${evidence.absolutePath}")
                    }
                }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
            compose.onNodeWithText(title).assertIsDisplayed()
            val sheetWindows = windowCount()
            compose.onNodeWithText(sortLabel).performClick()
            awaitWindows(sheetWindows + 1)
            // Verify a settled real popup window remains responsive after its entry spring.
            compose.mainClock.advanceTimeBy(1_200)
            compose.waitForIdle()
            assertEquals(sheetWindows + 1, windowCount())
            pressBack()
            awaitWindows(sheetWindows)
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, dismissals) }
            pressBack()
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeBy(100)
                !visible
            }
            compose.runOnIdle { assertEquals(1, dismissals) }
            awaitWindows(0)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun guardBlocksDismissal(enhanced: Boolean) {
        compose.runOnUiThread { MotionMode.sync(enhanced) }
        var visible by mutableStateOf(true)
        var allowed by mutableStateOf(false)
        var latestCallback by mutableStateOf(false)
        var oldDismissals = 0
        var dismissals = 0
        var controller: MotionSheetController? = null
        compose.setContent {
            TyranorNextTheme {
                if (visible) {
                    val sheet = rememberMotionSheetController()
                    SideEffect { controller = sheet }
                    val useLatest = latestCallback
                    AppBottomSheet(
                        onDismissRequest = {
                            if (useLatest) dismissals++ else oldDismissals++
                            visible = false
                        },
                        confirmValueChange = { it != SheetValue.Hidden || allowed },
                        controller = sheet,
                    ) { Text("Guarded sheet", Modifier.padding(48.dp)) }
                }
            }
        }
        compose.waitForIdle()
        pressBack()
        compose.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Guarded sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(visible)
            assertFalse(controller!!.closing)
            assertEquals(0, oldDismissals + dismissals)
            allowed = true
            latestCallback = true
        }
        compose.waitForIdle()
        if (enhanced) {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                controller!!.close()
                controller!!.close()
                assertEquals("Exit must finish before removing the modal window", 0, dismissals)
            }
            compose.mainClock.advanceTimeBy(4_000)
            compose.mainClock.autoAdvance = true
        } else {
            pressBack()
        }
        compose.waitUntil(5_000) { !visible }
        compose.runOnIdle {
            assertEquals("A replaced callback must not be retained", 0, oldDismissals)
            assertEquals("The modal window must be removed exactly once", 1, dismissals)
        }
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
    }

    private fun growthDuringOrdinaryOpening() {
        compose.runOnUiThread { MotionMode.sync(false) }
        compose.mainClock.autoAdvance = false
        var extraRow by mutableStateOf(false)
        var clicks = 0
        var controller: MotionSheetController? = null
        try {
            compose.setContent {
                TyranorNextTheme {
                    val sheet = rememberMotionSheetController()
                    SideEffect { controller = sheet }
                    AppBottomSheet(onDismissRequest = {}, controller = sheet) {
                        ResizableSheetFixture(extraRow = extraRow, onBottomClick = { clicks++ })
                    }
                }
            }
            // Wait until the sheet dialog has measured once and ordinary show() is actively opening
            // (currentValue != Expanded while targetValue == Expanded), then grow the content mid-open.
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                val state = controller?.sheetState
                val hasBottom = compose.onAllNodesWithText(BOTTOM_ACTION_LABEL).fetchSemanticsNodes().isNotEmpty()
                hasBottom && state != null &&
                    state.targetValue == SheetValue.Expanded &&
                    state.currentValue != SheetValue.Expanded
            }
            compose.runOnUiThread { extraRow = true }
            compose.mainClock.advanceTimeBy(1_500)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.waitUntil(5_000) {
                controller?.sheetState?.currentValue == SheetValue.Expanded
            }
            compose.onNodeWithText(EXTRA_ROW_LABEL).assertIsDisplayed()
            assertBottomActionFullyVisibleAndClickable(expectedClicks = { clicks })
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun growthDuringEnhancedCloseThenReopen() {
        compose.runOnUiThread { MotionMode.sync(true) }
        compose.mainClock.autoAdvance = false
        var extraRow by mutableStateOf(false)
        var clicks = 0
        var controller: MotionSheetController? = null
        try {
            compose.setContent {
                TyranorNextTheme {
                    val sheet = rememberMotionSheetController()
                    SideEffect { controller = sheet }
                    AppBottomSheet(onDismissRequest = {}, controller = sheet) {
                        ResizableSheetFixture(extraRow = extraRow, onBottomClick = { clicks++ })
                    }
                }
            }
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeBy(100)
                val sheet = controller
                sheet != null && sheet.height > 0f && kotlin.math.abs(sheet.offset.value) < 1f
            }
            compose.onNodeWithText(BOTTOM_ACTION_LABEL).assertIsDisplayed()

            // Start closing, let the exit animation progress partway without committing offscreen,
            // grow the sheet while closing is true, then reopen and verify bottom alignment.
            compose.runOnUiThread { controller!!.close() }
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle {
                assertTrue("Sheet should be actively closing before growth", controller!!.closing)
                assertFalse("Sheet must not be committed closed before reopen", controller!!.closeCommitted)
            }
            compose.runOnUiThread { extraRow = true }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnUiThread {
                assertTrue("Sheet should reopen mid-close", controller!!.reopen())
            }
            compose.mainClock.advanceTimeBy(2_000)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse("Sheet must no longer be closing after reopen", controller!!.closing)
                assertEquals(SheetValue.Expanded, controller!!.sheetState?.currentValue)
            }
            compose.onNodeWithText(EXTRA_ROW_LABEL).assertIsDisplayed()
            assertBottomActionFullyVisibleAndClickable(expectedClicks = { clicks })
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun unchangedHeightControl(enhanced: Boolean) {
        compose.runOnUiThread { MotionMode.sync(enhanced) }
        var clicks = 0
        var controller: MotionSheetController? = null
        compose.setContent {
            TyranorNextTheme {
                val sheet = rememberMotionSheetController()
                SideEffect { controller = sheet }
                AppBottomSheet(onDismissRequest = {}, controller = sheet) {
                    ResizableSheetFixture(extraRow = false, onBottomClick = { clicks++ })
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            val sheet = controller
            sheet != null &&
                sheet.sheetState?.currentValue == SheetValue.Expanded &&
                (!enhanced || (sheet.height > 0f && kotlin.math.abs(sheet.offset.value) < 1f))
        }
        val initialBounds = compose.onNodeWithText(BOTTOM_ACTION_LABEL).fetchSemanticsNode().boundsInRoot
        compose.waitForIdle()
        assertEquals(initialBounds, compose.onNodeWithText(BOTTOM_ACTION_LABEL).fetchSemanticsNode().boundsInRoot)
        assertBottomActionFullyVisibleAndClickable(expectedClicks = { clicks })
    }

    private fun assertBottomActionFullyVisibleAndClickable(expectedClicks: () -> Int) {
        val dialogBounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val actionNode = compose.onNodeWithText(BOTTOM_ACTION_LABEL).assertIsDisplayed()
        val semantics = actionNode.fetchSemanticsNode()
        val bounds = semantics.boundsInRoot
        assertTrue(
            "Bottom action must stay within the visible dialog window (action=$bounds, dialog=$dialogBounds)",
            bounds.top >= dialogBounds.top - 1f && bounds.bottom <= dialogBounds.bottom + 1f,
        )
        assertTrue(
            "Bottom action must retain its full unclipped height (bounds=$bounds, size=${semantics.size})",
            bounds.height >= semantics.size.height - 1f,
        )
        actionNode.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, expectedClicks()) }
    }

    @androidx.compose.runtime.Composable
    private fun ResizableSheetFixture(extraRow: Boolean, onBottomClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Resizable sheet fixture", Modifier.padding(bottom = 12.dp))
            if (extraRow) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(EXTRA_ROW_LABEL)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable(onClick = onBottomClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(BOTTOM_ACTION_LABEL)
            }
        }
    }

    private companion object {
        const val EXTRA_ROW_LABEL = "Async extra row"
        const val BOTTOM_ACTION_LABEL = "Bottom action"
    }
}
