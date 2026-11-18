package com.tyranor.next.core.game.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeSaveTransactionOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unownedBackupIsPreservedAndRejected() {
        val root = temporary.newFolder("game")
        val save = File(root, "data/save")
        val backup = File(save.parentFile, "save.import_backup").apply { mkdirs() }
        File(backup, "game.dat").writeText("resource")
        val ownership = ownership(root, save, "v1")

        assertThrows(GameSaveException::class.java) { ownership.begin() }
        assertEquals("resource", File(backup, "game.dat").readText())
    }

    @Test fun matchingRecordAllowsRecoveryAndIsRetiredAfterCleanup() {
        val root = temporary.newFolder("game")
        val save = File(root, "data/save")
        val ownership = ownership(root, save, "v1")
        ownership.begin()
        val backup = File(save.parentFile, "save.import_backup").apply { mkdirs() }

        ownership.begin()
        ownership.completeIfClean()
        assertTrue(backup.exists())
        backup.delete()
        ownership.completeIfClean()
        ownership.begin()
        ownership.completeIfClean()
    }

    @Test fun changedDeclarationCannotClaimPreviousStaging() {
        val root = temporary.newFolder("game")
        val save = File(root, "save")
        val old = ownership(root, save, "old").also { it.begin() }
        val staging = File(root, "save.import_staging").apply { mkdirs() }
        assertThrows(GameSaveException::class.java) { ownership(root, save, "new").begin() }
        assertTrue(staging.isDirectory)
        old.completeIfClean()
    }

    @Test fun ownedRegularFileJournalAllowsInterruptedTransactionRecovery() {
        val root = temporary.newFolder("game")
        val save = File(root, "save")
        val ownership = ownership(root, save, "v1").also { it.begin() }
        val journal = File(root, "save.import_transaction").apply { writeText("{}") }
        File(root, "save.import_backup").mkdirs()

        ownership.begin()
        assertEquals("{}", journal.readText())
        assertTrue(File(root, "save.import_backup").isDirectory)
    }

    private fun ownership(root: File, save: File, fingerprint: String) = RuntimeSaveTransactionOwnership(
        File(temporary.root.canonicalFile, "private"), root.canonicalFile,
        File(root.canonicalFile, save.relativeTo(root).path), "game-uri", "com.tyranor.runtime.demo", fingerprint,
    )

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
