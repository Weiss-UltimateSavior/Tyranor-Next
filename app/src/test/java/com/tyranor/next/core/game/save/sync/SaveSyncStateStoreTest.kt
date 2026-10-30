package com.tyranor.next.core.game.save.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveSyncStateStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun validStateRoundTrips() {
        val directory = temp.newFolder("valid")
        val store = SaveSyncStateStore(directory)
        store.save(KEY, JSONObject().put("token", "value").put("count", 2))

        val loaded = store.load(KEY)

        assertEquals("value", loaded.getString("token"))
        assertEquals(2, loaded.getInt("count"))
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".corrupt") })
    }

    @Test fun strictLoadQuarantinesCorruptionWithoutSilentlyResettingState() {
        val directory = temp.newFolder("strict")
        val corrupt = File(directory, "$KEY.json").apply { writeText("{not-json") }
        val store = SaveSyncStateStore(directory)

        repeat(2) {
            val failure = assertThrows(SaveSyncException::class.java) { store.load(KEY) }
            assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        }

        assertEquals("{not-json", corrupt.readText())
        val quarantine = File(directory, "$KEY.json.corrupt")
        assertEquals("{not-json", quarantine.readText())
    }

    @Test fun recoverCorruptTreatsQuarantinedStateAsMissing() {
        val directory = temp.newFolder("recover")
        val corrupt = File(directory, "$KEY.json").apply { writeText("{not-json") }
        val store = SaveSyncStateStore(directory, recoverCorrupt = true)

        assertEquals(0, store.load(KEY).length())

        assertFalse(corrupt.exists())
        val quarantine = File(directory, "$KEY.json.corrupt")
        assertEquals("{not-json", quarantine.readText())
        assertEquals(0, store.load(KEY).length())
    }

    @Test fun genuineReadFailureIsNotTreatedAsCorruptJson() {
        val directory = temp.newFolder("io-failure")
        val unreadable = File(directory, "$KEY.json").apply { mkdirs() }

        listOf(
            SaveSyncStateStore(directory),
            SaveSyncStateStore(directory, recoverCorrupt = true),
        ).forEach { store ->
            val failure = assertThrows(SaveSyncException::class.java) { store.load(KEY) }
            assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        }

        assertTrue(unreadable.isDirectory)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".corrupt") })
    }

    @Test fun removeClearsStateAndPerKeyQuarantinesOnly() {
        val directory = temp.newFolder("remove")
        val removed = listOf(
            "$KEY.json",
            "$KEY.json.corrupt",
            "$KEY.json.corrupt-1",
            "$KEY.json.corrupt-42",
        ).map { name -> File(directory, name).apply { writeText(name) } }
        val preserved = listOf(
            "$KEY.json.corrupt-old",
            "$OTHER_KEY.json",
            "$OTHER_KEY.json.corrupt",
        ).map { name -> File(directory, name).apply { writeText(name) } }

        SaveSyncStateStore(directory).remove(KEY)

        assertTrue(removed.none { it.exists() })
        assertTrue(preserved.all { it.exists() })
    }

    @Test fun discardCorruptRemovesOnlyUnreadableState() {
        val directory = temp.newFolder("discard")
        val corrupt = File(directory, "$KEY.json").apply { writeText("{not-json") }
        val valid = File(directory, "$OTHER_KEY.json").apply { writeText("{\"token\":\"keep\"}") }
        val store = SaveSyncStateStore(directory)

        store.discardCorrupt()

        assertFalse(corrupt.exists())
        assertTrue(valid.isFile)
        assertEquals("keep", store.load(OTHER_KEY).getString("token"))
    }

    companion object {
        private val KEY = "a".repeat(64)
        private val OTHER_KEY = "b".repeat(64)
    }
}
