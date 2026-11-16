package com.tyranor.next.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.motion.EntranceState
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Runs only with explicit opt-in against the isolated motion acceptance package. */
class MotionEntranceDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** Verifies drag skip isolation between entrance scopes and reset when a new entrance round starts. */
    @Test
    fun draggingOnlySkipsItsOwnScopeAndReplayStartsAFreshRound() {
        MotionDeviceAcceptance.require()
        val first = EntranceState()
        val second = EntranceState()
        compose.setContent {
            Row {
                ProvideEntrance(first) {
                    Box(Modifier.size(120.dp).skipEntranceOnDrag().testTag("firstEntrance")) {
                        Box(Modifier.staggeredEntrance(0).size(80.dp).background(Color.White))
                    }
                }
                ProvideEntrance(second) {
                    Box(Modifier.size(120.dp).skipEntranceOnDrag().testTag("secondEntrance")) {
                        Box(Modifier.staggeredEntrance(0).size(80.dp).background(Color.White))
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            first.replay()
            second.replay()
        }
        compose.onNodeWithTag("firstEntrance").performTouchInput { swipeUp(durationMillis = 64) }
        compose.runOnIdle {
            assertTrue("Dragging the first scope must finish its own entrance", first.isSkipped())
            assertFalse("The other scope must retain its normal entrance", second.isSkipped())
            first.replay()
            assertFalse("A replay must discard the previous round's skip signal", first.isSkipped())
        }
        compose.onNodeWithTag("secondEntrance").performTouchInput { swipeUp(durationMillis = 64) }
        compose.runOnIdle {
            assertTrue("Dragging the second scope must finish its own entrance", second.isSkipped())
            assertFalse("The replayed first scope must remain independent", first.isSkipped())
        }
        compose.mainClock.autoAdvance = true
    }

    /** Confirms ordinary mode leaves entrance state untouched by drag gestures. */
    @Test
    fun ordinaryModeDoesNotObserveEntranceDrag() {
        MotionDeviceAcceptance.require()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { MotionMode.sync(false) }
        val state = EntranceState()
        compose.setContent {
            ProvideEntrance(state) {
                Box(Modifier.size(120.dp).skipEntranceOnDrag().testTag("ordinaryEntrance"))
            }
        }
        compose.runOnIdle { state.replay() }
        compose.onNodeWithTag("ordinaryEntrance").performTouchInput { swipeUp(durationMillis = 64) }
        compose.runOnIdle { assertFalse("Ordinary mode must not install an entrance drag observer", state.isSkipped()) }
    }

}
