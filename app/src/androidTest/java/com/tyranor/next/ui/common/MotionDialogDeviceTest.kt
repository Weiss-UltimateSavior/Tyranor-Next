package com.tyranor.next.ui.common

import android.view.Window
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.tyranor.next.ui.common.motion.passThroughTouches
import com.tyranor.next.ui.common.motion.syncDialogWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses only the isolated motion target and never changes system animation settings. */
class MotionDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** Activates the same pending exit twice and verifies that only one deferred action is delivered. */
    @Test
    fun repeatedExitActivationDeliversOneAction() {
        MotionDeviceAcceptance.require()
        var visible by mutableStateOf(true)
        var controller: DialogExitController? = null
        var actions = 0
        compose.setContent {
            MaterialTheme {
                if (visible) {
                    AppAlertDialog(
                        onDismissRequest = { visible = false },
                        title = { Text("Title") },
                        text = { Text("Body") },
                        confirmButton = {
                            val exit = LocalDialogExit.current
                            SideEffect { controller = exit }
                            Text("Close")
                        },
                    )
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            assertNotNull(controller)
            val action = { actions++; visible = false }
            controller!!.closeThen(action)
            controller!!.closeThen(action)
            assertEquals("An accepted exit must defer its action until the existing animation ends", 0, actions)
        }
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle { assertEquals(1, actions) }
        compose.mainClock.autoAdvance = true
    }

    /** Checks platform animation restoration and explicit dim/touch cleanup on the real dialog window. */
    @Test
    fun ordinaryWindowAnimationCanBePreservedWithoutRetainingExitDimOrTouch() {
        MotionDeviceAcceptance.require()
        var preserve by mutableStateOf(true)
        var window: Window? = null
        compose.setContent {
            Dialog(onDismissRequest = {}) {
                val platformWindow = (LocalView.current.parent as DialogWindowProvider).window
                remember(platformWindow) {
                    // A real platform dialog animation style, independent of the device theme default.
                    platformWindow.setWindowAnimations(android.R.style.Animation_Dialog)
                    platformWindow.setDimAmount(0.32f)
                    true
                }
                val synced = syncDialogWindow(preserveWindowAnimations = preserve) { 1f }
                SideEffect { window = synced }
                Text("Dialog")
            }
        }
        compose.runOnIdle {
            assertEquals(android.R.style.Animation_Dialog, window!!.attributes.windowAnimations)
            assertEquals(0.32f, window!!.attributes.dimAmount, 0.001f)
            preserve = false
        }
        compose.runOnIdle {
            assertEquals(0, window!!.attributes.windowAnimations)
            preserve = true
        }
        compose.runOnIdle {
            assertEquals(android.R.style.Animation_Dialog, window!!.attributes.windowAnimations)
            assertEquals("Mode changes must retain the original full dim", 0.32f, window!!.attributes.dimAmount, 0.001f)
            window.passThroughTouches()
            window!!.setDimAmount(0f)
            assertTrue(window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(0f, window!!.attributes.dimAmount)
        }
    }

}
