package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RpgSaveFormatConverterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun convertsMvStandardSavesIncludingBackupsAndKeepsByteContent() {
        val save = temporaryFolder.newFolder("save")
        val globalBytes = "GLOBAL-DATA".toByteArray()
        val file1Bytes = "FILE1-DATA".toByteArray()
        val bakBytes = "BAK-DATA".toByteArray()
        save.resolve("global.rpgsave").writeBytes(globalBytes)
        save.resolve("file1.rpgsave").writeBytes(file1Bytes)
        save.resolve("file1.rpgsave.bak").writeBytes(bakBytes)

        val result = RpgSaveFormatConverter.convert(save, EngineType.RPG_MV)

        assertEquals(3, result.converted)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertArrayEquals(globalBytes, save.resolve("RPG Global.bin").readBytes())
        assertArrayEquals(file1Bytes, save.resolve("RPG File1.bin").readBytes())
        assertArrayEquals(bakBytes, save.resolve("RPG File1bak.bin").readBytes())
        // 源文件已移入 original/ 留底
        assertFalse(save.resolve("global.rpgsave").exists())
        assertTrue(save.resolve("original/global.rpgsave").isFile)
        assertTrue(save.resolve("original/file1.rpgsave.bak").isFile)
    }

    @Test
    fun convertsMzSavesWithoutExtensionCase() {
        val save = temporaryFolder.newFolder("save")
        save.resolve("global.rmmzsave").writeBytes("G".toByteArray())
        save.resolve("config.rmmzsave").writeBytes("C".toByteArray())

        val result = RpgSaveFormatConverter.convert(save, EngineType.RPG_MZ)

        assertEquals(2, result.converted)
        assertTrue(save.resolve("global.bin").isFile)
        assertTrue(save.resolve("config.bin").isFile)
        assertTrue(save.resolve("original/global.rmmzsave").isFile)
    }

    @Test
    fun skipsWhenTargetAlreadyExistsAndMovesSourceToOriginal() {
        val save = temporaryFolder.newFolder("save")
        val existing = "EXISTING-TYRANOR".toByteArray()
        save.resolve("RPG Global.bin").writeBytes(existing)
        save.resolve("global.rpgsave").writeBytes("STANDARD".toByteArray())

        val result = RpgSaveFormatConverter.convert(save, EngineType.RPG_MV)

        assertEquals(0, result.converted)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        // 不覆盖已有 Tyranor 存档
        assertArrayEquals(existing, save.resolve("RPG Global.bin").readBytes())
        // 源文件仍留底，避免重复提示
        assertTrue(save.resolve("original/global.rpgsave").isFile)
    }

    @Test
    fun originalNameCollisionGetsSuffix() {
        val save = temporaryFolder.newFolder("save")
        val original = save.resolve("original").apply { mkdirs() }
        original.resolve("global.rpgsave").writeText("OLD-BACKUP")
        save.resolve("global.rpgsave").writeText("NEW")

        val result = RpgSaveFormatConverter.convert(save, EngineType.RPG_MV)

        assertEquals(1, result.converted)
        assertEquals("OLD-BACKUP", save.resolve("original/global.rpgsave").readText())
        assertEquals("NEW", save.resolve("original/global_1.rpgsave").readText())
    }

    @Test
    fun legacyUppercaseSaveDirectoryIsConvertedIntoLowercaseSave() {
        val parent = temporaryFolder.newFolder("www")
        val lower = parent.resolve("save").apply { mkdirs() }
        val upper = parent.resolve("Save").apply { mkdirs() }
        upper.resolve("file2.rpgsave").writeText("UPPER")

        val result = RpgSaveFormatConverter.convert(lower, EngineType.RPG_MV)

        assertEquals(1, result.converted)
        assertTrue(lower.resolve("RPG File2.bin").isFile)
        assertEquals("UPPER", lower.resolve("RPG File2.bin").readText())
    }

    @Test
    fun hashedSavesAreReportedButNotConverted() {
        val save = temporaryFolder.newFolder("save")
        val hashed = "key_" + "c".repeat(64) + ".bin"
        save.resolve(hashed).writeText("hashed")
        save.resolve("global.rpgsave").writeText("std")

        val result = RpgSaveFormatConverter.convert(save, EngineType.RPG_MV)

        assertEquals(1, result.converted)
        assertEquals(1, result.hashedCount)
        assertTrue(save.resolve(hashed).isFile)
    }

    @Test
    fun nothingToConvertReturnsZero() {
        val save = temporaryFolder.newFolder("save")
        save.resolve("RPG Global.bin").writeText("tyranor")

        val result = RpgSaveFormatConverter.convert(save, EngineType.RPG_MV)
        assertEquals(0, result.converted)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
    }

    @Test
    fun nonRpgEngineIsNoOp() {
        val save = temporaryFolder.newFolder("save")
        save.resolve("global.rpgsave").writeText("std")
        val result = RpgSaveFormatConverter.convert(save, EngineType.TYRANO)
        assertEquals(0, result.converted)
        assertTrue(save.resolve("global.rpgsave").isFile)
    }
}
