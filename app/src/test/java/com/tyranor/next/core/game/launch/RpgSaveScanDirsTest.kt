package com.tyranor.next.core.game.launch

import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun resultHasNoDuplicateDirs() {
        val root = mvGameRoot()
        val savedata = root.resolve("savedata").apply { mkdirs() }
        val dirs = EngineLauncher.buildRpgSaveScanDirs(root.absolutePath, savedata, scoped = false)
        val keys = dirs.map { it.absolutePath.lowercase() }
        assertEquals(keys.size, keys.distinct().size)
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
