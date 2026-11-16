package com.tyranor.next.ui.game

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.game.launch.YurisLaunchFiles.ExeCandidate
import com.tyranor.next.core.game.manual.AndroidAppGames
import com.tyranor.next.core.game.manual.AndroidAppGames.InstalledApp
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Rebuilds the real dialog compositions with fresh directory/package listings and no library writes. */
class AddGameRestorationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun button(id: Int) = compose.onNodeWithText(context.getString(id))
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun androidQueryAndPackageSurviveFailedReloadAndRetry() {
        MotionDeviceAcceptance.require(enhanced = null)
        val app = InstalledApp("Fixture Game", "fixture.game")
        val loads = AtomicInteger()
        var existing by mutableStateOf(emptySet<String>())
        val restore = StateRestorationTester(compose)
        restore.setContent {
            TyranorNextTheme {
                AndroidGameAddDialog(existing, {}, { error("Must not write to the library") }, loadApps = {
                    if (loads.incrementAndGet() == 2) null else listOf(app, InstalledApp("Other", "other.app"))
                })
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Fixt")
        awaitText(app.packageName)
        compose.onNodeWithText(app.packageName).performClick()
        button(R.string.common_confirm).assertIsEnabled()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Fixt").assertExists()
        awaitText(context.getString(R.string.common_retry))
        button(R.string.common_confirm).assertIsNotEnabled()
        button(R.string.common_retry).performClick()
        awaitText(app.packageName)
        button(R.string.common_confirm).assertIsEnabled()
        // A game added elsewhere while the dialog was open must no longer be confirmable.
        compose.runOnIdle { existing = setOf(AndroidAppGames.uriFor(app.packageName)) }
        button(R.string.common_confirm).assertIsNotEnabled()
    }

    @Test
    fun pcRereadsDirectoryAndNeverSilentlySubstitutesTheFirstExe() {
        MotionDeviceAcceptance.require(enhanced = null)
        val uri = Uri.parse("content://overlay-fixture/tree/game")
        val picker = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, uri)
                }
            }
        }
        val loads = AtomicInteger()
        val restore = StateRestorationTester(compose)
        restore.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                TyranorNextTheme {
                    PcGameAddDialog({}, { error("Must not write to the library") }, loadDirectory = { _, tree ->
                        assertEquals(uri, tree)
                        "Fixture" to if (loads.incrementAndGet() < 3) listOf(ExeCandidate("A.exe", 1), ExeCandidate("B.exe", 2))
                            else listOf(ExeCandidate("A.exe", 1))
                    })
                }
            }
        }
        button(R.string.pc_add_pick_directory).performClick()
        awaitText("B.exe")
        compose.onNodeWithText("B.exe").performClick()
        restore.emulateSavedInstanceStateRestore()
        awaitText("B.exe")
        compose.onNodeWithText("B.exe").assertExists()
        button(R.string.common_confirm).assertIsEnabled()
        restore.emulateSavedInstanceStateRestore()
        awaitText("A.exe")
        compose.onNodeWithText("A.exe").assertExists()
        compose.onNodeWithText("B.exe").assertDoesNotExist()
        button(R.string.common_confirm).assertIsNotEnabled()
        compose.onNodeWithText("A.exe").performClick()
        button(R.string.common_confirm).assertIsEnabled()
        assertEquals(3, loads.get())
    }
}
