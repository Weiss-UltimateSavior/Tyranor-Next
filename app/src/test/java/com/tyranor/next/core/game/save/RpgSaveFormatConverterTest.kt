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

    // 引擎 MV 侧实际落盘名 = key_<sha256(legacy 键)>.bin（下面由真实键算出，可复核）
    private val mvGlobal = "key_215007509301dd409efc2827bcef990f603bba0b5f3deceed8e025c3b67392eb.bin"
    private val mvFile1 = "key_a7fccfa2378924192142c2ab1c361c0a2c005a332478e0668c371f6107eb18d2.bin"
    private val mvFile2 = "key_5dac4a9edf2025cfe97652d03fb468ca8a9b5c2d7b75480c5edefd0870f0fb9a.bin"
    private val mvFile1bak = "key_3f32fc82424f2dfbfd3025ba06953d159794c744f4d9adb789ae307af21bf40f.bin"
    private val mvGlobalbak = "key_4ae7448b065f23ae346c44c97189f5f9b7f0dbf0c2a0c892468d4dd20600cfbb.bin"

    private fun mvGameRoot(name: String = "Game"): java.io.File {
        val gameRoot = temporaryFolder.newFolder(name)
        gameRoot.resolve("www/js").mkdirs()
        gameRoot.resolve("www/index.html").writeText("<html></html>")
        gameRoot.resolve("www/js/rpg_core.js").writeText("// MV")
        return gameRoot
    }

    @Test
    fun convertsMvStandardSavesFromSavedataIncludingBackups() {        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        val globalBytes = "GLOBAL-DATA".toByteArray()
        val file1Bytes = "FILE1-DATA".toByteArray()
        val bakBytes = "BAK-DATA".toByteArray()
        savedata.resolve("global.rpgsave").writeBytes(globalBytes)
        savedata.resolve("file1.rpgsave").writeBytes(file1Bytes)
        savedata.resolve("file1.rpgsave.bak").writeBytes(bakBytes)

        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.RPG_MV)

        assertEquals(3, result.converted)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertArrayEquals(globalBytes, savedata.resolve(mvGlobal).readBytes())
        assertArrayEquals(file1Bytes, savedata.resolve(mvFile1).readBytes())
        assertArrayEquals(bakBytes, savedata.resolve(mvFile1bak).readBytes())
        assertFalse(savedata.resolve("global.rpgsave").exists())
        assertTrue(savedata.resolve("original/global.rpgsave").isFile)
    }

    @Test
    fun convertsMzSaves() {
        val gameRoot = mvGameRoot("Mz Game")
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        savedata.resolve("global.rmmzsave").writeBytes("G".toByteArray())
        savedata.resolve("config.rmmzsave").writeBytes("C".toByteArray())

        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.RPG_MZ)

        assertEquals(2, result.converted)
        assertTrue(savedata.resolve("global.bin").isFile)
        assertTrue(savedata.resolve("config.bin").isFile)
    }

    @Test
    fun skipsWhenTargetAlreadyExists() {
        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        val existing = "EXISTING-TYRANOR".toByteArray()
        savedata.resolve(mvGlobal).writeBytes(existing)
        savedata.resolve("global.rpgsave").writeBytes("STANDARD".toByteArray())

        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.RPG_MV)

        assertEquals(0, result.converted)
        assertEquals(1, result.skipped)
        assertArrayEquals(existing, savedata.resolve(mvGlobal).readBytes())
        assertTrue(savedata.resolve("original/global.rpgsave").isFile)
    }

    @Test
    fun originalNameCollisionGetsSuffix() {
        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        savedata.resolve("original").mkdirs()
        savedata.resolve("original/global.rpgsave").writeText("OLD")
        savedata.resolve("global.rpgsave").writeText("NEW")

        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.RPG_MV)

        assertEquals(1, result.converted)
        assertEquals("OLD", savedata.resolve("original/global.rpgsave").readText())
        assertEquals("NEW", savedata.resolve("original/global_1.rpgsave").readText())
    }

    @Test
    fun hashedSavesAreReportedButNotConverted() {
        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        val hashed = "key_" + "c".repeat(64) + ".bin"
        savedata.resolve(hashed).writeText("hashed")
        savedata.resolve("global.rpgsave").writeText("std")

        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.RPG_MV)

        assertEquals(1, result.converted)
        assertEquals(1, result.hashedCount)
        assertTrue(savedata.resolve(hashed).isFile)
    }

    @Test
    fun preserveFailureIsReportedAsFailedNotConverted() {
        // original/ 无法创建时不能报告转化成功：源文件仍在活动目录，下次检测会重复提示转化
        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        savedata.resolve("global.rpgsave").writeText("std")
        // 用同名文件占位 original/，迫使留底目录创建失败
        savedata.resolve("original").writeText("block")

        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.RPG_MV)

        assertEquals(0, result.converted)
        assertEquals(1, result.failed)
    }

    @Test
    fun legacyNamedTyranorSaveBlocksConversionAndIsNotDuplicated() {
        // 引擎优先读 legacy 名：该槽位已有 legacy 即视为「目标已存在」——跳过不覆盖，
        // 不得再写一份哈希文件（否则同槽位两份且被 legacy 遮蔽）
        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        savedata.resolve("RPG Global.bin").writeText("LEGACY")
        savedata.resolve("global.rpgsave").writeText("STANDARD")

        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.RPG_MV)

        assertEquals(0, result.converted)
        assertEquals(1, result.skipped)
        assertEquals("LEGACY", savedata.resolve("RPG Global.bin").readText())
        assertFalse(savedata.resolve(mvGlobal).exists())
        assertTrue(savedata.resolve("original/global.rpgsave").isFile)
    }

    @Test
    fun nothingToConvertReturnsZero() {
        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        savedata.resolve(mvGlobal).writeText("tyranor")

        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.RPG_MV)
        assertEquals(0, result.converted)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
    }

    @Test
    fun nonRpgEngineIsNoOp() {
        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        savedata.resolve("global.rpgsave").writeText("std")
        val result = RpgSaveFormatConverter.convert(gameRoot, EngineType.TYRANO)
        assertEquals(0, result.converted)
        assertTrue(savedata.resolve("global.rpgsave").isFile)
    }

    @Test
    fun convertsIntoEffectiveSaveDirectory() {
        // B1 回归：独立存档开启时引擎读外部目录——检测与转化必须消费传入的生效目录，
        // 而不是写死 <游戏根>/savedata（默认配置下写死会让转化结果永远不被引擎读取）
        val external = temporaryFolder.newFolder("external/tyrano/G1")
        external.resolve("global.rpgsave").writeText("G")
        external.resolve("file2.rpgsave").writeText("F2")

        val result = RpgSaveFormatConverter.convert(
            outputDir = external,
            sourceDirs = listOf(external),
            engine = EngineType.RPG_MV,
        )

        assertEquals(2, result.converted)
        assertEquals("G", external.resolve(mvGlobal).readText())
        assertEquals("F2", external.resolve(mvFile2).readText())
        assertTrue(external.resolve("original/global.rpgsave").isFile)
    }

    @Test
    fun convertsFromLegacySavedataIntoEffectiveDirectory() {
        // 非独立存档布局：历史 Savedata/ 里的标准存档也要被搬进生效目录转化
        val gameRoot = mvGameRoot()
        val legacy = gameRoot.resolve("Savedata").apply { mkdirs() }
        legacy.resolve("file1.rpgsave").writeText("LEGACY")

        val result = RpgSaveFormatConverter.convert(
            outputDir = gameRoot.resolve("savedata"),
            sourceDirs = listOf(gameRoot.resolve("savedata"), legacy),
            engine = EngineType.RPG_MV,
        )

        assertEquals(1, result.converted)
        assertEquals("LEGACY", gameRoot.resolve("savedata").resolve(mvFile1).readText())
        assertTrue(legacy.resolve("original/file1.rpgsave").isFile)
        assertFalse(gameRoot.resolve("Savedata/file1.rpgsave").exists())
    }
}
