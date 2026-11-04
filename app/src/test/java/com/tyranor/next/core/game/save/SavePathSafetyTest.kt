package com.tyranor.next.core.game.save

import com.tyranor.next.core.game.save.sync.SaveContentHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val target = newCanonicalFolder()
        val link = File(target.parentFile, "linked-root")
        createSymbolicLink(link, target)

        expectUnavailable { SavePathSafety.requireSafeRoot(link) }
        expectUnavailable { SavePathSafety.requirePlainDirectoryEntry(link) }
        expectUnavailable { SaveContentHash.files(link, listOf(File(link, "save.bin"))) }
    }

    @Test fun symbolicAncestorIsRejectedBeforeCanonicalization() {
        val target = newCanonicalFolder()
        val save = File(target, "save").apply { check(mkdirs()) }
        val ancestor = File(target.parentFile, "linked-ancestor")
        createSymbolicLink(ancestor, target)

        expectUnavailable { SavePathSafety.requireSafeRoot(File(ancestor, save.name)) }
    }

    @Test fun symbolicComponentBeforeParentTraversalIsRejected() {
        val targetParent = newCanonicalFolder()
        val target = File(targetParent, "nested").apply { check(mkdirs()) }
        val link = File(targetParent.parentFile, "linked-before-parent")
        createSymbolicLink(link, target)
        File(targetParent, "save").mkdirs()
        File(targetParent.parentFile, "save").mkdirs()

        expectUnavailable { SavePathSafety.requireSafeRoot(File(link, "../save")) }
    }

    @Test fun missingRootIsAllowedButExistingFileRootIsRejected() {
        val base = newCanonicalFolder()
        val missing = File(File(base, "not-created"), "save")

        val safeRoot = SavePathSafety.requireSafeRoot(missing)

        assertFalse(safeRoot.exists())
        SavePathSafety.requirePlainDirectoryEntry(missing)
        val file = File(base, "file").apply { writeText("file") }
        expectUnavailable { SavePathSafety.requireSafeRoot(file) }
        expectUnavailable { SavePathSafety.requirePlainDirectoryEntry(file) }
    }

    @Test fun symbolicFileAndIntermediateDirectoryAreRejected() {
        val root = SavePathSafety.requireSafeRoot(newCanonicalFolder())
        val outside = File(newCanonicalFolder(), "outside").apply { writeText("outside") }
        val fileLink = File(root, "linked-file")
        createSymbolicLink(fileLink, outside)
        expectUnavailable { SavePathSafety.requireSafeFile(root, fileLink) }

        val outsideDirectory = newCanonicalFolder()
        val directoryLink = File(root, "linked-directory")
        createSymbolicLink(directoryLink, outsideDirectory)
        val nested = File(directoryLink, "save.bin").apply { writeText("nested") }
        expectUnavailable { SavePathSafety.requireSafeFile(root, nested) }
    }

    @Test fun pathsOutsideTheTrustedRootAreRejected() {
        val root = SavePathSafety.requireSafeRoot(newCanonicalFolder())
        val outside = File(newCanonicalFolder(), "outside").apply { writeText("outside") }

        expectUnavailable { SavePathSafety.requireSafeFile(root, outside) }
        expectUnavailable { SavePathSafety.requireSafeFile(root, File(root, "../outside")) }
        expectUnavailable { SaveContentHash.files(root, listOf(outside)) }
    }

    @Test fun trustedRootItselfMayBeSymbolicLink() {
        val parent = newCanonicalFolder()
        val target = File(parent, "trusted").apply { check(mkdirs()) }
        val trusted = File(parent, "trusted-link")
        createSymbolicLink(trusted, target)
        val root = File(trusted, "save").apply { check(mkdirs()) }

        assertEquals(target.canonicalFile, SavePathSafety.requireSafeRoot(trusted, trusted))
        assertEquals(root.canonicalFile, SavePathSafety.requireSafeRoot(root, trusted))
    }

    @Test fun aliasedAncestorOfTrustedRootIsResolvedBeforeUserPath() {
        val parent = newCanonicalFolder()
        val realAnchor = File(parent, "real-anchor").apply { check(mkdirs()) }
        File(realAnchor, "trusted").mkdirs()
        val ancestor = File(parent, "anchor-link")
        createSymbolicLink(ancestor, realAnchor)
        val trusted = File(ancestor, "trusted")
        val root = File(trusted, "save").apply { check(mkdirs()) }

        assertEquals(root.canonicalFile, SavePathSafety.requireSafeRoot(root, trusted))
    }

    @Test fun symbolicComponentBelowTrustedRootIsRejectedBeforeParentTraversal() {
        val trusted = newCanonicalFolder()
        File(trusted, "save").mkdirs()
        val link = File(trusted, "linked-before-parent")
        createSymbolicLink(link, newCanonicalFolder())

        expectUnavailable { SavePathSafety.requireSafeRoot(File(link, "../save"), trusted) }
    }

    @Test fun pathOutsideProvidedTrustedRootIsRejected() {
        val trusted = newCanonicalFolder()
        val outside = File(trusted.parentFile, "outside").apply { check(mkdirs()) }

        expectUnavailable { SavePathSafety.requireSafeRoot(outside, trusted) }
        expectUnavailable {
            SavePathSafety.requireSafeRoot(File(trusted, "../outside"), trusted)
        }
    }

    @Test fun noFollowDeleteRemovesLinksWithoutDeletingTheirTargets() {
        val root = newCanonicalFolder()
        val outside = newCanonicalFolder()
        val marker = File(outside, "keep").apply { writeText("keep") }
        val link = File(root, "linked-directory")
        createSymbolicLink(link, outside)
        File(root, "nested").mkdirs()

        SavePathSafety.deleteTreeNoFollow(root)

        assertFalse(root.exists())
        assertTrue(marker.isFile)
    }

    private fun newCanonicalFolder(): File = temp.newFolder().canonicalFile

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
