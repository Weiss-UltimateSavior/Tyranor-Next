package com.tyranor.next.ui.game

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.ui.common.motion.MotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the actual crop dialog close callback, without reading user images or creating shortcuts. */
class MotionCropDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun ordinaryCropPreservesPlatformExitAndClearsDimBeforeDismiss() = verifyCropDismiss(enhanced = false)

    @Test
    fun enhancedCropRetainsItsExistingWindowPolicyAndClearsDimBeforeDismiss() = verifyCropDismiss(enhanced = true)

    private fun verifyCropDismiss(enhanced: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(instrumentation.targetContext.packageName == "com.tyranor.next.motionv2")
        assumeTrue(InstrumentationRegistry.getArguments().getString("motion_device_acceptance") == "true")
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        instrumentation.runOnMainSync { MotionMode.sync(enhanced) }
        var reference by mutableStateOf(true)
        var visible by mutableStateOf(true)
        var referenceWindow: Window? = null
        var originalAnimations = 0
        var closeFlags: Int? = null
        var closeDim: Float? = null
        compose.setContent {
            MaterialTheme {
                if (reference) {
                    Dialog(onDismissRequest = {}) {
                        val window = (LocalView.current.parent as DialogWindowProvider).window
                        SideEffect {
                            referenceWindow = window
                            originalAnimations = window.attributes.windowAnimations
                        }
                        Text("Platform dialog reference")
                    }
                } else if (visible) {
                    GameShortcutCropDialog(
                        imageUri = Uri.parse("content://motion-review-fixture/missing-image"),
                        onDismiss = {
                            val window = requireNotNull(findDialogWindow())
                            closeFlags = window.attributes.flags
                            closeDim = window.attributes.dimAmount
                            visible = false
                        },
                        onPickImage = {},
                        onConfirm = { error("This test must only cancel cropping") },
                    )
                }
            }
        }
        compose.runOnIdle {
            assertNotNull(referenceWindow)
            // Remove the reference without a second platform exit overlapping the tested dialog.
            referenceWindow!!.setWindowAnimations(0)
            reference = false
        }
        compose.runOnIdle {
            val window = requireNotNull(findDialogWindow())
            assertEquals(if (enhanced) 0 else originalAnimations, window.attributes.windowAnimations)
            if (!enhanced) assertTrue("The device must provide the original platform dialog animation", originalAnimations != 0)
        }
        val cancel = instrumentation.targetContext.getString(R.string.common_cancel)
        assertTrue("The real crop content must reach a rendered frame", compose.onNodeWithText(cancel).captureToImage().width > 0)
        // Observe the platform window in real frames as well as its attributes, including during recorded acceptance.
        val visibleUntil = android.os.SystemClock.uptimeMillis() + 600L
        compose.waitUntil(timeoutMillis = 1_500) { android.os.SystemClock.uptimeMillis() >= visibleUntil }
        compose.onNodeWithText(cancel).performClick()
        compose.runOnIdle {
            assertNotNull("The actual crop close callback must run", closeFlags)
            assertTrue(requireNotNull(closeFlags) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(0f, requireNotNull(closeDim), 0f)
        }
        val exitUntil = android.os.SystemClock.uptimeMillis() + 400L
        compose.waitUntil(timeoutMillis = 1_500) { android.os.SystemClock.uptimeMillis() >= exitUntil }
    }

    private fun findDialogWindow(): Window? = WindowInspector.getGlobalWindowViews().asSequence()
        .filter { it.isAttachedToWindow }
        .flatMap { descendants(it) }
        .filterIsInstance<DialogWindowProvider>()
        .map { it.window }
        .lastOrNull()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
