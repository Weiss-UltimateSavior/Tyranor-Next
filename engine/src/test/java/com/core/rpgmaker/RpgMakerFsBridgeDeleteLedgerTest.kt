package com.core.rpgmaker

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：新会话删除文件后，旧会话的退出刷盘不得把旧值写回来。
 *
 * 账本原来只在 `write()` 成功时记账，`remove()` 不记，于是新会话删掉键之后，旧会话查
 * `isSuperseded` 仍是 false，整批刷盘会把已经删除的键复活。这里用同根目录的两个 bridge
 * 模拟重叠的两个会话，直接走真实 Kotlin 实现。
 *
 * 路径一律用**相对路径**：`resolve()` 只把以 `/` 开头的路径当绝对路径（Android 的形态），
 * 而 Windows 的 `C:\...` 会被拼到 contentRoot 下导致写入失败——测试若传绝对路径，
 * 在 Windows 宿主上根本到不了账本断言（复审问题 6）。
 */
class RpgMakerFsBridgeDeleteLedgerTest {

    private val root: File = java.nio.file.Files.createTempDirectory("fsledger").toFile()

    private fun file(relative: String): File = File(root, relative)

    @After
    fun tearDown() {
        RpgMakerStorageWriteLedger.reset()
        root.deleteRecursively()
    }

    @Test
    fun newerSessionDeleteBlocksTheOlderSessionFlush() {
        val older = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 1L)
        val newer = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 2L)
        val path = "AppData/store.dat"

        assertTrue(older.writeText(path, "PERSISTED"))
        assertTrue(newer.remove(path))
        assertFalse("文件已被新会话删除", file(path).exists())

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
        val path = "AppData/never-written.dat"

        // 新会话对这个键做过 removeItem（文件本来不存在），旧会话正准备创建它
        assertTrue(newer.remove(path))
        assertTrue("删除意图也要进账本", older.isSuperseded(path))
    }

    @Test
    fun explicitDeleteIntentDoesNotRequireAnExistingFile() {
        val older = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 1L)
        val newer = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 2L)
        val path = "AppData/Local Storage/61.dat"

        // 真实 JS 路径：Storage.removeItem → commitStorageMutation({deletes})，
        // 目标文件根本不存在（还没物化过）也必须登记删除意图
        assertNotEquals(0, newer.commitCode(emptyMap(), deletes = listOf("61.dat")))
        assertFalse("删除意图本身不应该创建文件", file(path).exists())
        assertTrue("删除意图与文件是否存在无关", older.isSuperseded(path))
    }

    @Test
    fun directoryClearBlocksPendingKeysUnderIt() {
        val older = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 1L)
        val newer = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 2L)

        // Storage.clear()：旧会话内存里的待写键在磁盘上还不存在，只能靠目录级墓碑挡住
        assertNotEquals(0, newer.commitCode(emptyMap(), clear = true))
        assertTrue(
            "被清除目录下尚未落盘的键也必须让位",
            older.isSuperseded("AppData/Local Storage/61.dat"),
        )
        assertFalse(
            "目录之外的路径不受影响",
            older.isSuperseded("AppData/other/store.dat"),
        )
        assertFalse("发起清除的会话自己照常写", newer.isSuperseded("AppData/Local Storage/61.dat"))
    }

    @Test
    fun theOlderSessionsDeleteRetryCannotRemoveTheNewValue() {
        val older = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 1L)
        val newer = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 2L)
        val path = "AppData/queued.dat"

        // A 落盘 OLD → A 的 removeItem 失败（这里直接跳过删除动作，模拟失败留痕）
        assertTrue(older.writeText(path, "OLD"))
        // B 落盘 NEW：账本表明该路径归更新的会话所有
        assertTrue(newer.writeText(path, "NEW"))

        // A 的退出刷盘会重试删除：必须作废（返回 true 让 A 停止重试），而不是把 NEW 删掉
        assertTrue(older.remove(path))
        assertTrue("新会话的新值不能被旧会话的删除重试删掉", file(path).exists())
        assertEquals("NEW", file(path).readText())
    }

    @Test
    fun aPlainWriteStillBehavesLikeBefore() {
        val only = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 7L)
        val path = "AppData/plain.dat"
        assertTrue(only.writeText(path, "VALUE"))
        assertFalse("同一会话写自己写过的路径不算被覆盖", only.isSuperseded(path))
    }
}
