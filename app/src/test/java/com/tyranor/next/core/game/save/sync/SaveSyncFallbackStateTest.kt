package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveSyncFallbackStateTest {
    @get:Rule
    val temp = TemporaryFolder()

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
}
