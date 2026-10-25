package com.tyranor.next.core.game.save

import com.tyranor.next.core.game.save.sync.SaveContentHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SavePathSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun symbolicRootIsRejectedBeforeCanonicalization() {
        val target = temp.newFolder()
        val link = File(temp.root, "linked-root")
        createSymbolicLink(link, target)

        expectUnavailable { SavePathSafety.requireSafeRoot(link) }
        expectUnavailable { SaveContentHash.files(link, listOf(File(link, "save.bin"))) }
    }

    @Test fun symbolicAncestorIsRejectedBeforeCanonicalization() {
        val target = temp.newFolder()
        val save = File(target, "save").apply { check(mkdirs()) }
        val ancestor = File(temp.root, "linked-ancestor")
        createSymbolicLink(ancestor, target)

        expectUnavailable { SavePathSafety.requireSafeRoot(File(ancestor, save.name)) }
    }

    @Test fun symbolicComponentBeforeParentTraversalIsRejected() {
        val targetParent = temp.newFolder()
        val target = File(targetParent, "nested").apply { check(mkdirs()) }
        val link = File(temp.root, "linked-before-parent")
        createSymbolicLink(link, target)
        File(targetParent, "save").mkdirs()
        File(temp.root, "save").mkdirs()

        expectUnavailable { SavePathSafety.requireSafeRoot(File(link, "../save")) }
    }

    @Test fun missingRootIsAllowedButExistingFileRootIsRejected() {
        val missing = File(File(temp.root, "not-created"), "save")

        val safeRoot = SavePathSafety.requireSafeRoot(missing)

        assertFalse(safeRoot.exists())
        expectUnavailable { SavePathSafety.requireSafeRoot(temp.newFile()) }
    }

    @Test fun symbolicFileAndIntermediateDirectoryAreRejected() {
        val root = SavePathSafety.requireSafeRoot(temp.newFolder())
        val outside = temp.newFile().apply { writeText("outside") }
        val fileLink = File(root, "linked-file")
        createSymbolicLink(fileLink, outside)
        expectUnavailable { SavePathSafety.requireSafeFile(root, fileLink) }

        val outsideDirectory = temp.newFolder()
        val directoryLink = File(root, "linked-directory")
        createSymbolicLink(directoryLink, outsideDirectory)
        val nested = File(directoryLink, "save.bin").apply { writeText("nested") }
        expectUnavailable { SavePathSafety.requireSafeFile(root, nested) }
    }

    @Test fun pathsOutsideTheTrustedRootAreRejected() {
        val root = SavePathSafety.requireSafeRoot(temp.newFolder())
        val outside = temp.newFile().apply { writeText("outside") }

        expectUnavailable { SavePathSafety.requireSafeFile(root, outside) }
        expectUnavailable { SavePathSafety.requireSafeFile(root, File(root, "../outside")) }
        expectUnavailable { SaveContentHash.files(root, listOf(outside)) }
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

    private fun expectUnavailable(block: () -> Unit) {
        try {
            block()
            fail("Unsafe save path was accepted")
        } catch (expected: GameSaveException) {
            assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, expected.code)
        }
    }
}
