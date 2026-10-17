package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CasShaderGeometryTest {
    @Test public void casLimitsItsInternalScaleWhileRequestedFitCanExceedTwoTimes() {
        GraphicsShaderGeometry cas = new GraphicsShaderGeometry(640, 360, 2560, 1440, 1, true, 2);
        assertEquals(1280, cas.width); assertEquals(720, cas.height);
        LanczosScale.Plan finalScale = LanczosScale.resample(cas.width, cas.height, 2560, 1440);
        assertTrue(finalScale.horizontal); assertTrue(finalScale.vertical);
        GraphicsShaderGeometry oneAxis = new GraphicsShaderGeometry(100, 100, 101, 100, 2, false, 1);
        assertTrue(LanczosScale.resample(oneAxis.width, oneAxis.height, 101, 100).horizontal);
        assertTrue(LanczosScale.resample(oneAxis.width, oneAxis.height, 101, 100).vertical);
        assertFalse(LanczosScale.resample(200, 200, 200, 150).horizontal);
        assertTrue(LanczosScale.resample(200, 200, 200, 150).vertical);
    }

}
