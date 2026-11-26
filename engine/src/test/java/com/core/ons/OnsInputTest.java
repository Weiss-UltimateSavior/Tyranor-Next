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

    /** 鼠标释放/取消必须保留原始 0 掩码，不能默认置为 BUTTON_PRIMARY 导致键态粘滞。 */
    @Test
    public void mouseReleasePreservesZeroButtonMask() {
        assertEquals(0, OnsInput.resolveMouseButtonState(MotionEvent.ACTION_UP, 0));
        assertEquals(0, OnsInput.resolveMouseButtonState(MotionEvent.ACTION_CANCEL, 0));
        assertEquals(MotionEvent.BUTTON_SECONDARY,
                OnsInput.resolveMouseButtonState(MotionEvent.ACTION_UP, MotionEvent.BUTTON_SECONDARY));
        assertEquals(MotionEvent.BUTTON_PRIMARY,
                OnsInput.resolveMouseButtonState(MotionEvent.ACTION_DOWN, 0));
        assertEquals(MotionEvent.BUTTON_SECONDARY,
                OnsInput.resolveMouseButtonState(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_SECONDARY));
    }

    /** 未登记 DOWN 的第二指（失视口吞掉 POINTER_DOWN）不得派发 MOVE 或 UP；多指合成 UP 须用各自最后坐标。 */
    @Test
    public void touchTrackerDropsUnregisteredMovesAndKeepsPerPointerSyntheticUpCoordinates() {
        OnsTouchTracker tracker = new OnsTouchTracker();
        tracker.onDown(1, 10, 0.2f, 0.3f);

        // 视口恢复后，只登记过 A(10)，未登记 B(20) 的 MOVE/UP 均不得派发。
        assertTrue(tracker.onMove(1, 10, 0.25f, 0.35f));
        assertFalse(tracker.onMove(1, 20, 0.8f, 0.9f));
        assertEquals(null, tracker.onUp(1, 20));

        // 正常期间两指先后按下并分别移动：A 在 (0.25, 0.35)，B 最后在 (0.85, 0.95)。
        tracker.onDown(1, 20, 0.8f, 0.9f);
        assertTrue(tracker.onMove(1, 20, 0.85f, 0.95f));

        // 失视口期间 A 抬起：必须拿到 A 自己的最后坐标，而非 B 的坐标。
        OnsTouchTracker.PointerState syntheticA = tracker.onUp(1, 10);
        assertTrue(syntheticA != null);
        assertEquals(10, syntheticA.pointerId);
        assertEquals(0.25f, syntheticA.normX, 0.0001f);
        assertEquals(0.35f, syntheticA.normY, 0.0001f);

        // B 仍活跃，drainAll 时取出 B 自己的最后坐标。
        assertTrue(tracker.hasActivePointers());
        java.util.List<OnsTouchTracker.PointerState> remaining = tracker.drainAll();
        assertEquals(1, remaining.size());
        assertEquals(20, remaining.get(0).pointerId);
        assertEquals(0.85f, remaining.get(0).normX, 0.0001f);
        assertEquals(0.95f, remaining.get(0).normY, 0.0001f);
        assertFalse(tracker.hasActivePointers());
    }

    /** 直出回退编排必须先 destroyed、再 created 重取直出窗口、恢复几何、再 changed，最后释放代理。 */
    @Test
    public void directFallbackSequenceAcquiresNativeWindowBeforeSurfaceChanged() {
        java.util.List<String> steps = new java.util.ArrayList<>();
        OnsDirectFallback.execute(new OnsDirectFallback.Actions() {
            @Override public void onNativeSurfaceDestroyed() { steps.add("destroyed"); }
            @Override public boolean hasValidDirectSurface() { steps.add("checkDirect"); return true; }
            @Override public void onNativeSurfaceCreated() { steps.add("created"); }
            @Override public void restoreDirectGeometry() { steps.add("restoreGeometry"); }
            @Override public void onNativeSurfaceChanged() { steps.add("changed"); }
            @Override public void releaseProxy() { steps.add("releaseProxy"); }
        });
        assertEquals(java.util.Arrays.asList(
                "destroyed", "checkDirect", "created", "restoreGeometry", "changed", "releaseProxy"), steps);

        steps.clear();
        OnsDirectFallback.execute(new OnsDirectFallback.Actions() {
            @Override public void onNativeSurfaceDestroyed() { steps.add("destroyed"); }
            @Override public boolean hasValidDirectSurface() { steps.add("checkDirect"); return false; }
            @Override public void onNativeSurfaceCreated() { steps.add("created"); }
            @Override public void restoreDirectGeometry() { steps.add("restoreGeometry"); }
            @Override public void onNativeSurfaceChanged() { steps.add("changed"); }
            @Override public void releaseProxy() { steps.add("releaseProxy"); }
        });
        assertEquals(java.util.Arrays.asList(
                "destroyed", "checkDirect", "restoreGeometry", "releaseProxy"), steps);
    }
}
