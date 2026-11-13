package com.tyranor.next.ui.engine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.rememberTabPagerState
import com.tyranor.next.ui.common.motion.MotionMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Written for opt-in UI acceptance; paused device validation must not execute this test. */
class EnginePageInteractionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var originalMode: Boolean? = null

    @After fun restoreMode() {
        originalMode?.let { mode -> compose.runOnIdle { MotionMode.sync(mode) } }
        compose.mainClock.autoAdvance = true
    }

    @Test fun movingPageBlocksRealTouchAndAccessibilityUntilItSettles() {
        MotionDeviceAcceptance.require(enhanced = null)
        originalMode = MotionMode.enhanced
        compose.runOnIdle { MotionMode.sync(true) }
        val selected = mutableIntStateOf(0)
        val clicks = IntArray(2)
        compose.setContent {
            val pager = rememberTabPagerState({ selected.intValue }, 2)
            val active = remember { derivedStateOf { selected.intValue } }
            Box(Modifier.size(240.dp, 360.dp).testTag("engine-pages")) {
                repeat(2) { page ->
                    EnginePageHost(page, pager, active) {
                        Box(Modifier.fillMaxSize().testTag("engine-card-$page").clickable { clicks[page]++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("engine-card-0").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected.intValue = 1 }
        compose.mainClock.advanceTimeBy(32)
        compose.onAllNodesWithTag("engine-card-0").assertCountEquals(0)
        compose.onAllNodesWithTag("engine-card-1").assertCountEquals(0)
        compose.onNodeWithTag("engine-pages").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(0, clicks[0])
            assertEquals("A draw-phase offset must not expose the final-position click target", 0, clicks[1])
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("engine-card-1").assertIsDisplayed()
        compose.onNodeWithTag("engine-pages").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, clicks[1]) }
    }
}
