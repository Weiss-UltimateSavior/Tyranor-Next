package com.core.siglus;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SiglusViewportTest {
    @Test public void fitsLogicalStageAndRejectsTouchesInLetterboxing() {
        SiglusViewport v = SiglusViewport.fit(1280, 720, 2560, 1600);
        assertEquals(2560, v.width); assertEquals(1440, v.height);
        assertEquals(0, v.left); assertEquals(80, v.top);
        assertFalse(v.contains(100, 79)); assertTrue(v.contains(0, 80));
        assertFalse(v.contains(2560, 800)); assertFalse(v.contains(100, 1520));
        assertEquals(640, v.sourceX(1280), 0);
        assertEquals(360, v.sourceY(800), 0);
        assertEquals(0, v.sourceY(-100), 0);
        assertEquals(720, v.sourceY(2000), 0);
    }

    @Test public void fractionalResizeUsesTheSameIntegerViewportForInputAndIme() {
        SiglusViewport v = SiglusViewport.fit(1600, 900, 2608, 1200);
        assertEquals(2133, v.width); assertEquals(1200, v.height);
        assertEquals(237, v.left); assertEquals(0, v.top);
        assertFalse(v.contains(236.9, 600));
        for (double x : new double[]{0, 1, 455.75, 1599, 1600})
            assertEquals(x, v.sourceX(v.screenX(x)), 0.000001);
        for (double y : new double[]{0, 1, 240.5, 899, 900})
            assertEquals(y, v.sourceY(v.screenY(y)), 0.000001);
        SiglusViewport rotated = SiglusViewport.fit(1600, 900, 1200, 2608);
        assertEquals(1200, rotated.width); assertEquals(675, rotated.height);
        assertEquals(966, rotated.top);
        assertNull(SiglusViewport.fit(0, 720, 2560, 1600));
    }

    @Test public void rawClaimRequiresActualSurfaceAndViewportToMatchTheStage() {
        assertFalse(SiglusViewport.isOneToOne(null));
        assertFalse(SiglusViewport.isOneToOne(new int[8]));
        int[] g = {1280,720,1280,720,0,0,1280,720,-1,1};
        assertTrue(SiglusViewport.isOneToOne(g));
        assertEquals(0x1ffffffffL, SiglusViewport.renderSerial(g));
        g[2] = 2560; assertFalse(SiglusViewport.isOneToOne(g));
        g[2] = 1280; g[5] = 80; assertFalse(SiglusViewport.isOneToOne(g));
        g[5] = 0; g[6] = 1279; assertFalse(SiglusViewport.isOneToOne(g));
    }
}
