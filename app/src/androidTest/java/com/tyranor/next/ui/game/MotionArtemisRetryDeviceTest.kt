package com.tyranor.next.ui.game

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppStorageNames
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses a private filename-only fixture to exercise real confirmation dismissal without launching a game. */
class MotionArtemisRetryDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** Ordinary mode can retry launch preparation in the same drawer after cancelling its confirmation. */
    @Test
    fun ordinaryArtemisConfirmationCanBeCancelledAndReopened() = verifyRetry(enhanced = false)

    /** Enhanced mode releases preparation only through the actual animated dialog dismissal callback. */
    @Test
    fun enhancedArtemisConfirmationCanBeCancelledAndReopened() = verifyRetry(enhanced = true)

    /** Keeps the drawer composed, closes its real confirmation with Back, then requests it again. */
    private fun verifyRetry(enhanced: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(AppStorageNames.ENGINE_PREFS, Context.MODE_PRIVATE)
        val kernelKey = EngineSettingsStore.KEY_ARTEMIS_KERNEL
        val patchKey = EngineSettingsStore.KEY_ARTEMIS_AUTO_PATCH
        val previousKernel = prefs.getString(kernelKey, null)
        val previousPatch = prefs.getString(patchKey, null)
        val hadKernel = prefs.contains(kernelKey)
        val hadPatch = prefs.contains(patchKey)
        var previousMotion = true
        instrumentation.runOnMainSync {
            previousMotion = MotionMode.enhanced
        }
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "motion-artemis-retry-").toFile()
        val archive = directory.resolve("fixture.pfs")
        val launchLabel = context.getString(R.string.game_launch_action)
        val confirmationTitle = context.getString(R.string.game_auto_patch_title)
        var visible by mutableStateOf(true)
        var sheetDismissRequests = 0
        var contentInstalled = false
        try {
            instrumentation.runOnMainSync { MotionMode.sync(enhanced) }
            assertTrue(archive.createNewFile())
            assertTrue(
                prefs.edit()
                    .putString(kernelKey, EngineSettingsStore.ART_KERNEL_OFFICIAL)
                    .putString(patchKey, EngineSettingsStore.AUTO_PATCH_ASK)
                    .commit(),
            )
            val game = ScanGame(
                title = "Motion Artemis retry fixture",
                uri = Uri.fromFile(directory).toString(),
                engine = EngineType.ARTEMIS,
                launchTarget = "",
            )
            // Check the real fixture precondition before activating any launch callback.
            assertTrue(runBlocking(Dispatchers.IO) {
                withTimeout(10_000) { EngineLauncher.needsArtemisPatchConfirm(context, game) }
            })
            contentInstalled = true
            compose.setContent {
                TyranorNextTheme {
                    if (visible) {
                        GameActionsSheet(
                            game = game,
                            onDismiss = { sheetDismissRequests++; visible = false },
                            onGameUpdated = { error("This fixture must not update the library") },
                            onEngineSettings = { error("This fixture must not navigate to settings") },
                        )
                    }
                }
            }
            compose.onNodeWithText(launchLabel).assertExists()
            val drawerDialogCount = compose.onAllNodes(isDialog()).fetchSemanticsNodes().size
            repeat(2) {
                compose.onNodeWithText(launchLabel).performClick()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText(confirmationTitle).fetchSemanticsNodes().size == 1
                }
                assertEquals(drawerDialogCount + 1, compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)
                // Nested dialogs take focus away from the host Activity. Send Back only once
                // the window displaying this confirmation in the target package actually owns focus.
                val titleNode = compose.onNodeWithText(confirmationTitle).fetchSemanticsNode()
                val dialogView = compose.runOnUiThread { (titleNode.root as ViewRootForTest).view }
                assertEquals(context.packageName, dialogView.context.packageName)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.runOnUiThread { dialogView.isAttachedToWindow && dialogView.hasWindowFocus() }
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                // Waiting for the window root to disappear also waits for the real onChoice(null),
                // rather than only observing the semantics cleared when exit animation starts.
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodes(isDialog()).fetchSemanticsNodes().size == drawerDialogCount &&
                        compose.onAllNodesWithText(confirmationTitle).fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithText(launchLabel).assertExists()
                compose.runOnIdle { assertEquals("Cancelling confirmation must keep the same drawer", 0, sheetDismissRequests) }
            }
            assertEquals(listOf(archive.name), directory.list()?.sorted())
            assertEquals("No launch choice was accepted, so the archive must remain untouched", 0L, archive.length())
        } finally {
            try {
                if (contentInstalled) {
                    compose.runOnIdle { visible = false }
                    compose.waitForIdle()
                }
            } finally {
                try {
                    val restore = prefs.edit()
                    if (hadKernel) restore.putString(kernelKey, previousKernel) else restore.remove(kernelKey)
                    if (hadPatch) restore.putString(patchKey, previousPatch) else restore.remove(patchKey)
                    assertTrue("The isolated installation's original Artemis preferences must be restored", restore.commit())
                } finally {
                    instrumentation.runOnMainSync { MotionMode.sync(previousMotion) }
                    assertTrue("Only the fixture archive is removed", !archive.exists() || archive.delete())
                    assertTrue("The fixture directory must be empty before removal", !directory.exists() || directory.delete())
                }
            }
        }
    }
}
