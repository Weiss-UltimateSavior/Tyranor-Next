package com.tyranor.next.ui.save

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.SavePathSafety
import com.tyranor.next.core.game.storage.GameLibraryDatabase
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.save.sync.SaveActivationJournal
import com.tyranor.next.core.game.save.sync.SaveContentHash
import com.tyranor.next.core.game.save.sync.SaveSnapshotStore
import com.tyranor.next.core.game.save.sync.SaveSyncMode
import com.tyranor.next.core.game.save.sync.SaveSyncService
import com.tyranor.next.core.game.save.sync.SaveVersion
import com.tyranor.next.core.game.save.sync.SaveVersionSource
import com.tyranor.next.core.game.save.sync.WebDavConfig
import com.tyranor.next.core.game.save.sync.WebDavSettingsStore
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.AppearanceStyle
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.AppThemeColors
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device UI acceptance for the WebDAV save-sync pages.
 *
 * This test is skipped unless the isolated target package and explicit instrumentation
 * argument are both present. It never performs a network transfer: the configured
 * localhost endpoint is invalid and only local history/dialog UI is exercised.
 *
 * The large-font method requires the device to already use 2x font scale. The
 * device driver owns applying and restoring that setting around the focused run.
 */
@RunWith(AndroidJUnit4::class)
class WebDavUiDeviceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val appContext = targetContext.applicationContext
    private val localizedContext: Context
        get() = AppLocaleController.wrap(appContext)

    private lateinit var preferenceBaseline: Map<String, Map<String, *>>
    private var fixture: Fixture? = null
    private var gateAccepted = false
    private val evidenceRun = safeName(InstrumentationRegistry.getArguments()
        .getString("webdav_run") ?: UUID.randomUUID().toString())

    @Before
    fun setUp() {
        requireAcceptanceTarget()

        assertEquals(
            "The dedicated WebDAV acceptance target must start with an empty game library",
            0,
            runBlocking { GameLibraryDatabase.get(appContext).gameLibraryDao().countGames() },
        )
        assertTrue(GameLibraryFacade.loadGames(appContext).isEmpty())

        preferenceBaseline = PREFERENCE_FILES.associateWith { name ->
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        assertFalse(
            "The dedicated acceptance target must not contain an existing WebDAV payload",
            preferenceBaseline.getValue(PREF_WEBDAV).containsKey("config"),
        )
        gateAccepted = true

        AppSettingsStore.setLanguage(appContext, AppSettingsStore.LANGUAGE_EN)
        AppSettingsStore.setAppearanceStyle(appContext, AppearanceStyle.DEFAULT)
        AppSettingsStore.setThemeMode(appContext, AppSettingsStore.THEME_MODE_LIGHT)
        instrumentation.runOnMainSync { AppThemeColors.refresh(appContext) }

        EngineSettingsStore.saveOns(
            appContext,
            EngineSettingsStore.loadOns(appContext).copy(scopedSaveDir = true),
        )

        val created = Fixture(appContext)
        fixture = created
        GameLibraryFacade.updateGames(appContext) { it + created.game }
        assertTrue(GameLibraryFacade.loadGames(appContext).any { it.uri == created.game.uri })
        runBlocking { awaitLibraryCount(1) }

        WebDavSettingsStore.setEnabled(appContext, true)
        WebDavSettingsStore.save(
            appContext,
            WebDavConfig(
                serverUrl = ACCEPTANCE_ENDPOINT,
                username = ACCEPTANCE_USERNAME,
                password = ACCEPTANCE_PASSWORD,
                mode = SaveSyncMode.MANUAL,
                remoteLimit = 10,
                showSyncFeedback = false,
            ),
        )
    }

    @After
    fun tearDown() {
        if (!gateAccepted) return
        val failures = mutableListOf<Throwable>()

        fixture?.let { active ->
            runCatching {
                runBlocking { SaveSyncService(appContext).clearGame(active.game) }
            }.onFailure(failures::add)
            runCatching {
                runBlocking {
                    GameLibraryFacade.removeGame(appContext, active.game.uri)
                    awaitLibraryCount(0)
                    withTimeout(LIBRARY_CLEANUP_TIMEOUT_MS) {
                        while (appContext.getSharedPreferences("game_scanner", Context.MODE_PRIVATE)
                                .getString("scan_games", "").orEmpty().contains(active.game.uri)) delay(50)
                    }
                    val mirror = appContext.getSharedPreferences("game_scanner", Context.MODE_PRIVATE)
                    check(mirror.edit().putString("scan_games", mirror.getString("scan_games", "")).commit())
                }
            }.onFailure(failures::add)
            runCatching { active.deleteOwnedFiles() }.onFailure(failures::add)
        }

        runCatching {
            WebDavSettingsStore.setEnabled(appContext, true)
            WebDavSettingsStore.reset(appContext)
        }.onFailure(failures::add)

        runCatching {
            preferenceBaseline.forEach { (name, values) -> restorePreferences(name, values) }
            AppSettingsStore.initLanguage(appContext)
            instrumentation.runOnMainSync { AppThemeColors.refresh(appContext) }
            WebDavSettingsStore.isEnabled(appContext)
        }.onFailure(failures::add)

        fixture = null
        gateAccepted = false
        failures.firstOrNull()?.let { throw AssertionError("WebDAV UI acceptance cleanup failed", it) }
    }

    @Test(timeout = 60_000)
    fun passwordInputHasLabelMaskAndPasswordSemantics() {
        launchWebDav().use { scenario ->
            waitForText(R.string.webdav_title)
            listOf(R.string.webdav_server_url, R.string.webdav_username, R.string.webdav_password)
                .forEach { labelRes ->
                    composeRule.onNodeWithText(text(labelRes))
                        .performScrollTo()
                        .assertIsDisplayed()
                }

            val passwordLabel = text(R.string.webdav_password)
            waitForNode(hasText(passwordLabel) and hasClickAction())
            composeRule.onNodeWithText(passwordLabel).performScrollTo().performClick()

            val passwordField = hasSetTextAction() and
                hasContentDescription(passwordLabel, substring = true)
            waitForNode(passwordField)
            val field = composeRule.onNode(passwordField)
            field.assertContentDescriptionContains(passwordLabel)
            field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

            val shownText = field.fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text
            assertEquals("\u2022".repeat(ACCEPTANCE_PASSWORD.length), shownText)
            assertFalse(shownText.contains(ACCEPTANCE_PASSWORD))

            captureEvidence("webdav-password-semantics")
            composeRule.onNodeWithText(text(R.string.common_cancel)).performClick()
        }
    }

    @Test(timeout = 60_000)
    fun manualDirectionSurvivesActivityRecreate() {
        val manualLabel = text(R.string.webdav_manual_all)
        val uploadLabel = text(R.string.save_sync_direction_upload)

        launchWebDav().use { scenario ->
            waitForText(R.string.webdav_title)
            waitForNode(hasText(manualLabel) and hasClickAction())
            composeRule.onNodeWithText(manualLabel).performScrollTo().performClick()
            waitForText(uploadLabel)
            composeRule.onNodeWithText(uploadLabel).performClick()

            scenario.recreate()
            waitForText(R.string.webdav_title)
            waitForNode(hasText(manualLabel) and hasClickAction())
            composeRule.onNodeWithText(manualLabel).performScrollTo().performClick()
            waitForNode(hasText(uploadLabel) and isSelected())
            composeRule.onNode(hasText(uploadLabel) and isSelected()).assertExists()

            captureEvidence("webdav-manual-direction-after-recreate")
        }
    }

    @Test(timeout = 75_000)
    fun activationRequiresExplicitSelectionAndShowsRestoredTimes() {
        val active = requireNotNull(fixture)
        val version = active.seedRestoredVersion()
        val expectedRestoredRow = restoredRowText(version)
        val activateLabel = text(R.string.save_version_activate)
        val versionsLabel = text(R.string.save_versions_title)

        launchSaveManagement().use { scenario ->
            waitForText(R.string.save_management_title)
            waitForText(versionsLabel)
            composeRule.onNodeWithText(activateLabel)
                .performScrollTo()
                .assertIsNotEnabled()

            composeRule.onNodeWithText(versionsLabel).performScrollTo().performClick()
            waitForText(expectedRestoredRow)
            captureEvidence("webdav-version-dropdown")
            composeRule.onNodeWithText(expectedRestoredRow).performClick()
            composeRule.onNodeWithText(activateLabel)
                .performScrollTo()
                .assertIsEnabled()
                .performClick()

            waitForText(R.string.save_version_switch_title)
            val restoredInDialog = hasText(expectedRestoredRow) and hasAnyAncestor(hasScrollAction())
            composeRule.onNode(restoredInDialog).performScrollTo().assertIsDisplayed()

            captureEvidence("webdav-activation-confirm-restored-times")
            composeRule.onNodeWithText(text(R.string.common_cancel)).performClick()
        }
    }

    @Test(timeout = 90_000)
    fun longResetPolicyDialogScrollsAtTwiceFontInLandscape() {
        assumeTrue("Run this method with the actual device font_scale set to 2.0",
            kotlin.math.abs(appContext.resources.configuration.fontScale - 2f) < 0.01f)
        deleteWebDavKeystoreAlias()

        launchWebDav().use { scenario ->
            requestLandscape(scenario)
            waitForText(R.string.webdav_title)
            assertActivityConfig(scenario, Configuration.ORIENTATION_LANDSCAPE, 2f)

            val resetLabel = text(R.string.webdav_reset_config)
            waitForNode(hasText(resetLabel) and hasClickAction())
            composeRule.onNodeWithText(resetLabel).performScrollTo().performClick()
            waitForText(R.string.webdav_reset_config_title)

            val message = text(R.string.webdav_reset_config_message)
            // A single Text is merged into the scroll container's semantics.
            val messageInScrollable = hasText(message) and hasScrollAction()
            waitForNode(messageInScrollable)
            val body = composeRule.onNode(messageInScrollable)
            val scrollRange = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("The long policy must actually overflow", scrollRange.maxValue() > 0f)
            composeRule.onNodeWithText(text(R.string.common_confirm)).assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.common_cancel)).assertIsDisplayed()
            val confirmBounds = composeRule.onNodeWithText(text(R.string.common_confirm))
                .fetchSemanticsNode().boundsInRoot
            body.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                assertTrue(scrollBy(0f, 1_000f))
            }
            composeRule.waitForIdle()
            assertTrue("Policy must scroll", scrollRange.value() > 0f)
            assertEquals(confirmBounds, composeRule.onNodeWithText(text(R.string.common_confirm))
                .fetchSemanticsNode().boundsInRoot)

            captureEvidence("webdav-reset-policy-landscape-font2x")
            composeRule.onNodeWithText(text(R.string.common_cancel)).performClick()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test(timeout = 75_000)
    fun recoveryRemainsAvailableWhenSyncIsDisabled() {
        val active = requireNotNull(fixture)
        val version = active.seedRestoredVersion()
        active.seedPendingRecovery(version)
        WebDavSettingsStore.setEnabled(appContext, false)

        launchSaveManagement().use { scenario ->
            waitForText(R.string.save_management_title)
            val recoveryLabel = text(R.string.save_version_recover)
            waitForNode(hasText(recoveryLabel) and hasClickAction())
            composeRule.onNodeWithText(recoveryLabel)
                .performScrollTo()
                .assertHasClickAction()
                .performClick()

            waitForText(R.string.save_version_recovery_policy)
            composeRule.onNodeWithText(text(R.string.save_version_recovery_policy)).assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.common_confirm)).assertIsDisplayed()
            assertTrue(
                "Version selection must stay hidden while WebDAV is disabled",
                composeRule.onAllNodesWithText(text(R.string.save_versions_title))
                    .fetchSemanticsNodes().isEmpty(),
            )

            captureEvidence("webdav-recovery-available-disabled")
            composeRule.onNodeWithText(text(R.string.common_cancel)).performClick()
        }
    }

    @Test(timeout = 90_000)
    fun webDavPageRendersInAllThemeFamilies() {
        val version = requireNotNull(fixture).seedRestoredVersion()
        THEME_CASES.forEach { theme ->
            applyTheme(theme.style, theme.mode)
            launchWebDav().use {
                waitForText(R.string.webdav_title)
                captureEvidence("webdav-theme-${theme.name}")
            }
            launchSaveManagement().use {
                waitForText(R.string.save_versions_title)
                captureEvidence("save-management-theme-${theme.name}")
                if (theme.name == "default-light") {
                    composeRule.onNodeWithText(text(R.string.save_versions_title))
                        .performScrollTo().performClick()
                    waitForText(restoredRowText(version))
                    captureEvidence("save-management-dropdown-default-light")
                }
            }
        }
    }

    private fun launchWebDav(): ActivityScenario<WebDavSettingsActivity> =
        ActivityScenario.launch(WebDavSettingsActivity.createIntent(targetContext))

    private fun launchSaveManagement(): ActivityScenario<SaveManagementActivity> {
        val active = requireNotNull(fixture)
        return ActivityScenario.launch(SaveManagementActivity.createIntent(targetContext, active.game))
    }

    private fun waitForText(stringRes: Int) = waitForText(text(stringRes))

    private fun waitForText(value: String) {
        composeRule.waitUntil(NODE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForNode(matcher: SemanticsMatcher) {
        composeRule.waitUntil(NODE_TIMEOUT_MS) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun text(stringRes: Int, vararg args: Any): String =
        localizedContext.getString(stringRes, *args)

    private fun restoredRowText(version: SaveVersion): String {
        val formatter = SimpleDateFormat("yy.MM.dd HH:mm", Locale.ROOT)
        val previous = requireNotNull(version.previousCreatedAt)
        return localizedContext.getString(
            R.string.save_version_restored_row,
            text(R.string.save_version_local),
            formatter.format(Date(previous)),
            formatter.format(Date(version.createdAt)),
        )
    }

    private fun requestLandscape(scenario: ActivityScenario<WebDavSettingsActivity>) {
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitForActivity(scenario) {
            it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    private fun assertActivityConfig(
        scenario: ActivityScenario<WebDavSettingsActivity>,
        orientation: Int,
        fontScale: Float,
    ) {
        var actualOrientation = Configuration.ORIENTATION_UNDEFINED
        var actualFontScale = 0f
        scenario.onActivity {
            actualOrientation = it.resources.configuration.orientation
            actualFontScale = it.resources.configuration.fontScale
        }
        assertEquals("Activity orientation", orientation, actualOrientation)
        assertEquals("App-local font scale", fontScale, actualFontScale, 0.01f)
    }

    private fun <A : Activity> waitForActivity(
        scenario: ActivityScenario<A>,
        predicate: (A) -> Boolean,
    ) {
        composeRule.waitUntil(CONFIG_TIMEOUT_MS) {
            var matches = false
            runCatching {
                scenario.onActivity { activity -> matches = predicate(activity) }
            }
            matches
        }
    }

    private fun applyTheme(style: AppearanceStyle, mode: String) {
        AppSettingsStore.setAppearanceStyle(appContext, style)
        AppSettingsStore.setThemeMode(appContext, mode)
        instrumentation.runOnMainSync { AppThemeColors.refresh(appContext) }
    }

    private fun captureEvidence(name: String) {
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)

        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "UiAutomation.takeScreenshot returned null for $name"
        }
        val evidenceRoot = requireNotNull(targetContext.getExternalFilesDir(EVIDENCE_DIRECTORY)) {
            "External evidence directory unavailable"
        }
        val directory = File(evidenceRoot, evidenceRun)
        check(directory.isDirectory || directory.mkdirs())
        val profile = safeName(InstrumentationRegistry.getArguments()
            .getString("webdav_evidence_profile") ?: "phone")
        val file = File(directory, "${System.currentTimeMillis()}-$profile-${safeName(name)}.png")
        try {
            FileOutputStream(file).use { output ->
                assertTrue("Failed to encode evidence ${file.name}", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        Log.i(TAG, "WEBDAV_UI_ARTIFACT name=${file.name} path=${file.absolutePath}")
        println("WEBDAV_UI_ARTIFACT name=${file.name} path=${file.absolutePath}")
    }

    private fun deleteWebDavKeystoreAlias() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(WEBDAV_KEY_ALIAS)
    }

    private fun restorePreferences(name: String, values: Map<String, *>) {
        val preferences = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(
                    key,
                    value.map { item ->
                        check(item is String) { "Unsupported preference set value for $name/$key" }
                        item
                    }.toSet(),
                )
                else -> error("Unsupported preference value for $name/$key: ${value?.javaClass}")
            }
        }
        check(editor.commit()) {
            "Failed to restore $name preferences"
        }
    }

    private suspend fun awaitLibraryCount(expected: Int) {
        val dao = GameLibraryDatabase.get(appContext).gameLibraryDao()
        withTimeout(LIBRARY_CLEANUP_TIMEOUT_MS) {
            while (dao.countGames() != expected) delay(50)
        }
    }

    private fun safeName(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "-").trim('-')

    private fun requireAcceptanceTarget() {
        assumeTrue(
            "Run only with -e $ARG_WEBDAV_ACCEPTANCE true",
            InstrumentationRegistry.getArguments().getString(ARG_WEBDAV_ACCEPTANCE) == "true",
        )
        assumeTrue(
            "WebDAV UI acceptance requires an isolated webdavacceptance application ID",
            targetContext.packageName.startsWith(TARGET_PACKAGE_PREFIX),
        )
    }

    private class Fixture(app: Context) {
        private val appContext = app.applicationContext
        private val uuid = UUID.randomUUID().toString()
        private val manager = GameSaveManager(appContext)
        private val gameRoot = File(appContext.cacheDir, "webdav-ui-$uuid").apply { check(mkdirs()) }
        private val saveSyncRoot = File(appContext.noBackupFilesDir, "save_sync")
        private val snapshots = SaveSnapshotStore(File(saveSyncRoot, "versions"))
        private val activations = SaveActivationJournal(File(saveSyncRoot, "pending"))
        private val localKey = SaveContentHash.text(Uri.fromFile(gameRoot).toString())

        val game = ScanGame(
            title = "WebDAV UI Acceptance $uuid",
            uri = Uri.fromFile(gameRoot).toString(),
            engine = EngineType.ONS,
            launchTarget = "",
        )
        val saveDirectory: File = requireNotNull(manager.resolveSaveLocation(game).directory) {
            "The ONS scoped save directory was unavailable"
        }.apply {
            check(isDirectory || mkdirs())
        }

        init {
            write(saveDirectory, "slot.dat", "current".toByteArray(Charsets.UTF_8))
        }

        fun seedRestoredVersion(): SaveVersion {
            val archive = File(gameRoot, "restored-input.zip")
            writeZip(archive, "slot.dat", "selected-save".toByteArray(Charsets.UTF_8))
            return snapshots.add(
                localKey,
                archive,
                VERSION_CREATED_AT,
                SaveVersionSource.LOCAL,
                restored = true,
                previousCreatedAt = VERSION_PREVIOUS_AT,
            ).also { check(snapshots.isValid(localKey, it)) }
        }

        fun seedPendingRecovery(version: SaveVersion) {
            val staging = File(gameRoot, "pending-$uuid.zip")
            snapshots.file(localKey, version.id).copyTo(staging, overwrite = true)
            activations.prepare(
                localKey,
                version,
                staging,
                beforeHash = SaveContentHash.text("before-$uuid"),
            )
        }

        fun deleteOwnedFiles() {
            deleteExactTree(gameRoot, appContext.cacheDir)
            deleteExactTree(saveDirectory, File(requireNotNull(appContext.getExternalFilesDir(null)), "save"))

            val versionsRoot = File(saveSyncRoot, "versions")
            val recordsRoot = File(saveSyncRoot, "pending/records")
            val archivesRoot = File(saveSyncRoot, "pending/archives")
            val checkpointsRoot = File(saveSyncRoot, "checkpoints")
            val sessionsRoot = File(saveSyncRoot, "sessions")
            val baselinesRoot = File(saveSyncRoot, "baselines")

            deleteExactTree(File(versionsRoot, localKey), versionsRoot)
            deleteExactFile(File(recordsRoot, "$localKey.json"), recordsRoot)
            deleteExactFile(File(archivesRoot, "$localKey.zip"), archivesRoot)
            deleteExactFile(File(checkpointsRoot, "$localKey.json"), checkpointsRoot)
            deleteExactFile(File(sessionsRoot, "$localKey.json"), sessionsRoot)
            deleteExactFile(File(baselinesRoot, "$localKey.json"), baselinesRoot)
        }

        private fun deleteExactTree(target: File, expectedParent: File) {
            if (!target.exists()) return
            check(target.canonicalFile.parentFile == expectedParent.canonicalFile) {
                "Refusing to delete unexpected fixture path: ${target.absolutePath}"
            }
            SavePathSafety.deleteTreeNoFollow(target)
        }

        private fun deleteExactFile(target: File, expectedParent: File) {
            if (!target.exists()) return
            check(target.canonicalFile.parentFile == expectedParent.canonicalFile) {
                "Refusing to delete unexpected fixture file: ${target.absolutePath}"
            }
            check(target.delete()) { "Failed to delete fixture file: ${target.absolutePath}" }
        }

        private fun write(root: File, name: String, bytes: ByteArray): File =
            File(root, name).apply {
                check(parentFile?.isDirectory == true || parentFile?.mkdirs() == true)
                FileOutputStream(this).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
            }

        private fun writeZip(file: File, name: String, bytes: ByteArray) {
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                zip.putNextEntry(ZipEntry(name).apply { time = VERSION_PREVIOUS_AT })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private data class ThemeCase(val name: String, val style: AppearanceStyle, val mode: String)

    private companion object {
        const val TAG = "WebDavUiDeviceTest"
        const val ARG_WEBDAV_ACCEPTANCE = "webdav_device_acceptance"
        const val TARGET_PACKAGE_PREFIX = "com.tyranor.next.webdavacceptance"
        const val EVIDENCE_DIRECTORY = "webdav-acceptance-evidence"
        const val ACCEPTANCE_ENDPOINT = "http://127.0.0.1:1/webdav-acceptance/"
        const val ACCEPTANCE_USERNAME = "acceptance-user"
        const val ACCEPTANCE_PASSWORD = "acceptance-secret"
        const val WEBDAV_KEY_ALIAS = "tyranor_webdav_sync"
        const val PREF_WEBDAV = "webdav_sync_secure"
        const val PREF_APP = "app_settings"
        const val PREF_ONS = "onsyuri"
        val PREFERENCE_FILES = listOf(PREF_WEBDAV, PREF_APP, PREF_ONS)
        const val NODE_TIMEOUT_MS = 12_000L
        const val CONFIG_TIMEOUT_MS = 15_000L
        const val LIBRARY_CLEANUP_TIMEOUT_MS = 5_000L
        const val VERSION_PREVIOUS_AT = 1_700_000_000_000L
        const val VERSION_CREATED_AT = VERSION_PREVIOUS_AT + 2L * 24 * 60 * 60 * 1_000
        val THEME_CASES = listOf(
            ThemeCase("default-light", AppearanceStyle.DEFAULT, AppSettingsStore.THEME_MODE_LIGHT),
            ThemeCase("default-dark", AppearanceStyle.DEFAULT, AppSettingsStore.THEME_MODE_DARK),
            ThemeCase("retro-glass", AppearanceStyle.RETRO_GLASS, AppSettingsStore.THEME_MODE_LIGHT),
            ThemeCase("advanced-glass", AppearanceStyle.ADVANCED_GLASS, AppSettingsStore.THEME_MODE_LIGHT),
        )
    }
}
