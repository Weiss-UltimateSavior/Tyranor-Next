package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GraphicsShaderGeometryTest {
    @Test public void budgetIncludesBothFeatureBanksNativeFrameAndTwoPassResampling() {
        GraphicsShaderGeometry cnn = new GraphicsShaderGeometry(1600, 900, 2133, 1200, 2, false, 1);
        // Sixteen source-sized RGBA16F planes, a 3200x1800 RGBA8 reconstruction,
        // fitted RGBA8 output and a 2133x1800 RGBA16F horizontal intermediate.
        long bytes = 1600L * 900 * 8 * 16 + 3200L * 1800 * 4
                + 2133L * 1200 * 4 + 2133L * 1800 * 8;
        assertEquals(bytes, cnn.estimate(1600, 900, 2133, 1200, 16, 0, false));
        assertTrue(bytes < Anime4kPostProcessor.TARGET_MEMORY_BUDGET_BYTES);
        GraphicsShaderGeometry fullHd = new GraphicsShaderGeometry(1920, 1080, 2560, 1440, 2, false, 1);
        assertTrue(fullHd.estimate(1920, 1080, 2560, 1440, 16, 0, false)
                > Anime4kPostProcessor.TARGET_MEMORY_BUDGET_BYTES);
        assertEquals(bytes + 1600L * 900 * 4, cnn.estimate(1600, 900, 2133, 1200, 16, 0, true));
        assertEquals(Long.MAX_VALUE, cnn.estimate(1600, 900, 2133, 1200, -1, 0, false));
        GraphicsShaderGeometry huge = new GraphicsShaderGeometry(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, 1, false, 1);
        assertEquals(Long.MAX_VALUE, huge.estimate(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, 16, 0, true));
    }}
