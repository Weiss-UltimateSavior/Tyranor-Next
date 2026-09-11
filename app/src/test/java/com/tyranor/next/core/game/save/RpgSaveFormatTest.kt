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

    /** 构造 MV 游戏目录：gameRoot/www/{index.html,js/rpg_core.js}。返回 gameRoot。 */
    private fun mvGameRoot(name: String = "Locked and Wagered"): java.io.File {
        val gameRoot = temporaryFolder.newFolder(name)
        val www = gameRoot.resolve("www")
        www.resolve("js").mkdirs()
        www.resolve("index.html").writeText("<html></html>")
        www.resolve("js/rpg_core.js").writeText("// MV")
        return gameRoot
    }

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
            "RPG Global.bin", "RPG File1.bin", "global.bin",
            "file.rpgsave", "fileX.rpgsave", "global.rpgsave.tmp",
        ).forEach { name ->
            assertFalse(name, RpgSaveFormat.isStandardName(name, EngineType.RPG_MV))
        }
        // 哈希名不是标准名
        assertFalse(RpgSaveFormat.isStandardName("key_" + "a".repeat(64) + ".bin", EngineType.RPG_MV))
    }

    @Test
    fun mzStandardNamesUseRmmzsaveAndHaveNoBackup() {
        assertTrue(RpgSaveFormat.isStandardName("global.rmmzsave", EngineType.RPG_MZ))
        assertTrue(RpgSaveFormat.isStandardName("file4.rmmzsave", EngineType.RPG_MZ))
        assertFalse(RpgSaveFormat.isStandardName("global.rmmzsave.bak", EngineType.RPG_MZ))
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
        assertNull(RpgSaveFormat.tyranorToStandard("RPG Global.bin", EngineType.RPG_MZ))
        assertNull(RpgSaveFormat.tyranorToStandard("global.bin", EngineType.RPG_MV))
    }

    @Test
    fun nonRpgEnginesAreNotConvertible() {
        assertNull(RpgSaveFormat.standardToTyranor("global.rpgsave", EngineType.TYRANO))
        assertNull(RpgSaveFormat.tyranorToStandard("RPG Global.bin", EngineType.TYRANO))
        assertFalse(RpgSaveFormat.isStandardName("global.rpgsave", EngineType.TYRANO))
        assertFalse(RpgSaveFormat.isRpgWebEngine(EngineType.TYRANO))
    }

    // ===== 哈希名反解（导出标准模式） =====

    @Test
    fun reversesHashedSaveNamesBackToStandard() {
        // 实测：Txranor 内创建第 3 存档位生成 key_938e37….bin，其原始键为 "RPG File3"
        val slot3 = "key_938e37cbcee031a9bc044e6e784523ae2ee39d0cc30a060d16aedd0da815c8e5.bin"
        assertEquals("file3.rpgsave", RpgSaveFormat.hashedToStandardName(slot3, EngineType.RPG_MV))
        assertEquals("file1.rpgsave", RpgSaveFormat.hashedToStandardName("key_a7fccfa2378924192142c2ab1c361c0a2c005a332478e0668c371f6107eb18d2.bin", EngineType.RPG_MV))
        assertEquals("global.rpgsave", RpgSaveFormat.hashedToStandardName("key_215007509301dd409efc2827bcef990f603bba0b5f3deceed8e025c3b67392eb.bin", EngineType.RPG_MV))
        assertEquals("global.rpgsave.bak", RpgSaveFormat.hashedToStandardName("key_4ae7448b065f23ae346c44c97189f5f9b7f0dbf0c2a0c892468d4dd20600cfbb.bin", EngineType.RPG_MV))
    }

    @Test
    fun hashedReversalRejectsUnknownKeysAndNonHashedNames() {
        // 插件自定义键无法枚举还原
        assertNull(RpgSaveFormat.hashedToStandardName("key_" + "0".repeat(64) + ".bin", EngineType.RPG_MV))
        assertNull(RpgSaveFormat.hashedToStandardName("RPG File3.bin", EngineType.RPG_MV))
        assertNull(RpgSaveFormat.hashedToStandardName("key_abc.bin", EngineType.RPG_MV))
    }

    @Test
    fun hashedNameIsRecognizedButNotStandard() {
        val hashed = "key_" + "a".repeat(64) + ".bin"
        assertTrue(RpgSaveFormat.isHashedTyranorName(hashed))
        assertFalse(RpgSaveFormat.isStandardName(hashed, EngineType.RPG_MV))
        assertNull(RpgSaveFormat.standardToTyranor(hashed, EngineType.RPG_MV))
    }

    // ===== 目录检测 =====

    @Test
    fun detectOnlyScansDirectChildrenAndReportsHashed() {
        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        savedata.resolve("global.rpgsave").writeText("a")
        savedata.resolve("file1.rpgsave").writeText("b")
        savedata.resolve("key_" + "b".repeat(64) + ".bin").writeText("h")
        savedata.resolve("RPG Global.bin").writeText("existing")
        // original/ 内的标准文件不应再次触发
        val original = savedata.resolve("original").apply { mkdirs() }
        original.resolve("file2.rpgsave").writeText("c")

        val detection = RpgSaveFormat.detect(gameRoot, EngineType.RPG_MV)
        assertEquals(2, detection.convertibleCount)
        assertEquals(1, detection.hashedCount)
    }

    @Test
    fun detectFindsStandardSavesInSaveDirectory() {
        val gameRoot = mvGameRoot()
        val savedata = gameRoot.resolve("savedata").apply { mkdirs() }
        savedata.resolve("global.rpgsave").writeText("a")

        val detection = RpgSaveFormat.detect(gameRoot, EngineType.RPG_MV)
        assertEquals(1, detection.convertibleCount)
    }

    @Test
    fun detectToleratesLegacyUppercaseSavedata() {
        val gameRoot = mvGameRoot()
        val upper = gameRoot.resolve("Savedata").apply { mkdirs() }
        upper.resolve("file1.rpgsave").writeText("a")

        val detection = RpgSaveFormat.detect(gameRoot, EngineType.RPG_MV)
        assertEquals(1, detection.convertibleCount)
    }

    @Test
    fun saveDirectoryIsGameRootSavedata() {
        val gameRoot = mvGameRoot()
        assertEquals(gameRoot.resolve("savedata").absolutePath, RpgSaveFormat.saveDirectory(gameRoot).absolutePath)
    }
}
