package com.tyranor.next.ui.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.main.MainLibraryUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic UI fixtures only; requires the isolated package and explicit device acceptance opt-in. */
class MotionGameInteractionsDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** Reuses a queued drawer activation during exit and verifies a single business operation. */
    @Test
    fun closingSheetRejectsRepeatedBusinessAction() {
        MotionDeviceAcceptance.require()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val quickLaunchLabel = instrumentation.targetContext.getString(R.string.game_add_quick_launch)
        var quickLaunchChanges = 0
        var showSheet by mutableStateOf(true)
        compose.setContent {
            TyranorNextTheme {
                if (showSheet) {
                    GameActionsSheet(
                        game = fixtureGame,
                        onDismiss = { showSheet = false },
                        onGameUpdated = {},
                        onDeleteGame = {},
                        onEngineSettings = {},
                        quickLaunched = false,
                        onQuickLaunchToggle = { quickLaunchChanges++; true },
                    )
                }
            }
        }
        // Keep the original callback: a queued accessibility activation can outlive its semantics node.
        val activate = requireNotNull(
            compose.onNodeWithText(quickLaunchLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                activate()
                activate()
                assertEquals(1, quickLaunchChanges)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** Invokes a captured card callback while its content is leaving and verifies no action sheet opens. */
    @Test
    fun leavingLibraryContentRejectsStaleCardClick() {
        MotionDeviceAcceptance.require()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launchLabel = instrumentation.targetContext.getString(R.string.game_launch_action)
        val originalCardStyle = AppSettingsStore.gameCardStyleState.value
        var libraryState by mutableStateOf(MainLibraryUiState(games = listOf(fixtureGame), loaded = true))
        instrumentation.runOnMainSync {
            AppSettingsStore.gameCardStyleState.value = AppSettingsStore.GAME_CARD_STYLE_GRID
        }
        try {
            compose.setContent {
                TyranorNextTheme {
                    GameScreen(
                        libraryState = libraryState,
                        onGameUpdated = {},
                        onGameDeleted = {},
                        onQuickLaunchToggle = { true },
                        onScanLibrary = {},
                        onScrapeEventShown = {},
                        onSearchQueryChanged = {},
                        onAddManualGame = { true },
                    )
                }
            }
            val activate = requireNotNull(
                compose.onNodeWithText(fixtureGame.title).fetchSemanticsNode().config[SemanticsActions.OnClick].action,
            )
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { libraryState = libraryState.copy(scanning = true) }
            compose.mainClock.advanceTimeByFrame()
            // Assert that the outgoing card is still composed, so this exercises the exit interval.
            compose.onNodeWithText(fixtureGame.title).assertExists()
            compose.runOnIdle { activate() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(launchLabel).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            instrumentation.runOnMainSync { AppSettingsStore.gameCardStyleState.value = originalCardStyle }
        }
    }


    private val fixtureGame = ScanGame(
        title = "Motion review fixture",
        uri = "content://motion-review-fixture/game",
        engine = EngineType.UNKNOWN,
        launchTarget = "",
    )
}
