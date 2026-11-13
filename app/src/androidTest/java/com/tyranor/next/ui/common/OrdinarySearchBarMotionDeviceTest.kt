package com.tyranor.next.ui.common

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.MotionMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Keeps the library search/filter row's ordinary-mode transition equal to the upstream animation. */
class OrdinarySearchBarMotionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var originalMode: Boolean? = null

    @After fun restoreMode() {
        compose.mainClock.autoAdvance = true
        originalMode?.let { mode -> compose.runOnIdle { MotionMode.sync(mode) } }
    }

    @Test fun expandCollapseAndInterruptedReentryMatchOriginalFrames() {
        MotionDeviceAcceptance.require(enhanced = null)
        originalMode = MotionMode.enhanced
        compose.runOnIdle { MotionMode.sync(false) }
        var visible by mutableStateOf(true)
        compose.setContent {
            Column(Modifier.width(120.dp)) {
                Box(Modifier.width(120.dp).background(Color.Black).testTag("migrated")) {
                    ExpandVisibility(visible, ordinaryExpandFade = true) {
                        Box(Modifier.size(120.dp, 72.dp).background(Color.White))
                    }
                }
                Box(Modifier.width(120.dp).background(Color.Black).testTag("reference")) {
                    // This is the actual origin/main search-row transition, not a copy of the new helper.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = visible,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Box(Modifier.size(120.dp, 72.dp).background(Color.White))
                    }
                }
            }
        }
        val fullHeight = height("migrated")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false }
        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            assertSameFrame()
        }
        assertTrue("Ordinary collapse must retain intermediate frames", height("migrated") > 0f && height("migrated") < fullHeight)
        // Returning to the list top before collapse settles must continue the same transition.
        compose.runOnIdle { visible = true }
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            assertSameFrame()
        }
        assertEquals(fullHeight, height("migrated"), 0f)
        compose.runOnIdle { visible = false }
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            assertSameFrame()
        }
        assertEquals(0f, height("migrated"), 0f)
        compose.runOnIdle { visible = true }
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            assertSameFrame()
        }
        assertEquals(fullHeight, height("migrated"), 0f)
    }

    private fun height(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    private fun assertSameFrame() {
        assertEquals(height("reference"), height("migrated"), 0f)
        if (height("reference") <= 0f) return
        val actual = compose.onNodeWithTag("migrated").captureToImage().toPixelMap()
        val expected = compose.onNodeWithTag("reference").captureToImage().toPixelMap()
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                assertEquals("Pixel ($x,$y)", expected[x, y], actual[x, y])
            }
        }
    }
}
