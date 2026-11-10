package com.tyranor.next.core.game.storage

import java.util.concurrent.Callable
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLibraryWriteGateTest {
    @Test
    fun registrationWaitsForEvictionAndPreventsTheNextEviction() {
        val gate = GameLibraryWriteGate()
        val registered = AtomicBoolean(false)
        val evictionStarted = CountDownLatch(1)
        val releaseEviction = CountDownLatch(1)
        val registrationStarted = CountDownLatch(1)
        val evictionThread = AtomicReference<Thread>()
        val registrationThread = AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val eviction = executor.submit(Callable {
                gate.evictIfUnused(registered::get) {
                    evictionThread.set(Thread.currentThread())
                    evictionStarted.countDown()
                    assertTrue(releaseEviction.await(5, TimeUnit.SECONDS))
                    assertFalse(registered.get())
                    true
                }
            })
            assertTrue(evictionStarted.await(5, TimeUnit.SECONDS))

            val registration = executor.submit {
                registrationThread.set(Thread.currentThread())
                registrationStarted.countDown()
                gate.update { registered.set(true) }
            }
            assertTrue(registrationStarted.await(5, TimeUnit.SECONDS))
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val waiting = threads.getThreadInfo(registrationThread.get().id)
                if (waiting?.threadState == Thread.State.BLOCKED && waiting.lockOwnerId == evictionThread.get().id) break
                check(!registration.isDone && System.nanoTime() < deadline) { "Registration did not wait for eviction" }
                Thread.sleep(1)
            }
            releaseEviction.countDown()
            assertTrue(eviction.get(5, TimeUnit.SECONDS))
            registration.get(5, TimeUnit.SECONDS)

            assertFalse(gate.evictIfUnused(registered::get) { error("A referenced copy must not be evicted") })
        } finally {
            releaseEviction.countDown()
            executor.shutdownNow()
        }
    }
}
