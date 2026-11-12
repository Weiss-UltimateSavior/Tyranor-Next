package com.core.rpgmaker

import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：新会话删除文件后，旧会话的退出刷盘不得把旧值写回来。
 *
 * 账本原来只在 `write()` 成功时记账，`remove()` 不记，于是新会话删掉键之后，旧会话查
 * `isSuperseded` 仍是 false，整批刷盘会把已经删除的键复活。这里用同根目录的两个 bridge
 * 模拟重叠的两个会话，直接走真实 Kotlin 实现。
 */
class RpgMakerFsBridgeDeleteLedgerTest {

    private val root: File = java.nio.file.Files.createTempDirectory("fsledger").toFile()

    @After
    fun tearDown() {
        RpgMakerStorageWriteLedger.reset()
        root.deleteRecursively()
    }

    @Test
    fun newerSessionDeleteBlocksTheOlderSessionFlush() {
        val older = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 1L)
        val newer = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 2L)
        val path = File(root, "AppData/store.dat").absolutePath

        assertTrue(older.writeText(path, "PERSISTED"))
        assertTrue(newer.remove(path))
        assertFalse("文件已被新会话删除", File(path).exists())

        assertTrue(
            "新会话删除后，旧会话必须让位，否则会把已删的键写回来",
            older.isSuperseded(path),
        )
        // 写入方自己（同一会话）不算被覆盖
        assertFalse(newer.isSuperseded(path))
    }

    @Test
    fun deletingAPathThatNeverExistedStillBlocksTheOlderSession() {
        val older = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 1L)
        val newer = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 2L)
        val path = File(root, "AppData/never-written.dat").absolutePath

        // 新会话对这个键做过 removeItem（文件本来不存在），旧会话正准备创建它
        assertTrue(newer.remove(path))
        assertTrue("删除意图也要进账本", older.isSuperseded(path))
    }

    @Test
    fun aPlainWriteStillBehavesLikeBefore() {
        val only = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 7L)
        val path = File(root, "AppData/plain.dat").absolutePath
        assertTrue(only.writeText(path, "VALUE"))
        assertFalse("同一会话写自己写过的路径不算被覆盖", only.isSuperseded(path))
    }
}
