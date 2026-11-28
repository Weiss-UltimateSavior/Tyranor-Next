package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MZ 存档同步的**全量**文件覆盖：`config` / `file0` / `file1` / `file2` / `global`。
 *
 * 既有的 MZ 同步用例只用了 `global` + `file1` 两个文件，掩盖了一类风险：MZ 的
 * `config`（游戏设置）与 `global`（槽位列表）不是「存档位」，若槽位归一或配对把它们
 * 与 `fileN` 混在一起，就会「只同步了存档位，丢掉设置与槽位列表」，或在同一次同步里
 * 互相覆盖。这里一次性把四类文件都放进去，验证每个槽位各自独立成对。
 */
class RpgSaveSyncMzAllFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** MZ 的落盘键全集；`file0` 是自动存档位，容易被 `file\d+` 的正则漏掉。 */
    private val keys = listOf("config", "file0", "file1", "file2", "global")

    private fun store(): RpgSaveSyncState =
        RpgSaveSyncState(temporaryFolder.newFolder("state-" + System.nanoTime()))

    private fun dirs(): Pair<File, File> =
        temporaryFolder.newFolder("save") to temporaryFolder.newFolder("savedata")

    private fun File.writeAt(name: String, content: String, mtime: Long): File {
        val f = resolve(name)
        f.writeText(content)
        f.setLastModified(mtime)
        return f
    }

    /** 标准侧全部就位、Tyranor 侧为空：每个文件都要各自导入，且落到同名 `.bin`。 */
    @Test
    fun importsEveryMzFileIndependently() {
        val (standard, tyranor) = dirs()
        keys.forEachIndexed { index, key ->
            standard.writeAt("$key.rmmzsave", "STD-$key", 1_000L + index)
        }

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MZ, store(), "g")

        assertEquals("五个标准存档都要导入", keys.size, result.imported)
        assertEquals(0, result.failed)
        for (key in keys) {
            val bin = tyranor.resolve("$key.bin")
            assertTrue("$key.bin 必须生成", bin.isFile)
            assertEquals("$key 的内容必须对应自己的标准文件（不能被别的键覆盖）", "STD-$key", bin.readText())
            // 标准侧保留，供 PC 使用
            assertTrue("$key.rmmzsave 必须留在标准侧", standard.resolve("$key.rmmzsave").isFile)
        }
    }

    /** Tyranor 侧全部就位、标准侧为空：每个文件都要各自导出成 `.rmmzsave`。 */
    @Test
    fun exportsEveryMzFileIndependently() {
        val (standard, tyranor) = dirs()
        keys.forEachIndexed { index, key ->
            tyranor.writeAt("$key.bin", "TYR-$key", 1_000L + index)
        }

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MZ, store(), "g")

        assertEquals("五个 Tyranor 存档都要导出", keys.size, result.exported)
        assertEquals(0, result.failed)
        for (key in keys) {
            val std = standard.resolve("$key.rmmzsave")
            assertTrue("$key.rmmzsave 必须生成", std.isFile)
            assertEquals("$key 的内容必须对应自己的 Tyranor 文件", "TYR-$key", std.readText())
            assertTrue("$key.bin 必须留在 Tyranor 侧", tyranor.resolve("$key.bin").isFile)
        }
    }

    /** 两侧同时有新内容：每个槽位按自己的时间各自决胜，不能互相干扰。 */
    @Test
    fun eachMzSlotResolvesIndependently() {
        val (standard, tyranor) = dirs()
        // config / file0 标准侧更新 → 导入；file1 / global Tyranor 侧更新 → 导出
        standard.writeAt("config.rmmzsave", "STD-config", 9_000)
        tyranor.writeAt("config.bin", "TYR-config", 1_000)
        standard.writeAt("file0.rmmzsave", "STD-file0", 9_000)
        tyranor.writeAt("file0.bin", "TYR-file0", 1_000)

        tyranor.writeAt("file1.bin", "TYR-file1", 9_000)
        standard.writeAt("file1.rmmzsave", "STD-file1", 1_000)
        tyranor.writeAt("global.bin", "TYR-global", 9_000)
        standard.writeAt("global.rmmzsave", "STD-global", 1_000)

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MZ, store(), "g")

        // 这四个槽位两侧都存在：按较新旧覆盖，计入 toTyranor / toStandard，
        // 而不是 imported / exported（后两者是「只在一侧存在」的计数）。
        assertEquals("标准侧更新的两个槽位应被导入", 2, result.toTyranor)
        assertEquals("Tyranor 侧更新的两个槽位应被导出", 2, result.toStandard)
        assertEquals(0, result.failed)

        assertEquals("STD-config", tyranor.resolve("config.bin").readText())
        assertEquals("STD-file0", tyranor.resolve("file0.bin").readText())
        assertEquals("TYR-file1", standard.resolve("file1.rmmzsave").readText())
        assertEquals("TYR-global", standard.resolve("global.rmmzsave").readText())
    }

    /**
     * 删除某个存档位时不能被 `global`/`config` 的同步顺手复活：
     * 删掉 `file2` 后，其余四个文件仍应正常存在，`file2` 不再被重新创建。
     */
    @Test
    fun deletingOneSlotDoesNotResurrectOrDisturbOthers() {
        val (standard, tyranor) = dirs()
        keys.forEach { key -> tyranor.writeAt("$key.bin", "TYR-$key", 1_000) }
        RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MZ, store(), "g")

        // 用户删掉第 2 存档位（两侧都删），再同步
        tyranor.resolve("file2.bin").delete()
        standard.resolve("file2.rmmzsave").delete()

        val result = RpgSaveSync.sync(standard, tyranor, EngineType.RPG_MZ, store(), "g")

        assertEquals("删除不应被当作需要重新导入/导出", 0, result.imported)
        assertEquals(0, result.exported)
        assertTrue("file2 不应被复活（Tyranor 侧）", !tyranor.resolve("file2.bin").exists())
        assertTrue("file2 不应被复活（标准侧）", !standard.resolve("file2.rmmzsave").exists())
        // 其余槽位不受影响
        for (key in keys.filter { it != "file2" }) {
            assertTrue("$key.bin 应保持存在", tyranor.resolve("$key.bin").isFile)
            assertTrue("$key.rmmzsave 应保持存在", standard.resolve("$key.rmmzsave").isFile)
        }
    }
}