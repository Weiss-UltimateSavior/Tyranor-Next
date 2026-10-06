package com.core.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** 多键输出 / 保持（toggle）语义 / 动作段派发的派发器单测。 */
class KeyDispatcherTest {

    private class RecordingSink : InputSink {
        val events = ArrayList<Pair<Int, Boolean>>()
        val actions = ArrayList<Int>()

        override fun send(key: Int, down: Boolean) {
            events.add(key to down)
        }

        override fun releaseAll() {
            events.add(-1 to false)
        }

        override fun performAction(action: Int): Boolean {
            actions.add(action)
            return true
        }
    }

    @Test
    fun pressAndReleaseDispatchInOrder() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("ok", listOf(66, 62), autoKeep = false)
        dispatcher.release("ok", autoKeep = false)
        assertEquals(listOf(66 to true, 62 to true, 62 to false, 66 to false), sink.events)
    }

    @Test
    fun repeatedPressDoesNotDuplicate() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("ok", listOf(66), autoKeep = false)
        dispatcher.press("ok", listOf(66), autoKeep = false)
        assertEquals(1, sink.events.size)
    }

    @Test
    fun autoKeepTogglesOnSecondPress() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("skip", listOf(113), autoKeep = true)
        dispatcher.release("skip", autoKeep = true) // 触摸抬起：保持不释放
        assertEquals(listOf(113 to true), sink.events)
        dispatcher.press("skip", listOf(113), autoKeep = true) // 再按一次：释放
        assertEquals(listOf(113 to true, 113 to false), sink.events)
    }

    @Test
    fun releaseAllClearsToggledKeys() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("skip", listOf(113), autoKeep = true)
        dispatcher.releaseAll()
        assertEquals(listOf(113 to true, 113 to false), sink.events)
        assertEquals(false, dispatcher.isToggled("skip"))
    }

    @Test
    fun emptyKeysIsNoop() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("none", emptyList(), autoKeep = false)
        dispatcher.release("none", autoKeep = false)
        assertEquals(0, sink.events.size)
    }

    @Test
    fun actionKeyFiresOncePerPressWithoutKeyEvents() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("shot", listOf(CanonicalKeys.ACTION_SCREENSHOT), autoKeep = false)
        dispatcher.release("shot", autoKeep = false)
        assertEquals(listOf(CanonicalKeys.ACTION_SCREENSHOT), sink.actions)
        // 动作不产生 keydown/keyup
        assertEquals(0, sink.events.size)

        // 连续两次点按都要触发（动作没有「已按下」抑制）
        dispatcher.press("shot", listOf(CanonicalKeys.ACTION_SCREENSHOT), autoKeep = false)
        dispatcher.release("shot", autoKeep = false)
        assertEquals(2, sink.actions.size)
    }

    @Test
    fun actionKeyIgnoresAutoKeep() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        // 手柄把截屏绑在默认带 autoKeep 的键上时，仍应每次按下都触发
        dispatcher.press("shot", listOf(CanonicalKeys.ACTION_SCREENSHOT), autoKeep = true)
        dispatcher.release("shot", autoKeep = true)
        dispatcher.press("shot", listOf(CanonicalKeys.ACTION_SCREENSHOT), autoKeep = true)
        assertEquals(2, sink.actions.size)
        assertEquals(0, sink.events.size)
    }

    @Test
    fun mixedKeysDispatchActionAndHoldKeyTogether() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        // 同一条映射里既能出普通键也能出动作，互不干扰
        dispatcher.press("combo", listOf(CanonicalKeys.ACTION_SCREENSHOT, 66), autoKeep = false)
        dispatcher.release("combo", autoKeep = false)
        assertEquals(listOf(CanonicalKeys.ACTION_SCREENSHOT), sink.actions)
        assertEquals(listOf(66 to true, 66 to false), sink.events)
    }

    @Test
    fun releaseAllDoesNotFireActions() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("shot", listOf(CanonicalKeys.ACTION_SCREENSHOT), autoKeep = false)
        dispatcher.releaseAll()
        // 释放不应重放动作
        assertEquals(1, sink.actions.size)
    }
}
