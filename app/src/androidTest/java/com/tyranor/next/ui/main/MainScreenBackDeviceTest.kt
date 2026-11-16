package com.tyranor.next.ui.main

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenBackDeviceTest {
    @Test fun quickCommitUsesStartEdgeAndCancelCannotLeakIntoTheNextBack() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val decisions = mutableListOf<Boolean>()
            val dispatcher = OnBackPressedDispatcher()
            dispatcher.addCallback(MainScreenBackCallback(true) { decisions.add(it) })
            val left = BackEventCompat(0f, 120f, 0f, BackEventCompat.EDGE_LEFT)
            val right = BackEventCompat(1200f, 120f, 0f, BackEventCompat.EDGE_RIGHT)
            dispatcher.dispatchOnBackStarted(left)
            dispatcher.onBackPressed() // no progress before commit
            dispatcher.dispatchOnBackStarted(left)
            dispatcher.dispatchOnBackCancelled()
            dispatcher.onBackPressed() // keyboard/button; no edge
            dispatcher.dispatchOnBackStarted(right)
            dispatcher.onBackPressed()
            assertEquals(listOf(true, false, false), decisions)
        }
    }
}
