package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GraphicsOutputSizeTest {
    @Test public void fitsAspectRatioBeforeComputingActualScale() {
        GraphicsOutputSize.Size wide = GraphicsOutputSize.fit(1280, 720, 2560, 1600);
        assertEquals(2560, wide.width); assertEquals(1440, wide.height);
        assertEquals(2, wide.scaleX(1280), 0); assertEquals(2, wide.scaleY(720), 0);
        GraphicsOutputSize.Size fractional = GraphicsOutputSize.fit(1600, 900, 2400, 1200);
        assertEquals(2133, fractional.width); assertEquals(1200, fractional.height);
        assertEquals(4.0 / 3.0, fractional.scaleY(900), 0.000001);
        assertEquals(2133.0 / 1600, fractional.scaleX(1600), 0);
        assertNull(GraphicsOutputSize.fit(0, 720, 2560, 1600));
        assertEquals(Integer.MAX_VALUE, GraphicsOutputSize.fit(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE).width);
    }
}
