package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RpgSaveSyncTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): RpgSaveSyncState = RpgSaveSyncState(temporaryFolder.newFolder("state"))

    /** 建一个两侧目录并返回 (standard, tyranor)。 */
    private fun dirs(): Pair<File, File> {
        val standard = temporaryFolder.newFolder("save")
        val tyranor = temporaryFolder.newFolder("savedata")
        return standard to tyranor
    }

    private fun File.writeAt(name: String, content: String, mtime: Long): File {
        val f = resolve(name)
        f.writeText(content)
        f.setLastModified(mtime)
        return f
    }

    @Test
    fun importsNewStandardSaveIntoTyranor() {
        val (standard, tyranor) = dirs()
        standard.writeAt("file1.rpgsave", "PC-SAVE", 1_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.imported)
        assertEquals("PC-SAVE", tyranor.resolve("RPG File1.bin").readText())
        // 标准侧保留，供 PC 使用
        assertTrue(standard.resolve("file1.rpgsave").isFile)
    }

    @Test
    fun exportsNewTyranorSaveToStandard() {
        val (standard, tyranor) = dirs()
        tyranor.writeAt("RPG File2.bin", "PHONE-SAVE", 1_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.exported)
        assertEquals("PHONE-SAVE", standard.resolve("file2.rpgsave").readText())
        assertTrue(tyranor.resolve("RPG File2.bin").isFile)
    }

    @Test
    fun newerStandardWinsAndPropagatesMtime() {
        val (standard, tyranor) = dirs()
        standard.writeAt("global.rpgsave", "NEW-PC", 5_000)
        tyranor.writeAt("RPG Global.bin", "OLD-PHONE", 1_000)
        val store = store()

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, result.toTyranor)
        assertEquals("NEW-PC", tyranor.resolve("RPG Global.bin").readText())
        assertEquals(5_000, tyranor.resolve("RPG Global.bin").lastModified())
        // 幂等：再同步一次应为 skipped
        val second = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")
        assertEquals(1, second.skipped)
        assertEquals(0, second.changed)
    }

    @Test
    fun newerTyranorWinsAndPropagatesMtime() {
        val (standard, tyranor) = dirs()
        standard.writeAt("global.rpgsave", "OLD-PC", 1_000)
        tyranor.writeAt("RPG Global.bin", "NEW-PHONE", 9_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.toStandard)
        assertEquals("NEW-PHONE", standard.resolve("global.rpgsave").readText())
        assertEquals(9_000, standard.resolve("global.rpgsave").lastModified())
    }

    @Test
    fun tyranorDeletionMovesStandardFileToDeleted() {
        val (standard, tyranor) = dirs()
        val store = store()
        standard.writeAt("file3.rpgsave", "S", 1_000)
        tyranor.writeAt("RPG File3.bin", "S", 1_000)
        assertEquals(0, RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g").changed)

        // Tyranor 侧删除该槽位
        tyranor.resolve("RPG File3.bin").delete()
        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, result.movedToDeleted)
        assertFalse(standard.resolve("file3.rpgsave").exists())
        assertTrue(standard.resolve("deleted/file3.rpgsave").isFile)
        // 不反向删除已删存档
        assertFalse(tyranor.resolve("RPG File3.bin").exists())
    }

    @Test
    fun standardDeletionKeepsTyranorAndReExports() {
        val (standard, tyranor) = dirs()
        val store = store()
        standard.writeAt("file4.rpgsave", "S", 1_000)
        tyranor.writeAt("RPG File4.bin", "S", 1_000)
        RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        // 外部（PC 清理）删了标准文件：不得删掉手机存档
        standard.resolve("file4.rpgsave").delete()
        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, result.exported)
        assertTrue(tyranor.resolve("RPG File4.bin").isFile)
        assertEquals("S", standard.resolve("file4.rpgsave").readText())
    }

    @Test
    fun hashNamedTyranorSaveIsExportedToStandard() {
        val (standard, tyranor) = dirs()
        // key_938e37... == sha256("RPG File3")
        val slot3 = "key_938e37cbcee031a9bc044e6e784523ae2ee39d0cc30a060d16aedd0da815c8e5.bin"
        tyranor.writeAt(slot3, "HASHED", 2_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.exported)
        assertEquals("HASHED", standard.resolve("file3.rpgsave").readText())
    }

    @Test
    fun unmappedHashedSaveIsReportedAndUntouched() {
        val (standard, tyranor) = dirs()
        val unknown = "key_" + "0".repeat(64) + ".bin"
        tyranor.writeAt(unknown, "X", 1_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.unmapped)
        assertEquals(0, result.exported)
        assertTrue(tyranor.resolve(unknown).isFile)
    }

    @Test
    fun originalAndDeletedSubdirsAreNotSynced() {
        val (standard, tyranor) = dirs()
        tyranor.resolve("original").mkdirs()
        tyranor.resolve("original/global.rpgsave").writeText("LEFTOVER")
        standard.resolve("deleted").mkdirs()
        standard.resolve("deleted/file9.rpgsave").writeText("LEFT")

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(0, result.changed)
        assertFalse(standard.resolve("global.rpgsave").exists())
    }

    @Test
    fun mvBackupSlotIsSynced() {
        val (standard, tyranor) = dirs()
        standard.writeAt("file1.rpgsave.bak", "BAK", 1_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.imported)
        assertEquals("BAK", tyranor.resolve("RPG File1bak.bin").readText())
    }

    @Test
    fun mzMappingIsUsedForMzEngine() {
        val (standard, tyranor) = dirs()
        standard.writeAt("global.rmmzsave", "MZ", 1_000)
        tyranor.writeAt("file1.bin", "MZ-1", 2_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MZ, store(), "g")

        assertEquals(1, result.imported)
        assertEquals(1, result.exported)
        assertEquals("MZ", tyranor.resolve("global.bin").readText())
        assertEquals("MZ-1", standard.resolve("file1.rmmzsave").readText())
    }

    @Test
    fun firstRunOverwritePreservesLoserOnTyranorSide() {
        // 首次同步（无清单）：标准侧 mtime 被复制刷新成较新，覆盖前须把手机存档留底
        val (standard, tyranor) = dirs()
        standard.writeAt("global.rpgsave", "PC", 9_000)
        tyranor.writeAt("RPG Global.bin", "PHONE", 1_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.toTyranor)
        assertEquals("PC", tyranor.resolve("RPG Global.bin").readText())
        assertTrue(tyranor.resolve("original/RPG Global.bin").isFile)
        assertEquals("PHONE", tyranor.resolve("original/RPG Global.bin").readText())
    }

    @Test
    fun firstRunOverwritePreservesLoserOnStandardSide() {
        val (standard, tyranor) = dirs()
        standard.writeAt("global.rpgsave", "PC", 1_000)
        tyranor.writeAt("RPG Global.bin", "PHONE", 9_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.toStandard)
        assertEquals("PHONE", standard.resolve("global.rpgsave").readText())
        assertTrue(standard.resolve("deleted/global.rpgsave").isFile)
        assertEquals("PC", standard.resolve("deleted/global.rpgsave").readText())
    }

    @Test
    fun establishedSlotDoesNotKeepBackingUpOnEachOverwrite() {
        // 已有清单后再次覆盖不应再留底（避免无限膨胀）
        val (standard, tyranor) = dirs()
        val store = store()
        standard.writeAt("global.rpgsave", "A", 1_000)
        RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        standard.writeAt("global.rpgsave", "B", 5_000)
        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, result.toTyranor)
        assertFalse(tyranor.resolve("original/RPG Global.bin").exists())
    }

    @Test
    fun uppercaseSaveDirectoryIsSyncedAndWrittenInPlace() {
        // 兼容 Save/：槽位已在 Save/ 中存在时就地更新（大小写不敏感文件系统上 save==Save）
        val parent = temporaryFolder.newFolder("www")
        val lower = parent.resolve("save")
        val upper = parent.resolve("Save").apply { mkdirs() }
        val tyranor = temporaryFolder.newFolder("savedata")
        upper.writeAt("global.rpgsave", "FROM-UPPER", 1_000)
        val store = store()

        val result = RpgSaveSync.sync(
            standardDirs = listOf(lower, upper),
            tyranorDir = tyranor,
            engine = EngineType.RPG_MV,
            stateStore = store,
            gameKey = "g",
        )

        assertEquals(1, result.imported)
        assertEquals("FROM-UPPER", tyranor.resolve("RPG Global.bin").readText())
        // 回写应落在该已存在的标准目录内（不另造目录）
        tyranor.resolve("RPG Global.bin").apply { writeText("NEW"); setLastModified(9_000) }
        val second = RpgSaveSync.sync(listOf(lower, upper), tyranor, EngineType.RPG_MV, store, "g")
        assertEquals(1, second.toStandard)
        assertEquals("NEW", upper.resolve("global.rpgsave").readText())
        assertEquals(1, parent.listFiles().orEmpty().count { it.name.equals("save", ignoreCase = true) })
    }

    @Test
    fun uppercaseSaveIsWrittenWhenItIsTheOnlyExistingDir() {
        // 只有 Save/ 存在时，无槽位的新导出应写入该目录（首选=已存在目录）
        val parent = temporaryFolder.newFolder("www")
        val lower = parent.resolve("save")
        val upper = parent.resolve("Save").apply { mkdirs() }
        val tyranor = temporaryFolder.newFolder("savedata")
        tyranor.writeAt("RPG File1.bin", "PHONE", 1_000)

        val result = RpgSaveSync.sync(listOf(lower, upper), tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.exported)
        assertEquals("PHONE", upper.resolve("file1.rpgsave").readText())
        assertEquals(1, parent.listFiles().orEmpty().count { it.name.equals("save", ignoreCase = true) })
    }

    @Test
    fun preserveFailureAbortsOverwriteAndKeepsBothCopies() {
        // 留底（original/）创建失败时不得继续覆盖：否则较旧的唯一手机存档会在没有留底的情况下被抹掉
        val (standard, tyranor) = dirs()
        standard.writeAt("global.rpgsave", "PC-NEW", 9_000)
        tyranor.writeAt("RPG Global.bin", "PHONE-OLD", 1_000)
        // 用同名文件占位 original/，迫使 preserveLoser 无法创建留底目录
        tyranor.resolve("original").writeText("block")

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.failed)
        assertEquals(0, result.toTyranor)
        assertEquals("PHONE-OLD", tyranor.resolve("RPG Global.bin").readText())
        assertEquals("PC-NEW", standard.resolve("global.rpgsave").readText())
    }

    @Test
    fun sameMtimeDifferentContentIsNotSkipped() {
        // mtime 相同但内容不同（保留时间戳的复制/恢复）不能判为一致而跳过
        val (standard, tyranor) = dirs()
        standard.writeAt("global.rpgsave", "PC-CONTENT", 5_000)
        tyranor.writeAt("RPG Global.bin", "PHONE-DIFFERENT", 5_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(0, result.skipped)
        assertEquals(1, result.toTyranor + result.toStandard)
        // 平局以标准侧为准，且较旧一方须留底（平局下新旧未知）
        assertEquals("PC-CONTENT", tyranor.resolve("RPG Global.bin").readText())
        assertTrue(tyranor.resolve("original/RPG Global.bin").isFile)
    }

    @Test
    fun sameMtimeSameContentIsSkipped() {
        val (standard, tyranor) = dirs()
        standard.writeAt("global.rpgsave", "IDENTICAL", 5_000)
        tyranor.writeAt("RPG Global.bin", "IDENTICAL", 5_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.skipped)
        assertEquals(0, result.changed)
    }

    @Test
    fun zeroMtimeSlotIsStillTrackedAsPresent() {
        // mtime 为 0 是合法时间戳：不能被当成「该侧不存在」，否则删除语义错乱
        val (standard, tyranor) = dirs()
        val store = store()
        val std = standard.resolve("file7.rpgsave").apply { writeText("S") }
        std.setLastModified(0L)

        val first = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")
        assertEquals(1, first.imported)
        assertTrue(tyranor.resolve("RPG File7.bin").isFile)

        // Tyranor 侧删除后，第二轮应识别为「已删除」而非「新建」而复活
        tyranor.resolve("RPG File7.bin").delete()
        val second = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")
        assertEquals(1, second.movedToDeleted)
        assertFalse(standard.resolve("file7.rpgsave").exists())
    }

    @Test
    fun nonRpgEngineIsNoOp() {        val (standard, tyranor) = dirs()
        standard.writeAt("global.rpgsave", "X", 1_000)
        val result = RpgSaveSync.sync(standard, tyranor, EngineType.TYRANO, store(), "g")
        assertEquals(0, result.changed)
    }
}
