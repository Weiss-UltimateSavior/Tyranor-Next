package com.tyranor.next.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.main.MainLibraryUiState
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Restores only synthetic home state in the explicitly enabled isolated motion installation. */
class MotionHomeRestorationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tabletRecentPagerKeepsItsSelectionWhenAPlayedGameIsAdded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(instrumentation.targetContext.resources.configuration.smallestScreenWidthDp >= 600)
        MotionDeviceAcceptance.require(enhanced = null)
        val previousHomeStyle = AppSettingsStore.homeStyleState.value
        val previousMotion = MotionMode.enhanced
        val games = (1..9).map { index ->
            ScanGame("Recent fixture $index", "content://home-recent-fixture/$index", EngineType.UNKNOWN, "")
        }
        val newlyPlayed = games.first()
        val previousLeading = games[1]
        var recentGames by mutableStateOf(games.drop(1))
        val inPager = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        val previousCard = compose.onNode(hasContentDescription(previousLeading.title) and inPager)
        val newCard = compose.onNode(hasContentDescription(newlyPlayed.title) and inPager)
        instrumentation.runOnMainSync {
            AppSettingsStore.homeStyleState.value = AppSettingsStore.HOME_STYLE_NATIVE
        }
        try {
            compose.setContent {
                TyranorNextTheme {
                    HomeScreen(
                        libraryState = MainLibraryUiState(games = games, recentGames = recentGames, loaded = true),
                        onGameUpdated = {},
                        onGameDeleted = {},
                        onRecentRemoved = {},
                        onQuickLaunchToggle = { true },
                    )
                }
            }
            previousCard.assertIsSelected()

            // Launching a game prepends its first recent entry while the home page is still composed.
            compose.runOnIdle { recentGames = games }
            previousCard.assertIsSelected().performTouchInput { swipeRight(durationMillis = 160) }
            newCard.assertIsSelected()
            compose.onNodeWithText(newlyPlayed.title).assertIsDisplayed()

            compose.runOnIdle { recentGames = games.drop(1) }
            previousCard.assertIsSelected()
        } finally {
            instrumentation.runOnMainSync {
                AppSettingsStore.homeStyleState.value = previousHomeStyle
            }
        }
    }

    @Test
    fun tabletRecentScrollEnlargesTheLeadingCardAndRestoresItsPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.resources.configuration.smallestScreenWidthDp >= 600)
        MotionDeviceAcceptance.require(enhanced = null)
        val previousHomeStyle = AppSettingsStore.homeStyleState.value
        val previousMotion = MotionMode.enhanced
        val first = ScanGame("Most recent fixture", "content://home-fixture/first", EngineType.UNKNOWN, "")
        val second = ScanGame("Second recent fixture", "content://home-fixture/second", EngineType.UNKNOWN, "")
        val quick = ScanGame("Quick launch fixture", "content://home-fixture/quick", EngineType.UNKNOWN, "")
        val games = listOf(first, second, quick)
        var library by mutableStateOf(
            MainLibraryUiState(games = games, recentGames = listOf(first, second), quickLaunch = listOf(quick), loaded = true),
        )
        var restoring = false
        var loadCompleted by mutableStateOf(false)
        val selected = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        val firstCard = compose.onNode(hasContentDescription(first.title) and selected)
        val secondCard = compose.onNode(hasContentDescription(second.title) and selected)
        val restoration = StateRestorationTester(compose)
        instrumentation.runOnMainSync {
            AppSettingsStore.homeStyleState.value = AppSettingsStore.HOME_STYLE_NATIVE
        }
        try {
            restoration.setContent {
                TyranorNextTheme {
                    HomeScreen(
                        libraryState = if (restoring && !loadCompleted) MainLibraryUiState() else library,
                        onGameUpdated = {},
                        onGameDeleted = {},
                        onRecentRemoved = { removed ->
                            library = library.copy(recentGames = library.recentGames.filterNot { it.uri == removed.uri })
                        },
                        onQuickLaunchToggle = { true },
                    )
                }
            }
            val firstBounds = firstCard.assertIsSelected().fetchSemanticsNode().boundsInRoot
            val secondWidth = secondCard.fetchSemanticsNode().boundsInRoot.width
            val quickTop = compose.onNodeWithText(quick.title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
            assertTrue(firstBounds.bottom < quickTop)
            assertTrue(firstBounds.width > secondWidth)
            compose.onAllNodesWithContentDescription(first.title).assertCountEquals(1)
            compose.onAllNodesWithContentDescription(context.getString(R.string.home_empty_slot_content_description))
                .assertCountEquals(2)

            firstCard.performTouchInput { swipeRight(durationMillis = 160) }
            assertTrue(firstCard.assertIsSelected().fetchSemanticsNode().boundsInRoot == firstBounds)

            firstCard.performTouchInput {
                down(Offset(width * 0.85f, height * 0.65f))
                moveBy(Offset(-firstBounds.width * 0.4f, 0f))
            }
            val draggingWidth = secondCard.fetchSemanticsNode().boundsInRoot.width
            assertTrue("The incoming card should grow before the finger is lifted", draggingWidth > secondWidth)
            firstCard.performTouchInput {
                moveBy(Offset(-firstBounds.width * 0.4f, 0f))
                up()
            }
            val leadingBounds = secondCard.assertIsSelected().fetchSemanticsNode().boundsInRoot
            assertTrue(leadingBounds.width > secondWidth)
            assertTrue(kotlin.math.abs(leadingBounds.left - firstBounds.left) <= 1f)
            compose.onNodeWithText(second.title).assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
            restoring = true
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { loadCompleted = true }
            secondCard.assertIsSelected().performTouchInput { swipeRight(durationMillis = 160) }
            firstCard.assertIsSelected()
            secondCard.performClick().assertIsSelected()
            secondCard.performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
            secondCard.assertDoesNotExist()
            firstCard.assertIsSelected().performTouchInput { click(Offset(width * 0.85f, height * 0.2f)) }
            compose.onNode(hasText(first.title) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        } finally {
            instrumentation.runOnMainSync {
                AppSettingsStore.homeStyleState.value = previousHomeStyle
            }
        }
    }

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
