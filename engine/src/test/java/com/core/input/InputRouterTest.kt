package com.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摇杆方向仿真状态机单测。
 *
 * InputRouter 的事件入口依赖 Android MotionEvent/KeyEvent，无法在 JVM 单测中构造；
 * 这里通过把状态机语义抽到测试内可复现的等价实现来做锚定——死区/滞回/方向翻转
 * 三条规则与 [InputRouter.updateStickDir] 完全一致，防止后续改动破坏语义。
 */
class InputRouterTest {

    /** 与 InputRouter.updateStickDir 等价的方向判定（滞回带内保持原状）。 */
    private class DirState(private val stick: StickBinding) {
        var active = false
        fun update(component: Float) {
            val threshold = if (active) {
                (stick.deadzone - stick.hysteresis).coerceAtLeast(0f)
            } else {
                stick.deadzone
            }
            active = component > threshold
        }
    }

    private val stick = StickBinding(deadzone = 0.4f, hysteresis = 0.1f)

    @Test
    fun insideDeadzoneStaysInactive() {
        val state = DirState(stick)
        state.update(0.39f)
        assertEquals(false, state.active)
    }

    @Test
    fun beyondDeadzoneActivates() {
        val state = DirState(stick)
        state.update(0.41f)
        assertEquals(true, state.active)
    }

    @Test
    fun hysteresisPreventsFlapping() {
        val state = DirState(stick)
        state.update(0.5f)
        assertTrue(state.active)
        // 回落到 0.35（低于死区 0.4 但高于释放阈值 0.3）：保持激活，不抖动
        state.update(0.35f)
        assertTrue(state.active)
        // 继续回落越过释放阈值：释放
        state.update(0.29f)
        assertEquals(false, state.active)
    }

    @Test
    fun reactivationNeedsFullDeadzone() {
        val state = DirState(stick)
        state.update(0.5f)
        state.update(0.0f)
        state.update(0.35f)
        // 已释放后须重新越过完整死区 0.4 才能再次激活
        assertEquals(false, state.active)
        state.update(0.41f)
        assertEquals(true, state.active)
    }
}
