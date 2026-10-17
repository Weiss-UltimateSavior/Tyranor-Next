package com.tyranor.next.core.game.save

import com.tyranor.next.core.game.model.EthornellPaths
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EthornellSaveStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = "content://provider/tree/游戏😀"

    private fun store(files: File = temporary.newFolder()): EthornellSaveStore = EthornellSaveStore(files, identity)

    private fun write(root: File, name: String, value: String): File = File(root, name).apply {
        parentFile!!.mkdirs()
        writeText(value)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, value) ->
                out.putNextEntry(ZipEntry(name))
                out.write(value.toByteArray())
                out.closeEntry()
            }
        }
    }.toByteArray()

    private fun import(store: EthornellSaveStore, vararg entries: Pair<String, String>): Int =
        store.import { ByteArrayInputStream(zip(*entries)) }

    private fun expect(code: SaveErrorCode, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: GameSaveException) { assertEquals(code, error.code) }
    }

    @Test fun gateRemainsDisabledUntilDeviceFixtureAcceptance() {
        assertFalse(EthornellSaveCapability.DEVICE_SAVE_IO_VERIFIED)
    }

    @Test fun exactRootUsesFullIdentityHashAndNeverResourceUserData() {
        val files = temporary.newFolder()
        val store = store(files)
        val resource = temporary.newFolder()
        val original = write(resource, "UserData/save.bin", "original")
        assertEquals(EthornellPaths.userDataDir(files, identity), store.root)
        assertEquals(64, store.root.name.length)
        assertEquals(File(files.canonicalFile, "ethornell/userdata"), store.root.parentFile)
        assertTrue(store.listFiles().isEmpty())
        assertEquals(0, store.delete())
        assertEquals("original", original.readText())
        expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { store.validateRoot(resource) }
        expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            store.validateRoot(EthornellPaths.userDataDir(files, "$identity/other"))
        }
    }

    @Test fun absentEmptyAndOnlyInternalHaveNoExportableFilesWithoutOpeningOutput() {
        val store = store()
        var opened = false
        fun check() {
            expect(SaveErrorCode.NO_EXPORTABLE_FILES) {
                store.export { opened = true; ByteArrayOutputStream() }
            }
            assertFalse(opened)
        }
        check()
        store.root.mkdirs()
        check()
        val lock = write(store.root, ".ethornell-session.lock", "lock")
        check()
        assertEquals(0, store.delete())
        assertEquals("lock", lock.readText())
    }

    @Test fun exportAndDeleteExcludeReservedAtEveryDepthAndLeaveOtherIdentityAndResources() {
        val files = temporary.newFolder()
        val store = store(files)
        val another = EthornellSaveStore(files, "other")
        val otherFile = write(another.root, "progress", "other")
        val resource = write(temporary.newFolder(), "data.arc", "resource")
        write(store.root, "UserData/slot.dat", "slot")
        write(store.root, "global", "global")
        val lock = write(store.root, ".ethornell-session.lock", "lock")
        val nested = write(store.root, "nested/.ETHORNELL-internal/secret", "internal")
        val bytes = ByteArrayOutputStream()
        assertEquals(2, store.export { bytes })
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
            while (true) { names += (input.nextEntry ?: break).name }
        }
        assertEquals(setOf("UserData/slot.dat", "global"), names.toSet())
        assertEquals(2, store.delete())
        assertEquals("lock", lock.readText())
        assertEquals("internal", nested.readText())
        assertEquals("other", otherFile.readText())
        assertEquals("resource", resource.readText())
    }

    @Test fun importReplacesOnlyUserTreeAndPreservesInternalInodesAndRelativeStructure() {
        val store = store()
        val old = write(store.root, "old.dat", "old")
        val lock = write(store.root, ".ethornell-session.lock", "lock")
        val key = Files.readAttributes(lock.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        write(store.root, "nested/.ethornell-private", "nested internal")
        assertEquals(2, import(store, "UserData/new.dat" to "new", "arbitrary.valid" to "data"))
        assertFalse(old.exists())
        assertEquals("new", File(store.root, "UserData/new.dat").readText())
        assertEquals("lock", lock.readText())
        assertEquals(key, Files.readAttributes(lock.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertEquals("nested internal", File(store.root, "nested/.ethornell-private").readText())
    }

    @Test fun zipAttacksNeverChangeOldSaveOrInternalFiles() {
        val store = store()
        val old = write(store.root, "slot", "old")
        val lock = write(store.root, ".ethornell-session.lock", "lock")
        val names = listOf("../escape", "/absolute", "C:/drive", "a\\b", "a/../escape", "a/./alias", "a//alias",
            "a/..", ".ethornell-session.lock", "nested/.ETHORNELL-data", "a/\u0000nul")
        names.forEach { name ->
            expect(SaveErrorCode.ILLEGAL_ZIP_PATH) { import(store, name to "bad") }
            assertEquals("old", old.readText())
            assertEquals("lock", lock.readText())
        }
    }

    @Test fun emptyAndBudgetExceededArchivesDoNotReplaceOldData() {
        val files = temporary.newFolder()
        val store = EthornellSaveStore(files, identity, maxFiles = 2, maxBytes = 4)
        val old = write(store.root, "old", "old")
        expect(SaveErrorCode.NO_FILES_IN_ZIP) { import(store) }
        expect(SaveErrorCode.ZIP_TOO_LARGE) { import(store, "new" to "12345") }
        expect(SaveErrorCode.TOO_MANY_ZIP_FILES) { import(store, "a" to "", "b" to "", "c" to "") }
        assertEquals("old", old.readText())
    }

    @Test fun duplicateZipEntriesFailBeforeCommit() {
        val store = store()
        val old = write(store.root, "old", "old")
        val bytes = zip("slotA" to "first", "slotB" to "second")
        // ZipOutputStream forbids duplicate names; alter same-length local and
        // central names to create the hostile input (payload CRC is unchanged).
        val needle = "slotB".toByteArray()
        for (i in 0..bytes.size - needle.size) {
            if (needle.indices.all { bytes[i + it] == needle[it] }) bytes[i + needle.lastIndex] = 'A'.code.toByte()
        }
        expect(SaveErrorCode.DUPLICATE_ZIP_ENTRY) { store.import { ByteArrayInputStream(bytes) } }
        assertEquals("old", old.readText())
    }

    @Test fun failedSecondRenameRollsBackOldTreeWithoutChangingResources() {
        val files = temporary.newFolder()
        val store = EthornellSaveStore(files, identity, rename = { from, to ->
            if (from.name.endsWith("-import-staging")) false else from.renameTo(to)
        })
        val old = write(store.root, "old", "old")
        val lock = write(store.root, ".ethornell-session.lock", "lock")
        val resource = write(temporary.newFolder(), "UserData/original", "resource")
        expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { import(store, "new" to "new") }
        assertEquals("old", old.readText())
        assertEquals("lock", lock.readText())
        assertEquals("resource", resource.readText())
        assertFalse(File(store.root, "new").exists())
    }

    @Test fun failedRollbackRetainsBackupAndNextLaunchRecoversIt() {
        val files = temporary.newFolder()
        val store = EthornellSaveStore(files, identity, rename = { from, to ->
            if (from.name.endsWith("-import-staging") || from.name.endsWith("-import-backup")) false
            else from.renameTo(to)
        })
        write(store.root, "old", "old")
        val backup = File(store.root.parentFile, ".ethornell-${store.root.name}-import-backup")
        expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { import(store, "new" to "new") }
        assertEquals("old", File(backup, "old").readText())
        assertFalse(store.root.exists())
        val healthy = store(files)
        healthy.recoverForLaunch()
        assertEquals("old", File(store.root, "old").readText())
    }

    @Test fun directoryFileAliasesAndReservedAncestorConflictsAreRejectedBeforeCommit() {
        val store = store()
        val old = write(store.root, "old", "old")
        val internal = write(store.root, "nested/.ethornell-private", "keep")
        expect(SaveErrorCode.ILLEGAL_ZIP_PATH) { import(store, "a/b" to "a", "a" to "b") }
        expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { import(store, "nested" to "collision") }
        assertEquals("old", old.readText())
        assertEquals("keep", internal.readText())
    }

    @Test fun rootAndParentAndFileSymlinksFailClosedForEveryOperation() {
        for (kind in listOf("root", "parent", "child", "reserved", "staging", "backup")) {
            val files = temporary.newFolder()
            val store = store(files)
            val outside = temporary.newFolder()
            val untouched = write(outside, "untouched", "outside")
            when (kind) {
                "root" -> { store.root.parentFile!!.mkdirs(); Files.createSymbolicLink(store.root.toPath(), outside.toPath()) }
                "parent" -> { File(files, "ethornell").mkdirs(); Files.createSymbolicLink(store.root.parentFile!!.toPath(), outside.toPath()) }
                "child", "reserved" -> {
                    store.root.mkdirs()
                    Files.createSymbolicLink(File(store.root, if (kind == "child") "link" else ".ethornell-link").toPath(), outside.toPath())
                }
                else -> {
                    store.root.parentFile!!.mkdirs()
                    val suffix = if (kind == "staging") "import-staging" else "import-backup"
                    Files.createSymbolicLink(File(store.root.parentFile, ".ethornell-${store.root.name}-$suffix").toPath(), outside.toPath())
                }
            }
            expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { store.listFiles() }
            expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { store.export { ByteArrayOutputStream() } }
            expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { import(store, "new" to "data") }
            expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { store.delete() }
            assertEquals("outside", untouched.readText())
        }
    }

    @Test fun interruptedRenameIsRecoveredBeforeLaunchOrAnotherImport() {
        val store = store()
        write(store.root, "old", "old")
        write(store.root, ".ethornell-session.lock", "lock")
        val backup = File(store.root.parentFile, ".ethornell-${store.root.name}-import-backup")
        assertTrue(store.root.renameTo(backup))
        write(File(store.root.parentFile, ".ethornell-${store.root.name}-import-staging"), "new", "partial")
        store.recoverForLaunch()
        assertEquals("old", File(store.root, "old").readText())
        assertEquals("lock", File(store.root, ".ethornell-session.lock").readText())
        assertFalse(backup.exists())
    }

    @Test fun postCommitRecoveryRestoresReservedMetadataBeforeDiscardingBackup() {
        val store = store()
        write(store.root, "old", "old")
        write(store.root, ".ethornell-session.lock", "lock")
        val backup = File(store.root.parentFile, ".ethornell-${store.root.name}-import-backup")
        assertTrue(store.root.renameTo(backup))
        write(store.root, "new", "new")
        store.recoverForLaunch()
        assertEquals("lock", File(store.root, ".ethornell-session.lock").readText())
        assertEquals("new", File(store.root, "new").readText())
        assertFalse(File(store.root, "old").exists())
        assertFalse(backup.exists())
    }

    @Test fun cancellationBeforeCommitKeepsOldTreeAndPropagatesUnchanged() {
        val files = temporary.newFolder()
        var calls = 0
        val cancelled = CancellationException("test cancellation")
        val store = EthornellSaveStore(files, identity, checkCancelled = { if (++calls >= 4) throw cancelled })
        val old = write(store.root, "old", "old")
        try { import(store, "new" to "new"); fail("Expected cancellation") }
        catch (error: CancellationException) { assertSame(cancelled, error) }
        assertEquals("old", old.readText())
    }

    @Test fun implicitParentsAreCountedBeforeCreationAndFailureLeavesRecoverableTree() {
        val files = temporary.newFolder()
        // Small injected bound exercises the production 100k bound without
        // generating 100k physical nodes on the developer's filesystem.
        val store = EthornellSaveStore(files, identity, maxNodes = 65)
        val old = write(store.root, "old", "old")
        fun deep(branch: String) = (listOf(branch) + List(62) { "d" } + "save").joinToString("/")
        expect(SaveErrorCode.TOO_MANY_ZIP_FILES) {
            import(store, deep("a") to "a", deep("b") to "b")
        }
        val staging = File(store.root.parentFile, ".ethornell-${store.root.name}-import-staging")
        assertFalse(staging.exists())
        assertEquals("old", old.readText())
        // Previous failure cannot poison prepare(), a subsequent launch, or import.
        store.recoverForLaunch()
        assertEquals(1, import(store, "new" to "new"))
        assertEquals("new", File(store.root, "new").readText())
    }

    @Test fun productionNodeBudgetRejects1600DisjointDeepPathsWithoutReservingRejectedEntry() {
        val nodes = EthornellExtractionNodes(100_000)
        repeat(1562) { branch ->
            nodes.reserve((listOf("b$branch") + List(62) { "d" } + "save").joinToString("/"))
        }
        assertEquals(99_969, nodes.size)
        expect(SaveErrorCode.TOO_MANY_ZIP_FILES) {
            nodes.reserve((listOf("b1562") + List(62) { "d" } + "save").joinToString("/"))
        }
        assertEquals(99_969, nodes.size)
        nodes.reserve("small")
        assertEquals(99_970, nodes.size)
    }

    @Test fun reservedMetadataIsIncludedInFinalTreeBudgetBeforeCommit() {
        val files = temporary.newFolder()
        val store = EthornellSaveStore(files, identity, maxNodes = 5)
        val old = write(store.root, "old", "old")
        val internal = write(store.root, "nested/.ethornell-state", "keep")
        expect(SaveErrorCode.TOO_MANY_ZIP_FILES) { import(store, "a/b/c" to "new") }
        assertEquals("old", old.readText())
        assertEquals("keep", internal.readText())
        store.recoverForLaunch()
        assertEquals(1, import(store, "a/b" to "new"))
        store.validateRoot()
    }

    @Test fun cancellationDuringInternalReservationClosesAlreadyOpenedProviderStream() {
        val files = temporary.newFolder()
        var opened = false
        var closed = false
        var reads = 0
        val cancelled = CancellationException("internal reservation")
        val store = EthornellSaveStore(files, identity, checkCancelled = {
            if (opened) throw cancelled
        })
        val internal = write(store.root, ".ethornell-session.lock", "keep")
        val old = write(store.root, "old", "old")
        val input = object : ByteArrayInputStream(zip("new" to "new")) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return super.read(b, off, len)
            }
            override fun close() { closed = true; super.close() }
        }
        try {
            store.import { opened = true; input }
            fail("Expected reservation cancellation")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        assertTrue(opened)
        assertTrue(closed)
        assertEquals(0, reads)
        assertEquals("keep", internal.readText())
        assertEquals("old", old.readText())
        assertFalse(File(store.root.parentFile, ".ethornell-${store.root.name}-import-staging").exists())
        store(files).recoverForLaunch()
    }

    @Test fun platformZipValidationAndParserFailuresAreTypedCloseInputAndKeepOldTree() {
        val store = store()
        val old = write(store.root, "old", "old")
        val internal = write(store.root, ".ethornell-session.lock", "keep")
        for (failure in listOf(
            ZipException("Invalid zip entry path: ../escape"),
            ZipException("invalid entry CRC"),
            IllegalArgumentException("malformed ZIP name"),
        )) {
            var closed = false
            val input = object : InputStream() {
                override fun read(): Int = throw failure
                override fun close() { closed = true }
            }
            try {
                store.import { input }
                fail("Expected typed ZIP read failure")
            } catch (error: GameSaveException) {
                assertEquals(SaveErrorCode.READ_IMPORT_ZIP, error.code)
                assertNull(error.detail) // Never expose the platform error as localized UI text.
                assertSame(failure, error.cause)
            }
            assertTrue(closed)
            assertEquals("old", old.readText())
            assertEquals("keep", internal.readText())
            assertFalse(File(store.root.parentFile, ".ethornell-${store.root.name}-import-staging").exists())
            store.recoverForLaunch()
        }
        assertEquals(1, import(store, "new" to "new"))
    }

    @Test fun actualTruncatedZipIsTypedAndDoesNotCommitPartialExtractedSave() {
        val store = store()
        val old = write(store.root, "old", "old")
        val archive = zip("new" to "new data")
        // Local header (30 bytes) + name (3 bytes) + incomplete deflate payload.
        val truncated = archive.copyOf(35)
        var closed = false
        val input = object : ByteArrayInputStream(truncated) {
            override fun close() { closed = true; super.close() }
        }
        expect(SaveErrorCode.READ_IMPORT_ZIP) { store.import { input } }
        assertTrue(closed)
        assertEquals("old", old.readText())
        assertFalse(File(store.root.parentFile, ".ethornell-${store.root.name}-import-staging").exists())
        store.recoverForLaunch()
    }

    @Test fun directoryEntryPayloadIsRejectedImmediatelyWithoutDrainingIt() {
        val store = store()
        val old = write(store.root, "old", "old")
        val bytes = zip("dir/" to "x".repeat(1_000_000), "later" to "never")
        var consumed = 0
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int {
                // Limit compressed consumption below the full ZIP to detect
                // closeEntry/nextEntry accidentally draining the directory bomb.
                if (consumed >= 700) throw IOException("directory payload was drained")
                return if (consumed < bytes.size) bytes[consumed++].toInt() and 255 else -1
            }
            override fun close() { closed = true }
        }
        expect(SaveErrorCode.ILLEGAL_ZIP_PATH) { store.import { input } }
        assertTrue(closed)
        assertTrue(consumed < 700)
        assertEquals("old", old.readText())
        store.recoverForLaunch()
        assertEquals(1, import(store, "valid" to "valid"))
    }

    @Test fun validEmptyDirectoryEntryHasNoHiddenDrainAndCancellationCleansStaging() {
        val files = temporary.newFolder()
        val store = store(files)
        assertEquals(1, import(store, "empty/" to "", "save" to "ok"))
        val old = File(store.root, "save")
        var cancel = false
        val cancelled = CancellationException("directory read")
        val cancellable = EthornellSaveStore(files, identity, checkCancelled = { if (cancel) throw cancelled })
        val bytes = zip("other/" to "", "new" to "no")
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val read = super.read(b, off, len)
                cancel = true
                return read
            }
        }
        try { cancellable.import { input }; fail("Expected cancellation") }
        catch (error: CancellationException) { assertSame(cancelled, error) }
        assertEquals("ok", old.readText())
        assertFalse(File(store.root.parentFile, ".ethornell-${store.root.name}-import-staging").exists())
        store.recoverForLaunch()
    }
}
