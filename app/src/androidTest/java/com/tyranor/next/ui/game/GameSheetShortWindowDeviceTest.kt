package com.tyranor.next.ui.game

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.AppBottomSheet
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual sheet host and launch layout; synthetic actions never start a game. */
@OptIn(ExperimentalMaterial3Api::class)
class GameSheetShortWindowDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var originalMotion: Boolean? = null

    @Before fun requireIsolatedTarget() {
        MotionDeviceAcceptance.require(enhanced = null)
        compose.runOnUiThread { originalMotion = MotionMode.enhanced }
    }

    @After fun restoreMotion() {
    }

    @Test fun enhancedShortWindowKeepsLaunchReachable() = checkLayout(true, true, 1f)
    @Test fun enhancedShortWindowWithLargeFontKeepsLaunchReachable() = checkLayout(true, true, 1.5f)
    @Test fun enhancedNormalWindowPinsLaunch() = checkLayout(true, false, 1f)

    private fun checkLayout(enhanced: Boolean, short: Boolean, fontScale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val label = context.getString(R.string.game_launch_action)
        var launches = 0
        compose.setContent {
            TyranorNextTheme {
                AppBottomSheet(onDismissRequest = {}) {
                    // A Dialog provides its own Android locals. Constrain the actual content inside
                    // that window and apply font scale there, rather than overriding the outer host.
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        GameSheetContent(
                            onLaunch = { launches++ },
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                                .heightIn(max = if (short) 100.dp else Dp.Infinity),
                        ) {
                            item { Text("Short-window fixture") }
                            items(8) { index ->
                                AppNavItem(title = "Action $index", onClick = {}, indication = null)
                            }
                        }
                    }
                }
            }
        }
        if (short) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        }
        val launch = compose.onNodeWithText(label).assertIsDisplayed()
        val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("The launch label must not be clipped", text.boundsInRoot.height >= text.size.height - 1f)
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(getLayout(layouts))
                assertFalse("The launch label must retain its full line height", layouts.single().didOverflowHeight)
            }
        val pinnedBounds = launch.fetchSemanticsNode().boundsInRoot
        if (!short) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Action 7"))
            assertEquals(pinnedBounds, launch.fetchSemanticsNode().boundsInRoot)
        }
        // Real pointer dispatch verifies a reachable target, rather than invoking a semantics callback.
        launch.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, launches) }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        if (screenshot != null) {
            val evidence = File(context.externalCacheDir,
                "game-sheet-${if (short) "short" else "normal"}-${if (enhanced) "enhanced" else "ordinary"}-$fontScale.png")
            evidence.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }
}
