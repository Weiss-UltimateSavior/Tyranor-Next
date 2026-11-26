package com.core.ons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 追踪 ONScripter 滤镜路径中已向 SDL 派发 DOWN 的触点及其各自最后归一化坐标，
 * 防止临时失视口后补发无 DOWN 的 MOVE/UP，以及多指场景合成 UP 错用其他手指坐标。
 */
public final class OnsTouchTracker {
    public static final class PointerState {
        public final int deviceId;
        public final int pointerId;
        public float normX;
        public float normY;

        PointerState(int deviceId, int pointerId, float normX, float normY) {
            this.deviceId = deviceId;
            this.pointerId = pointerId;
            this.normX = normX;
            this.normY = normY;
        }
    }

    private final Map<Long, PointerState> activePointers = new LinkedHashMap<>();

    private static long key(int deviceId, int pointerId) {
        return (((long) deviceId) << 32) | (pointerId & 0xFFFFFFFFL);
    }

    /** 登记一次实际派发给 SDL 的按下触点及其初始坐标。 */
    public void onDown(int deviceId, int pointerId, float normX, float normY) {
        activePointers.put(key(deviceId, pointerId), new PointerState(deviceId, pointerId, normX, normY));
    }

    /** 仅当该触点已登记 DOWN 时更新最后坐标并返回 true；未登记触点不得发送 MOVE。 */
    public boolean onMove(int deviceId, int pointerId, float normX, float normY) {
        PointerState state = activePointers.get(key(deviceId, pointerId));
        if (state == null) {
            return false;
        }
        state.normX = normX;
        state.normY = normY;
        return true;
    }

    /**
     * 结束并移除已登记触点，返回其包含自身最后坐标的快照；
     * 若传入了最新有效坐标（非合成 UP），同步更新后再返回。
     */
    public PointerState onUp(int deviceId, int pointerId, float normX, float normY) {
        PointerState state = activePointers.remove(key(deviceId, pointerId));
        if (state == null) {
            return null;
        }
        state.normX = normX;
        state.normY = normY;
        return state;
    }

    /** 结束并移除已登记触点，保留其最后记录坐标用于失视口时的合成抬起。 */
    public PointerState onUp(int deviceId, int pointerId) {
        return activePointers.remove(key(deviceId, pointerId));
    }

    /** 取出并清空当前仍登记的全部触点，按登记顺序返回以补发抬起。 */
    public List<PointerState> drainAll() {
        List<PointerState> snapshot = new ArrayList<>(activePointers.values());
        activePointers.clear();
        return snapshot;
    }

    public boolean hasActivePointers() {
        return !activePointers.isEmpty();
    }

    public void clear() {
        activePointers.clear();
    }
}
