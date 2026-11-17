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
                        assertTrue(releaseTask.await(5, TimeUnit.SECONDS))
                        store.set("new-token")
                        "new-token"
                    },
                ),
            )
        }
        val waiterResults = CopyOnWriteArrayList<String?>()
        val waiters = mutableListOf<Thread>()
        try {
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS))

            // 在途期间启动并发等待方：必须等待在途任务，不得执行第二个任务
            repeat(4) {
                waiters += thread {
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
            awaitWaiters(gate, waiters.size)
        } finally {
            releaseTask.countDown()
            runner.join(TimeUnit.SECONDS.toMillis(5))
            waiters.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
        }

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
                    assertTrue(releaseTask.await(5, TimeUnit.SECONDS))
                    null // 刷新失败，未写回
                },
            )
        }
        val waiterResults = CopyOnWriteArrayList<String?>()
        val waiters = mutableListOf<Thread>()
        try {
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS))

            repeat(3) {
                waiters += thread {
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
            awaitWaiters(gate, waiters.size)
        } finally {
            releaseTask.countDown()
            runner.join(TimeUnit.SECONDS.toMillis(5))
            waiters.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
        }

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

    @Test
    fun adoptWaiterReceivesLeaderResultForShortTtlResponses() {
        val gate = SingleFlightRefresh()
        val leaderEntered = CountDownLatch(1)
        val releaseLeader = CountDownLatch(1)
        val leaderResult = AtomicReference<String?>(null)
        val waiterResult = AtomicReference<String?>(null)
        val recheckCalls = AtomicInteger(0)

        val leader = thread {
            leaderResult.set(
                gate.run(
                    recheck = { null },
                    task = {
                        leaderEntered.countDown()
                        assertTrue(releaseLeader.await(5, TimeUnit.SECONDS))
                        "short-lived-token"
                    },
                ),
            )
        }
        var waiter: Thread? = null
        try {
            assertTrue(leaderEntered.await(5, TimeUnit.SECONDS))
            waiter = thread {
                waiterResult.set(
                    gate.run<String?>(
                        recheck = {
                            recheckCalls.incrementAndGet()
                            null
                        },
                        adoptWaiter = { fromLeader -> fromLeader ?: "fallback" },
                        task = { error("waiter must not execute task") },
                    ),
                )
            }
            awaitWaiters(gate, 1)
        } finally {
            releaseLeader.countDown()
            leader.join(TimeUnit.SECONDS.toMillis(5))
            waiter?.join(TimeUnit.SECONDS.toMillis(5))
        }

        assertEquals("short-lived-token", leaderResult.get())
        assertEquals("short-lived-token", waiterResult.get())
        assertEquals(0, recheckCalls.get())

        val rawOverflow = SecureAuthStore.oauthExpiresAtMillis(
            expiresInSeconds = Long.MAX_VALUE,
            nowMillis = 1_000_000L,
        )
        assertTrue(rawOverflow > 1_000_000L)
        assertEquals(0L, SecureAuthStore.oauthExpiresAtMillis(0L, 1_000_000L))
    }

    @Test
    fun adoptSingleFlightAccessTokenAcceptsSubsequentShortTtlTokenWhenStillValid() {
        val now = 1_000_000L
        // 领导者写回短 TTL 令牌 T1 后，另一并发方抢先续期写入 T2（仍在绝对有效期内、未越界），
        // 等待方仍应采纳最新的 T2，而不会因 stored != leaderToken 误判失败。
        assertEquals(
            "token_T2",
            SecureAuthStore.adoptSingleFlightAccessToken(
                leaderToken = "token_T1",
                needsReauth = false,
                storedAccessToken = "token_T2",
                storedExpiresAtMillis = now + 25_000L,
                allowZeroExpiry = false,
                refreshSkewMs = 60_000L,
                nowMillis = now,
            ),
        )
        // 已过期或已置位 needsReauth 或 leader 失败均不得采纳
        assertEquals(
            null,
            SecureAuthStore.adoptSingleFlightAccessToken(
                leaderToken = "token_T1",
                needsReauth = false,
                storedAccessToken = "token_T2",
                storedExpiresAtMillis = now - 1L,
                allowZeroExpiry = false,
                refreshSkewMs = 60_000L,
                nowMillis = now,
            ),
        )
        assertEquals(
            null,
            SecureAuthStore.adoptSingleFlightAccessToken(
                leaderToken = null,
                needsReauth = false,
                storedAccessToken = "token_T2",
                storedExpiresAtMillis = now + 25_000L,
                allowZeroExpiry = false,
                refreshSkewMs = 60_000L,
                nowMillis = now,
            ),
        )
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
