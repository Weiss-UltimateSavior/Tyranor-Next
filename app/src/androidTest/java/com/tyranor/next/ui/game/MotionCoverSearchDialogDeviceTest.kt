package com.tyranor.next.ui.game

import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opens the real search dialog through its sheet entry; never starts a search or downloads a cover. */
class MotionCoverSearchDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** Ordinary mode keeps the matching platform window policy and clears dim on close and disposal. */
    @Test
    fun ordinarySearchPreservesPlatformExitAndClearsWindowOnEveryRemoval() = verifyWindow(enhanced = false)

    /** Enhanced mode keeps its existing zero-animation policy and performs the same ownership cleanup. */
    @Test
    fun enhancedSearchRetainsWindowPolicyAndClearsWindowOnEveryRemoval() = verifyWindow(enhanced = true)

    /** Compares a matching fullscreen platform reference, then closes and externally removes the real UI. */
    private fun verifyWindow(enhanced: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = AppSettingsStore.COVER_SOURCE_VNDB
        val sourceEnabled = AppSettingsStore.isCoverScraperSourceEnabled(context, source)
        var previousMode = true
        var reference by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var referenceWindow: Window? = null
        var originalAnimations = 0
        try {
            instrumentation.runOnMainSync {
                previousMode = MotionMode.enhanced
                MotionMode.sync(enhanced)
                AppSettingsStore.setCoverScraperSourceEnabled(context, source, true)
            }
            compose.setContent {
                TyranorNextTheme {
                    if (reference) {
                        Dialog(
                            onDismissRequest = {},
                            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                        ) {
                            val window = (LocalView.current.parent as DialogWindowProvider).window
                            SideEffect {
                                referenceWindow = window
                                originalAnimations = window.attributes.windowAnimations
                            }
                            Text("Platform search-window reference")
                        }
                    } else if (mounted) {
                        GameActionsSheet(
                            game = fixtureGame,
                            onDismiss = { mounted = false },
                            onGameUpdated = {},
                            onEngineSettings = {},
                        )
                    }
                }
            }
            compose.runOnIdle {
                requireNotNull(referenceWindow).setWindowAnimations(0)
                reference = false
            }
            val searchLabel = context.getString(R.string.game_search_cover)
            val closeLabel = context.getString(R.string.common_close)
            openSearch(searchLabel, closeLabel)
            val searchWindow = requireNotNull(findDialogWindow())
            compose.runOnIdle {
                assertEquals(if (enhanced) 0 else originalAnimations, searchWindow.attributes.windowAnimations)
            }
            val close = requireNotNull(
                compose.onNodeWithText(closeLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action,
            )
            compose.runOnIdle {
                close()
                assertReleased(searchWindow)
            }
            compose.waitForIdle()
            openSearch(searchLabel, closeLabel)
            val removedWindow = requireNotNull(findDialogWindow())
            compose.runOnIdle { mounted = false }
            compose.runOnIdle { assertReleased(removedWindow) }
        } finally {
            instrumentation.runOnMainSync {
                AppSettingsStore.setCoverScraperSourceEnabled(context, source, sourceEnabled)
                MotionMode.sync(previousMode)
            }
        }
    }

    /** Uses real UI entry points without invoking the dialog's network-backed search action. */
    private fun openSearch(searchLabel: String, closeLabel: String) {
        compose.onNodeWithText(searchLabel).performClick()
        compose.onNodeWithText("VNDB").performClick()
        assertTrue("The real search panel must draw", compose.onNodeWithText(closeLabel).captureToImage().width > 0)
    }

    /** Checks cleanup on the captured window so a later replacement cannot mask an ownership error. */
    private fun assertReleased(window: Window) {
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(0f, window.attributes.dimAmount, 0f)
    }

    /** Finds an attached Compose dialog within this explicitly gated instrumentation process. */
    private fun findDialogWindow(): Window? = WindowInspector.getGlobalWindowViews().asSequence()
        .filter { it.isAttachedToWindow }
        .flatMap { descendants(it) }
        .filterIsInstance<DialogWindowProvider>()
        .map { it.window }
        .lastOrNull()

    /** Traverses nested view groups while retaining their owning window references. */
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private val fixtureGame = ScanGame(
        title = "Motion cover-window fixture",
        uri = "content://motion-review-fixture/cover-window",
        engine = EngineType.UNKNOWN,
        launchTarget = "",
    )
}
