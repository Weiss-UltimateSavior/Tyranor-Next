package com.core.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 异步落盘会话账本单测。
 *
 * 锁定的是「迟到结果不得再影响界面」这条语义：编辑器把写盘放到 IO 线程后，结果回来时
 * 可能已被新提交接替、或用户已取消编辑。逐条对应用户可见的坏体验：
 *  - 连点两次保存：先发的结果不得抢着提示/收尾（否则「已保存成功又弹失败」）；
 *  - 取消或退出编辑后：结果不得再提示（否则用户已离开页面还看到弹窗）；
 *  - 失败结果同样要按会话丢弃（失败提示也会出现在错误的时刻）。
 */
class SaveSessionTrackerTest {

    @Test
    fun newerSubmissionSupersedesOlderOne() {
        val tracker = SaveSessionTracker()
        val first = tracker.begin()
        val second = tracker.begin()

        assertFalse("先发起的会话必须失效", tracker.isCurrent(first))
        assertTrue("最后一次发起才是当前会话", tracker.isCurrent(second))
        assertFalse("过期结果不得被接受", tracker.complete(first))
        assertTrue(tracker.complete(second))
        assertFalse(tracker.hasPending)
    }

    @Test
    fun sessionEndedByCancelRejectsLateResult() {
        // 用户点保存后立刻取消（或返回上一页）：会话边界清空在途状态
        val tracker = SaveSessionTracker()
        val session = tracker.begin()
        tracker.clear()

        assertFalse(tracker.isCurrent(session))
        assertFalse("会话已结束，结果不得生效", tracker.complete(session))
    }

    @Test
    fun failureResultIsAlsoSessionChecked() {
        // 失败与成功走同一条校验：否则「已离开编辑页还弹保存失败」
        val tracker = SaveSessionTracker()
        val session = tracker.begin()
        tracker.clear()
        assertFalse(tracker.complete(session))

        // 仍属当前会话的失败结果必须被接受（调用方据此留在编辑态并提示）
        val fresh = tracker.begin()
        assertTrue(tracker.complete(fresh))
    }

    @Test
    fun staleSessionCannotClearFreshPending() {
        // 关键回归：过期结果先回来时，不得把仍在途的新会话一并清掉
        // （清掉会让新会话的成功结果被判过期 → 文件已写好但界面停在编辑态）
        val tracker = SaveSessionTracker()
        val first = tracker.begin()
        val second = tracker.begin()

        assertFalse(tracker.complete(first))
        assertTrue("新会话必须仍在途", tracker.hasPending)
        assertTrue(tracker.isCurrent(second))
        assertTrue(tracker.complete(second))
    }

    @Test
    fun repeatedCompleteIsIdempotent() {
        val tracker = SaveSessionTracker()
        val session = tracker.begin()
        assertTrue(tracker.complete(session))
        assertFalse("重复收尾必须为空操作", tracker.complete(session))
        assertFalse(tracker.hasPending)
        // 收尾后可以正常发起下一轮提交
        assertTrue(tracker.isCurrent(tracker.begin()))
    }
}
