package com.tyranor.next.ui.save

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.AppAlertDialog
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Compile in ordinary AndroidTest; execution requires the explicit isolated acceptance target. */
class SaveDialogDismissDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun busyDialogIgnoresScrimAndBackThenDismissesWhenIdle() {
        requireAcceptanceTarget()
        var busy by mutableStateOf(true)
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible) AppAlertDialog(
                    onDismissRequest = { visible = false },
                    dismissEnabled = !busy,
                    title = { Text("Save operation") },
                    text = { Text("Visible while busy") },
                    confirmButton = {},
                )
            }
        }
        compose.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
        pressBack()
        compose.waitForIdle()
        assertTrue(visible)
        compose.onNodeWithText("Visible while busy").assertIsDisplayed()
        compose.runOnIdle { busy = false }
        pressBack()
        compose.waitUntil(3_000) { !visible }
    }

    @Test
    fun acceptedDismissalCompletesEvenWhenBusyChangesDuringExitAnimation() {
        requireAcceptanceTarget()
        var busy by mutableStateOf(false)
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible) AppAlertDialog(
                    onDismissRequest = { visible = false },
                    dismissEnabled = !busy,
                    title = { Text("Save operation") },
                    text = { Text("Dismissal in progress") },
                    confirmButton = {},
                )
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
        compose.runOnIdle { busy = true }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(3_000) { !visible }
    }

    private fun requireAcceptanceTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(instrumentation.targetContext.packageName.startsWith("com.tyranor.next.webdavacceptance"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("webdav_device_acceptance") == "true")
    }
}
