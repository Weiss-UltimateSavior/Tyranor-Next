package com.tyranor.next.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class SidePanelPositionTest {
    @Test fun restingPortraitPanelKeepsTheOriginalPixelWithAnAsymmetricTaskbar() {
        // 1600 × 2560 Pixel Tablet: baseline title y=980, 36px below the 672px card's top.
        assertEquals(944, sidePanelStart(2560, 672, leading = 60, trailing = 64))
        assertEquals(550, sidePanelStart(1600, 500, leading = 96, trailing = 0))
        assertEquals(551, sidePanelStart(1601, 500, leading = 0, trailing = 96))
    }

    @Test fun onlyAnObstructedEdgeMovesTheMeasuredPanel() {
        assertEquals(100, sidePanelStart(600, 500, leading = 100, trailing = 0))
        assertEquals(40, sidePanelStart(600, 500, leading = 0, trailing = 60))
        // Tall scrolling content consumes all the safe height; both edges must fit.
        assertEquals(60, sidePanelStart(800, 644, leading = 60, trailing = 96))
    }
}
