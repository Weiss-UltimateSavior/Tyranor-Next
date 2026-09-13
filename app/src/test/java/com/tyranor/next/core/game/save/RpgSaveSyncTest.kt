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
        // mtime 相同、长度也相同（长度校验挡不住）但内容不同：必须靠内容哈希识别，不能跳过
        val (standard, tyranor) = dirs()
        standard.writeAt("global.rpgsave", "PC-CONTENT", 5_000)
        tyranor.writeAt("RPG Global.bin", "PHONE-DIFF", 5_000)

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
        assertEquals(0, result.changed)    }

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

    @Test
    fun conflictingDuplicateSlotAcrossDirsIsQuarantined() {
        // 同一槽位出现在两个标准侧目录且内容不一致：不得静默丢弃后者——
        // 未选中的副本须隔离进其所在目录的 deleted/，同步以首选目录为准
        val lower = temporaryFolder.newFolder("save")
        val alt = temporaryFolder.newFolder("save_alt")
        val tyranor = temporaryFolder.newFolder("savedata")
        lower.writeAt("global.rpgsave", "PREFERRED", 1_000)
        alt.writeAt("global.rpgsave", "DIVERGED", 2_000)

        val result = RpgSaveSync.sync(listOf(lower, alt), tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(1, result.movedToDeleted)
        assertTrue(alt.resolve("deleted/global.rpgsave").isFile)
        assertEquals("DIVERGED", alt.resolve("deleted/global.rpgsave").readText())
        assertFalse(alt.resolve("global.rpgsave").exists())
        assertEquals("PREFERRED", tyranor.resolve("RPG Global.bin").readText())
    }

    @Test
    fun duplicateSlotWithSameContentIsNotQuarantined() {
        // 内容一致的等价副本不算冲突：任取其一，未选中者原样保留
        val lower = temporaryFolder.newFolder("save")
        val alt = temporaryFolder.newFolder("save_alt")
        val tyranor = temporaryFolder.newFolder("savedata")
        lower.writeAt("global.rpgsave", "SAME", 1_000)
        alt.writeAt("global.rpgsave", "SAME", 2_000)

        val result = RpgSaveSync.sync(listOf(lower, alt), tyranor, EngineType.RPG_MV, store(), "g")

        assertEquals(0, result.movedToDeleted)
        assertEquals(1, result.imported)
        assertTrue(alt.resolve("global.rpgsave").isFile)
    }

    @Test
    fun corruptManifestAbortsSyncWithoutImporting() {
        // 清单损坏不能当成「首次同步」继续：已删除记录丢失后会把已删存档当新存档导入。
        // 读取失败必须中止本轮同步（不做任何文件改动、如实报失败）。
        val (standard, tyranor) = dirs()
        val stateDir = temporaryFolder.newFolder("state")
        val store = RpgSaveSyncState(stateDir)
        standard.writeAt("file1.rpgsave", "V1", 1_000)
        tyranor.writeAt("RPG File1.bin", "V1", 1_000)
        RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        stateDir.listFiles().orEmpty().forEach { it.writeText("{corrupt") }
        // 清单损坏时，同槽位冲突副本也不得被隔离——隔离必须发生在清单验证成功之后
        val alt = temporaryFolder.newFolder("save_alt")
        standard.writeAt("global.rpgsave", "PREFERRED", 2_000)
        alt.writeAt("global.rpgsave", "DIVERGED", 3_000)
        standard.writeAt("file2.rpgsave", "V2", 2_000)

        val result = RpgSaveSync.sync(listOf(standard, alt), tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, result.failed)
        assertEquals(0, result.imported)
        assertEquals(0, result.changed)
        assertFalse(tyranor.resolve("RPG File2.bin").exists())
        // 冲突副本原位保留：清单验证失败前不做任何文件改动
        assertTrue(alt.resolve("global.rpgsave").isFile)
        assertFalse(alt.resolve("deleted").exists())
    }

    /** 在 [stateDir] 里写入一份指定内容的清单（覆盖首个 .json 文件；无则跳过）。 */
    private fun overwriteManifest(stateDir: File, json: String) {
        val files = stateDir.listFiles().orEmpty().filter { it.isFile }
        if (files.isEmpty()) error("manifest file not found in ${stateDir.absolutePath}")
        files.forEach { it.writeText(json) }
    }

    @Test
    fun manifestWithoutSlotsObjectAbortsSync() {
        // 缺 slots 键的清单是损坏形态：绝不能当成空清单（首次同步）继续
        val (standard, tyranor) = dirs()
        val stateDir = temporaryFolder.newFolder("state")
        val store = RpgSaveSyncState(stateDir)
        standard.writeAt("file1.rpgsave", "V1", 1_000)
        tyranor.writeAt("RPG File1.bin", "V1", 1_000)
        RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        overwriteManifest(stateDir, """{"other":{}}""")

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, result.failed)
        assertEquals(0, result.changed)
    }

    @Test
    fun manifestWithNonObjectSlotEntryAbortsSync() {
        // 槽位值不是对象（或缺 std/tyr 字段）同样是损坏清单：静默跳过会丢该槽位的删除历史
        val (standard, tyranor) = dirs()
        val stateDir = temporaryFolder.newFolder("state")
        val store = RpgSaveSyncState(stateDir)
        standard.writeAt("file1.rpgsave", "V1", 1_000)
        tyranor.writeAt("RPG File1.bin", "V1", 1_000)
        RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        overwriteManifest(stateDir, """{"slots":{"file1":"bogus"}}""")

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, result.failed)
        assertEquals(0, result.changed)
    }

    @Test
    fun manifestWithNonBooleanExistsFlagAbortsSync() {
        // 审查跟进 #1：std_x/tyr_x 字段存在但类型不是 Boolean 属于损坏清单——
        // 静默回退会让 tyr_x 误成 false，把「已删除」当「新建」而复活
        val (standard, tyranor) = dirs()
        val stateDir = temporaryFolder.newFolder("state")
        val store = RpgSaveSyncState(stateDir)
        standard.writeAt("file1.rpgsave", "V1", 1_000)
        tyranor.writeAt("RPG File1.bin", "V1", 1_000)
        RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        overwriteManifest(stateDir, """{"slots":{"file1":{"std":1000,"tyr":1000,"tyr_x":"yes"}}}""")

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, result.failed)
        assertEquals(0, result.changed)
    }

    @Test
    fun manifestWithMissingRequiredFieldAbortsSync() {
        // 槽位条目缺 std/tyr 必需字段：视为损坏，中止同步
        val (standard, tyranor) = dirs()
        val stateDir = temporaryFolder.newFolder("state")
        val store = RpgSaveSyncState(stateDir)
        standard.writeAt("file1.rpgsave", "V1", 1_000)
        tyranor.writeAt("RPG File1.bin", "V1", 1_000)
        RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        overwriteManifest(stateDir, """{"slots":{"file1":{"std":1000}}}""")

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, result.failed)
        assertEquals(0, result.changed)
    }

    @Test
    fun inlineFailureKeepsManifestHistoryForDeletionSemantics() {
        // H2 回归：moveToDeleted 失败的内联分支也必须回填清单历史——否则该槽位从清单消失，
        // 下一轮 existedOnTyranor==false 会把「Tyranor 侧已删除」误判为「新建」而复活
        val (standard, tyranor) = dirs()
        val store = store()
        standard.writeAt("file7.rpgsave", "S", 1_000)
        tyranor.writeAt("RPG File7.bin", "S", 1_000)
        RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        // 用同名文件占位 deleted/，迫使 moveToDeleted 失败
        standard.resolve("deleted").writeText("block")
        tyranor.resolve("RPG File7.bin").delete()
        val failedRound = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, failedRound.failed)
        assertEquals(0, failedRound.movedToDeleted)
        // 关键断言：清单仍记录该槽位曾在 Tyranor 存在
        val state = when (val loaded = store.load("g")) {
            is RpgSaveSyncState.LoadResult.Loaded -> loaded.slots
            else -> error("manifest must still be readable")
        }
        assertEquals(true, state["file7"]?.tyranorExists)

        // 清除占位后重试：应走「已删除」分支，而不是把标准文件当新存档导入
        standard.resolve("deleted").delete()
        val retry = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MV, store, "g")

        assertEquals(1, retry.movedToDeleted)
        assertEquals(0, retry.imported)
        assertFalse(tyranor.resolve("RPG File7.bin").exists())
    }
}
