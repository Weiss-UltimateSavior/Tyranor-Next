package com.tyranor.next.core.game.save.sync

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

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
            assertTrue(failure.cause is JSONException)
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
            assertTrue(failure.cause is IOException)
            assertFalse(failure.cause is JSONException)
        }

        assertTrue(unreadable.isDirectory)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".corrupt") })
    }

    @Test fun removeClearsOnlyLiveStateAndPreservesEveryRecoveryCopy() {
        val directory = temp.newFolder("remove")
        val removed = File(directory, "$KEY.json").apply { writeText("live state") }
        val preserved = listOf(
            "$KEY.json.corrupt",
            "$KEY.json.corrupt-1",
            "$KEY.json.corrupt-42",
            "$KEY.json.corrupt-old",
            "$OTHER_KEY.json",
            "$OTHER_KEY.json.corrupt",
        ).map { name -> File(directory, name).apply { writeText(name) } }
        val unreadable = File(directory, "${"c".repeat(64)}.json").apply { mkdir() }
        File(unreadable, "child").writeText("unrelated I/O failure")

        SaveSyncStateStore(directory).remove(KEY)

        assertFalse(removed.exists())
        preserved.forEach { assertEquals(it.name, it.readText()) }
        assertEquals("unrelated I/O failure", File(unreadable, "child").readText())
    }

    @Test fun discardCorruptRepairsOnlyTheDeclaredKeyAndPreservesItsQuarantine() {
        val directory = temp.newFolder("discard")
        val corrupt = File(directory, "$KEY.json").apply { writeText("{not-json") }
        val unrelated = File(directory, "$OTHER_KEY.json").apply { writeText("{unrelated-corruption") }
        val store = SaveSyncStateStore(directory)

        assertTrue(store.discardCorrupt(KEY))

        assertFalse(corrupt.exists())
        assertEquals("{not-json", File(directory, "$KEY.json.corrupt").readText())
        assertEquals("{unrelated-corruption", unrelated.readText())
        assertFalse(File(directory, "$OTHER_KEY.json.corrupt").exists())
        assertFalse(store.discardCorrupt(KEY))
        store.remove(KEY)
        assertEquals("{not-json", File(directory, "$KEY.json.corrupt").readText())
    }

    @Test fun discardCorruptLeavesValidStateAndExistingRecoveryCopiesUntouched() {
        val directory = temp.newFolder("valid-discard")
        val store = SaveSyncStateStore(directory)
        store.save(KEY, JSONObject().put("token", "keep"))
        val quarantine = File(directory, "$KEY.json.corrupt").apply { writeText("prior recovery") }

        assertFalse(store.discardCorrupt(KEY))

        assertEquals("keep", store.load(KEY).getString("token"))
        assertEquals("prior recovery", quarantine.readText())
    }

    @Test fun discardCorruptFailsClosedOnEmptyAndNonemptyDirectoryRecords() {
        listOf(false, true).forEach { nonempty ->
            val directory = temp.newFolder("discard-io-$nonempty")
            val record = File(directory, "$KEY.json").apply { mkdir() }
            if (nonempty) File(record, "child").writeText("keep")
            val store = SaveSyncStateStore(directory)

            val failure = assertThrows(SaveSyncException::class.java) { store.discardCorrupt(KEY) }

            assertEquals(SaveSyncError.LOCAL_IO, failure.code)
            assertTrue(failure.cause is IOException)
            assertTrue(record.isDirectory)
            if (nonempty) assertEquals("keep", File(record, "child").readText())
            assertFalse(File(directory, "$KEY.json.corrupt").exists())
            assertThrows(SaveSyncException::class.java) { store.remove(KEY) }
            assertTrue(record.isDirectory)
        }
    }

    @Test fun schemaValidationQuarantinesAndRepairsOnlyTheKnownKey() {
        val directory = temp.newFolder("schema")
        val record = File(directory, "$KEY.json").apply { writeText("{\"token\":7}") }
        val store = SaveSyncStateStore(directory)
        val validate: (JSONObject) -> Unit = {
            if (it.opt("token") !is String) throw JSONException("Invalid token")
        }

        val failure = assertThrows(SaveSyncException::class.java) { store.load(KEY, validate) }

        assertTrue(failure.cause is JSONException)
        assertEquals("{\"token\":7}", record.readText())
        assertTrue(store.discardCorrupt(KEY, validate))
        assertFalse(record.exists())
        assertEquals("{\"token\":7}", File(directory, "$KEY.json.corrupt").readText())
    }

    @Test fun explicitRepairStillReportsCorruptionForReconstructibleStores() {
        val directory = temp.newFolder("cache-repair")
        File(directory, "$KEY.json").writeText("{broken-cache")
        val store = SaveSyncStateStore(directory, recoverCorrupt = true)

        assertTrue(store.discardCorrupt(KEY))

        assertEquals("{broken-cache", File(directory, "$KEY.json.corrupt").readText())
        assertEquals(0, store.load(KEY).length())
    }

    @Test fun inspectionLeavesUnrelatedCorruptBytesAndRecoveryFilesUntouched() {
        val directory = temp.newFolder("inspection")
        val record = File(directory, "$KEY.json").apply { writeText("{broken") }
        val store = SaveSyncStateStore(directory, recoverCorrupt = true)

        val failure = assertThrows(SaveSyncException::class.java) { store.inspect(KEY) }

        assertTrue(failure.cause is JSONException)
        assertEquals("{broken", record.readText())
        assertFalse(File(directory, "$KEY.json.corrupt").exists())
        assertEquals(listOf(KEY), store.keys())
    }

    @Test fun knownOwnedDirectoryCleanupRemainsRetryableAndPreservesRecoveryCopies() {
        val directory = temp.newFolder("owned-directory-retry")
        val record = File(directory, "$KEY.json").apply { mkdir() }
        val obstruction = File(record, "child").apply { writeText("must survive failure") }
        val quarantine = File(directory, "$KEY.json.corrupt").apply { writeText("recovery") }
        val store = SaveSyncStateStore(directory)

        val failure = assertThrows(SaveSyncException::class.java) { store.removeOwned(KEY) }

        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        assertEquals("must survive failure", obstruction.readText())
        assertEquals("recovery", quarantine.readText())
        assertTrue(obstruction.delete())
        store.removeOwned(KEY)
        assertFalse(record.exists())
        assertEquals("recovery", quarantine.readText())
    }

    @Test fun knownOwnedCorruptFileIsQuarantinedBeforeRemoval() {
        val directory = temp.newFolder("owned-corrupt")
        val record = File(directory, "$KEY.json").apply { writeText("{owned-broken") }
        val unrelated = File(directory, "$OTHER_KEY.json").apply { writeText("{unrelated-broken") }

        SaveSyncStateStore(directory).removeOwned(KEY)

        assertFalse(record.exists())
        assertEquals("{owned-broken", File(directory, "$KEY.json.corrupt").readText())
        assertEquals("{unrelated-broken", unrelated.readText())
        assertFalse(File(directory, "$OTHER_KEY.json.corrupt").exists())
    }

    companion object {
        private val KEY = "a".repeat(64)
        private val OTHER_KEY = "b".repeat(64)
    }
}
