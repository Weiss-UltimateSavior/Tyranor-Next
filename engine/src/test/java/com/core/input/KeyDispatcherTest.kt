package com.core.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** 多键输出 / 保持（toggle）语义的派发器单测。 */
class KeyDispatcherTest {

    private class RecordingSink : InputSink {
        val events = ArrayList<Pair<Int, Boolean>>()
        override fun send(key: Int, down: Boolean) {
            events.add(key to down)
        }

        override fun releaseAll() {
            events.add(-1 to false)
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
}
