package com.tyranor.next.ui.tablet

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.ProvideAppLocale
import com.tyranor.next.ui.game.GameShortcutCropDialog
import com.tyranor.next.ui.settings.AppSettingsActivity
import com.tyranor.next.ui.settings.GamepadMapActivity
import com.tyranor.next.ui.settings.PerGameSettingsActivity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Physical display rotation on a tablet AVD. No app orientation request or synthetic Configuration. */
@RunWith(AndroidJUnit4::class)
class MotionTabletPagesDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val inDialog = hasAnyAncestor(isDialog())
    private var originalRotation: Int? = null
    private var wasAuto = false

    @Before fun requireTablet() {
        MotionDeviceAcceptance.require(enhanced = null)
        assumeTrue("Dedicated tablet emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue("Requires sw600dp tablet AVD", context.resources.configuration.smallestScreenWidthDp >= 600)
        wasAuto = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
        originalRotation = rotation
        // Drive the real page clock explicitly: the animated default background continuously asks for frames.
        compose.mainClock.autoAdvance = false
    }

    @After fun restoreRotation() {
        originalRotation?.let {
            try {
                assertTrue(inst.uiAutomation.setRotation(it))
            } finally {
                if (wasAuto) assertTrue(inst.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE))
            }
        }
    }

    private val rotation get() = requireNotNull(
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY),
    ).rotation

    private fun text(id: Int, vararg args: Any) = AppLocaleController.wrap(context).getString(id, *args)
    private fun label(id: Int) = hasText(text(id))
    private fun node(matcher: SemanticsMatcher) = compose.onNode(matcher)
    private fun ready(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 12_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        compose.mainClock.advanceTimeBy(1_200)
        node(matcher).assertIsDisplayed()
    }

    private inline fun <reified A : Activity> page(intent: Intent) =
        ActivityScenario.launch<A>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun <A : Activity> orient(scenario: ActivityScenario<A>, desired: Int) {
        var current = Configuration.ORIENTATION_UNDEFINED
        lateinit var hostClass: Class<*>
        scenario.onActivity {
            current = it.resources.configuration.orientation
            hostClass = it.javaClass
        }
        val angle = if (current == desired) rotation else (rotation + 1) % 4
        assertTrue("AVD refused display rotation", inst.uiAutomation.setRotation(angle))
        compose.waitUntil(timeoutMillis = 15_000) {
            var measured = false
            inst.runOnMainSync {
                // During recreation there may briefly be no RESUMED instance.
                val page = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .singleOrNull { it.javaClass == hostClass } ?: return@runOnMainSync
                val view = page.window.decorView
                measured = page.resources.configuration.orientation == desired &&
                    view.width > 0 && view.height > 0 && when (desired) {
                        Configuration.ORIENTATION_LANDSCAPE -> view.width > view.height
                        else -> view.height > view.width
                    }
            }
            measured
        }
        compose.mainClock.advanceTimeBy(1_200)
    }

    @Test fun validPerGameIntentLaysOutTitleSaveAndEngineControlsInBothOrientations() {
        val game = ScanGame(title = "Tablet ONS fixture", uri = "content://tablet-fixture/ons",
            engine = EngineType.ONS, launchTarget = "")
        page<PerGameSettingsActivity>(PerGameSettingsActivity.createIntent(context, game)).use { scenario ->
            for (direction in listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
                orient(scenario, direction)
                val title = hasText(game.title)
                val control = label(R.string.engine_settings_scoped_save_dir)
                ready(title)
                ready(control)
                val titleBounds = node(title).fetchSemanticsNode().boundsInRoot
                val save = node(androidx.compose.ui.test.hasContentDescription(text(R.string.common_save)))
                save.assertIsDisplayed().assertIsEnabled()
                val saveBounds = save.fetchSemanticsNode().boundsInRoot
                assertTrue("Save overlaps title", titleBounds.right <= saveBounds.left + 1f)
                assertTrue("Engine setting overlaps top bar",
                    node(control).fetchSemanticsNode().boundsInRoot.top >= titleBounds.bottom - 1f)
            }
        }
    }

    @Test fun unconfirmedGamepadBindingSurvivesBothRotations() {
        val saved = InputRemapRepository.gamepadEntries(context).first { it.id == InputRemapRepository.GAMEPAD_A }
        val countAfterToggle = saved.keys.size + if (KeyEvent.KEYCODE_A in saved.keys) -1 else 1
        page<GamepadMapActivity>(GamepadMapActivity.createIntent(context)).use { scenario ->
            orient(scenario, Configuration.ORIENTATION_PORTRAIT)
            val entry = label(R.string.input_settings_gamepad_key_a)
            ready(entry)
            node(entry).performTouchInput { click() }
            val key = hasText("A") and inDialog and hasClickAction()
            ready(key)
            node(key).performTouchInput { click() }
            val count = hasText(text(R.string.input_settings_binding_keys, countAfterToggle)) and inDialog
            ready(count)
            for (direction in listOf(Configuration.ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_PORTRAIT)) {
                orient(scenario, direction)
                ready(count)
                node(label(R.string.common_confirm) and inDialog).assertIsDisplayed().assertIsEnabled()
            }
        }
        // Closing the page without Confirm must not write the draft.
        assertEquals(saved, InputRemapRepository.gamepadEntries(context).first { it.id == saved.id })
    }

    @Test fun customHomeAddressDraftSurvivesRotationAndCanBeCancelled() {
        val previous = AppSettingsStore.getHomeWebUrl(context)
        val draft = "https://tablet-draft.invalid/" + java.util.UUID.randomUUID()
        page<AppSettingsActivity>(AppSettingsActivity.createIntent(context)).use { scenario ->
            orient(scenario, Configuration.ORIENTATION_PORTRAIT)
            val entry = label(R.string.settings_home_web_url)
            node(hasScrollToIndexAction()).performScrollToNode(entry)
            node(entry).performScrollTo().performTouchInput { click() }
            val custom = label(R.string.settings_home_web_custom) and inDialog
            ready(custom)
            node(custom).performTouchInput { click() }
            val field = hasSetTextAction() and inDialog
            ready(field)
            node(field).performTextReplacement(draft)
            orient(scenario, Configuration.ORIENTATION_LANDSCAPE)
            ready(field and hasText(draft))
            assertEquals("Editing must not save the URL", previous, AppSettingsStore.getHomeWebUrl(context))
            orient(scenario, Configuration.ORIENTATION_PORTRAIT)
            ready(field and hasText(draft))
            node(label(R.string.common_cancel) and inDialog).performClick()
            compose.mainClock.advanceTimeBy(1_200)
            node(isDialog()).assertDoesNotExist()
        }
        assertEquals("Cancel must leave the saved URL intact", previous, AppSettingsStore.getHomeWebUrl(context))
    }

    @Test fun squareCropKeepsActionRowAndPannedImageAfterRotation() {
        val file = File.createTempFile("tablet-crop-", ".png", context.cacheDir)
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        // Render the production dialog in the debug ui-test-manifest host, including on Android recreation.
        val setup = ActivityLifecycleCallback { activity, stage ->
            if (activity.javaClass == ComponentActivity::class.java && stage == Stage.CREATED) {
                (activity as ComponentActivity).setContent {
                    var open by remember { mutableStateOf(true) }
                    ProvideAppLocale {
                        TyranorNextTheme {
                            if (open) GameShortcutCropDialog(Uri.fromFile(file), onDismiss = { open = false },
                                onPickImage = {}, onConfirm = { error("No shortcut should be published") })
                        }
                    }
                }
            }
        }
        try {
            val pixels = IntArray(300 * 100) { when (it % 300) {
                in 0 until 100 -> Color.RED
                in 100 until 200 -> Color.GREEN
                else -> Color.BLUE
            } }
            val bitmap = Bitmap.createBitmap(pixels, 300, 100, Bitmap.Config.ARGB_8888)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            inst.runOnMainSync { monitor.addLifecycleCallback(setup) }
            page<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use { scenario ->
                val preview = hasTestTag("shortcutCropPreview")
                val confirm = label(R.string.common_confirm) and inDialog
                for (direction in listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
                    orient(scenario, direction)
                    ready(preview)
                    val bounds = node(preview).fetchSemanticsNode().boundsInRoot
                    val root = node(isDialog()).fetchSemanticsNode().boundsInRoot
                    assertEquals("Square crop area", bounds.width, bounds.height, 1f)
                    assertEquals("Centered crop", root.center.x, bounds.center.x, 2f)
                    assertTrue("Tablet crop should not stretch from edge to edge", bounds.width < root.width * 0.8f)
                    node(confirm).assertIsDisplayed().assertIsEnabled()
                    val buttons = node(confirm).fetchSemanticsNode().boundsInRoot
                    assertTrue("Confirm must be below the preview and inside the window",
                        buttons.top >= bounds.bottom && buttons.bottom <= root.bottom && buttons.height > 0)
                    if (direction == Configuration.ORIENTATION_PORTRAIT) {
                        val initial = node(preview).captureToImage().toPixelMap()
                        val center = initial[initial.width / 2, initial.height / 2]
                        assertTrue("Portrait before pan: expected green, got $center",
                            center.green > 0.9f && center.blue < 0.1f && center.red < 0.1f)
                        node(preview).performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f) }
                        compose.mainClock.advanceTimeBy(1_200)
                    }
                    val sample = node(preview).captureToImage().toPixelMap()
                    val center = sample[sample.width / 2, sample.height / 2]
                    val stage = if (direction == Configuration.ORIENTATION_PORTRAIT) "Portrait after pan" else "Landscape after rotation"
                    assertTrue("$stage: expected right-hand blue strip at center, got $center ($bounds)",
                        center.blue > 0.9f && center.green < 0.1f && center.red < 0.1f)
                }
                node(label(R.string.common_cancel) and inDialog).performTouchInput { click() }
                compose.mainClock.advanceTimeBy(1_200)
                node(preview).assertDoesNotExist()
            }
        } finally {
            inst.runOnMainSync { monitor.removeLifecycleCallback(setup) }
            file.delete()
        }
    }
}
