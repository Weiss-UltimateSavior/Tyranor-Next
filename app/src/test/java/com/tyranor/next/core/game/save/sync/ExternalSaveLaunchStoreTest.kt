package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assume.assumeNoException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class ExternalSaveLaunchStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun markersPersistAndReloadWithoutTouchingVersions() {
        val root = temp.newFolder("root")
        val versions = File(root, "save_sync/versions/keep.bin").apply {
            check(parentFile.mkdirs())
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val uri = "content://games/example"
        val activeKey = "content://saves/example"
        val store = ExternalSaveLaunchStore(root, "owner-a")

        store.prepare(uri, "dispatch-token", activeKey)
        store.dispatched(uri, "dispatch-token", activeKey, sequence = 17L)

        val marker = markerFile(root, uri)
        assertTrue(marker.isFile)
        val json = JSONObject(marker.readText())
        assertEquals(uri, json.getString("uri"))
        val entry = json.getJSONArray("entries").getJSONObject(0)
        assertEquals("dispatch-token", entry.getString("token"))
        assertEquals(activeKey, entry.getString("activeKey"))
        assertEquals("owner-a", entry.getString("owner"))
        assertEquals(17L, entry.getLong("dispatchSequence"))

        val reloaded = ExternalSaveLaunchStore(root, "owner-b")
        assertTrue(reloaded.hasActive(setOf(uri), "different-save-directory"))
        assertTrue(reloaded.hasActive(setOf("content://unrelated-game"), activeKey))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), versions.readBytes())
    }

    @Test
    fun failedTokenOnlyRemovesItsPreparedSibling() {
        val root = temp.newFolder("root")
        val uri = "content://games/shared"
        val store = ExternalSaveLaunchStore(root, "owner")
        store.prepare(uri, "first-token", "first-directory")
        store.prepare(uri, "second-token", "second-directory")

        store.failed(uri, "second-token")

        assertTrue(store.hasActive(setOf(uri), "unrelated-directory"))
        val entries = JSONObject(markerFile(root, uri).readText()).getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals("first-token", entries.getJSONObject(0).getString("token"))

        store.failed(uri, "first-token")
        assertFalse(store.hasActive(setOf(uri), "unrelated-directory"))
        assertFalse(markerFile(root, uri).exists())
    }

    @Test
    fun foregroundRetiresOldOwnerButPreservesCurrentPreparedMarker() {
        val root = temp.newFolder("root")
        val oldUri = "content://games/old"
        val preparedUri = "content://games/prepared"
        val current = ExternalSaveLaunchStore(root, "current-owner")
        ExternalSaveLaunchStore(root, "old-owner").prepare(oldUri, "old-token", "old-directory")
        current.prepare(preparedUri, "prepared-token", "prepared-directory")

        current.foreground(sequenceCutoff = 0L, returned = false)

        assertFalse(current.hasActive(setOf(oldUri), "old-directory"))
        assertTrue(current.hasActive(setOf(preparedUri), "unrelated-directory"))
    }

    @Test
    fun returnedForegroundUsesCapturedSequenceAndHonorsLaterCalls() {
        val root = temp.newFolder("root")
        val dueUri = "content://games/due"
        val futureUri = "content://games/future"
        val store = ExternalSaveLaunchStore(root, "owner")
        store.dispatched(dueUri, "due-token", "due-directory", sequence = 5L)
        store.dispatched(futureUri, "future-token", "future-directory", sequence = 6L)

        store.foreground(sequenceCutoff = 5L, returned = true)

        assertFalse(store.hasActive(setOf(dueUri), "due-directory"))
        assertTrue(store.hasActive(setOf(futureUri), "future-directory"))

        store.foreground(sequenceCutoff = 6L, returned = true)
        assertFalse(store.hasActive(setOf(futureUri), "future-directory"))
    }

    @Test
    fun unknownActiveKeysRemainConservative() {
        val nullRoot = temp.newFolder("null-root")
        val nullStore = ExternalSaveLaunchStore(nullRoot, "owner")
        nullStore.prepare("content://games/unknown", "null-token", activeKey = null)
        assertTrue(nullStore.hasActive(setOf("content://games/unrelated"), "known-directory"))

        val blankRoot = temp.newFolder("blank-root")
        val blankStore = ExternalSaveLaunchStore(blankRoot, "owner")
        blankStore.prepare("content://games/blank", "blank-token", activeKey = "")
        assertTrue(blankStore.hasActive(emptySet(), "known-directory"))

        val queryRoot = temp.newFolder("query-root")
        val queryStore = ExternalSaveLaunchStore(queryRoot, "owner")
        queryStore.prepare("content://games/query", "query-token", "known-directory")
        assertTrue(queryStore.hasActive(emptySet(), ""))
    }

    @Test
    fun malformedRecordFailsStrictlyAndRemainsOnDisk() {
        val root = temp.newFolder("root")
        val uri = "content://games/corrupt"
        val store = ExternalSaveLaunchStore(root, "owner")
        val marker = markerFile(root, uri)
        check(marker.parentFile.mkdirs())
        marker.writeText(JSONObject().put("uri", uri).toString())

        val activeFailure = assertThrows(SaveSyncException::class.java) {
            store.hasActive(setOf(uri), "known-directory")
        }
        val foregroundFailure = assertThrows(SaveSyncException::class.java) {
            store.foreground(sequenceCutoff = Long.MAX_VALUE, returned = true)
        }

        assertEquals(SaveSyncError.LOCAL_IO, activeFailure.code)
        assertEquals(SaveSyncError.LOCAL_IO, foregroundFailure.code)
        assertTrue(marker.isFile)
    }

    @Test
    fun existingEmptyEntryArrayIsMalformedInsteadOfIdle() {
        val root = temp.newFolder("root")
        val uri = "content://games/empty"
        val marker = markerFile(root, uri)
        check(marker.parentFile.mkdirs())
        marker.writeText(
            JSONObject()
                .put("uri", uri)
                .put("entries", JSONArray())
                .toString(),
        )

        assertLocalIo {
            ExternalSaveLaunchStore(root, "owner").hasActive(setOf(uri), "known-directory")
        }
        assertTrue(marker.isFile)
    }

    @Test
    fun cancellationFromPathValidationIsRethrownUnchanged() {
        val cancellation = CancellationException("cancelled while validating marker root")
        val root = object : File(temp.root, "cancelling-root") {
            override fun getAbsoluteFile(): File = throw cancellation
        }

        val observed = assertThrows(CancellationException::class.java) {
            ExternalSaveLaunchStore(root, "owner").hasActive(emptySet(), "known-directory")
        }

        assertSame(cancellation, observed)
    }

    @Test
    fun symbolicExternalLaunchDirectoryIsNotTraversed() {
        val root = temp.newFolder("root").canonicalFile
        val saveSync = File(root, "save_sync").apply { check(mkdirs()) }
        val outside = temp.newFolder("outside").canonicalFile
        val uri = "content://games/linked-directory"
        val externalRecord = File(outside, "${sha256(uri)}.json")
        writeRecord(externalRecord, uri)
        createSymbolicLink(File(saveSync, "external_launches"), outside)

        assertLocalIo {
            ExternalSaveLaunchStore(root, "owner").hasActive(setOf(uri), "directory")
        }
        assertTrue(externalRecord.isFile)
    }

    @Test
    fun symbolicMarkerIsRejectedBeforeEnumerationAndDirectLoad() {
        val root = temp.newFolder("root")
        val directory = File(root, "save_sync/external_launches").apply { check(mkdirs()) }
        val outside = temp.newFolder("outside").canonicalFile
        val uri = "content://games/linked-marker"
        val externalRecord = File(outside, "synthetic.json")
        writeRecord(externalRecord, uri)
        val marker = markerFile(root, uri)
        createSymbolicLink(marker, externalRecord)
        val store = ExternalSaveLaunchStore(root, "owner")

        assertLocalIo { store.hasActive(setOf(uri), "directory") }
        assertLocalIo { store.prepare(uri, "prepared-token", "directory") }
        assertTrue(directory.isDirectory)
        assertTrue(externalRecord.isFile)
    }

    @Test
    fun nonregularMarkerIsRejected() {
        val root = temp.newFolder("root")
        val uri = "content://games/nonregular"
        val marker = markerFile(root, uri)
        check(marker.parentFile.mkdirs())
        check(marker.mkdirs())
        val store = ExternalSaveLaunchStore(root, "owner")

        assertLocalIo { store.hasActive(setOf(uri), "directory") }
        assertLocalIo { store.prepare(uri, "prepared-token", "directory") }
        assertTrue(marker.isDirectory)
    }

    private fun writeRecord(file: File, uri: String) {
        file.parentFile.mkdirs()
        file.writeText(
            JSONObject()
                .put("uri", uri)
                .put(
                    "entries",
                    JSONArray().put(
                        JSONObject()
                            .put("token", "external-token")
                            .put("activeKey", "directory")
                            .put("owner", "external-owner")
                            .put("dispatchSequence", 1L),
                    ),
                )
                .toString(),
        )
    }

    private fun assertLocalIo(block: () -> Unit) {
        val failure = assertThrows(SaveSyncException::class.java, block)
        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
    }

    private fun createSymbolicLink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (failure: UnsupportedOperationException) {
            assumeNoException(failure)
        } catch (failure: SecurityException) {
            assumeNoException(failure)
        } catch (failure: IOException) {
            assumeNoException(failure)
        }
    }

    private fun markerFile(root: File, uri: String): File =
        File(root, "save_sync/external_launches/${sha256(uri)}.json")

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) append("%02x".format(byte.toInt() and 0xff))
        }
    }
}
