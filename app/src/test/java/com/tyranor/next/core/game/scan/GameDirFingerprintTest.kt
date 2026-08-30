package com.tyranor.next.core.game.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 游戏目录指纹回归（迁移方案阶段 5 任务 5）：内容变化须改变指纹，目录不可读返回 null。 */
class GameDirFingerprintTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fingerprintIsStableForUnchangedDirectory() {
        val root = temporaryFolder.newFolder("game")
        root.resolve("root.pfs").writeText("a")
        root.resolve("system.ini").writeText("x=1")
        root.resolve("data").mkdir()

        val first = GameDirFingerprint.compute(root.absolutePath)
        val second = GameDirFingerprint.compute(root.absolutePath)

        assertEquals(first, second)
    }

    @Test
    fun fingerprintChangesWhenFileAddedOrReplaced() {
        val root = temporaryFolder.newFolder("game")
        root.resolve("root.pfs").writeText("a")
        val before = GameDirFingerprint.compute(root.absolutePath)

        root.resolve("patch.pfs.001").writeText("p")
        val afterAdd = GameDirFingerprint.compute(root.absolutePath)

        root.resolve("root.pfs").writeText("longer content")
        val afterModify = GameDirFingerprint.compute(root.absolutePath)

        assertNotEquals(before, afterAdd)
        assertNotEquals(afterAdd, afterModify)
    }

    @Test
    fun returnsNullForUnreadableOrVirtualPaths() {
        assertNull(GameDirFingerprint.compute(temporaryFolder.root.resolve("missing").absolutePath))
        assertNull(GameDirFingerprint.compute("content://com.android.externalstorage.documents/tree/primary%3Agames"))
        assertNull(GameDirFingerprint.compute(""))
    }
}
