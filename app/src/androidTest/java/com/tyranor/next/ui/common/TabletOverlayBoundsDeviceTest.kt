package com.tyranor.next.ui.common

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.game.GameShortcutCropDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Run on the isolated tablet in each orientation; these checks inject real touches. */
class TabletOverlayBoundsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requireTablet() {
        MotionDeviceAcceptance.require(enhanced = null)
        assumeTrue(context.resources.configuration.smallestScreenWidthDp >= 600)
    }

    @Test fun tallTitleAndLazyBodyLeaveFooterTappable() {
        var clicks = 0
        compose.setContent {
            TyranorNextTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    title = {
                        Box(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .58f).dp)
                            .testTag("title"))
                    },
                    text = {
                        LazyColumn(Modifier.testTag("choices")) {
                            items(100) { Text("Item $it", Modifier.height(48.dp)) }
                        }
                    },
                    confirmButton = { Text("Accept", Modifier.clickable { clicks++ }) },
                )
            }
        }
        // The full-window scrim merges semantics. Inspect the title/list themselves, then
        // inject a touch into the actual footer so an off-screen or covered action still fails.
        val title = compose.onNodeWithTag("title", useUnmergedTree = true).assertIsDisplayed()
        val titleWidth = title.fetchSemanticsNode().boundsInRoot.width
        assertTrue(titleWidth > 0 && titleWidth <= (640 - 36) * context.resources.displayMetrics.density + 1)
        compose.onNodeWithTag("choices", useUnmergedTree = true).performScrollToIndex(99)
        compose.onNodeWithText("Item 99", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Accept", useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun cropMarginIsOutsideTheCardAndButtonsRemainReachable() {
        var open by mutableStateOf(true)
        var picks = 0
        compose.setContent {
            TyranorNextTheme {
                if (open) GameShortcutCropDialog(
                    imageUri = Uri.parse("content://overlay-fixture/missing"),
                    onDismiss = { open = false }, onPickImage = { picks++ }, onConfirm = {},
                )
            }
        }
        val choose = compose.onNodeWithText(context.getString(R.string.game_desktop_shortcut_crop_choose_image), useUnmergedTree = true)
        choose.assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, picks); assertTrue(open) }
        compose.onNodeWithText(context.getString(R.string.common_cancel), useUnmergedTree = true).assertIsDisplayed()
        val confirm = compose.onNodeWithText(context.getString(R.string.common_confirm), useUnmergedTree = true)
            .assertIsDisplayed().assertIsNotEnabled() // Missing image: no export should be possible.
        // The outer actions anchor opposite sides of the row, including in RTL. Test either
        // side of the card boundary at the same height, clear of the corners and buttons.
        val first = choose.fetchSemanticsNode().boundsInRoot
        val last = confirm.fetchSemanticsNode().boundsInRoot
        val rowRight = maxOf(first.right, last.right)
        val rowWidth = rowRight - minOf(first.left, last.left)
        val density = context.resources.displayMetrics.density
        assertTrue(rowWidth > 0 && rowWidth <= (420 - 36) * density + 2)
        val title = compose.onNodeWithText(context.getString(R.string.game_desktop_shortcut_crop_title), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val dialog = compose.onNode(isDialog())
        val root = dialog.fetchSemanticsNode().boundsInRoot
        val y = (title.center.y + first.center.y) / 2 - root.top
        val rightEdge = rowRight + 18 * density - root.left
        dialog.performTouchInput {
            click(Offset(rightEdge - 8 * density, y))
        }
        compose.runOnIdle { assertTrue("The card's empty padding must consume the tap", open) }
        dialog.performTouchInput {
            val outside = rightEdge + 8 * density
            assertTrue("The margin tap must land in the dialog window", outside in 0f..(width - 1).toFloat())
            click(Offset(outside, y))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
