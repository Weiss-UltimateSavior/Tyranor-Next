package com.core.games;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FramebufferViewportTest {
    @Test public void fitsTheEngineFrameProportionallyAndCentersTheResult() {
        FramebufferViewport v = FramebufferViewport.fit(800, 600, 1600, 900);
        assertEquals(1200, v.width); assertEquals(900, v.height);
        assertEquals(200, v.left); assertEquals(0, v.top);
        assertNull(FramebufferViewport.fit(0, 600, 1600, 900));
        assertNull(FramebufferViewport.fit(800, 600, 0, 900));
    }

    @Test public void touchMappingClampsIntoTheFrameLikeTheClassicView() {
        FramebufferViewport v = FramebufferViewport.fit(800, 600, 1600, 900);
        assertArrayEquals(new int[]{0, 0}, v.toSource(v.left, v.top));
        assertArrayEquals(new int[]{799, 599}, v.toSource(v.left + v.width - 1, v.top + v.height - 1));
        // Letterboxing and overshoot clamp to the edge pixel, never out of frame.
        assertArrayEquals(new int[]{0, 300}, v.toSource(-50, 450));
        assertArrayEquals(new int[]{799, 300}, v.toSource(1700, 450));
        assertArrayEquals(new int[]{400, 0}, v.toSource(800, -20));
        assertArrayEquals(new int[]{400, 599}, v.toSource(800, 950));
    }

    @Test public void narrowScreenFitsByHeightAndKeepsOneRectForPresentationAndTouch() {
        FramebufferViewport v = FramebufferViewport.fit(640, 480, 480, 800);
        assertEquals(480, v.width); assertEquals(360, v.height);
        assertEquals(0, v.left); assertEquals(220, v.top);
        int[] center = v.toSource(240, 400);
        assertEquals(320, center[0]);
        assertEquals(240, center[1]);
    }

    @Test public void nonFiniteTouchCoordinatesClampSafelyIntoFrame() {
        FramebufferViewport v = FramebufferViewport.fit(800, 600, 1600, 900);
        assertArrayEquals(new int[]{0, 0}, v.toSource(Float.NaN, Float.NaN));
        assertArrayEquals(new int[]{799, 0}, v.toSource(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));
        assertArrayEquals(new int[]{0, 599}, v.toSource(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY));
    }
}
