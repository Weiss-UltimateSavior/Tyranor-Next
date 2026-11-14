package com.tyranor.next.core.auth

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新去重协调器行为回归：同一时刻至多一个刷新在途；并发等待方在在途任务
 * 结束后重走快速路径（成功则采纳其写回结果，失败则同步放弃），不重复发起网络刷新。
 */
class SingleFlightRefreshTest {

    @Test
    fun concurrentWaitersShareOneRefreshAndAdoptItsResult() {
        val gate = SingleFlightRefresh()
        val taskStarted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        val executions = AtomicInteger(0)
        val store = AtomicReference<String?>(null)

        val runnerResult = AtomicReference<String?>("unset")
        val runner = thread {
            runnerResult.set(
                gate.run(
                    recheck = { store.get() },
                    task = {
                        executions.incrementAndGet()
                        taskStarted.countDown()
                        releaseTask.await()
                        store.set("new-token")
                        "new-token"
                    },
                ),
            )
        }
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS))

        // 在途期间启动并发等待方：必须等待在途任务，不得执行第二个任务
        val waiterResults = CopyOnWriteArrayList<String?>()
        val waiters = (1..4).map {
            thread {
                waiterResults.add(
                    gate.run(
                        recheck = { store.get() },
                        task = {
                            executions.incrementAndGet()
                            "dup"
                        },
                    ),
                )
            }
        }
        // 等待全部等待方确定挂起在条件队列后再释放任务，消除固定 sleep 竞态
        try {
            awaitWaiters(gate, waiters.size)
        } finally {
            releaseTask.countDown()
        }
        runner.join(TimeUnit.SECONDS.toMillis(5))
        waiters.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        assertEquals(1, executions.get())
        assertEquals("new-token", runnerResult.get())
        assertEquals(listOf("new-token", "new-token", "new-token", "new-token"), waiterResults.toList())
    }

    @Test
    fun waitersDoNotRetryAfterAFailedInFlightRefresh() {
        val gate = SingleFlightRefresh()
        val taskStarted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        val executions = AtomicInteger(0)
        val store = AtomicReference<String?>(null)

        val runner = thread {
            gate.run(
                recheck = { store.get() },
                task = {
                    executions.incrementAndGet()
                    taskStarted.countDown()
                    releaseTask.await()
                    null // 刷新失败，未写回
                },
            )
        }
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS))

        val waiterResults = CopyOnWriteArrayList<String?>()
        val waiters = (1..3).map {
            thread {
                waiterResults.add(
                    gate.run(
                        recheck = { store.get() },
                        task = {
                            executions.incrementAndGet()
                            "dup"
                        },
                    ),
                )
            }
        }
        // 等待全部等待方确定挂起在条件队列后再释放任务，消除固定 sleep 竞态
        try {
            awaitWaiters(gate, waiters.size)
        } finally {
            releaseTask.countDown()
        }
        runner.join(TimeUnit.SECONDS.toMillis(5))
        waiters.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        // 等待方同步放弃（null），不重试网络刷新
        assertEquals(1, executions.get())
        assertEquals(listOf<String?>(null, null, null), waiterResults.toList())
    }

    @Test
    fun runnerRecheckAvoidsSequentialDuplicateRefresh() {
        val gate = SingleFlightRefresh()
        val executions = AtomicInteger(0)
        val store = AtomicReference<String?>("fresh-token")

        // 进闸前双检：快路径已命中（并发刷新刚写回）时不重复执行任务
        val result = gate.run(
            recheck = { store.get() },
            task = {
                executions.incrementAndGet()
                "dup"
            },
        )
        assertEquals("fresh-token", result)
        assertEquals(0, executions.get())
    }

    private fun awaitWaiters(gate: SingleFlightRefresh, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (gate.waiterCount() < expected && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue(
            "expected $expected waiters in queue, got ${gate.waiterCount()}",
            gate.waiterCount() >= expected,
        )
    }
}
