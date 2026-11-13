package com.tyranor.next.ui.tablet

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Display
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.CmvsPaths
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.sync.WebDavSettingsStore
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.GraphicsFamily
import com.tyranor.next.core.settings.GraphicsFilterCatalog
import com.tyranor.next.core.settings.GraphicsFilterCategory
import com.tyranor.next.core.unpack.ArchiveStaging
import com.tyranor.next.ui.archive.ArchiveUnpackActivity
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.patch.KrkrOnlinePatchActivity
import com.tyranor.next.ui.save.SaveManagementActivity
import com.tyranor.next.ui.save.WebDavSettingsActivity
import com.tyranor.next.ui.settings.EngineSettingsActivity
import com.tyranor.next.ui.settings.EngineSettingsKind
import com.tyranor.next.ui.settings.InputSettingsActivity
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real secondary Activity entry points. Run serially on the round-two isolated Pixel Tablet. */
@RunWith(AndroidJUnit4::class)
class MotionTabletSecondaryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val dialog = hasAnyAncestor(isDialog())
    private val list = hasScrollToIndexAction()
    private val field = hasSetTextAction()
    private var restoreAngle: Int? = null
    private var autoRotate = false

    @Before fun requireIsolatedTablet() {
        MotionDeviceAcceptance.require(enhanced = null)
        assumeTrue("API 36 tablet emulator required", Build.VERSION.SDK_INT == 36 &&
            Build.HARDWARE in setOf("ranchu", "goldfish") && context.resources.configuration.smallestScreenWidthDp >= 600)
        val property = inst.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
        val avd = ParcelFileDescriptor.AutoCloseInputStream(property).bufferedReader().use { it.readText().trim() }
        assumeTrue("Requires the designated Pixel Tablet AVD, found '$avd'", avd == "tyn_motion_r2_pixel_tablet_api36")
        autoRotate = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
        compose.mainClock.autoAdvance = false
    }

    @After fun restoreDisplay() {
        restoreAngle?.let { angle ->
            try {
                assertTrue(inst.uiAutomation.setRotation(angle))
            } finally {
                if (autoRotate) assertTrue(inst.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE))
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = AppLocaleController.wrap(context).getString(id, *args)
    private fun label(id: Int) = hasText(text(id))
    private fun action(id: Int) = label(id) and hasClickAction()
    private fun node(m: SemanticsMatcher) = compose.onNode(m)
    private fun bounds(m: SemanticsMatcher) = node(m).fetchSemanticsNode().boundsInRoot
    private fun settle() = compose.mainClock.advanceTimeBy(1_200)

    private fun ready(m: SemanticsMatcher) {
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodes(m).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        settle()
        node(m).assertIsDisplayed()
    }

    private fun tap(m: SemanticsMatcher) {
        ready(m and isEnabled())
        node(m).assertIsEnabled().performTouchInput { click() }
        settle()
    }

    private fun reveal(m: SemanticsMatcher) {
        node(list).performScrollToNode(m)
        node(m).performScrollTo()
        ready(m)
    }

    private inline fun <reified A : Activity> page(intent: Intent) =
        ActivityScenario.launch<A>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun <A : Activity> rotate(scenario: ActivityScenario<A>, direction: Int) {
        lateinit var type: Class<*>
        var angle = 0
        scenario.onActivity { activity ->
            type = activity.javaClass
            val current = requireNotNull(activity.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)).rotation
            if (restoreAngle == null) {
                restoreAngle = Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, current)
            }
            angle = if (activity.resources.configuration.orientation == direction) current else (current + 1) % 4
        }
        assertTrue("Display refused rotation $angle", inst.uiAutomation.setRotation(angle))
        compose.waitUntil(15_000) {
            var measured = false
            inst.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .singleOrNull { it.javaClass == type } ?: return@runOnMainSync
                val view = resumed.window.decorView
                measured = resumed.resources.configuration.orientation == direction && view.width > 0 && view.height > 0 &&
                    (if (direction == ORIENTATION_LANDSCAPE) view.width > view.height else view.height > view.width)
            }
            measured
        }
        settle()
    }

    @Test fun siglusFilterCategoryAndPresetPanesSurviveBothRotations() {
        val before = EngineSettingsStore.getGraphicsSettings(context, GraphicsFamily.SIGLUS)
        val language = AppLocaleController.wrap(context).resources.configuration.locales[0].language
        val anime = GraphicsFilterCategory.entries.single { it.name == "Anime4K" }
        val titles = GraphicsFilterCatalog.options(language).toMap()
        val category = hasText(anime.title(language)) and hasClickAction() and dialog
        fun preset(id: String) = hasText(titles.getValue(id)) and hasClickAction() and dialog
        val first = preset(anime.presetIds.first())
        val last = preset(anime.presetIds.last())
        val back = action(R.string.graphics_filter_back_categories) and dialog
        val cancel = action(R.string.common_cancel) and dialog
        val add = action(R.string.graphics_chain_add)
        val title = label(EngineSettingsKind.SIGLUS.titleRes)
        page<EngineSettingsActivity>(EngineSettingsActivity.createIntent(context, EngineSettingsKind.SIGLUS)).use { scenario ->
            rotate(scenario, ORIENTATION_LANDSCAPE)
            ready(title)
            reveal(add)
            tap(add)
            tap(category)
            var sawTwoPanes = false
            for (direction in listOf(ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE)) {
                rotate(scenario, direction)
                ready(first)
                ready(back)
                ready(cancel)
                // SpaceBetween pins these two buttons to the edges of the actual dialog body.
                val bodyPx = bounds(cancel).right - bounds(back).left
                val resources = context.resources
                val wide = bodyPx >= 520f * resources.displayMetrics.density * resources.configuration.fontScale.coerceAtLeast(1f)
                // The clickable scrim may merge a list's scroll semantics; measure the actual list nodes.
                compose.onAllNodes(list and dialog, useUnmergedTree = true).assertCountEquals(if (wide) 2 else 1)
                if (wide) {
                    sawTwoPanes = true
                    ready(category)
                    val left = bounds(category)
                    fun paneShowing(id: String) = compose.onNode(
                        list and dialog and hasAnyDescendant(hasText(titles.getValue(id))), useUnmergedTree = true,
                    )
                    val right = paneShowing(anime.presetIds.first())
                    assertTrue("Presets must be beside categories", left.right <= right.fetchSemanticsNode().boundsInRoot.left)
                    right.performScrollToIndex(anime.presetIds.lastIndex)
                    ready(last)
                    assertEquals("Scrolling presets moved categories", left, bounds(category))
                    paneShowing(anime.presetIds.last()).performScrollToIndex(0)
                    ready(first)
                } else {
                    node(category).assertDoesNotExist()
                }
                assertTrue("Footer overlaps preset", bounds(first).bottom <= bounds(cancel).top + 1f)
            }
            assertTrue("Run with the baseline tablet font scale to exercise the dual pane", sawTwoPanes)
            tap(back)
            ready(category)
            node(first).assertDoesNotExist()
            tap(cancel)
            node(isDialog()).assertDoesNotExist()
            ready(add)
        }
        assertEquals(before, EngineSettingsStore.getGraphicsSettings(context, GraphicsFamily.SIGLUS))
    }

    @Test fun archivePackLevelAndModeSurviveBothRotations() {
        // Opening the real page cleans stale staging. Leave any pre-existing staging alone.
        assumeTrue("Archive staging must be unused", !ArchiveStaging.stagingDir(context).exists())
        val packTab = action(R.string.archive_mode_pack) and !hasAnyAncestor(list)
        val packButton = action(R.string.archive_pack_action) and hasAnyAncestor(list)
        val choose = action(R.string.archive_pack_pick_dir)
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        page<ArchiveUnpackActivity>(ArchiveUnpackActivity.createIntent(context)).use { scenario ->
            rotate(scenario, ORIENTATION_PORTRAIT)
            ready(action(R.string.archive_pick_source_dir))
            tap(packTab)
            ready(choose)
            ready(slider)
            node(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
            for (direction in listOf(ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT)) {
                rotate(scenario, direction)
                reveal(choose)
                node(choose).assertIsEnabled()
                reveal(slider)
                ready(hasText("3"))
                reveal(packButton)
                node(packButton).assertIsNotEnabled()
                assertTrue("Pack tab overlaps unpack tab", bounds(action(R.string.archive_mode_unpack)).right <= bounds(packTab).left)
            }
            tap(action(R.string.archive_mode_unpack))
            ready(action(R.string.archive_pick_source_dir))
            node(packButton).assertDoesNotExist()
        }
    }

    @Test fun saveDeleteCancelKeepsGameAndSlotInBothOrientations() {
        val id = UUID.randomUUID().toString()
        val game = ScanGame(title = "Tablet CMVS $id", uri = "content://tablet-fixture/$id", engine = EngineType.CMVS, launchTarget = "")
        val dir = CmvsPaths.userDataDir(context.filesDir, game.uri)
        check(!dir.exists() && dir.mkdirs())
        val slot = File(dir, "save001.dat")
        val bytes = "slot-$id".toByteArray()
        try {
            slot.writeBytes(bytes)
            val count = hasText(text(R.string.save_file_count, 1))
            val entry = action(R.string.save_delete_title)
            page<SaveManagementActivity>(SaveManagementActivity.createIntent(context, game)).use { scenario ->
                for (direction in listOf(ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT)) {
                    rotate(scenario, direction)
                    reveal(hasText(game.title))
                    ready(count)
                    reveal(entry)
                    tap(entry)
                    ready(hasText(text(R.string.save_delete_message, game.title)) and dialog)
                    val cancel = action(R.string.common_cancel) and dialog
                    val delete = action(R.string.common_delete) and dialog
                    ready(delete)
                    assertTrue("Delete overlaps Cancel", bounds(cancel).right <= bounds(delete).left)
                    tap(cancel)
                    node(isDialog()).assertDoesNotExist()
                    ready(entry)
                    assertArrayEquals("Cancel modified the save slot", bytes, slot.readBytes())
                }
            }
        } finally {
            if (slot.exists()) check(slot.delete())
            check(dir.delete())
        }
    }

    @Test fun webDavDirectionAndUrlCancelInBothOrientations() {
        // The dedicated package starts with sync disabled. Seed just the master switch on a
        // pristine installation; never open an existing account or write an encrypted payload.
        val prefs = context.getSharedPreferences("webdav_sync_secure", Context.MODE_PRIVATE)
        assumeTrue("Requires an unused WebDAV configuration", prefs.all.isEmpty())
        val row = action(R.string.webdav_server_url)
        val directionRow = action(R.string.webdav_manual_all)
        val upload = label(R.string.save_sync_direction_upload)
        val cancel = action(R.string.common_cancel) and dialog
        val edit = field and dialog
        try {
            WebDavSettingsStore.setEnabled(context, true)
            page<WebDavSettingsActivity>(WebDavSettingsActivity.createIntent(context)).use { scenario ->
                rotate(scenario, ORIENTATION_LANDSCAPE)
                ready(label(R.string.webdav_title))
                reveal(directionRow)
                tap(directionRow)
                tap(upload)
                for (direction in listOf(ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE)) {
                    rotate(scenario, direction)
                    ready(label(R.string.webdav_title))
                    reveal(directionRow)
                    ready(directionRow and upload)
                    reveal(row)
                    tap(row)
                    ready(edit)
                    val draft = "https://tablet.invalid/$direction"
                    node(edit).performTextReplacement(draft)
                    ready(edit and hasText(draft))
                    ready(cancel)
                    node(action(R.string.common_confirm) and dialog).assertIsDisplayed().assertIsEnabled()
                    assertTrue("URL field overlaps footer", bounds(edit).bottom <= bounds(cancel).top + 1f)
                    tap(cancel)
                    node(isDialog()).assertDoesNotExist()
                    assertTrue("Cancel saved a WebDAV endpoint", WebDavSettingsStore.load(context).serverUrl.isEmpty())
                }
            }
        } finally {
            // setEnabled(true) on empty prefs writes precisely these two keys.
            check(prefs.edit().remove("enabled").remove("disable_generation").commit())
            assertFalse(WebDavSettingsStore.isEnabled(context))
            assertTrue("Config was persisted", prefs.all.isEmpty())
        }
    }

    @Test fun newInputProfileDraftSurvivesBothRotationsWithoutCreatingProfile() {
        val before = InputRemapRepository.listProfileSummaries(context)
        val active = InputRemapRepository.globalProfileId(context)
        val draft = "Tablet profile " + UUID.randomUUID()
        val newProfile = action(R.string.input_settings_profile_new)
        val edit = field and dialog
        page<InputSettingsActivity>(InputSettingsActivity.createIntent(context)).use { scenario ->
            rotate(scenario, ORIENTATION_PORTRAIT)
            // The first scrolling card and the fixed top bar share the same localized title.
            ready(label(R.string.input_settings_title) and !hasAnyAncestor(list))
            reveal(newProfile)
            tap(newProfile)
            ready(edit)
            node(edit).performTextReplacement(draft)
            for (direction in listOf(ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT)) {
                rotate(scenario, direction)
                ready(edit and hasText(draft))
                val confirm = action(R.string.common_confirm) and dialog
                ready(confirm)
                node(confirm).assertIsEnabled()
                node(action(R.string.common_cancel) and dialog).assertIsDisplayed()
                assertTrue("Draft overlaps confirm", bounds(edit).bottom <= bounds(confirm).top + 1f)
            }
            tap(action(R.string.common_cancel) and dialog)
            node(isDialog()).assertDoesNotExist()
            reveal(newProfile)
        }
        assertEquals(before, InputRemapRepository.listProfileSummaries(context))
        assertEquals(active, InputRemapRepository.globalProfileId(context))
    }

    @Suppress("DEPRECATION")
    private fun requireOffline() {
        // loadIndex() is automatic on each creation. Only enter on an already offline AVD;
        // do not change radios, DNS, the app's HTTP client or the response.
        val automation = inst.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.ACCESS_NETWORK_STATE)
        try {
            val cm = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
            assumeTrue("Patch page requires an already disconnected AVD", cm.allNetworks.isEmpty())
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    @Test fun offlinePatchSearchDraftSurvivesBothRotations() {
        requireOffline()
        val id = UUID.randomUUID().toString()
        val game = ScanGame(title = "Tablet KRKR $id", uri = "content://tablet-fixture/$id", engine = EngineType.KIRIKIRI, launchTarget = "")
        val title = label(R.string.patch_title)
        page<KrkrOnlinePatchActivity>(KrkrOnlinePatchActivity.createIntent(context, game)).use { scenario ->
            requireOffline()
            rotate(scenario, ORIENTATION_PORTRAIT)
            ready(field and hasText(game.title))
            var draft = "tablet-patch-$id"
            node(field).performTextReplacement(draft)
            for (direction in listOf(ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT)) {
                requireOffline()
                rotate(scenario, direction)
                ready(field and hasText(draft))
                // Wait for the real lazy-list error item, independent of DNS error wording.
                compose.waitUntil(25_000) {
                    compose.mainClock.advanceTimeBy(100)
                    node(list).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("message") >= 0
                }
                ready(title)
                node(hasContentDescription(text(R.string.patch_refresh_content_description))).assertIsDisplayed()
                assertTrue("Search field overlaps the toolbar", bounds(field).top >= bounds(title).bottom - 1f)
                draft += "-edited"
                node(field).performTextReplacement(draft)
                ready(field and hasText(draft))
            }
        }
    }
}
