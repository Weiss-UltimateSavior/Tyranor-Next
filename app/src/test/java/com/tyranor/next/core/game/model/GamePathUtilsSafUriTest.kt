package com.tyranor.next.core.game.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GamePathUtils.documentIdToPath] / [GamePathUtils.isRemovableStoragePath]：
 * 主存储根按进程视角解析（issue #152）。单 user 环境主存储为 /storage/emulated/0，
 * 三星安全文件夹等隔离 user 下挂载为 /storage/emulated/<uid>（如 150），
 * SAF documentId 的 `primary:` 卷标识不随 user 变化。完整 URI 解析依赖
 * android.net.Uri（设备验证覆盖），此处按项目惯例覆盖纯决策部分。
 */
class GamePathUtilsSafUriTest {

    private val secureFolderRoot = "/storage/emulated/150"

    @Test
    fun primaryDocumentIdMapsToInjectedRoot() {
        assertEquals(
            "$secureFolderRoot/Games/ATRI",
            GamePathUtils.documentIdToPath("primary:Games/ATRI", secureFolderRoot),
        )
    }

    @Test
    fun primaryRootItselfMapsWithoutTrailingSlash() {
        assertEquals(secureFolderRoot, GamePathUtils.documentIdToPath("primary:", secureFolderRoot))
        assertEquals(secureFolderRoot, GamePathUtils.documentIdToPath("primary", secureFolderRoot))
    }

    @Test
    fun primaryMatchingIsCaseInsensitive() {
        assertEquals(
            "$secureFolderRoot/Games/ATRI",
            GamePathUtils.documentIdToPath("PRIMARY:Games/ATRI", secureFolderRoot),
        )
    }

    @Test
    fun legacyEmulatedZeroStillResolvedWhenInjected() {
        // 显式注入 user 0 根：常规环境行为与旧实现一致
        assertEquals(
            "/storage/emulated/0/Games/fsn",
            GamePathUtils.documentIdToPath("primary:Games/fsn", "/storage/emulated/0"),
        )
    }

    @Test
    fun nonPrimaryVolumeStillUsesStorageVolumePath() {
        assertEquals("/storage/0000-0001/Games/SD", GamePathUtils.documentIdToPath("0000-0001:Games/SD", secureFolderRoot))
        assertEquals("/storage/0000-0001", GamePathUtils.documentIdToPath("0000-0001:", secureFolderRoot))
        assertEquals("/storage/removable1", GamePathUtils.documentIdToPath("removable1", secureFolderRoot))
    }

    @Test
    fun emptyVolumeReturnsNull() {
        assertNull(GamePathUtils.documentIdToPath("", secureFolderRoot))
        assertNull(GamePathUtils.documentIdToPath(":Games/ATRI", secureFolderRoot))
    }

    @Test
    fun traversalSegmentsAreRejected() {
        // documentId 来自 provider、不可信：拒绝 .. 段与反斜杠，映射不越出卷根
        assertNull(GamePathUtils.documentIdToPath("primary:../etc", secureFolderRoot))
        assertNull(GamePathUtils.documentIdToPath("primary:Games/../../etc", secureFolderRoot))
        assertNull(GamePathUtils.documentIdToPath("primary:..", secureFolderRoot))
        assertNull(GamePathUtils.documentIdToPath("primary:Games/../..", secureFolderRoot))
        assertNull(GamePathUtils.documentIdToPath("0000-0001:Games/../../etc", secureFolderRoot))
        assertNull(GamePathUtils.documentIdToPath("primary:Games\\..\\x", secureFolderRoot))
        // 非穿越的相邻写法仍正常映射
        assertEquals(
            "$secureFolderRoot/Games/..x",
            GamePathUtils.documentIdToPath("primary:Games/..x", secureFolderRoot),
        )
    }

    @Test
    fun normalizedSegmentsCollapseDotAndEmpty() {
        // "." 段与空段规范化消除，结果仍位于卷根内
        assertEquals(
            "$secureFolderRoot/Games/ATRI",
            GamePathUtils.documentIdToPath("primary:Games/./ATRI", secureFolderRoot),
        )
        assertEquals(
            "$secureFolderRoot/Games/ATRI",
            GamePathUtils.documentIdToPath("primary:Games//ATRI", secureFolderRoot),
        )
        assertEquals(
            "$secureFolderRoot",
            GamePathUtils.documentIdToPath("primary:./", secureFolderRoot),
        )
    }

    @Test
    fun emulatedVolumesAreNeverRemovable() {
        assertFalse(GamePathUtils.isRemovableStoragePath("/storage/emulated/0/Games"))
        assertFalse(GamePathUtils.isRemovableStoragePath("/storage/emulated/150/Games"))
        assertFalse(GamePathUtils.isRemovableStoragePath("/storage/emulated/150"))
        assertFalse(GamePathUtils.isRemovableStoragePath("/storage/emulated/0"))
        // 仿冒前缀（emulated/150abc 不是卷名）仍应按可移动存储处理
        assertTrue(GamePathUtils.isRemovableStoragePath("/storage/emulated/150abc/Games"))
    }

    @Test
    fun realRemovableVolumeDetected() {
        assertTrue(GamePathUtils.isRemovableStoragePath("/storage/0000-0001/Games"))
        assertTrue(GamePathUtils.isRemovableStoragePath("/storage/0000-0001"))
        assertFalse(GamePathUtils.isRemovableStoragePath("/data/data/x"))
        assertFalse(GamePathUtils.isRemovableStoragePath("\\storage\\emulated\\150\\Games"))
    }
}
