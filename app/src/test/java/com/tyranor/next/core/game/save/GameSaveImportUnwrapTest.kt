package com.tyranor.next.core.game.save

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

        val root = GameSaveManager.unwrapOuterDirs(extracted)
        assertEquals(wrapper.absolutePath, root.absolutePath)
    }

    @Test
    fun keepsRootWhenMultipleTopLevelEntries() {
        val extracted = temporaryFolder.newFolder("extracted")
        extracted.resolve("global.rpgsave").writeText("a")
        extracted.resolve("file1.rpgsave").writeText("b")

        val root = GameSaveManager.unwrapOuterDirs(extracted)
        assertEquals(extracted.absolutePath, root.absolutePath)
    }

    @Test
    fun keepsRootWhenSingleTopLevelEntryIsFile() {
        val extracted = temporaryFolder.newFolder("extracted")
        extracted.resolve("global.rpgsave").writeText("a")

        val root = GameSaveManager.unwrapOuterDirs(extracted)
        assertEquals(extracted.absolutePath, root.absolutePath)
    }

    @Test
    fun keepsEmptyRoot() {
        val extracted = temporaryFolder.newFolder("extracted")
        val root = GameSaveManager.unwrapOuterDirs(extracted)
        assertEquals(extracted.absolutePath, root.absolutePath)
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun unwrapsMultipleNestedWrapperDirs() {
        // www/save/ 这类多层包装也要剥到内容根
        val extracted = temporaryFolder.newFolder("extracted")
        val inner = extracted.resolve("www/save").apply { mkdirs() }
        inner.resolve("global.rpgsave").writeText("a")

        val root = GameSaveManager.unwrapOuterDirs(extracted)
        assertEquals(inner.absolutePath, root.absolutePath)
    }

    @Test
    fun stopsUnwrappingWhenSiblingPresent() {
        val extracted = temporaryFolder.newFolder("extracted")
        val save = extracted.resolve("save").apply { mkdirs() }
        save.resolve("global.rpgsave").writeText("a")
        extracted.resolve("readme.txt").writeText("x")

        val root = GameSaveManager.unwrapOuterDirs(extracted)
        assertEquals(extracted.absolutePath, root.absolutePath)
    }
}
