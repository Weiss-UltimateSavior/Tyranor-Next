package com.tyranor.next.ui.game

import android.animation.ValueAnimator
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleRegistry
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ShelfBookRuntimeSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dirtySettingsYieldToTopLeafAndDiscardReturnsToContents() = runBlocking {
        assumeTrue(context.packageName == "com.tyranor.next.ethornelltest")
        assumeTrue(InstrumentationRegistry.getArguments().getString("runtime_module_test") == "true")
        val module = "com.tyranor.runtime.example"
        val wasEnabled = module in AppSettingsStore.getEnabledRuntimeModules(context)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "book-runtime-").toFile()
        val game = ScanGame("Runtime book", Uri.fromFile(root).toString(), EngineType.RUNTIME_MODULE,
            "game.tyranor-example", runtimeModulePackage = module)
        val draft = "Book draft"
        var showBook by mutableStateOf(true)
        try {
            AppSettingsStore.setRuntimeModuleEnabled(context, module, true)
            RuntimeModuleRegistry.refresh(context)
            val items = requireNotNull(RuntimeModuleRegistry.find(module)?.settings) {
                "Install the standalone runtime example APK first"
            }.items
            val textTitle = items.single { it.key == "player_name" }.title
            val numberTitle = items.single { it.key == "fps_limit" }.title
            GameLibraryFacade.registerGames(context) { it + game }
            GameLibraryRepository.awaitPendingWrites()
            compose.setContent {
                TyranorNextTheme {
                    if (showBook) ShelfOpenBook(
                        game = game, stage = remember { ShelfBookStage() }, quickLaunched = false,
                        onDismiss = { error("Settings navigation closed the book") }, onGameUpdated = {},
                        onGameCoverUpdated = { _, _ -> }, onGameLaunchFileUpdated = null,
                        onGameBindingUpdated = { _, _ -> }, onGameRenamed = null, onDeleteGame = {},
                        onQuickLaunchToggle = { false },
                    )
                }
            }

            val chapter = context.getString(R.string.settings_engine_settings)
            waitForText(chapter)
            compose.onNodeWithText(chapter).performScrollTo().performClick()
            waitForText(context.getString(R.string.runtime_settings_restore_all_defaults))
            openRow(textTitle)
            compose.onNode(hasSetTextAction()).performTextReplacement(draft)
            button(R.string.common_confirm).performClick()

            openRow(numberTitle)
            pressBack()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            button(R.string.runtime_settings_unsaved_title).assertDoesNotExist()
            openRow(textTitle)
            backLink().performClick()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            button(R.string.runtime_settings_unsaved_title).assertDoesNotExist()

            pressBack()
            button(R.string.runtime_settings_unsaved_title).assertExists()
            pressBack()
            button(R.string.runtime_settings_unsaved_title).assertDoesNotExist()
            backLink().performClick()
            button(R.string.common_cancel).performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(draft))
            compose.onNodeWithText(draft).assertExists()

            if (ValueAnimator.areAnimatorsEnabled()) {
                swipeBack()
                button(R.string.runtime_settings_unsaved_title).assertExists()
                swipeBack()
                button(R.string.runtime_settings_unsaved_title).assertDoesNotExist()
            }
            button(R.string.common_close).performClick()
            button(R.string.common_confirm).performClick()
            button(R.string.runtime_settings_unsaved_title).assertDoesNotExist()
            button(R.string.runtime_settings_title).assertDoesNotExist()
            compose.onNodeWithText(chapter).performScrollTo().performClick()
            waitForText(context.getString(R.string.runtime_settings_restore_all_defaults))
            openRow(textTitle)
            compose.onNode(hasSetTextAction() and hasText("")).assertExists()
            assertEquals(0, PerGameSettingsStore.getRuntimeModuleSettings(context, game.uri, module).length())
        } finally {
            compose.runOnUiThread { showBook = false }
            PerGameSettingsStore.clear(context, game.uri)
            GameLibraryFacade.updateGames(context) { games -> games.filterNot { it.uri == game.uri } }
            GameLibraryRepository.awaitPendingWrites()
            AppSettingsStore.setRuntimeModuleEnabled(context, module, wasEnabled)
            RuntimeModuleRegistry.refresh(context)
            root.delete()
        }
    }

    private fun openRow(title: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
    }

    private fun swipeBack() = compose.onNode(isDialog()).performTouchInput {
        swipeRight(startX = width * 0.25f, endX = width * 0.90f, durationMillis = 140)
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun backLink() = compose.onNode(hasContentDescription(context.getString(R.string.common_back)) and isEnabled())

    private fun button(resource: Int) = compose.onNodeWithText(context.getString(resource))
}
