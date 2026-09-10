package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RpgSaveFormatTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ===== 标准名判定 =====

    @Test
    fun detectsMvStandardNamesIncludingBackups() {
        listOf(
            "global.rpgsave", "config.rpgsave", "file1.rpgsave", "file12.rpgsave",
            "global.rpgsave.bak", "config.rpgsave.bak", "file3.rpgsave.bak",
        ).forEach { name ->
            assertTrue(name, RpgSaveFormat.isStandardName(name, EngineType.RPG_MV))
        }
    }

    @Test
    fun mvStandardNameIsCaseInsensitive() {
        assertTrue(RpgSaveFormat.isStandardName("Global.RPGSAVE", EngineType.RPG_MV))
        assertTrue(RpgSaveFormat.isStandardName("FILE2.RPGSave.BAK", EngineType.RPG_MV))
    }

    @Test
    fun rejectsNonStandardNames() {
        listOf(
            "RPG Global.bin", "RPG File1.bin", "key_abc.bin", "global.bin",
            "file.rpgsave", "fileX.rpgsave", "global.rpgsave.tmp",
        ).forEach { name ->
            assertFalse(name, RpgSaveFormat.isStandardName(name, EngineType.RPG_MV))
        }
    }

    @Test
    fun mzStandardNamesUseRmmzsaveAndHaveNoBackup() {
        assertTrue(RpgSaveFormat.isStandardName("global.rmmzsave", EngineType.RPG_MZ))
        assertTrue(RpgSaveFormat.isStandardName("file4.rmmzsave", EngineType.RPG_MZ))
        // MZ 核心不带备份，.bak 不视为标准文件
        assertFalse(RpgSaveFormat.isStandardName("global.rmmzsave.bak", EngineType.RPG_MZ))
        // MV 扩展名不适用于 MZ
        assertFalse(RpgSaveFormat.isStandardName("global.rpgsave", EngineType.RPG_MZ))
    }

    // ===== 双向映射 =====

    @Test
    fun mvStandardToTyranorMapping() {
        assertEquals("RPG Global.bin", RpgSaveFormat.standardToTyranor("global.rpgsave", EngineType.RPG_MV))
        assertEquals("RPG Config.bin", RpgSaveFormat.standardToTyranor("config.rpgsave", EngineType.RPG_MV))
        assertEquals("RPG File7.bin", RpgSaveFormat.standardToTyranor("file7.rpgsave", EngineType.RPG_MV))
        assertEquals("RPG Globalbak.bin", RpgSaveFormat.standardToTyranor("global.rpgsave.bak", EngineType.RPG_MV))
        assertEquals("RPG File7bak.bin", RpgSaveFormat.standardToTyranor("file7.rpgsave.bak", EngineType.RPG_MV))
    }

    @Test
    fun mzStandardToTyranorMapping() {
        assertEquals("global.bin", RpgSaveFormat.standardToTyranor("global.rmmzsave", EngineType.RPG_MZ))
        assertEquals("config.bin", RpgSaveFormat.standardToTyranor("config.rmmzsave", EngineType.RPG_MZ))
        assertEquals("file9.bin", RpgSaveFormat.standardToTyranor("file9.rmmzsave", EngineType.RPG_MZ))
    }

    @Test
    fun tyranorToStandardMapping() {
        assertEquals("global.rpgsave", RpgSaveFormat.tyranorToStandard("RPG Global.bin", EngineType.RPG_MV))
        assertEquals("file3.rpgsave", RpgSaveFormat.tyranorToStandard("RPG File3.bin", EngineType.RPG_MV))
        assertEquals("file3.rpgsave.bak", RpgSaveFormat.tyranorToStandard("RPG File3bak.bin", EngineType.RPG_MV))
        assertEquals("global.rmmzsave", RpgSaveFormat.tyranorToStandard("global.bin", EngineType.RPG_MZ))
        assertEquals("file8.rmmzsave", RpgSaveFormat.tyranorToStandard("file8.bin", EngineType.RPG_MZ))
    }

    @Test
    fun mvAndMzTyranorNamesDoNotCrossMap() {
        // MV 的 Tyranor 名（含 "RPG " 前缀）不被 MZ 识别
        assertNull(RpgSaveFormat.tyranorToStandard("RPG Global.bin", EngineType.RPG_MZ))
        // MZ 的无前缀名不被 MV 识别
        assertNull(RpgSaveFormat.tyranorToStandard("global.bin", EngineType.RPG_MV))
    }

    @Test
    fun nonRpgEnginesAreNotConvertible() {
        assertNull(RpgSaveFormat.standardToTyranor("global.rpgsave", EngineType.TYRANO))
        assertNull(RpgSaveFormat.tyranorToStandard("RPG Global.bin", EngineType.TYRANO))
        assertFalse(RpgSaveFormat.isStandardName("global.rpgsave", EngineType.TYRANO))
        assertFalse(RpgSaveFormat.isRpgWebEngine(EngineType.TYRANO))
    }

    @Test
    fun hashedTyranorNameIsRecognizedButNotStandard() {
        val hashed = "key_" + "a".repeat(64) + ".bin"
        assertTrue(RpgSaveFormat.isHashedTyranorName(hashed))
        assertFalse(RpgSaveFormat.isStandardName(hashed, EngineType.RPG_MV))
        assertNull(RpgSaveFormat.standardToTyranor(hashed, EngineType.RPG_MV))
    }

    // ===== 目录检测 =====

    @Test
    fun detectOnlyScansDirectChildrenAndReportsHashed() {
        val save = temporaryFolder.newFolder("save")
        save.resolve("global.rpgsave").writeText("a")
        save.resolve("file1.rpgsave").writeText("b")
        save.resolve("key_" + "b".repeat(64) + ".bin").writeText("h")
        save.resolve("RPG Global.bin").writeText("existing")
        // original/ 内的标准文件不应再次触发
        val original = save.resolve("original").apply { mkdirs() }
        original.resolve("file2.rpgsave").writeText("c")

        val detection = RpgSaveFormat.detect(save, EngineType.RPG_MV)
        assertEquals(2, detection.convertibleCount)
        assertEquals(1, detection.hashedCount)
    }

    @Test
    fun detectAnyFindsLegacyUppercaseSaveDirectory() {
        val root = temporaryFolder.newFolder("Locked and Wagered", "www")
        val lower = root.resolve("save")
        val upper = root.resolve("Save")
        lower.mkdirs()
        upper.mkdirs()
        upper.resolve("global.rpgsave").writeText("a")

        val detection = RpgSaveFormat.detectAny(lower, EngineType.RPG_MV)
        assertEquals(1, detection.convertibleCount)
    }

    @Test
    fun detectAnyFindsRedundantNestedSaveDirectory() {
        // 导入带单层文件夹的备份包后可能落成 save/save/：检测需自愈命中，转化回写到 save/ 根
        val save = temporaryFolder.newFolder("www", "save")
        val nested = save.resolve("save").apply { mkdirs() }
        nested.resolve("global.rpgsave").writeText("a")

        val detection = RpgSaveFormat.detectAny(save, EngineType.RPG_MV)
        assertEquals(1, detection.convertibleCount)

        val result = RpgSaveFormatConverter.convert(save, EngineType.RPG_MV)
        assertEquals(1, result.converted)
        assertTrue(save.resolve("RPG Global.bin").isFile)
    }

    @Test
    fun resolveSaveDirectoryUsesContentRootUnderWww() {
        val gameRoot = temporaryFolder.newFolder("Locked and Wagered")
        val www = gameRoot.resolve("www")
        www.resolve("js").mkdirs()
        www.resolve("index.html").writeText("<html></html>")
        www.resolve("js/rpg_core.js").writeText("// MV")

        val save = RpgSaveFormat.resolveSaveDirectory(gameRoot, EngineType.RPG_MV)
        assertEquals(www.resolve("save").absolutePath, save.absolutePath)
    }

    @Test
    fun resolveSaveDirectoryFallsBackToGameRootWithoutEntry() {
        val gameRoot = temporaryFolder.newFolder("No Entry")
        val save = RpgSaveFormat.resolveSaveDirectory(gameRoot, EngineType.RPG_MV)
        assertEquals(gameRoot.resolve("save").absolutePath, save.absolutePath)
    }
}
