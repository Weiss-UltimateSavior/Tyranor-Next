package com.tyranor.next.ui.runtime

import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleDiscoveryResult
import com.tyranor.next.core.engine.runtime.RuntimeModuleRegistry
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.core.settings.AppearanceStyle
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.ui.settings.PerGameSettingsActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real module discovery, Compose screen, Activity recreation and Room-backed settings acceptance. */
@RunWith(AndroidJUnit4::class)
class RuntimeModuleSettingsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val modulePackage = "com.tyranor.runtime.example"

    @Test(timeout = 60_000)
    fun moduleSettingsDraftSurvivesRecreationAndSavesOnlyForBoundGame() = runBlocking {
        assumeTrue("Dedicated runtime-module target only", context.packageName == ISOLATED_PACKAGE)
        assumeTrue(
            "Pass runtime_module_test=true to opt into the real example-module test",
            InstrumentationRegistry.getArguments().getString("runtime_module_test") == "true",
        )
        assumeTrue("Grant file access to the isolated test application", Environment.isExternalStorageManager())

        val wasEnabled = modulePackage in AppSettingsStore.getEnabledRuntimeModules(context)
        val originalLanguage = AppSettingsStore.getLanguage(context)
        val originalStyle = AppSettingsStore.getAppearanceStyle(context)
        val originalTheme = AppSettingsStore.getThemeMode(context)
        val originalMotion = AppSettingsStore.isMotionEnhanced(context)
        val parent = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TyranorRuntimeExample")
        check(parent.isDirectory || parent.mkdirs())
        val roots = List(2) { index -> File(parent, "settings-ui-${UUID.randomUUID()}-$index").apply { check(mkdir()) } }
        val gameFileName = "game.tyranor-example"
        val games = roots.mapIndexed { index, root ->
            File(root, gameFileName).writeText("fixture-$index")
            ScanGame(
                title = "Runtime settings acceptance $index",
                uri = Uri.fromFile(root).toString(),
                engine = EngineType.RUNTIME_MODULE,
                launchTarget = gameFileName,
                runtimeModulePackage = modulePackage,
            )
        }
        val game = games[0]
        var scenario: ActivityScenario<PerGameSettingsActivity>? = null
        compose.mainClock.autoAdvance = false
        try {
            AppSettingsStore.setLanguage(context, AppSettingsStore.LANGUAGE_EN)
            val arguments = InstrumentationRegistry.getArguments()
            arguments.getString("runtime_appearance")?.let { value ->
                val style = AppearanceStyle.entries.single { it.storageValue == value }
                AppSettingsStore.setAppearanceStyle(context, style)
            }
            arguments.getString("runtime_theme")?.let { value ->
                check(value in setOf(AppSettingsStore.THEME_MODE_LIGHT, AppSettingsStore.THEME_MODE_DARK))
                AppSettingsStore.setThemeMode(context, value)
            }
            arguments.getString("runtime_motion")?.let { value ->
                AppSettingsStore.setMotionEnhanced(context, value.toBooleanStrict())
            }
            instrumentation.runOnMainSync { AppThemeColors.refresh(context) }
            AppSettingsStore.setRuntimeModuleEnabled(context, modulePackage, true)
            assertTrue(RuntimeModuleRegistry.refresh(context) is RuntimeModuleDiscoveryResult.Success)
            val descriptor = requireNotNull(RuntimeModuleRegistry.find(modulePackage)) {
                "Install the standalone runtime example APK first"
            }
            val spec = requireNotNull(descriptor.settings)
            assertEquals(setOf("vsync", "renderer", "player_name", "fps_limit"), spec.items.map { it.key }.toSet())

            GameLibraryFacade.registerGames(context) { existing -> existing + games }
            GameLibraryRepository.awaitPendingWrites()

            scenario = ActivityScenario.launch(
                PerGameSettingsActivity.createIntent(context, game)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )

            waitForNode(hasText(spec.items.first().title))
            spec.items.forEach { item ->
                val title = hasText(item.title)
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(title)
                waitForNode(title)
                compose.onNode(title).performScrollTo().assertIsDisplayed()
            }

            val playerName = spec.items.single { it.key == "player_name" }.title
            compose.onNodeWithText(playerName).performScrollTo().performClick()
            waitForNode(hasSetTextAction())
            compose.onNode(hasSetTextAction()).performTextReplacement(DRAFT_VALUE)
            val confirm = AppLocaleController.wrap(context).getString(R.string.common_confirm)
            compose.onNodeWithText(confirm).performClick()
            waitForText(DRAFT_VALUE)

            scenario.recreate()
            waitForText(DRAFT_VALUE)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForNode(hasText(AppLocaleController.wrap(context).getString(R.string.runtime_settings_unsaved_title)))
            compose.onNodeWithText(AppLocaleController.wrap(context).getString(R.string.common_cancel)).performClick()
            waitForText(DRAFT_VALUE)
            assertEquals(
                "Unsaved edits remain only in the UI draft until the explicit save action",
                0,
                runBlocking(Dispatchers.IO) {
                    PerGameSettingsStore.getRuntimeModuleSettings(context, game.uri, modulePackage).length()
                },
            )

            val saveDescription = AppLocaleController.wrap(context).getString(R.string.runtime_settings_save)
            compose.onNodeWithContentDescription(saveDescription).performClick()
            compose.waitUntil(15_000) {
                compose.mainClock.advanceTimeBy(100)
                runBlocking(Dispatchers.IO) {
                    PerGameSettingsStore.getRuntimeModuleSettings(context, game.uri, modulePackage)
                        .optString("player_name") == DRAFT_VALUE
                }
            }

            val saved = withContext(Dispatchers.IO) {
                PerGameSettingsStore.getRuntimeModuleSettings(context, game.uri, modulePackage)
            }
            assertEquals(DRAFT_VALUE, saved.getString("player_name"))
            assertEquals(
                "A different game using the same module must retain independent settings",
                0,
                withContext(Dispatchers.IO) {
                    PerGameSettingsStore.getRuntimeModuleSettings(context, games[1].uri, modulePackage).length()
                },
            )

            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            waitForNode(hasText(AppLocaleController.wrap(context).getString(R.string.runtime_settings_restore_all_defaults)) and isEnabled())
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                "UiAutomation.takeScreenshot returned null"
            }
            File(context.filesDir, "runtime-settings-acceptance.png").outputStream().use { output ->
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            screenshot.recycle()

            val restoreAll = AppLocaleController.wrap(context).getString(R.string.runtime_settings_restore_all_defaults)
            compose.onNodeWithText(restoreAll).performClick()
            waitForNode(androidx.compose.ui.test.hasContentDescription(saveDescription))
            compose.onNodeWithContentDescription(saveDescription).performClick()
            compose.waitUntil(15_000) {
                compose.mainClock.advanceTimeBy(100)
                runBlocking(Dispatchers.IO) {
                    PerGameSettingsStore.getRuntimeModuleSettings(context, game.uri, modulePackage).length() == 0
                }
            }
        } finally {
            scenario?.close()
            games.forEach { gameEntry ->
                PerGameSettingsStore.clear(context, gameEntry.uri)
            }
            GameLibraryFacade.updateGames(context) { current -> current.filterNot { it.uri in games.map(ScanGame::uri) } }
            GameLibraryRepository.awaitPendingWrites()
            AppSettingsStore.setRuntimeModuleEnabled(context, modulePackage, wasEnabled)
            RuntimeModuleRegistry.refresh(context)
            roots.forEach(File::deleteRecursively)
            AppSettingsStore.setLanguage(context, originalLanguage)
            AppSettingsStore.setAppearanceStyle(context, originalStyle)
            AppSettingsStore.setThemeMode(context, originalTheme)
            AppSettingsStore.setMotionEnhanced(context, originalMotion)
            instrumentation.runOnMainSync { AppThemeColors.refresh(context) }
        }
    }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    private fun waitForNode(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodes(matcher, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(1_200)
    }

    private companion object {
        const val ISOLATED_PACKAGE = "com.tyranor.next.ethornelltest"
        const val DRAFT_VALUE = "UI Draft"
    }
}
