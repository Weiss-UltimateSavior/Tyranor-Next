package com.tyranor.next.core.engine.runtime

import com.tyranor.next.core.game.save.GameSaveException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class RuntimeModuleFileImportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesFixedAndMultipleTargetsWithoutSettings() {
        val manifest = requireNotNull(RuntimeModuleManifestParser.parse(
            """{"fileImports":{"version":1,"items":[
                {"key":"assets","title":"Assets","directory":"data/mods","multiple":true},
                {"key":"config","title":"Config","directory":".","fileName":"game.ini"},
                {"key":"config","title":"Duplicate","directory":"ignored"},
                {"key":"original","title":"Original","directory":"data"}
            ]}}""",
        ))
        assertNull(manifest.settings)
        assertEquals(listOf("assets", "config", "original"), manifest.fileImports.map { it.key })
        val (assets, config, original) = manifest.fileImports
        assertEquals("data/mods/Patch.bin", assets.destination("Patch.bin"))
        assertTrue(assets.multiple)
        assertEquals("game.ini", config.displayPath)
        assertFalse(config.multiple)
        assertEquals("data/Source.txt", original.destination("Source.txt"))
    }

    @Test
    fun rejectsUnsafeTargetsAndConflictingOptionsWithoutAffectingScan() {
        val manifest = requireNotNull(RuntimeModuleManifestParser.parse(
            """{"scan":{"extensions":[".dat"]},"fileImports":{"version":1,"items":[
                {"key":"absolute","title":"Absolute","directory":"/data"},
                {"key":"escape","title":"Escape","directory":"data/../outside"},
                {"key":"separator","title":"Separator","directory":".","fileName":"nested/file"},
                {"key":"traversal","title":"Traversal","directory":".","fileName":".."},
                {"key":"conflict","title":"Conflict","directory":".","fileName":"file","multiple":true}
            ]}}""",
        ))
        assertEquals(listOf(".dat"), manifest.scan?.extensions)
        assertTrue(manifest.fileImports.isEmpty())
    }

    @Test
    fun rejectsLinkedParentsAndTargetsWithoutCreatingDirectories() {
        val root = temporaryFolder.newFolder("game").canonicalFile
        val outside = temporaryFolder.newFolder("outside")
        val importer = RuntimeModuleFileImporter
        assertEquals(File(root, "mods/patch.bin"), importer.checkedTarget(root, "mods/patch.bin"))
        assertFalse(File(root, "mods").exists())
        assertNull(importer.snapshot(File(root, "missing.bin")))
        assertThrows(IllegalArgumentException::class.java) { importer.checkedTarget(root, "../bad.bin") }
        val linkedParent = File(root, "linked")
        Files.createSymbolicLink(linkedParent.toPath(), outside.toPath())
        assertThrows(GameSaveException::class.java) { importer.checkedTarget(root, "linked/bad.bin") }
        val linkedFile = File(root, "linked.bin")
        Files.createSymbolicLink(linkedFile.toPath(), File(outside, "missing.bin").toPath())
        assertThrows(IOException::class.java) { importer.snapshot(linkedFile) }
        assertThrows(IOException::class.java) { importer.snapshot(root) }
        val regular = File(root, "game.ini").apply { writeText("original") }
        assertEquals(8L, importer.snapshot(regular)?.size)
    }

    @Test
    fun stagesNewFilesAndPreservesOriginalOnCopyFailure() {
        val root = temporaryFolder.newFolder("game")
        val target = File(root, "game.ini")
        val importer = RuntimeModuleFileImporter
        runBlocking {
            importer.stageAndWrite(target, false, { "original".byteInputStream() }) {}
        }
        assertEquals("original", target.readText())
        assertThrows(IOException::class.java) {
            runBlocking {
                importer.stageAndWrite(target, true, {
                    object : InputStream() {
                        override fun read(): Int = throw IOException("source interrupted")
                    }
                }) {}
            }
        }
        assertEquals("original", target.readText())
        assertEquals(listOf("game.ini"), root.list()?.toList())
        runBlocking {
            importer.stageAndWrite(target, true, { "replacement".byteInputStream() }) {}
        }
        assertEquals("replacement", target.readText())
    }
}
