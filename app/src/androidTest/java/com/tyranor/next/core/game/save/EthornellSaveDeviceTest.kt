package com.tyranor.next.core.game.save

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.core.ethornell.EthornellDispatchRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.launch.EthornellOperationGate
import com.tyranor.next.core.game.model.EthornellPaths
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Real Android private-files backend IO, not native VM slots, PC interoperability,
 * or cross-process host acceptance. Tiny, self-contained byte fixtures only.
 * The release capability remains disabled; direct backend construction is scoped
 * to an isolated private fixture, never used to bypass public API admission.
 */
@RunWith(AndroidJUnit4::class)
class EthornellSaveDeviceTest {
    @Test fun privateTreeZipRoundTripPreservesMetadataResourcesAndInternalInodes() = fixture { f ->
        val store = f.store()
        val expected = linkedMapOf(
            "UserData/slot-fixture.bin" to byteArrayOf(0, 1, 2, -1, 0, 42),
            "nested/日本語😀/state.bin" to byteArrayOf(9, 8, 7),
        )
        expected.forEach { (name, bytes) -> write(store.root, name, bytes) }
        val lock = write(store.root, ".ethornell-session.lock", byteArrayOf(3))
        val nested = write(store.root, "nested/.ETHORNELL-private/state", byteArrayOf(4))
        val internalBefore = listOf(lock, nested).associateWith(::metadata)
        val other = write(f.store("other identity").root, "untouched.bin", byteArrayOf(5))
        val otherBefore = metadata(other)
        val resourceBefore = snapshot(f.resources)
        assertEquals(EthornellPaths.userDataDir(f.files, f.identity), store.root)
        assertEquals(64, store.root.name.length)
        expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { store.validateRoot(f.resources) }

        // Keep the actual lock descriptor open across rename/import/delete. The
        // path must still address this inode, not a replacement with equal bytes.
        FileInputStream(lock).use { descriptor ->
            val held = Os.fstat(descriptor.fd)
            val archive = File(f.base, "roundtrip.zip")
            assertEquals(expected.size, store.export { FileOutputStream(archive) })
            val exported = readZip(archive)
            assertEquals(expected.keys, exported.keys)
            expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, exported.getValue(name)) }
            assertEquals(expected.size, store.delete())
            assertTrue(store.listFiles().isEmpty())
            assertEquals(expected.size, store.import { FileInputStream(archive) })
            assertEquals(expected.keys, store.listFiles().map { it.relativeTo(store.root).invariantSeparatorsPath }.toSet())
            expected.forEach { (name, bytes) ->
                val file = File(store.root, name)
                assertArrayEquals(bytes, file.readBytes())
                assertEquals("ZIP timestamp survives actual filesystem IO", FIXTURE_TIME, file.lastModified())
                assertTrue(file.canRead())
            }
            assertEquals(held.st_dev, Os.stat(lock.path).st_dev)
            assertEquals(held.st_ino, Os.stat(lock.path).st_ino)
            internalBefore.forEach { (file, before) -> assertEquals(before, metadata(file)) }
            // Import above executes the production directory-force path. Also
            // exercise Android's explicit fsync and the same Java API on disk;
            // successful return is IO compatibility, NOT power-loss proof.
            listOf(store.root, store.root.parentFile!!, File(store.root, "UserData")).forEach(::syncDirectory)
            assertNoTransactionResidue(store)
            assertEquals(expected.size, store.delete())
            internalBefore.forEach { (file, before) -> assertEquals(before, metadata(file)) }
        }
        assertEquals(otherBefore, metadata(other))
        assertEquals(resourceBefore, snapshot(f.resources))
    }

    @Test fun hostileZipPathsAndSmallNodeBudgetNeverCommitOrEscape() = fixture { f ->
        // Existing constructor bound: exercise real staging files, not 100k
        // device nodes, while counting implicit parents and reserved metadata.
        val store = f.store(maxNodes = 8)
        val old = write(store.root, "old.bin", byteArrayOf(1))
        val lock = write(store.root, ".ethornell-session.lock", byteArrayOf(2))
        val oldBefore = metadata(old)
        val lockBefore = metadata(lock)
        val resourcesBefore = snapshot(f.resources)
        listOf("../escape", "/absolute", "C:/drive", "a\\b", "a/../alias", "a//alias",
            "nested/.ETHORNELL-private", ".ethornell-session.lock").forEach { name ->
            val archive = f.archive(name to byteArrayOf(99))
            // Android 14+ ZIP path validation can reject nextEntry() before
            // safeName sees the name. Parser failures map to READ_IMPORT_ZIP;
            // backend path checks map to ILLEGAL_ZIP_PATH. Never disable the
            // platform validator or classify localized exception messages.
            try {
                store.import { FileInputStream(archive) }
                fail("Hostile ZIP path must be rejected: $name")
            } catch (error: GameSaveException) {
                assertTrue("Unexpected typed failure: ${error.code}", error.code in setOf(
                    SaveErrorCode.ILLEGAL_ZIP_PATH, SaveErrorCode.READ_IMPORT_ZIP,
                ))
                if (error.code == SaveErrorCode.READ_IMPORT_ZIP) assertNull(error.detail)
            }
            assertEquals(oldBefore, metadata(old))
            assertEquals(lockBefore, metadata(lock))
            assertNoTransactionResidue(store)
        }
        val oversized = f.archive("a/b/c/d" to byteArrayOf(3), "e/f/g/h" to byteArrayOf(4))
        expect(SaveErrorCode.TOO_MANY_ZIP_FILES) { store.import { FileInputStream(oversized) } }
        assertEquals(oldBefore, metadata(old))
        assertEquals(lockBefore, metadata(lock))
        assertFalse(File(store.root.parentFile, "escape").exists())
        assertNoTransactionResidue(store)
        store.recoverForLaunch()
        assertEquals(1, store.import { FileInputStream(f.archive("new.bin" to byteArrayOf(5))) })
        assertEquals(lockBefore, metadata(lock))
        assertEquals(resourcesBefore, snapshot(f.resources))
    }

    @Test fun failedCommitRenameRollsBackRealPrivateTree() = fixture { f ->
        val store = EthornellSaveStore(f.files, f.identity, rename = { from, to ->
            // Fail only the second commit step; all performed renames are real.
            !from.name.endsWith("-import-staging") && from.renameTo(to)
        })
        val old = write(store.root, "old.bin", byteArrayOf(10))
        val lock = write(store.root, ".ethornell-session.lock", byteArrayOf(11))
        val before = listOf(old, lock).associateWith(::metadata)
        val resourcesBefore = snapshot(f.resources)
        expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            store.import { FileInputStream(f.archive("new.bin" to byteArrayOf(12))) }
        }
        before.forEach { (file, original) -> assertEquals(original, metadata(file)) }
        assertFalse(File(store.root, "new.bin").exists())
        assertNoTransactionResidue(store)
        f.store().recoverForLaunch()
        assertEquals(resourcesBefore, snapshot(f.resources))
    }

    @Test fun cancellationClosesRealInputAndOutputStreamsAndCleansStaging() = fixture { f ->
        var inputOpened = false
        var inputClosed = false
        val cancelled = CancellationException("device fixture cancellation")
        val store = EthornellSaveStore(f.files, f.identity, checkCancelled = {
            if (inputOpened) throw cancelled
        })
        val old = write(store.root, "old.bin", byteArrayOf(1))
        val lock = write(store.root, ".ethornell-session.lock", byteArrayOf(2))
        val before = listOf(old, lock).associateWith(::metadata)
        val archive = f.archive("new.bin" to byteArrayOf(3))
        var reads = 0
        try {
            store.import {
                object : FileInputStream(archive) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        reads++
                        return super.read(b, off, len)
                    }
                    override fun close() { inputClosed = true; super.close() }
                }.also { inputOpened = true }
            }
            fail("Expected cancellation during reserved-node traversal")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        assertTrue(inputOpened)
        assertTrue(inputClosed)
        assertEquals(0, reads)
        assertNoTransactionResidue(store)
        before.forEach { (file, original) -> assertEquals(original, metadata(file)) }

        var outputOpened = false
        var outputClosed = false
        val exporting = EthornellSaveStore(f.files, f.identity, checkCancelled = {
            if (outputOpened) throw cancelled
        })
        try {
            exporting.export {
                object : FileOutputStream(File(f.base, "cancelled-export.zip")) {
                    override fun close() { outputClosed = true; super.close() }
                }.also { outputOpened = true }
            }
            fail("Expected cancellation after acquiring output")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        assertTrue(outputOpened)
        assertTrue(outputClosed)
        before.forEach { (file, original) -> assertEquals(original, metadata(file)) }
        f.store().recoverForLaunch()
    }

    @Test fun publicApisStayClosedWithDurablePendingDispatchWithoutCapabilityOverride() = fixture { f ->
        runBlocking {
            assertFalse(EthornellSaveCapability.available)
            val store = f.store()
            val old = write(store.root, "old.bin", byteArrayOf(1))
            val before = metadata(old)
            val manager = GameSaveManager(f.context)
            val game = ScanGame("Backend fixture only", f.identity, EngineType.ETHORNELL, "")
            val token = EthornellDispatchRegistry.issue(f.files, f.identity)
            try {
                val record = requireNotNull(EthornellDispatchRegistry.status(f.files, token))
                assertEquals(EthornellDispatchRegistry.Stage.ISSUED, record.stage)
                assertEquals(f.identity, record.identity)
                val location = manager.resolveSaveLocation(game)
                assertEquals(store.root, location.directory)
                assertFalse(location.available)
                assertTrue(manager.listSaveFiles(game).isEmpty())
                val destination = write(f.base, "must-not-truncate.zip", byteArrayOf(7))
                val destinationBefore = metadata(destination)
                expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { manager.exportToZip(game, Uri.fromFile(destination)) }
                expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { manager.importFromZip(game, Uri.fromFile(destination)) }
                expect(SaveErrorCode.SAVE_DIR_UNAVAILABLE) { manager.deleteSaves(game) }
                manager.cleanupAppData(game)
                assertEquals(before, metadata(old))
                assertEquals(destinationBefore, metadata(destination))
                // No disabled action silently revokes a dispatch or claims that
                // the pending host is stopped. This proves capability refusal,
                // not the distinct real-host gate while capability is enabled.
                assertEquals(EthornellDispatchRegistry.Stage.ISSUED,
                    EthornellDispatchRegistry.status(f.files, token).stage)
            } finally {
                assertTrue(EthornellDispatchRegistry.revokeUnclaimed(f.files, token, f.identity))
            }
        }
    }

    @Test fun sameProcessClaimRevokeRaceHasOneWinnerAndUncertainHostEvidenceRefusesSaves() = fixture { f ->
        val token = EthornellDispatchRegistry.issue(f.files, f.identity)
        val pid = Process.myPid()
        val stat = File("/proc/self/stat").readText()
        val startTime = stat.substring(stat.lastIndexOf(") ") + 2).trim().split(Regex("\\s+"))[19]
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val claim = pool.submit<Boolean> {
                ready.countDown()
                check(go.await(1, TimeUnit.SECONDS))
                EthornellDispatchRegistry.claim(f.files, token, f.identity, pid, startTime)
            }
            val revoke = pool.submit<Boolean> {
                ready.countDown()
                check(go.await(1, TimeUnit.SECONDS))
                EthornellDispatchRegistry.revokeUnclaimed(f.files, token, f.identity)
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            go.countDown()
            val claimed = claim.get(2, TimeUnit.SECONDS)
            assertTrue("Exactly one atomic transition may win", claimed.xor(revoke.get(2, TimeUnit.SECONDS)))
            val record = requireNotNull(EthornellDispatchRegistry.status(f.files, token))
            assertEquals(if (claimed) EthornellDispatchRegistry.Stage.CLAIMED else EthornellDispatchRegistry.Stage.REVOKED,
                record.stage)
            assertFalse(EthornellDispatchRegistry.claim(f.files, token, f.identity, pid, startTime))
            if (claimed) {
                assertEquals(pid, record.pid)
                assertEquals(startTime, record.startTime)
                assertFalse(EthornellDispatchRegistry.revokeUnclaimed(f.files, token, f.identity))
                assertTrue(EthornellDispatchRegistry.finish(f.files, token, f.identity, pid, startTime))
            }
            assertFalse(File(f.files, "ethornell_dispatch/$token.tmp").exists())
            // Deterministic refusal coverage, regardless of the race winner.
            // This really is OUR PID/starttime, not a fabricated native-host
            // owner. No actual :ethornell host is launched or impersonated.
            val uncertain = EthornellDispatchRegistry.issue(f.files, f.identity)
            assertTrue(EthornellDispatchRegistry.claim(f.files, uncertain, f.identity, pid, startTime))
            try {
                assertNotEquals("Run this test in the instrumentation process, not the native host",
                    "${f.context.packageName}:ethornell", File("/proc/self/cmdline").readText().substringBefore('\u0000'))
                try {
                    EthornellOperationGate.requireIdle(f.context)
                    fail("Non-host owner evidence must not admit save IO")
                } catch (expected: IOException) {
                    assertNotNull(expected.message)
                }
            } finally {
                assertTrue(EthornellDispatchRegistry.finish(f.files, uncertain, f.identity, pid, startTime))
            }
        } finally {
            go.countDown()
            pool.shutdownNow()
            assertTrue("Metadata workers must not outlive their fixture", pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private class Fixture : AutoCloseable {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val base: File = Files.createTempDirectory(app.filesDir.toPath(), "ethornell-save-device-").toFile().canonicalFile
        val files = File(base, "private-files").apply { check(mkdir()) }
        val resources = File(base, "resource-日本語😀").apply { check(mkdir()) }
        val identity = "content://device-fixture/tree/日本語😀/${UUID.randomUUID()}"
        val context: Context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = files
            override fun getApplicationContext(): Context = this
        }

        init {
            write(resources, "data.arc", byteArrayOf(66, 71, 73))
            write(resources, "UserData/resource-sentinel.bin", byteArrayOf(4, 5, 6))
        }

        fun store(gameIdentity: String = identity, maxNodes: Int = 100_000) =
            EthornellSaveStore(files, gameIdentity, maxNodes = maxNodes)

        fun archive(vararg entries: Pair<String, ByteArray>): File = File(base, "input.zip").also { archive ->
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = FIXTURE_TIME })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

        override fun close() {
            // Only our freshly-created UUID fixture is eligible. walkFileTree
            // does not follow symlinks; never recurse through a redirected root.
            check(base.parentFile == app.filesDir.canonicalFile)
            check(base.name.startsWith("ethornell-save-device-"))
            Files.walkFileTree(base.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.delete(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: java.nio.file.Path, error: IOException?): java.nio.file.FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
    }

    private companion object {
        const val FIXTURE_TIME = 1_700_000_000_000L // Exact ZIP/DOS two-second timestamp.

        fun fixture(block: (Fixture) -> Unit) { Fixture().use(block) }

        fun write(root: File, name: String, bytes: ByteArray): File = File(root, name).apply {
            check(parentFile!!.isDirectory || parentFile!!.mkdirs())
            FileOutputStream(this).use { out -> out.write(bytes); out.fd.sync() }
            check(setLastModified(FIXTURE_TIME))
        }

        data class Metadata(val device: Long, val inode: Long, val size: Long, val modified: Long, val digest: String)

        fun metadata(file: File): Metadata {
            val stat = Os.lstat(file.path)
            val digest = if (file.isFile) MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) } else "directory"
            return Metadata(stat.st_dev, stat.st_ino, stat.st_size, file.lastModified(), digest)
        }

        fun snapshot(root: File): Map<String, Metadata> = root.walkTopDown().associate {
            it.relativeTo(root).invariantSeparatorsPath to metadata(it)
        }

        fun readZip(file: File): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { entries ->
            ZipInputStream(FileInputStream(file)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    assertFalse(entry.isDirectory)
                    assertFalse(entries.containsKey(entry.name))
                    entries[entry.name] = ByteArrayOutputStream().also { zip.copyTo(it) }.toByteArray()
                    zip.closeEntry()
                }
            }
        }

        fun syncDirectory(directory: File) {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                assertTrue(OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
                Os.fsync(fd)
            } finally { Os.close(fd) }
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

        fun assertNoTransactionResidue(store: EthornellSaveStore) {
            for (suffix in listOf("staging", "backup")) {
                assertFalse(File(store.root.parentFile, ".ethornell-${store.root.name}-import-$suffix").exists())
            }
        }

        inline fun expect(code: SaveErrorCode, block: () -> Unit) {
            try { block(); fail("Expected $code") }
            catch (error: GameSaveException) { assertEquals(code, error.code) }
        }
    }
}
