package org.tvp.krkrsdl3

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * C-1 回归护栏（audit/13 §二）：锁定 KRKRCall.WaitInputResult 的四条防冻结语义。
 *
 * 任一条被回退（初始 latch 改回未触发态 / 删掉 latch 作废检测 / 删掉保险丝 /
 * 吞掉中断标记），对应用例将挂起直至 @Test(timeout) 超时失败，而非静默通过。
 */
class KRKRCallWaitInputResultTest {

    @Before
    fun resetStaticState() {
        KRKRCall.mInputDialog = null
        KRKRCall.mInputResult = ""
        KRKRCall.mInputResultCode = -1
        // 与生产初值一致：已触发态，杂散等待立即返回
        KRKRCall.mInputLatch = CountDownLatch(0)
        KRKRCall.maxWaitMs = KRKRCall.DEFAULT_MAX_WAIT_MS
    }

    @After
    fun restoreFuse() {
        KRKRCall.maxWaitMs = KRKRCall.DEFAULT_MAX_WAIT_MS
    }

    /** 无弹窗时的杂散等待必须立即按取消返回（初始 latch=已触发态）。回退为未触发态则本例挂死。 */
    @Test(timeout = 5_000L)
    fun strayWaitWithoutDialogReturnsCancelImmediately() {
        val start = System.nanoTime()
        assertEquals(-1, KRKRCall.WaitInputResult())
        assertTrue("应立即返回而非阻塞", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
    }

    /** 等待期间出现新对话框（latch 被换）时，旧等待必须作废返回，而非等错对象无限挂起。 */
    @Test(timeout = 10_000L)
    fun staleWaiterInvalidatedWhenLatchReplaced() {
        val stale = CountDownLatch(1)
        KRKRCall.mInputLatch = stale
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit(java.util.concurrent.Callable { KRKRCall.WaitInputResult() })
            // 让等待方至少进入一个 200ms 轮询切片
            Thread.sleep(300)
            // 模拟新一轮 ShowInputBox 已同步替换 latch 且该轮已完成（count=0）
            KRKRCall.mInputLatch = CountDownLatch(0)
            assertEquals("旧等待应作废并按取消返回", -1, future.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    /** 保险丝：孤儿等待（latch 永不触发且不被替换）最迟在时限后解锁。删除保险丝检查则本例挂死超时。 */
    @Test(timeout = 10_000L)
    fun fuseCapsOrphanWait() {
        KRKRCall.maxWaitMs = 400L
        KRKRCall.mInputLatch = CountDownLatch(1)
        val start = System.nanoTime()
        assertEquals(-1, KRKRCall.WaitInputResult())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("应在保险丝时限附近返回，实际 ${elapsedMs}ms", elapsedMs in 300..9_000)
    }

    /** 中断路径：恢复中断标记并按取消返回，不吞异常不丢标记。 */
    @Test(timeout = 5_000L)
    fun interruptedWaitReturnsCancelAndRestoresFlag() {
        KRKRCall.mInputLatch = CountDownLatch(1)
        Thread.currentThread().interrupt()
        try {
            assertEquals(-1, KRKRCall.WaitInputResult())
            assertTrue("中断标记应被恢复", Thread.interrupted())
        } finally {
            // 兜底清理，避免污染同线程后续用例
            Thread.interrupted()
        }
    }

    /** 正常路径：对话框完成（countDown + 写结果码）后等待方拿到结果码而非哨兵值。 */
    @Test(timeout = 5_000L)
    fun resultCodePropagatesAfterDialogCompletes() {
        val latch = CountDownLatch(1)
        KRKRCall.mInputLatch = latch
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit {
                Thread.sleep(50)
                KRKRCall.mInputResult = "input"
                KRKRCall.mInputResultCode = 0
                latch.countDown()
                null
            }
            future.get(2, TimeUnit.SECONDS)
            assertEquals(0, KRKRCall.WaitInputResult())
        } finally {
            pool.shutdownNow()
        }
    }

    /** 宿主销毁兜底：cancelPendingInput 必须解除 native 侧阻塞（onDestroy join 死锁回归）。 */
    @Test(timeout = 5_000L)
    fun cancelPendingInputReleasesWaiter() {
        KRKRCall.mInputLatch = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit(java.util.concurrent.Callable { KRKRCall.WaitInputResult() })
            Thread.sleep(100)
            KRKRCall.cancelPendingInput()
            assertEquals("销毁兜底应放行等待线程并按取消返回", -1, future.get(4, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }
}
