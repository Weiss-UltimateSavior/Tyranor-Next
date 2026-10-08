package com.core.input

/**
 * 摇杆方向仿真状态机（纯 Kotlin，无 Android 依赖，可直接单测）。
 *
 * 规则：进入某方向需越过死区；已激活的方向在「死区 − 滞回」以上保持激活——
 * 滞回带存在的意义是消除摇杆停在阈值附近时的抖动（否则会以采样率反复 down/up）。
 * 释放后必须重新越过**完整死区**才能再次激活（否则在滞回带内来回也能触发）。
 *
 * 由 [InputRouter] 持有并委托；把状态机从 Router 里抽出来是因为 Router 的事件入口
 * 依赖 MotionEvent/KeyEvent，无法在 JVM 单测中构造——此前测试侧维护了一份手抄副本，
 * 改生产代码不会让测试失败（真实测试盲区）。阈值由调用方按当前映射表传入，
 * 因此手柄映射在 [InputRouter.updateMap] 后变化时状态机无需重建。
 */
class StickDirectionResolver {

    /** 各方向（调用方给的 key，如 `"left.up"`）当前是否激活。 */
    private val active = HashMap<String, Boolean>()

    /**
     * 录入一次轴采样。
     *
     * @param component 该方向的轴分量（已按方向取符号：上 = −y、右 = +x）
     * @return 本次是否发生状态翻转（true 才需要派发按键事件）
     */
    fun update(key: String, component: Float, deadzone: Float, hysteresis: Float): Boolean {
        val wasActive = active[key] == true
        val threshold = if (wasActive) {
            (deadzone - hysteresis).coerceAtLeast(0f)
        } else {
            deadzone
        }
        val nowActive = component > threshold
        if (nowActive == wasActive) return false
        active[key] = nowActive
        return true
    }

    fun isActive(key: String): Boolean = active[key] == true

    fun clear() {
        active.clear()
    }
}
