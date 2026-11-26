package com.core.ons;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OnsViewportTest {
    @Test
    public void fitsLogicalStageAndRejectsTouchesInLetterboxing() {
        OnsViewport v = OnsViewport.fit(640, 480, 1920, 1080);
        assertNotNull(v);
        assertEquals(1440, v.width);
        assertEquals(1080, v.height);
        assertEquals(240, v.left);
        assertEquals(0, v.top);

        assertFalse(v.contains(100, 500));
        assertTrue(v.contains(240, 0));
        assertTrue(v.contains(1679, 1079));
        assertFalse(v.contains(1680, 500));

        assertEquals(320, v.sourceX(960), 0);
        assertEquals(240, v.sourceY(540), 0);
        assertEquals(0, v.sourceX(100), 0);
        // ONS 采用「独占上界」的浮点坐标（见 OnsViewport.sourceX 的取舍说明），
        // 与 FramebufferViewport 的整数末像素约定不同，非越界缺陷。
        assertEquals(640, v.sourceX(2000), 0);
    }

    @Test
    public void widescreenStageFitAndCoordinateMapping() {
        OnsViewport v = OnsViewport.fit(1280, 720, 2560, 1600);
        assertNotNull(v);
        assertEquals(2560, v.width);
        assertEquals(1440, v.height);
        assertEquals(0, v.left);
        assertEquals(80, v.top);

        assertFalse(v.contains(100, 79));
        assertTrue(v.contains(0, 80));
        assertFalse(v.contains(2560, 800));
        assertFalse(v.contains(100, 1520));

        assertEquals(640, v.sourceX(1280), 0);
        assertEquals(360, v.sourceY(800), 0);
        assertEquals(0, v.sourceY(-100), 0);
        assertEquals(720, v.sourceY(2000), 0);

        for (double x : new double[]{0, 1, 320.5, 1279, 1280}) {
            assertEquals(x, v.sourceX(v.screenX(x)), 0.000001);
        }
        for (double y : new double[]{0, 1, 180.25, 719, 720}) {
            assertEquals(y, v.sourceY(v.screenY(y)), 0.000001);
        }
    }

    @Test
    public void standard800x600OnPhoneDisplay() {
        OnsViewport v = OnsViewport.fit(800, 600, 2400, 1080);
        assertNotNull(v);
        assertEquals(1440, v.width);
        assertEquals(1080, v.height);
        assertEquals(480, v.left);
        assertEquals(0, v.top);

        // Letterbox boundary tests
        assertFalse(v.contains(479, 540));
        assertTrue(v.contains(480, 540));
        assertTrue(v.contains(1919, 540));
        assertFalse(v.contains(1920, 540));

        // Source mapping: center should map to exactly 400x300
        assertEquals(400, v.sourceX(1200), 0.001);
        assertEquals(300, v.sourceY(540), 0.001);

        // Normalized touch coordinate mapping within stage bounds
        double normXCenter = (1200 - v.left) / (double) v.width;
        double normYCenter = (540 - v.top) / (double) v.height;
        assertEquals(0.5, normXCenter, 0.0001);
        assertEquals(0.5, normYCenter, 0.0001);

        double normXLeft = (v.left - v.left) / (double) v.width;
        double normXRight = (v.left + v.width - v.left) / (double) v.width;
        assertEquals(0.0, normXLeft, 0.0001);
        assertEquals(1.0, normXRight, 0.0001);
    }

    @Test
    public void invalidDimensionsReturnNull() {
        assertNull(OnsViewport.fit(0, 480, 1920, 1080));
        assertNull(OnsViewport.fit(640, 0, 1920, 1080));
        assertNull(OnsViewport.fit(640, 480, 0, 1080));
        assertNull(OnsViewport.fit(640, 480, 1920, 0));
        assertNull(OnsViewport.fit(-1, 480, 1920, 1080));
    }

    @Test
    public void nonFiniteSourceCoordinatesClampToZero() {
        OnsViewport v = OnsViewport.fit(800, 600, 2400, 1080);
        assertNotNull(v);
        assertEquals(0.0, v.sourceX(Double.NaN), 0.0);
        assertEquals(0.0, v.sourceY(Double.NaN), 0.0);
        assertEquals(0.0, v.sourceX(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(0.0, v.sourceX(Double.NEGATIVE_INFINITY), 0.0);
        assertEquals(0.0, v.sourceY(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(0.0, v.sourceY(Double.NEGATIVE_INFINITY), 0.0);
    }
}
