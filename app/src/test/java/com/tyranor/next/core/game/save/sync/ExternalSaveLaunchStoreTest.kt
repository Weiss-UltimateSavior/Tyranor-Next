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
    fun preparedEvidenceSurvivesOwnerChangeUntilExplicitConfirmation() {
        val root = temp.newFolder("root")
        val oldUri = "content://games/old"
        val preparedUri = "content://games/prepared"
        val current = ExternalSaveLaunchStore(root, "current-owner")
        ExternalSaveLaunchStore(root, "old-owner").prepare(oldUri, "old-token", "old-directory")
        current.prepare(preparedUri, "prepared-token", "prepared-directory")

        assertTrue(current.hasActive(setOf(oldUri), "old-directory"))
        assertTrue(current.hasActive(setOf(preparedUri), "unrelated-directory"))
        current.confirmExit(current.confirmation(oldUri), liveTokens = setOf("prepared-token")) {}
        assertFalse(current.hasActive(setOf(oldUri), "old-directory"))
        assertTrue(current.hasActive(setOf(preparedUri), "prepared-directory"))
    }

    @Test
    fun confirmationIsScopedAndCannotRetireDispatchAfterDialogOpened() {
        val root = temp.newFolder("root")
        val dueUri = "content://games/due"
        val futureUri = "content://games/future"
        val store = ExternalSaveLaunchStore(root, "owner")
        store.dispatched(dueUri, "due-token", "due-directory", sequence = 5L)
        store.dispatched(futureUri, "future-token", "future-directory", sequence = 6L)

        val snapshot = store.confirmation(dueUri)
        store.dispatched(dueUri, "new-token", "due-directory", sequence = 7L)
        val failure = assertThrows(SaveSyncException::class.java) {
            store.confirmExit(snapshot, liveTokens = emptySet()) { throw AssertionError("stale confirmation committed") }
        }
        assertEquals(SaveSyncError.LOCAL_STATE_CHANGED, failure.code)
        assertTrue(store.hasActive(setOf(dueUri), "due-directory"))
        assertTrue(store.hasActive(setOf(futureUri), "future-directory"))
        ExternalSaveLaunchStore(root, "restarted-owner").confirmExit(store.confirmation(dueUri), emptySet()) {}
        assertFalse(store.hasActive(setOf(dueUri), "due-directory"))
        assertTrue(store.hasActive(setOf(futureUri), "future-directory"))
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
    fun malformedRecordIsIsolatedToItsKey() {
        val root = temp.newFolder("root")
        val uri = "content://games/corrupt"
        val store = ExternalSaveLaunchStore(root, "owner")
        val marker = markerFile(root, uri)
        check(marker.parentFile.mkdirs())
        marker.writeText(JSONObject().put("uri", uri).toString())

        val activeFailure = assertThrows(SaveSyncException::class.java) {
            store.hasActive(setOf(uri), "known-directory")
        }
        assertFalse(store.hasActive(setOf("content://games/unrelated"), "known-directory"))

        assertEquals(SaveSyncError.LOCAL_IO, activeFailure.code)
        assertTrue(marker.isFile)

        store.confirmExit(store.confirmation(uri), liveTokens = emptySet()) {}
        assertFalse(store.hasActive(setOf(uri), "known-directory"))
    }

    @Test
    fun livePreparedTokenCannotBeConfirmedBeforeDispatchFinishes() {
        val root = temp.newFolder("root")
        val uri = "content://games/prepared-stale"
        val store = ExternalSaveLaunchStore(root, "owner")
        store.prepare(uri, "stale-token", "directory")

        val snapshot = store.confirmation(uri)
        val failure = assertThrows(SaveSyncException::class.java) {
            store.confirmExit(snapshot, liveTokens = setOf("stale-token")) { throw AssertionError("live prepare cleared") }
        }
        assertEquals(SaveSyncError.GAME_RUNNING, failure.code)
        assertTrue(store.hasActive(setOf(uri), "directory"))
        store.confirmExit(store.confirmation(uri), liveTokens = emptySet()) {}
        assertFalse(store.hasActive(setOf(uri), "directory"))
        assertFalse(markerFile(root, uri).exists())
    }

    @Test
    fun confirmingHealthyRunsDoesNotAccumulateRetiredMarkersOrReceiptLinks() {
        val root = temp.newFolder("repeated-confirmation")
        val uri = "content://games/repeated"
        val store = ExternalSaveLaunchStore(root, "owner")
        repeat(8) { sequence ->
            store.dispatched(uri, "token-$sequence", "directory", sequence.toLong() + 1)
            store.confirmExit(store.confirmation(uri), emptySet()) {}
            assertFalse(store.hasActive(setOf(uri), "directory"))
        }
        assertTrue(markerFile(root, uri).parentFile.listFiles().orEmpty().isEmpty())
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

    @Test
    fun corruptedNonemptyDirectoryIsQuarantinedOnlyForConfirmedUri() {
        val root = temp.newFolder("recovery")
        val uri = "content://games/corrupt-directory"
        val other = "content://games/other-corrupt-directory"
        val marker = markerFile(root, uri).apply { check(mkdirs()) }
        val otherMarker = markerFile(root, other).apply { check(mkdirs()) }
        File(marker, "recover-me.dat").writeText("original-data")
        File(otherMarker, "other.dat").writeText("other-data")
        val store = ExternalSaveLaunchStore(root, "owner")
        val snapshot = store.confirmation(uri)
        assertTrue(marker.isDirectory)
        assertLocalIo { store.hasActive(setOf(uri), "directory") }
        store.confirmExit(snapshot, emptySet()) {}
        assertFalse(store.hasActive(setOf(uri), "directory"))
        assertLocalIo { store.hasActive(setOf(other), "directory") }
        val quarantine = marker.parentFile.listFiles()!!.single { it.name.startsWith("${marker.name}.confirmed-") }
        assertEquals("original-data", File(quarantine, "recover-me.dat").readText())
        assertEquals("other-data", File(otherMarker, "other.dat").readText())
    }

    @Test
    fun identicalCorruptReplacementCannotReuseOldDialogSnapshot() {
        val root = temp.newFolder("replacement")
        val uri = "content://games/corrupt-replaced"
        val marker = markerFile(root, uri)
        check(marker.parentFile.mkdirs())
        marker.writeText("{broken")
        val store = ExternalSaveLaunchStore(root, "owner")
        val snapshot = store.confirmation(uri)
        val originalAttributes = Files.readAttributes(marker.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        val replacement = File(marker.parentFile, "replacement.tmp")
        Files.copy(marker.toPath(), replacement.toPath(), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
        Files.getFileAttributeView(replacement.toPath(), java.nio.file.attribute.BasicFileAttributeView::class.java)
            .setTimes(originalAttributes.lastModifiedTime(), originalAttributes.lastAccessTime(), originalAttributes.creationTime())
        Files.move(replacement.toPath(), marker.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val failure = assertThrows(SaveSyncException::class.java) {
            store.confirmExit(snapshot, emptySet()) { throw AssertionError("replacement accepted") }
        }
        assertEquals(SaveSyncError.LOCAL_STATE_CHANGED, failure.code)
        assertEquals("{broken", marker.readText())
    }

    @Test
    fun copiedCorruptDirectoryCannotReuseSnapshotEvenWithReceiptAndPreservedTimes() {
        val root = temp.newFolder("copied-directory")
        val uri = "content://games/directory-replaced"
        val marker = markerFile(root, uri).apply { check(mkdirs()) }
        File(marker, "save.dat").writeText("precious")
        val store = ExternalSaveLaunchStore(root, "owner")
        val snapshot = store.confirmation(uri)
        val attributes = Files.readAttributes(marker.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        val retained = File(marker.parentFile, "retained-original")
        Files.move(marker.toPath(), retained.toPath())
        check(marker.mkdir())
        for (child in retained.listFiles()!!) {
            Files.copy(child.toPath(), File(marker, child.name).toPath(), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
        }
        Files.getFileAttributeView(marker.toPath(), java.nio.file.attribute.BasicFileAttributeView::class.java)
            .setTimes(attributes.lastModifiedTime(), attributes.lastAccessTime(), attributes.creationTime())
        val failure = assertThrows(SaveSyncException::class.java) {
            store.confirmExit(snapshot, emptySet()) { throw AssertionError("copied directory accepted") }
        }
        assertEquals(SaveSyncError.LOCAL_STATE_CHANGED, failure.code)
        assertEquals("precious", File(marker, "save.dat").readText())
        assertEquals("precious", File(retained, "save.dat").readText())
    }

    @Test
    fun symlinkRecoveryRenamesLinkAndLeavesTargetUntouched() {
        val root = temp.newFolder("link-recovery")
        val uri = "content://games/link-recovery"
        val marker = markerFile(root, uri)
        check(marker.parentFile.mkdirs())
        val outside = temp.newFile("outside-data").apply { writeText("precious") }
        createSymbolicLink(marker, outside)
        val store = ExternalSaveLaunchStore(root, "owner")
        store.confirmExit(store.confirmation(uri), emptySet()) {}
        assertEquals("precious", outside.readText())
        assertFalse(Files.exists(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(marker.parentFile.listFiles()!!.any { Files.isSymbolicLink(it.toPath()) })
    }

    @Test
    fun missingMarkerSnapshotCannotClearNewEvidence() {
        val root = temp.newFolder("initially-missing")
        val uri = "content://games/new-dispatch"
        val store = ExternalSaveLaunchStore(root, "owner")
        val snapshot = store.confirmation(uri)
        assertFalse(snapshot.hasEvidence)
        store.dispatched(uri, "new-token", "directory", 1L)
        val failure = assertThrows(SaveSyncException::class.java) { store.confirmExit(snapshot, emptySet()) {} }
        assertEquals(SaveSyncError.LOCAL_STATE_CHANGED, failure.code)
        assertTrue(store.hasActive(setOf(uri), "directory"))
    }

    @Test
    fun failureWhileCommittingSessionRetainsMarkerForRestart() {
        val root = temp.newFolder("failed-commit")
        val uri = "content://games/commit-failure"
        val store = ExternalSaveLaunchStore(root, "owner")
        store.dispatched(uri, "token", "directory", 1L)
        assertLocalIo { store.confirmExit(store.confirmation(uri), emptySet()) { throw IOException("disk failure") } }
        assertTrue(ExternalSaveLaunchStore(root, "restarted").hasActive(setOf(uri), "directory"))
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
