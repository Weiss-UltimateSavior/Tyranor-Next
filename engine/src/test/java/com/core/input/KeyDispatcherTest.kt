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
    fun releaseScopeClearsToggledKeys() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("pad:skip", listOf(113), autoKeep = true)
        dispatcher.releaseScope("pad:")
        assertEquals(listOf(113 to true, 113 to false), sink.events)
        assertEquals(false, dispatcher.isToggled("pad:skip"))
    }

    @Test
    fun emptyKeysIsNoop() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("none", emptyList(), autoKeep = false)
        dispatcher.release("none", autoKeep = false)
        assertEquals(0, sink.events.size)
    }

    // ---------- 键级引用计数（跨来源共享同一 dispatcher） ----------

    @Test
    fun sharedKeyIsNotReleasedWhileAnotherControlHoldsIt() {
        // 默认布局里虚拟按键 OK 与手柄 A 都映射 Enter+Space：
        // 屏幕按住 OK 时碰一下手柄 A 并松开，不得打断屏幕上的长按
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)

        dispatcher.press("pad.ok", listOf(66, 62), autoKeep = false)
        dispatcher.press("gamepad.A", listOf(66, 62), autoKeep = false)
        dispatcher.release("gamepad.A", autoKeep = false)

        // 手柄抬起后仍未发任何 keyup
        assertEquals(listOf(66 to true, 62 to true), sink.events)

        dispatcher.release("pad.ok", autoKeep = false)
        assertEquals(listOf(66 to true, 62 to true, 62 to false, 66 to false), sink.events)
    }

    @Test
    fun duplicateKeyWithinSamePressDispatchesOnce() {
        // 同一控件的 keys 里重复同一键：只应发一次 down/up
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("dup", listOf(66, 66), autoKeep = false)
        dispatcher.release("dup", autoKeep = false)
        assertEquals(listOf(66 to true, 66 to false), sink.events)
    }

    @Test
    fun partiallyOverlappingKeysReleaseOnlyWhenLastHolderLeaves() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)

        dispatcher.press("a", listOf(66, 62), autoKeep = false)
        dispatcher.press("b", listOf(62), autoKeep = false)
        dispatcher.release("a", autoKeep = false)

        // 66 已无持有者（释放），62 仍被 b 持有（不释放）
        assertEquals(listOf(66 to true, 62 to true, 66 to false), sink.events)

        dispatcher.release("b", autoKeep = false)
        assertEquals(listOf(66 to true, 62 to true, 66 to false, 62 to false), sink.events)
    }

    @Test
    fun scopeReleaseReleasesEveryHeldKeyExactlyOnce() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("pad:ok", listOf(66, 62), autoKeep = false)
        dispatcher.press("gp:A", listOf(66), autoKeep = false)

        dispatcher.releaseScope("pad:")
        dispatcher.releaseScope("gp:")

        // 两个键各按一次、各放一次；释放不得重放 down
        val downs = sink.events.filter { it.second }.map { it.first }.sorted()
        val ups = sink.events.filter { !it.second }.map { it.first }.sorted()
        assertEquals(listOf(62, 66), downs)
        assertEquals(listOf(62, 66), ups)
        assertEquals(2, ups.size)

        // 释放后表已清空：再次按 scope 释放不应重复发 up
        sink.events.clear()
        dispatcher.releaseScope("pad:")
        dispatcher.releaseScope("gp:")
        assertEquals(0, sink.events.size)
    }

    // ---------- 来源命名空间隔离 ----------

    @Test
    fun releaseScopeDoesNotReleaseOtherSource() {
        // 手柄拔插/轴 CANCEL 只应释放手柄侧：屏幕上按住的虚拟按键不得被放掉
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)

        dispatcher.press("pad:ok", listOf(66), autoKeep = false)
        dispatcher.press("gp:A", listOf(66), autoKeep = false)

        dispatcher.releaseScope("gp:")

        // 66 仍被 pad:ok 持有，不得发 keyup
        assertEquals(listOf(66 to true), sink.events)

        dispatcher.releaseScope("pad:")
        assertEquals(listOf(66 to true, 66 to false), sink.events)
    }

    @Test
    fun releaseScopeClearsOwnAutoKeepToggle() {
        // 关手柄开关时，手柄侧 autoKeep（如跳过=Ctrl）必须解除且不影响按键层
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)

        dispatcher.press("gp:X", listOf(113), autoKeep = true)
        dispatcher.press("pad:skip", listOf(113), autoKeep = true)

        dispatcher.releaseScope("gp:")
        assertEquals("手柄侧保持应被解除，按键层仍持有", listOf(113 to true), sink.events)
        assertEquals(false, dispatcher.isToggled("gp:X"))
        assertEquals(true, dispatcher.isToggled("pad:skip"))

        dispatcher.releaseScope("pad:")
        assertEquals(listOf(113 to true, 113 to false), sink.events)
    }

    @Test
    fun releaseScopeOnEmptySourceIsNoop() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("pad:ok", listOf(66), autoKeep = false)
        dispatcher.releaseScope("gp:")
        assertEquals(listOf(66 to true), sink.events)
    }

    @Test
    fun directionKeysParticipateInRefCountAcrossSources() {
        // 虚拟方向键与手柄 D-Pad 默认同为方向键：任一侧松开不得打断另一侧
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)

        dispatcher.press("pad:direction.up", listOf(38), autoKeep = false)
        dispatcher.press("gp:DPAD_UP", listOf(38), autoKeep = false)
        dispatcher.release("gp:DPAD_UP", autoKeep = false)

        assertEquals("手柄侧松开不得打断按键层方向", listOf(38 to true), sink.events)
        dispatcher.release("pad:direction.up", autoKeep = false)
        assertEquals(listOf(38 to true, 38 to false), sink.events)
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
        dispatcher.press("pad:combo", listOf(CanonicalKeys.ACTION_SCREENSHOT, 66), autoKeep = false)
        dispatcher.release("pad:combo", autoKeep = false)
        assertEquals(listOf(CanonicalKeys.ACTION_SCREENSHOT), sink.actions)
        assertEquals(listOf(66 to true, 66 to false), sink.events)
    }

    @Test
    fun releaseAllDoesNotFireActions() {
        val sink = RecordingSink()
        val dispatcher = KeyDispatcher(sink)
        dispatcher.press("shot", listOf(CanonicalKeys.ACTION_SCREENSHOT), autoKeep = false)
        dispatcher.releaseScope("pad:")
        dispatcher.releaseScope("gp:")
        // 释放不应重放动作
        assertEquals(1, sink.actions.size)
    }
}
