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

}
