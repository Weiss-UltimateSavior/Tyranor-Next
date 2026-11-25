package com.core.ons;

import android.view.InputDevice;
import android.view.MotionEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 滤镜路径输入转换的回归。这里直接调用生产实现 {@link OnsInput}，不再
 * 在测试内复制一份判定逻辑——否则删掉生产分支测试依然全绿。
 */
public final class OnsInputTest {

    @Test
    public void touchscreenIsNotMisidentifiedAsMouse() {
        int touchscreenSource = InputDevice.SOURCE_TOUCHSCREEN; // 4098 (0x1002)
        int mouseSource = InputDevice.SOURCE_MOUSE;             // 8194 (0x2002)
        int mouseRelativeSource = 12290;                        // 0x3002

        // The historical buggy condition bit-tested SOURCE_MOUSE against a touchscreen source.
        boolean buggyCondition = (touchscreenSource & InputDevice.SOURCE_CLASS_POINTER) != 0
                && (touchscreenSource & InputDevice.SOURCE_MOUSE) != 0;
        assertTrue("Regression check: buggy condition did trigger on touchscreen", buggyCondition);

        assertFalse(OnsInput.isMouseSource(touchscreenSource, MotionEvent.TOOL_TYPE_FINGER));
        assertTrue(OnsInput.isMouseSource(mouseSource, MotionEvent.TOOL_TYPE_MOUSE));
        assertTrue(OnsInput.isMouseSource(mouseRelativeSource, MotionEvent.TOOL_TYPE_MOUSE));
        // Tool-type fallback: a mouse that reports a generic source still routes to onNativeMouse.
        assertTrue(OnsInput.isMouseSource(InputDevice.SOURCE_UNKNOWN, MotionEvent.TOOL_TYPE_MOUSE));
        assertFalse(OnsInput.isMouseSource(InputDevice.SOURCE_UNKNOWN, MotionEvent.TOOL_TYPE_FINGER));
    }

    @Test
    public void normalizedMappingClampsInsideAndOutsideStage() {
        OnsViewport vp = OnsViewport.fit(800, 600, 2400, 1080);
        // Stage is 1440x1080, left=480, top=0.
        assertEquals(480, vp.left);
        assertEquals(1440, vp.width);
        assertEquals(0, vp.top);
        assertEquals(1080, vp.height);

        // Left letterbox click clamps to 0.0; right letterbox clamps to 1.0.
        assertEquals(0.0f, OnsInput.normalizeX(vp, 100), 0.0001f);
        assertEquals(1.0f, OnsInput.normalizeX(vp, 2300), 0.0001f);
        // Center maps to exactly 0.5 in both axes.
        assertEquals(0.5f, OnsInput.normalizeX(vp, 1200), 0.0001f);
        assertEquals(0.5f, OnsInput.normalizeY(vp, 540), 0.0001f);
        // Degenerate viewport never divides by zero.
        assertEquals(0f, OnsInput.normalizeX(null, 100), 0f);
        assertEquals(0f, OnsInput.normalizeY(null, 100), 0f);
        // Vertical letterboxing (4:3 stage on a tall window) clamps the same way.
        OnsViewport tall = OnsViewport.fit(800, 600, 1000, 2000);
        assertEquals(0f, OnsInput.normalizeY(tall, tall.top - 50), 0.0001f);
        assertEquals(1f, OnsInput.normalizeY(tall, tall.top + tall.height + 50), 0.0001f);
        assertEquals(0.5f, OnsInput.normalizeY(tall, tall.top + tall.height / 2f), 0.0001f);
    }

    /** 非有限坐标不得透传 NaN / 无穷给 native 触控状态机。 */
    @Test
    public void nonFiniteCoordinatesNeverLeakNaN() {
        OnsViewport vp = OnsViewport.fit(800, 600, 2400, 1080);
        assertEquals(0f, OnsInput.normalizeX(vp, Float.NaN), 0f);
        assertEquals(0f, OnsInput.normalizeY(vp, Float.NaN), 0f);
        assertEquals(0f, OnsInput.normalizeX(vp, Float.POSITIVE_INFINITY), 0f);
        assertEquals(0f, OnsInput.normalizeX(vp, Float.NEGATIVE_INFINITY), 0f);
        assertEquals(0f, OnsInput.normalizeY(vp, Float.POSITIVE_INFINITY), 0f);
        assertEquals(0f, OnsInput.normalizeY(vp, Float.NEGATIVE_INFINITY), 0f);
    }

    /**
     * 从黑边起手的手势整段不派发；只有真正落在舞台内的起手才进入手势。
     * 这里覆盖判定本身（宿主用它维护 {@code filterTouchOutsideStage}）。
     */
    @Test
    public void gesturesStartingInLetterboxingAreRejected() {
        OnsViewport vp = OnsViewport.fit(800, 600, 2400, 1080);
        assertTrue(OnsInput.startsOutsideStage(vp, 479, 540));
        assertTrue(OnsInput.startsOutsideStage(vp, 1920, 540));
        assertFalse(OnsInput.startsOutsideStage(vp, 480, 540));
        assertFalse(OnsInput.startsOutsideStage(vp, 1200, 540));
        assertTrue(OnsInput.startsOutsideStage(vp, Float.NaN, 540));
        assertTrue(OnsInput.startsOutsideStage(vp, 1200, Float.NaN));
        assertTrue(OnsInput.startsOutsideStage(vp, Float.POSITIVE_INFINITY, 540));
        assertTrue(OnsInput.startsOutsideStage(vp, Float.NEGATIVE_INFINITY, 540));
        assertTrue(OnsInput.startsOutsideStage(vp, 1200, Float.POSITIVE_INFINITY));
        assertTrue(OnsInput.startsOutsideStage(vp, 1200, Float.NEGATIVE_INFINITY));
    }

    @Test
    public void scrollAxisSanitizesNonFiniteValues() {
        assertEquals(1.5f, OnsInput.sanitizeScrollAxis(1.5f), 0f);
        assertEquals(-2.0f, OnsInput.sanitizeScrollAxis(-2.0f), 0f);
        assertEquals(0f, OnsInput.sanitizeScrollAxis(Float.NaN), 0f);
        assertEquals(0f, OnsInput.sanitizeScrollAxis(Float.POSITIVE_INFINITY), 0f);
        assertEquals(0f, OnsInput.sanitizeScrollAxis(Float.NEGATIVE_INFINITY), 0f);
    }
}
