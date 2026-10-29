package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SaveSyncTemporaryFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun classifierDistinguishesEnospcOrdinaryIoAndLowSpace() {
        val full = classifySaveSyncStorageFailure(
            IOException("ENOSPC: No space left on device"),
            directory = null,
            remainingBytes = 1,
        )
        val ordinary = classifySaveSyncStorageFailure(
            IOException("ordinary write failure"),
            directory = null,
            remainingBytes = 1,
        )
        val lowSpaceDirectory = object : File(temp.root, "low-space") {
            override fun getUsableSpace(): Long = 0
        }
        val lowSpace = classifySaveSyncStorageFailure(
            IOException("ordinary write failure"),
            lowSpaceDirectory,
            remainingBytes = 1,
        )

        assertEquals(SaveSyncError.STORAGE_FULL, full.code)
        assertEquals(SaveSyncError.LOCAL_IO, ordinary.code)
        assertEquals(SaveSyncError.STORAGE_FULL, lowSpace.code)
    }

    @Test
    fun classifierPreservesTypedFailureAndCancellation() {
        val typed = SaveSyncException(SaveSyncError.SAVE_LOCATION_CHANGED)
        assertSame(typed, classifySaveSyncStorageFailure(typed, directory = null, remainingBytes = 0))

        val cancelled = CancellationException("cancelled")
        val observed = assertThrows(CancellationException::class.java) {
            classifySaveSyncStorageFailure(cancelled, directory = null, remainingBytes = 0)
        }
        assertSame(cancelled, observed)
    }

    @Test
    fun createClassifiesSpaceAndFactoryFailuresWithoutRegisteringTargets() {
        val cache = temp.newFolder("create-cache")
        var factoryCalls = 0
        val lowSpaceFileSystem = SaveSyncTemporaryFileSystem(
            usableSpace = { 0 },
            createTempFile = { _, _, _ ->
                factoryCalls++
                File(cache, "unexpected.tmp")
            },
        )
        val lowSpace = assertThrows(SaveSyncException::class.java) {
            SaveSyncTemporaryFiles.create("save_sync_", ".zip", cache, lowSpaceFileSystem)
        }
        assertEquals(SaveSyncError.STORAGE_FULL, lowSpace.code)
        assertEquals(0, factoryCalls)
        assertTrue(cache.listFiles().orEmpty().isEmpty())

        val unknownDirectory = SaveSyncTemporaryFileSystem(
            createTempFile = { _, _, _ -> throw IOException("ENOSPC: no space left on device") },
        )
        val noDirectoryFailure = assertThrows(SaveSyncException::class.java) {
            SaveSyncTemporaryFiles.create(
                "save_sync_",
                ".zip",
                directory = null,
                fileSystem = unknownDirectory,
            )
        }
        assertEquals(SaveSyncError.STORAGE_FULL, noDirectoryFailure.code)

        val failures = listOf(
            IOException("ordinary create failure") to SaveSyncError.LOCAL_IO,
            IOException("ENOSPC: no space left on device") to SaveSyncError.STORAGE_FULL,
            SecurityException("create denied") to SaveSyncError.LOCAL_IO,
        )
        for ((failure, expected) in failures) {
            val fileSystem = SaveSyncTemporaryFileSystem(
                usableSpace = { Long.MAX_VALUE },
                createTempFile = { _, _, _ -> throw failure },
            )
            val observed = assertThrows(SaveSyncException::class.java) {
                SaveSyncTemporaryFiles.create("save_sync_", ".zip", cache, fileSystem)
            }
            assertEquals(failure.toString(), expected, observed.code)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun copyBudgetIsCheckedBeforeCreation() {
        val cache = temp.newFolder("copy-before-cache")
        val source = temp.newFile("source-before.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var factoryCalls = 0
        val fileSystem = SaveSyncTemporaryFileSystem(
            usableSpace = { source.length() },
            createTempFile = { _, _, _ ->
                factoryCalls++
                File(cache, "unexpected.tmp")
            },
        )

        val failure = assertThrows(SaveSyncException::class.java) {
            SaveSyncTemporaryFiles.copy(source, "save_sync_", ".zip", cache, fileSystem)
        }

        assertEquals(SaveSyncError.STORAGE_FULL, failure.code)
        assertEquals(0, factoryCalls)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun copyBudgetIsRecheckedAfterCreationAndReleasesTarget() {
        val cache = temp.newFolder("copy-recheck-cache")
        val source = temp.newFile("source-recheck.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var spaceChecks = 0
        var factoryCalls = 0
        val fileSystem = SaveSyncTemporaryFileSystem(
            usableSpace = {
                if (++spaceChecks < 3) Long.MAX_VALUE else source.length()
            },
            createTempFile = { prefix, suffix, directory ->
                factoryCalls++
                File.createTempFile(prefix, suffix, directory)
            },
        )

        val failure = assertThrows(SaveSyncException::class.java) {
            SaveSyncTemporaryFiles.copy(source, "save_sync_", ".zip", cache, fileSystem)
        }

        assertEquals(SaveSyncError.STORAGE_FULL, failure.code)
        assertEquals(3, spaceChecks)
        assertEquals(1, factoryCalls)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun failedPinnedCopyReleasesItsCreatedFile() {
        val cache = temp.newFolder("cache")
        val fileSystem = SaveSyncTemporaryFileSystem(usableSpace = { Long.MAX_VALUE })
        val failure = assertThrows(SaveSyncException::class.java) {
            SaveSyncTemporaryFiles.copy(
                File(temp.root, "missing-source.zip"),
                "save_sync_",
                ".zip",
                cache,
                fileSystem,
            )
        }
        assertEquals(SaveSyncError.LOCAL_IO, failure.code)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
        SaveSyncTemporaryFiles.clean(cache, "save_sync_", ".zip")
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun successfulPinnedCopyStaysProtectedUntilReleased() {
        val cache = temp.newFolder("cache")
        val source = temp.newFile("source.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val fileSystem = SaveSyncTemporaryFileSystem(usableSpace = { Long.MAX_VALUE })
        val pinned = SaveSyncTemporaryFiles.copy(source, "save_sync_", ".zip", cache, fileSystem)
        try {
            SaveSyncTemporaryFiles.clean(cache, "save_sync_", ".zip")
            assertTrue(pinned.isFile)
            assertArrayEquals(source.readBytes(), pinned.readBytes())
        } finally {
            SaveSyncTemporaryFiles.release(pinned)
        }
        assertFalse(pinned.exists())
        assertTrue(source.isFile)
    }
}
