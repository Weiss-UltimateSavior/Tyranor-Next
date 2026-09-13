package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameSaveImportUnwrapTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unwrapsSingleTopLevelDirectory() {
        val extracted = temporaryFolder.newFolder("extracted")
        val wrapper = extracted.resolve("save").apply { mkdirs() }
        wrapper.resolve("global.rpgsave").writeText("a")

        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.RPG_MV)
        assertEquals(wrapper.absolutePath, root.absolutePath)
    }

    @Test
    fun keepsRootWhenMultipleTopLevelEntries() {
        val extracted = temporaryFolder.newFolder("extracted")
        extracted.resolve("global.rpgsave").writeText("a")
        extracted.resolve("file1.rpgsave").writeText("b")

        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.RPG_MV)
        assertEquals(extracted.absolutePath, root.absolutePath)
    }

    @Test
    fun keepsRootWhenSingleTopLevelEntryIsFile() {
        val extracted = temporaryFolder.newFolder("extracted")
        extracted.resolve("global.rpgsave").writeText("a")

        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.RPG_MV)
        assertEquals(extracted.absolutePath, root.absolutePath)
    }

    @Test
    fun keepsEmptyRoot() {
        val extracted = temporaryFolder.newFolder("extracted")
        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.RPG_MV)
        assertEquals(extracted.absolutePath, root.absolutePath)
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun unwrapsMultipleNestedWrapperDirs() {
        // www/save/ 这类多层包装也要剥到内容根
        val extracted = temporaryFolder.newFolder("extracted")
        val inner = extracted.resolve("www/save").apply { mkdirs() }
        inner.resolve("global.rpgsave").writeText("a")

        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.RPG_MV)
        assertEquals(inner.absolutePath, root.absolutePath)
    }

    @Test
    fun stopsUnwrappingWhenSiblingPresent() {
        val extracted = temporaryFolder.newFolder("extracted")
        val save = extracted.resolve("save").apply { mkdirs() }
        save.resolve("global.rpgsave").writeText("a")
        extracted.resolve("readme.txt").writeText("x")

        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.RPG_MV)
        assertEquals(extracted.absolutePath, root.absolutePath)
    }

    @Test
    fun nonRpgEngineKeepsStructureUnchanged() {
        // 非 RPG 引擎（如 Artemis 顶层 system/）不得剥离，否则文件结构被改写
        val extracted = temporaryFolder.newFolder("extracted")
        val system = extracted.resolve("system").apply { mkdirs() }
        system.resolve("data.xp3").writeText("a")

        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.ARTEMIS)
        assertEquals(extracted.absolutePath, root.absolutePath)
    }

    @Test
    fun doesNotUnwrapOriginalDirWrapper() {
        // 导入包仅含 original/（转化留底目录）时绝不下钻：下钻会绕过 excludeFor 对
        // 目录名的过滤，把历史留底当活动存档复制，用旧备份覆盖当前存档
        val extracted = temporaryFolder.newFolder("extracted")
        val original = extracted.resolve("original").apply { mkdirs() }
        original.resolve("global.rpgsave").writeText("STALE")

        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.RPG_MV)
        assertEquals(extracted.absolutePath, root.absolutePath)
    }

    @Test
    fun doesNotUnwrapDeletedDirWrapper() {
        // 同上：deleted/（删除归置目录）也不得穿越
        val extracted = temporaryFolder.newFolder("extracted")
        val deleted = extracted.resolve("deleted").apply { mkdirs() }
        deleted.resolve("file1.rpgsave").writeText("REMOVED")

        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.RPG_MV)
        assertEquals(extracted.absolutePath, root.absolutePath)
    }

    @Test
    fun unwrapsThroughWrapperAroundOriginalDir() {
        // save/original/ 两层包装：save 是包装目录可下钻，original 是语义目录停下——
        // 语义目录本身不被剥离，其内容也不会被误当活动存档根
        val extracted = temporaryFolder.newFolder("extracted")
        val original = extracted.resolve("save/original").apply { mkdirs() }
        original.resolve("global.rpgsave").writeText("STALE")

        val root = GameSaveManager.unwrapOuterDirs(extracted, EngineType.RPG_MV)
        assertEquals(original.parentFile?.absolutePath, root.absolutePath)
    }
}
