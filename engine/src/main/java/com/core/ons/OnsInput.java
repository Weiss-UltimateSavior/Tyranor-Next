package com.core.ons;

import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * 滤镜路径的输入判定与坐标归一化，供 {@code ONScripter} 与 JVM 回归共用。
 *
 * <p>这些判定过去各自在宿主里写成 private 或测试内副本，测试通过复制实现「验证」自己，
 * 生产路径实际零覆盖。收口到这里后，宿主与测试调用同一份代码。</p>
 */
public final class OnsInput {
    private static final int SOURCE_MOUSE_ABSOLUTE_LEGACY = 8194;
    private static final int SOURCE_MOUSE_RELATIVE_LEGACY = 12290;

    private OnsInput() {}

    /** 真实鼠标（含相对模式与 tool type 兜底）才走 onNativeMouse，触摸屏不得误判。 */
    public static boolean isMouseDevice(MotionEvent motionEvent) {
        if (motionEvent == null) return false;
        int toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        try {
            toolType = motionEvent.getToolType(0);
        } catch (RuntimeException ignored) {
            // 个别 OEM InputManager 会对越界 pointer 抛 ArrayIndexOutOfBounds 等；
            // 这里只需要 tool type 兜底，非 JNI 路径统一不收 Throwable。
        }
        return isMouseSource(motionEvent.getSource(), toolType);
    }

    /** 与 {@link #isMouseDevice} 同源的纯判定，便于在 JVM 上直接回归。 */
    static boolean isMouseSource(int source, int toolType) {
        if (source == SOURCE_MOUSE_ABSOLUTE_LEGACY || source == SOURCE_MOUSE_RELATIVE_LEGACY) return true;
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true;
        return toolType == MotionEvent.TOOL_TYPE_MOUSE;
    }

    /** 视图 x → 舞台归一化 [0,1]，黑边内钳制到边界。NaN / Infinity 不作为有效输入透传。 */
    public static float normalizeX(OnsViewport viewport, float rawX) {
        if (viewport == null || viewport.width <= 0) return 0f;
        return clampUnit((rawX - viewport.left) / (double) viewport.width);
    }

    /** 视图 y → 舞台归一化 [0,1]，黑边内钳制到边界。 */
    public static float normalizeY(OnsViewport viewport, float rawY) {
        if (viewport == null || viewport.height <= 0) return 0f;
        return clampUnit((rawY - viewport.top) / (double) viewport.height);
    }

    private static float clampUnit(double value) {
        // `Math.max/min` 会把 NaN 原样传下去（NaN 与任何数比较都为 false），
        // 归一化坐标一旦是 NaN，native 触控状态机会整体错乱。非有限值一律归零。
        if (!Double.isFinite(value)) return 0f;
        return (float) Math.max(0.0, Math.min(1.0, value));
    }

    /** 鼠标滚轮增量非有限值归零，避免把 NaN / Infinity 透传给 SDL 滚轮状态机。 */
    public static float sanitizeScrollAxis(float value) {
        return Float.isFinite(value) ? value : 0f;
    }

    /**
     * 该事件是否属于「从黑边起手」的手势。只吞 ACTION_DOWN 时，同一手势的
     * MOVE/UP 仍会带钳制坐标派发，引擎会收到「没有 DOWN 的 MOVE/UP」。
     */
    public static boolean startsOutsideStage(OnsViewport viewport, float x, float y) {
        return viewport == null || !viewport.contains(x, y);
    }

    public static boolean startsOutsideStage(OnsViewport viewport, MotionEvent event) {
        return event == null || startsOutsideStage(viewport, event.getX(), event.getY());
    }
}
