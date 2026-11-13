package com.tyranor.next.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.main.MainLibraryUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Restores only synthetic home state in the explicitly enabled isolated motion installation. */
class MotionHomeRestorationDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** Keeps a saved management target while the restored library is empty and still loading. */
    @Test
    fun restoredQuickActionsWaitForLibraryLoadingBeforeDiscardingTheirTarget() {
        MotionDeviceAcceptance.require()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previousHomeStyle = AppSettingsStore.homeStyleState.value
        val renameLabel = instrumentation.targetContext.getString(R.string.game_rename)
        val fixture = ScanGame(
            title = "Motion home restoration fixture",
            uri = "content://motion-review-fixture/home-restoration",
            engine = EngineType.UNKNOWN,
            launchTarget = "",
        )
        val loaded = MainLibraryUiState(games = listOf(fixture), quickLaunch = listOf(fixture), loaded = true)
        val loading = MainLibraryUiState(loaded = false)
        // Change the next composition's initial library without invalidating the state being saved.
        var restoring = false
        var loadCompleted by mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        instrumentation.runOnMainSync { AppSettingsStore.homeStyleState.value = AppSettingsStore.HOME_STYLE_NATIVE }
        try {
            restoration.setContent {
                val state = if (restoring && !loadCompleted) loading else loaded
                TyranorNextTheme {
                    HomeScreen(
                        libraryState = state,
                        onGameUpdated = {},
                        onGameDeleted = {},
                        onRecentRemoved = {},
                        onQuickLaunchToggle = { true },
                    )
                }
            }
            compose.onNodeWithText(fixture.title).performSemanticsAction(SemanticsActions.OnLongClick) {
                assertTrue(it())
            }
            compose.onNodeWithText(renameLabel).assertExists()
            restoring = true
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(renameLabel).assertDoesNotExist()
            compose.runOnIdle { loadCompleted = true }
            compose.onNodeWithText(renameLabel).assertExists()
        } finally {
            instrumentation.runOnMainSync { AppSettingsStore.homeStyleState.value = previousHomeStyle }
        }
    }
}
