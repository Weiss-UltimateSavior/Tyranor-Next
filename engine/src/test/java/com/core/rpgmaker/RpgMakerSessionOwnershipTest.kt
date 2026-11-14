package com.core.rpgmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：旧会话的退出任务不得杀掉新会话所在的 `:rpgmaker` 进程。
 *
 * 该 Activity 是独立进程 + `singleInstance`，`finish()` 排队的 `killProcess` 会延迟执行；
 * 用户在延迟窗口内重开游戏时进程还活着，会被直接复用给新会话——旧任务若无条件执行，
 * 新会话刚起来就被杀。`PROCESS_EXIT_DELAY_MS` 从 500ms 提到 1500ms 正是放大这个窗口的
 * 改动，所以这条不变量必须有 JVM 覆盖。
 */
class RpgMakerSessionOwnershipTest {

    @Test
    fun newerSessionTakesOwnershipAndInvalidatesTheOlderExit() {
        val ownership = RpgMakerSessionOwnership()
        val first = ownership.beginSession()
        assertTrue("首个会话拥有进程", ownership.isOwner(first))

        val second = ownership.beginSession()
        assertFalse("新会话启动后，旧会话不得再杀进程", ownership.isOwner(first))
        assertTrue("新会话接管进程", ownership.isOwner(second))
    }

    @Test
    fun repeatedStartsKeepOnlyTheLatestOwner() {
        val ownership = RpgMakerSessionOwnership()
        val epochs = (1..5).map { ownership.beginSession() }

        assertEquals("期号必须单调递增", epochs, epochs.sorted())
        assertEquals(5, epochs.toSet().size)
        assertTrue(ownership.isOwner(epochs.last()))
        assertTrue("除最后一期外全部失效", epochs.dropLast(1).none { ownership.isOwner(it) })
    }

    @Test
    fun staleTicketFromBeforeAnyRestartStillOwnsTheProcess() {
        // 没有新会话启动时，排队中的退出任务必须照常执行（否则进程永远不退出）
        val ownership = RpgMakerSessionOwnership()
        val only = ownership.beginSession()
        assertTrue(ownership.isOwner(only))
    }
}
