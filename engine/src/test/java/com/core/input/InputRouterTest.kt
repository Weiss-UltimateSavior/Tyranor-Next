package com.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摇杆方向仿真状态机的单测。
 *
 * 测的是**生产类** [StickDirectionResolver]（[InputRouter] 持有并委托它）：此前本文件
 * 在测试侧维护了一份手抄副本，改坏 `InputRouter` 的阈值判定不会让测试失败——那是真实
 * 的测试盲区。现在测试直接锚定生产实现，破坏阈值逻辑必然让下面的用例失败。
 *
 * InputRouter 的事件入口本身依赖 Android MotionEvent/KeyEvent，JVM 单测无法构造，
 * 因此状态机被刻意抽成不依赖 Android 的纯 Kotlin 类（这也是抽取它的原因之一）。
 */
class InputRouterTest {

    private val deadzone = 0.4f
    private val hysteresis = 0.1f

    private fun resolver() = StickDirectionResolver()

    /** 生产路径的调用方式：一次轴采样映射到四个方向的状态翻转。 */
    private fun update(resolver: StickDirectionResolver, key: String, component: Float): Boolean =
        resolver.update(key, component, deadzone, hysteresis)

    @Test
    fun insideDeadzoneStaysInactive() {
        val state = resolver()
        assertFalse("死区内不得激活", update(state, "left.up", 0.39f))
        assertFalse(state.isActive("left.up"))
    }

    @Test
    fun beyondDeadzoneActivates() {
        val state = resolver()
        assertTrue("越过死区必须翻转", update(state, "left.up", 0.41f))
        assertTrue(state.isActive("left.up"))
    }

    @Test
    fun hysteresisPreventsFlapping() {
        val state = resolver()
        assertTrue(update(state, "left.up", 0.5f))
        assertTrue(state.isActive("left.up"))
        // 回落到 0.35（低于死区 0.4 但高于释放阈值 0.3）：不翻转、保持激活，不抖动
        assertFalse("滞回带内不得翻转", update(state, "left.up", 0.35f))
        assertTrue(state.isActive("left.up"))
        // 继续回落越过释放阈值：释放
        assertTrue(update(state, "left.up", 0.29f))
        assertFalse(state.isActive("left.up"))
    }

    @Test
    fun reactivationNeedsFullDeadzone() {
        val state = resolver()
        update(state, "left.up", 0.5f)
        update(state, "left.up", 0.0f)
        // 已释放后须重新越过完整死区 0.4 才能再次激活
        assertFalse(update(state, "left.up", 0.35f))
        assertFalse(state.isActive("left.up"))
        assertTrue(update(state, "left.up", 0.41f))
        assertTrue(state.isActive("left.up"))
    }

    @Test
    fun directionsAreTrackedIndependently() {
        val state = resolver()
        assertTrue(update(state, "left.up", 0.5f))
        assertFalse("另一方向不得被联动", update(state, "left.down", 0.1f))
        assertTrue(state.isActive("left.up"))
        assertFalse(state.isActive("left.down"))
    }

    @Test
    fun clearResetsAllDirections() {
        // reset() 路径：拔插手柄 / 轴 CANCEL 后不得残留激活状态
        val state = resolver()
        update(state, "left.up", 0.5f)
        update(state, "right.left", 0.5f)
        state.clear()
        assertFalse(state.isActive("left.up"))
        assertFalse(state.isActive("right.left"))
        // 清空后按未激活处理：需重新越过完整死区
        assertFalse(update(state, "left.up", 0.35f))
        assertTrue(update(state, "left.up", 0.41f))
    }

    @Test
    fun hysteresisWiderThanDeadzoneNeverSticks() {
        // 退化配置：滞回大于死区时释放阈值为 0，仍不得卡在激活态
        val state = resolver()
        assertTrue(state.update("left.up", 0.5f, deadzone = 0.3f, hysteresis = 0.5f))
        assertTrue(state.update("left.up", 0.0f, deadzone = 0.3f, hysteresis = 0.5f))
        assertEquals(false, state.isActive("left.up"))
    }
}
