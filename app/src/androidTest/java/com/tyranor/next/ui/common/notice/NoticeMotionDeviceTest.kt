package com.tyranor.next.ui.common.notice

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NoticeMotionDeviceTest {
    @get:Rule val compose = createComposeRule()

    private var previousMode: Boolean? = null
    private var scrimDismissals = 0

    @Before fun enableEnhancedMotion() {
        MotionDeviceAcceptance.require(enhanced = null)
        compose.runOnUiThread {
            previousMode = MotionMode.enhanced
            MotionMode.sync(true)
        }
    }

    @After fun restoreMode() {
        previousMode?.let { compose.runOnUiThread { MotionMode.sync(it) } }
    }

    @Test fun releasingPointerHeldOnADoesNotDismissBButFreshTapDoes() {
        mount()
        show("Notice A", Toast.LENGTH_SHORT)
        text("Notice A").performTouchInput { down(center) }
        try {
            // A missed DOWN would also leave B untouched. Prove A actually received the press:
            // its 2s SHORT dwell uses main-looper uptime, independent of the Compose test clock.
            val pastShortDwell = CountDownLatch(1)
            Handler(Looper.getMainLooper()).postDelayed({ pastShortDwell.countDown() }, 2_100L)
            assertTrue("Main looper did not reach A's dwell deadline", pastShortDwell.await(10, TimeUnit.SECONDS))
            text("Notice A").assertIsDisplayed()

            show("Notice B")
            text("Notice B").assertIsDisplayed()
            text("Notice A").assertDoesNotExist()
        } finally {
            // The injector retains the pointer across calls on the same window. A's text node
            // no longer exists, so send only UP through the stable page root.
            compose.onNodeWithTag(PAGE).performTouchInput { up() }
        }
        text("Notice B").assertIsDisplayed().performTouchInput { click() }
        text("Notice B").assertDoesNotExist()
    }

    @Test fun outsideTapsReachControlsAndSameNoticeMovesFromPageToDialogAndBack() {
        mount()
        val notice = "Window handoff"
        val pageNotice = hasText(notice) and hasAnyAncestor(hasTestTag(PAGE))
        val dialogNotice = hasText(notice) and hasAnyAncestor(isDialog())
        show(notice)
        compose.onNode(pageNotice, useUnmergedTree = true).assertIsDisplayed()

        // Both controls are behind a full-window NoticeHost but outside its capsule.
        text(OPEN).performTouchInput { click() }
        compose.onNode(dialogNotice, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText(notice, useUnmergedTree = true).assertCountEquals(1)
        text(CLOSE).performTouchInput { click() }

        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(pageNotice, useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
        text(notice).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, scrimDismissals) }
    }

    @Test fun tappingCapsuleAboveDialogScrimConsumesOnlyThatTap() {
        mount()
        text(OPEN).performTouchInput { click() }
        val dialog = compose.onNode(isDialog())
        dialog.assertExists()
        show("Dialog notice")
        text("Dialog notice").performTouchInput { click() }
        text("Dialog notice").assertDoesNotExist()
        text(CLOSE).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, scrimDismissals) }

        // The same scrim must still respond away from the capsule and the bottom dialog card.
        dialog.performTouchInput { click(Offset(2f, 2f)) }
        dialog.assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, scrimDismissals) }
    }

    private fun mount() {
        var dialogOpen by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag(PAGE)) {
                    Text(OPEN, Modifier.align(Alignment.Center).clickable { dialogOpen = true })
                    NoticeHost(Modifier.matchParentSize())
                    if (dialogOpen) {
                        AppAlertDialog(
                            onDismissRequest = { scrimDismissals++; dialogOpen = false },
                            title = { Text("Notice test dialog") },
                            text = {},
                            confirmButton = { Text(CLOSE, Modifier.clickable { dialogOpen = false }) },
                        )
                    }
                }
            }
        }
    }

    private fun show(message: String, duration: Int = Toast.LENGTH_LONG) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle { AppNotice.makeText(context, message, duration, AppNotice.Kind.Info).show() }
    }

    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)

    private companion object {
        const val PAGE = "noticeTestPage"
        const val OPEN = "Open dialog"
        const val CLOSE = "Return to page"
    }
}
