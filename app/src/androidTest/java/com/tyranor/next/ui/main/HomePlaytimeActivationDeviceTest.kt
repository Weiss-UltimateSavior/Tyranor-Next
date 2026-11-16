package com.tyranor.next.ui.main

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.playtime.GamePlaytimeDatabase
import com.tyranor.next.core.game.playtime.PlaytimeCheckpointStore
import com.tyranor.next.core.game.playtime.PlaytimeRecordSnapshot
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.rememberEntranceState
import com.tyranor.next.ui.common.motion.rememberSheetBackdrop
import com.tyranor.next.ui.home.HomeScreen
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Imports synthetic checkpoints through the real home-page activation, without launching games. */
class HomePlaytimeActivationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeHomeImportsOnSelectionAndReentryInEnhancedMode() = verifyActivation(enhanced = true)

    @Test fun nativeHomeImportsOnSelectionAndReentryInOrdinaryMode() = verifyActivation(enhanced = false)

    private fun verifyActivation(enhanced: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldMode = MotionMode.enhanced
        val oldStyle = AppSettingsStore.homeStyleState.value
        val id = "home-activation-${UUID.randomUUID()}"
        val uri = "content://motion-review-fixture/$id"
        val game = ScanGame("Home activation fixture", uri, EngineType.TYRANO, "")
        val library = MainLibraryUiState(games = listOf(game), recentGames = listOf(game), loaded = true)
        val checkpoints = PlaytimeCheckpointStore(context.noBackupFilesDir)
        val database = GamePlaytimeDatabase.get(context)
        var selectedPage by mutableIntStateOf(1)
        var homeEnabled by mutableStateOf(true)
        var homeActive = false

        fun checkpoint(duration: Long) = checkpoints.write(
            PlaytimeRecordSnapshot(id, id, uri, duration, 1L, closed = true, processId = 0),
        )

        fun total(): Long = runBlocking(Dispatchers.IO) {
            database.records().all().filter { it.gameUri == uri }.sumOf { it.durationMillis }
        }

        try {
            compose.runOnUiThread {
                MotionMode.sync(enhanced)
                AppSettingsStore.homeStyleState.value = AppSettingsStore.HOME_STYLE_NATIVE
            }
            compose.setContent {
                TyranorNextTheme {
                    val transition = updateTransition(selectedPage, label = "homeActivation")
                    val motion = rememberMainTabMotion(transition, pageCount = 2)
                    val backdrop = rememberSheetBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        if (homeEnabled) {
                            MainTabPageHost(
                                page = 0,
                                selected = selectedPage == 0,
                                pageTransition = transition,
                                motion = motion[0],
                                entrance = rememberEntranceState(playOnStart = false),
                                overlay = backdrop,
                                allowHiddenDraw = true,
                                reportActive = false,
                            ) { active ->
                                SideEffect { homeActive = active }
                                HomeScreen(
                                    libraryState = library,
                                    onGameUpdated = {},
                                    onGameDeleted = {},
                                    onRecentRemoved = {},
                                    onQuickLaunchToggle = { true },
                                    isActive = active,
                                )
                            }
                        } else {
                            SideEffect { homeActive = false }
                        }
                    }
                }
            }
            compose.waitForIdle()
            checkpoint(360_000L)
            assertFalse(homeActive)
            assertEquals("A hidden native home must not consume the checkpoint", 0L, total())

            compose.runOnIdle { selectedPage = 0 }
            compose.waitUntil(5_000L) { homeActive && total() == 360_000L }
            compose.onNodeWithText("0.1h").assertExists()

            compose.runOnIdle { selectedPage = 1 }
            compose.waitForIdle()
            checkpoint(720_000L)
            assertFalse(homeActive)
            assertEquals(360_000L, total())
            compose.runOnIdle { selectedPage = 0 }
            compose.waitUntil(5_000L) { homeActive && total() == 720_000L }
            compose.onNodeWithText("0.2h").assertExists()

            compose.runOnIdle { homeEnabled = false; selectedPage = 1 }
            compose.waitForIdle()
            checkpoint(1_080_000L)
            assertFalse(homeActive)
            assertEquals("A removed home must not import checkpoints", 720_000L, total())
            compose.runOnIdle { homeEnabled = true; selectedPage = 0 }
            compose.waitUntil(5_000L) { homeActive && total() == 1_080_000L }
            compose.onNodeWithText("0.3h").assertExists()
        } finally {
            compose.runOnIdle { homeEnabled = false }
            compose.waitForIdle()
            checkpoints.deleteClosed(id)
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM playtime_records WHERE gameUri = ?",
                arrayOf(uri),
            )
            compose.runOnUiThread {
                MotionMode.sync(oldMode)
                AppSettingsStore.homeStyleState.value = oldStyle
            }
        }
    }
}
