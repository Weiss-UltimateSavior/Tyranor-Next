package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SaveSyncFallbackStateTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun retryWithEmptyExclusionsKeepsOwnershipAfterProcessRecreation() {
        val directory = File(temp.root, "empty-retry")
        val store = SaveSyncFallbackStateStore(directory)
        store.schedule("exit", "worker", emptySet()) {}

        store.complete("exit", "worker", retry = true, emptySet(), sharedChain = true)

        val restarted = SaveSyncFallbackStateStore(directory)
        assertFalse(restarted.isSuperseded("exit", "worker"))
        assertTrue(restarted.load("exit").isEmpty())
        restarted.complete("exit", "worker", retry = false, emptySet(), sharedChain = true)
        assertTrue(restarted.isSuperseded("exit", "worker"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun exclusionsAccumulateAndReloadPerWorkerId() {
        val directory = File(temp.root, "workers")
        val store = SaveSyncFallbackStateStore(directory)
        store.save("worker-a", listOf("content://first"))
        store.save("worker-a", store.load("worker-a") + setOf("content://second"))
        store.save("worker-b", listOf("content://independent"))

        assertEquals(
            setOf("content://first", "content://second"),
            SaveSyncFallbackStateStore(directory).load("worker-a"),
        )
        assertEquals(setOf("content://independent"), store.load("worker-b"))
    }

    @Test
    fun canceledWorkerStateExpiresWithoutTouchingSaveData() {
        var now = 1_000_000L
        val workers = File(temp.root, "workers")
        val saveData = File(temp.root, "save_data.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val store = SaveSyncFallbackStateStore(workers) { now }
        store.save("cancelled-worker", listOf("content://full"))

        now += SaveSyncFallbackStateStore.MAX_AGE_MILLIS + 1
        store.pruneExpired()

        assertTrue(saveData.isFile)
        assertEquals(emptySet<String>(), store.load("cancelled-worker"))
        assertFalse(workers.listFiles().orEmpty().any { it.name.startsWith("state_") })
    }

    @Test
    fun appendedSuccessorKeepsChainExclusionsAndRejectsPredecessorCleanup() {
        val directory = File(temp.root, "replacement")
        val store = SaveSyncFallbackStateStore(directory)
        val scheduled = mutableListOf<String>()
        store.schedule("open", "request-a", setOf("content://first")) { scheduled += "request-a" }
        store.schedule("open", "request-b", setOf("content://second")) { scheduled += "request-b" }
        store.remove("open", "request-a")
        assertTrue(store.isSuperseded("open", "request-a"))
        assertFalse(store.isSuperseded("open", "request-b"))

        val restarted = SaveSyncFallbackStateStore(directory)
        assertEquals(listOf("request-a", "request-b"), scheduled)
        assertEquals(setOf("content://first", "content://second"), restarted.load("open"))
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })

        restarted.merge("open", setOf("content://late-predecessor"), sharedChain = true)
        assertEquals(setOf("content://first", "content://second", "content://late-predecessor"), store.load("open"))
        restarted.remove("open", "request-b")
        store.merge("open", setOf("content://too-late"), sharedChain = true)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun repeatedReplacementHasBoundedStateAndIndependentDirections() {
        val directory = File(temp.root, "bounded")
        val store = SaveSyncFallbackStateStore(directory)
        repeat(25) { index ->
            store.schedule("open", "request-$index", setOf("content://$index")) {}
            store.remove("open", "request-${index - 1}")
        }
        store.schedule("exit", "exit-request", setOf("content://exit")) {}

        assertEquals((0 until 25).map { "content://$it" }.toSet(), store.load("open"))
        assertEquals(setOf("content://exit"), store.load("exit"))
        assertEquals(2, directory.listFiles().orEmpty().count { it.extension == "json" })
        store.remove("open", "request-24")
        assertEquals(setOf("content://exit"), store.load("exit"))
        store.removeAll()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pendingChainDoesNotLoseExclusionsWhileWaitingForUnmeteredNetwork() {
        var now = 1_000_000L
        val directory = File(temp.root, "offline")
        val store = SaveSyncFallbackStateStore(directory) { now }
        store.schedule("open", "request-a", setOf("content://full")) {}

        now += SaveSyncFallbackStateStore.MAX_AGE_MILLIS * 2
        val restarted = SaveSyncFallbackStateStore(directory) { now }
        restarted.pruneExpired()

        assertEquals(setOf("content://full"), restarted.load("open"))
        restarted.remove("open", "request-a")
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun concurrentSchedulesAndWorkerMergesDoNotOverwriteExclusions() {
        val directory = File(temp.root, "concurrent")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        val store = SaveSyncFallbackStateStore(directory)
        store.claim("open", "initial", setOf("content://initial"))
        try {
            val futures = (0 until 12).map { index ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    val instance = SaveSyncFallbackStateStore(directory)
                    if (index % 2 == 0) instance.schedule("open", "request-$index", setOf("content://$index")) {}
                    else instance.merge("open", setOf("content://$index"), sharedChain = true)
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals((0 until 12).map { "content://$it" }.toSet() + "content://initial", store.load("open"))
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test
    fun failedScheduleKeepsExclusionsForNextReplacementWithoutUuidRecords() {
        val directory = File(temp.root, "enqueue_failure")
        val store = SaveSyncFallbackStateStore(directory)
        store.schedule("open", "previous", setOf("content://previous")) {}
        assertThrows(IOException::class.java) {
            store.schedule("open", "rejected", setOf("content://new")) { throw IOException("enqueue failed") }
        }
        assertFalse(store.isSuperseded("open", "previous"))
        assertTrue(store.isSuperseded("open", "rejected"))
        store.schedule("open", "retry", emptySet()) {}

        assertEquals(setOf("content://previous", "content://new"), store.load("open"))
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })
        store.remove("open", "retry")
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun corruptExclusionsFailClosedAndRemainAvailableForRepair() {
        val directory = File(temp.root, "corrupt").apply { mkdirs() }
        val record = File(directory, SaveContentHash.text("open") + ".json").apply { writeText("{not-json") }

        val failure = assertThrows(SaveSyncException::class.java) {
            SaveSyncFallbackStateStore(directory).load("open")
        }

        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        assertEquals("{not-json", record.readText())
    }
}
