package com.tyranor.next.core.game.launch

import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.game.save.RpgSaveFormatConverter
import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 检测/转化目录组回归：标准格式存档实际位于 `<内容根>/save`（MV 常见 `<游戏根>/www/save`），
 * 必须在扫描范围内，否则转化永远检测不到标准档（曾出现「转化不生效」的根因）。
 */
class RpgSaveScanDirsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** `gameRoot/www/index.html` 布局（MV 常见）。 */
    private fun mvGameRoot(name: String = "魔法少女天穹法妮雅"): File {
        val root = temporaryFolder.newFolder(name)
        val www = root.resolve("www")
        www.resolve("js").mkdirs()
        www.resolve("index.html").writeText("<html></html>")
        www.resolve("js/rpg_core.js").writeText("// MV")
        return root
    }

    @Test
    fun standardDirUnderWwwIsIncludedForNonScoped() {
        val root = mvGameRoot()
        val savedata = root.resolve("savedata").apply { mkdirs() }

        val dirs = EngineLauncher.buildRpgSaveScanDirs(root.absolutePath, savedata, scoped = false)

        // 首个必须是生效目录（转化输出落点）
        assertEquals(savedata.absolutePath, dirs.first().absolutePath)
        // 标准侧 www/save 必须被扫描
        assertTrue(
            "扫描目录必须包含 <内容根>/save（www/save）",
            dirs.any { it.absolutePath == root.resolve("www/save").absolutePath },
        )
    }

    @Test
    fun standardDirAtGameRootIsIncludedWhenNoWww() {
        // MZ 无 www：内容根 = 游戏根，标准侧为 <游戏根>/save
        val root = temporaryFolder.newFolder("MzGame")
        root.resolve("index.html").writeText("<html></html>")
        val savedata = root.resolve("savedata").apply { mkdirs() }

        val dirs = EngineLauncher.buildRpgSaveScanDirs(root.absolutePath, savedata, scoped = false)

        assertTrue(dirs.any { it.absolutePath == root.resolve("save").absolutePath })
    }

    @Test
    fun scopedScanAddsGameRootSavedataForMigration() {
        val root = mvGameRoot()
        val external = temporaryFolder.newFolder("external/tyrano/G")

        val dirs = EngineLauncher.buildRpgSaveScanDirs(root.absolutePath, external, scoped = true)

        assertEquals(external.absolutePath, dirs.first().absolutePath)
        // 独立存档时游戏根旧档仍要被检测（引擎只读外部目录，转化是唯一迁移通道）。
        // 注意：大小写不敏感文件系统上 `Savedata` 与 `savedata` 是同一目录，去重后只留一个，
        // 故这里按大小写不敏感比较，不假设两者同时出现。
        val keys = dirs.map { it.absolutePath.lowercase() }
        assertTrue(keys.contains(root.resolve("savedata").absolutePath.lowercase()))
        assertTrue(keys.contains(root.resolve("www/save").absolutePath.lowercase()))
    }

    /**
     * 区分大小写的文件系统上，`savedata/`（生效目录）与 `Savedata/`（历史兼容目录）
     * 是两个不同目录，必须都保留——只放在 `Savedata/` 的旧存档否则漏检、漏转化。
     *
     * 该用例只在大小写敏感的文件系统上有意义（Linux/Android 真机、CI）；大小写不敏感
     * 的文件系统上两者本就是同一目录，会自动跳过。
     */
    @Test
    fun keepsSavedataAndSavedataDistinctOnCaseSensitiveFilesystem() {
        val root = mvGameRoot()
        val lower = File(root, "savedata")
        val upper = File(root, "Savedata")
        // 探测文件系统是否区分大小写：创建小写目录后，大写拼写是否指向同一目录
        lower.mkdirs()
        assumeTrue(
            "本文件系统不区分大小写，跳过（该场景仅存在于 Linux/Android 真机）",
            !upper.exists(),
        )
        upper.mkdirs()
        assertTrue(upper.isDirectory)

        val dirs = EngineLauncher.buildRpgSaveScanDirs(root.absolutePath, lower, scoped = false)

        assertTrue(
            "生效目录 savedata/ 必须保留",
            dirs.any { it.canonicalPath == lower.canonicalPath },
        )
        assertTrue(
            "历史兼容目录 Savedata/ 必须保留（只放在其中的旧存档否则漏检）",
            dirs.any { it.canonicalPath == upper.canonicalPath },
        )
    }

    /**
     * 去重键**不得折叠已存在目录的大小写**——这是修复的核心性质，且在任何文件系统上
     * 都可验证（Windows 上 `canonicalPath` 同样返回磁盘上的真实大小写）。
     * 若有人改回无条件 `lowercase`，本用例立即失败。
     */
    @Test
    fun dedupKeyPreservesCaseOfExistingDirectories() {
        val root = mvGameRoot()
        val mixed = File(root, "MixedCaseDir").apply { mkdirs() }
        assumeTrue("目录未创建成功", mixed.isDirectory)

        val key = RpgSaveFormat.saveDirDedupKey(mixed)

        assertTrue(
            "已存在目录的去重键必须保留真实大小写（否则区分大小写的文件系统上会误合并不同目录）：$key",
            key.contains("MixedCaseDir"),
        )
        // 同目录的另一种拼写仍应折叠为同一条（大小写不敏感文件系统上自然同一路径）
        val sameDirOtherSpelling = File(root, "mixedcasedir")
        if (sameDirOtherSpelling.isDirectory) {
            assertEquals(
                "同一目录的不同拼写仍需折叠",
                key,
                RpgSaveFormat.saveDirDedupKey(sameDirOtherSpelling),
            )
        }
    }

    /**
     * 不存在的目录按小写折叠：避免同一目录的两种拼写重复入列（此时目录为空，不影响检测），
     * 同时不误伤上面那类「真实存在的两个目录」。
     */
    @Test
    fun nonexistentSpellingsDoNotDuplicateTheScanList() {
        val root = mvGameRoot()
        val savedata = root.resolve("savedata").apply { mkdirs() }

        val dirs = EngineLauncher.buildRpgSaveScanDirs(root.absolutePath, savedata, scoped = false)

        // www/save 与 www/Save 都不存在时只应出现一条（避免重复扫描）
        val saveEntries = dirs.filter { it.name.equals("save", ignoreCase = true) }
        assertEquals("不存在的大小写拼写不应重复入列", 1, saveEntries.size)
    }

    @Test
    fun resultHasNoDuplicateDirs() {
        val root = mvGameRoot()
        val savedata = root.resolve("savedata").apply { mkdirs() }
        val dirs = EngineLauncher.buildRpgSaveScanDirs(root.absolutePath, savedata, scoped = false)
        val keys = dirs.map { it.absolutePath.lowercase() }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun endToEndConvertsStandardSavesFromWwwSaveIntoSavedata() {
        // 用户场景端到端：标准档在 www/save，转化必须落到引擎读取的 <游戏根>/savedata，
        // 且文件名为引擎实际落盘名（MV 为 key_<sha256(键)>.bin）
        val root = mvGameRoot()
        val savedata = root.resolve("savedata").apply { mkdirs() }
        val wwwSave = root.resolve("www/save").apply { mkdirs() }
        wwwSave.resolve("global.rpgsave").writeText("GLOBAL")
        wwwSave.resolve("file21.rpgsave").writeText("FILE21")

        val dirs = EngineLauncher.buildRpgSaveScanDirs(root.absolutePath, savedata, scoped = false)
        val result = RpgSaveFormatConverter.convert(
            outputDir = dirs.first(),
            sourceDirs = dirs,
            engine = EngineType.RPG_MV,
        )

        assertEquals(2, result.converted)
        val globalName = requireNotNull(RpgSaveFormat.tyranorFileNameForStandard("global.rpgsave", EngineType.RPG_MV))
        val file21Name = requireNotNull(RpgSaveFormat.tyranorFileNameForStandard("file21.rpgsave", EngineType.RPG_MV))
        // 落点在引擎读取的 savedata，而不是标准目录
        assertEquals("GLOBAL", savedata.resolve(globalName).readText())
        assertEquals("FILE21", savedata.resolve(file21Name).readText())
        // 标准侧源文件被留底（original/），不丢失
        assertTrue(wwwSave.resolve("original/global.rpgsave").isFile)
    }

    @Test
    fun standardSaveDetectionFindsFilesInWwwSave() {
        // 端到端：标准档放在 www/save 时，按扫描目录组必须能检测到
        val root = mvGameRoot()
        val savedata = root.resolve("savedata").apply { mkdirs() }
        val wwwSave = root.resolve("www/save").apply { mkdirs() }
        wwwSave.resolve("global.rpgsave").writeText("G")
        wwwSave.resolve("file21.rpgsave").writeText("F21")

        val dirs = EngineLauncher.buildRpgSaveScanDirs(root.absolutePath, savedata, scoped = false)
        val detection = RpgSaveFormat.detectInDirs(dirs, EngineType.RPG_MV)

        assertEquals(2, detection.convertibleCount)
    }
}
