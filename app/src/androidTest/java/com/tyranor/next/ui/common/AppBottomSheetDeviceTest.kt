package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
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
            compose.mainClock.advanceTimeBy(1_200)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val title = context.getString(R.string.game_filter_title)
            val sortLabel = context.getString(R.string.game_filter_sort_section)
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
}
