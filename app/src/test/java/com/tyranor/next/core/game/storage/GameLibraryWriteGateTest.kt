package com.tyranor.next.core.game.storage

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        val registrationFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val eviction = executor.submit(Callable {
                gate.evictIfUnused(registered::get) {
                    evictionStarted.countDown()
                    assertTrue(releaseEviction.await(5, TimeUnit.SECONDS))
                    assertFalse(registered.get())
                    true
                }
            })
            assertTrue(evictionStarted.await(5, TimeUnit.SECONDS))

            val registration = executor.submit {
                registrationStarted.countDown()
                gate.update { registered.set(true) }
                registrationFinished.countDown()
            }
            assertTrue(registrationStarted.await(5, TimeUnit.SECONDS))
            assertFalse(registrationFinished.await(150, TimeUnit.MILLISECONDS))
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
