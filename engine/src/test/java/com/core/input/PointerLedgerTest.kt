package com.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟按键层的触摸指针账本单测。
 *
 * 覆盖三条曾经出问题的语义：
 *  - 「按住按钮 → 滑出范围」这一常见取消手势只移除**一次**（旧实现里 `return@let`
 *    只退出 lambda，控制流继续走到第二次 `remove` 而抛 `IllegalStateException`）；
 *  - 多指共按同一按钮时，先抬起/滑出的那一指不得触发释放；
 *  - 指针抬起后账本清空，重复收尾不产生副作用。
 */
class PointerLedgerTest {

    private fun button(id: String) = PadButton(id = id, text = id, x = 0.5f, y = 0.5f, size = 0.1f)

    @Test
    fun slidingOutRemovesPointerExactlyOnce() {
        // 按下 → 滑出：retainOnly 每轮至多删一项，不得抛 IllegalStateException
        val ledger = PointerLedger()
        val target = button("ok")
        ledger.press(1, target)
        assertEquals(1, ledger.size)

        val released = ledger.retainOnly { false }
        assertEquals("滑出后必须摘掉该指针", 0, ledger.size)
        assertEquals(listOf(target), released)
        assertNull("指针已摘除，二次查询必须为空", ledger.buttonOf(1))

        // 再滑一次（同一指针）：账本已空，不得重复产出待释放按钮
        assertTrue(ledger.retainOnly { false }.isEmpty())
    }

    @Test
    fun keepPredicateRetainsOtherPointers() {
        val ledger = PointerLedger()
        val held = button("ok")
        val left = button("cancel")
        ledger.press(1, held)
        ledger.press(2, left)

        // 只有 1 离开范围
        val released = ledger.retainOnly { pointerId -> pointerId == 1 }
        assertEquals(listOf(left), released)
        assertEquals(held, ledger.buttonOf(1))
        assertNull(ledger.buttonOf(2))
    }

    @Test
    fun heldByIdGuardsSharedButtonRelease() {
        // 两指按同一按钮：先滑出的那一指不得让按钮进入释放流程
        val ledger = PointerLedger()
        val shared = button("ok")
        ledger.press(1, shared)
        ledger.press(2, shared)

        val releasedA = ledger.retainOnly { pointerId -> pointerId != 1 }
        assertEquals(listOf(shared), releasedA)
        assertTrue("另一指仍按着，按钮必须保持按下", ledger.isHeldById(shared.id))

        val releasedB = ledger.retainOnly { false }
        assertEquals(listOf(shared), releasedB)
        assertFalse("最后一指离开后才允许释放", ledger.isHeldById(shared.id))
    }

    @Test
    fun releaseReturnsButtonAndClearsPointer() {
        val ledger = PointerLedger()
        val target = button("ok")
        ledger.press(7, target)
        assertEquals(target, ledger.release(7))
        assertNull(ledger.buttonOf(7))
        assertNull("重复释放必须为空操作", ledger.release(7))
    }

    @Test
    fun allButtonsDeduplicatesByButtonId() {
        val ledger = PointerLedger()
        val a = button("a")
        val b = button("b")
        ledger.press(1, a)
        ledger.press(2, b)
        ledger.press(3, a)
        // 整层释放要按**按钮**去重：同一按钮被多指按住时重复释放会送出错位的 keyup 计数
        assertEquals(listOf("a", "b"), ledger.allButtons().map { it.id })
        ledger.clear()
        assertTrue(ledger.isEmpty())
        assertTrue(ledger.allButtons().isEmpty())
    }
}
