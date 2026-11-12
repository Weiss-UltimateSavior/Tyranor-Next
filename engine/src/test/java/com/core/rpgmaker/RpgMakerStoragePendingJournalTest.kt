package com.core.rpgmaker

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：文档销毁时没写完的 localStorage 数据必须能被下一次文档初始化重放。
 *
 * 背景（复审问题 1/2/5）：`setItem` 是内存镜像 + 合并写，宿主在退出或重建时会销毁
 * WebView——JS 定时器与内存镜像一起消失。`flushAll` 一旦没写完（预算耗尽、单键 I/O
 * 失败），剩下的值就永久丢失。修复把它交给原生恢复队列，由新文档重放。
 *
 * 这里直接跑真实 Kotlin 实现（不经过 WebView）：覆盖恢复、删除重试、条目合并、
 * 快照失配（入队后被改写）、账本让位与非法文件名。
 */
class RpgMakerStoragePendingJournalTest {

    private val root: File = java.nio.file.Files.createTempDirectory("pendingjournal").toFile()
    private val storeDir = File(File(root, "AppData"), "Local Storage")
    private val journalFile: File get() = File(storeDir, RpgMakerStoragePendingJournal.FILE_NAME)

    private fun bridge(session: Long) = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = session)

    private fun storedFile(name: String, content: String? = null): File =
        File(storeDir, name).apply {
            parentFile?.mkdirs()
            if (content != null) writeText(content)
        }

    @After
    fun tearDown() {
        RpgMakerStorageWriteLedger.reset()
        root.deleteRecursively()
    }

    @Test
    fun stagedValueIsRestoredOnTheNextDocument() {
        val name = "61.dat"
        storedFile(name, "OLD")

        assertTrue(
            "入队必须成功（这是「宿主销毁前最后的持久化」）",
            bridge(1L).stagePendingStorage("""{"values":{"$name":"PENDING"},"deletes":[]}"""),
        )
        assertTrue("待写数据已落到恢复队列", journalFile.isFile)

        // 下一次文档初始化（新会话期号）
        assertEquals(1, bridge(2L).replayPendingStorage())
        assertEquals("PENDING", storedFile(name).readText())
        assertFalse("重放完成后队列清空，避免下次重复应用", journalFile.exists())
    }

    @Test
    fun stagedDeletesAreRetriedOnTheNextDocument() {
        val name = "62.dat"
        val target = storedFile(name, "KEEP")

        assertTrue(bridge(1L).stagePendingStorage("""{"values":{},"deletes":["$name"]}"""))
        assertEquals(1, bridge(2L).replayPendingStorage())
        assertFalse("删除意图必须跨文档保留并按原样重放", target.exists())
    }

    @Test
    fun replaySkipsEntriesWhoseFileChangedAfterStaging() {
        val name = "63.dat"
        storedFile(name, "OLD")

        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"$name":"PENDING"},"deletes":[]}"""))
        // 入队之后有人写了新值（新会话/插件自己）：快照 (存在, mtime, size) 失配
        storedFile(name, "NEWER-VALUE")

        assertEquals(0, bridge(2L).replayPendingStorage())
        assertEquals("NEWER-VALUE", storedFile(name).readText())
    }

    @Test
    fun replayDetectsASameLengthRewriteEvenWhenTheMtimeIsRestored() {
        val name = "88.dat"
        val target = storedFile(name, "AAAA")
        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"$name":"PENDING"},"deletes":[]}"""))
        val stagedMtime = JSONObject(journalFile.readText())
            .getJSONObject("entries").getJSONObject(name).getLong("m")

        // 同长度改写，并把 mtime 恢复成入队时的值：只靠 (存在, size, mtime) 分不出来
        target.writeText("BBBB")
        assertTrue("测试前提：mtime 能被恢复", target.setLastModified(stagedMtime))
        assertEquals("测试前提：(存在, size, mtime) 与入队快照完全一致", stagedMtime, target.lastModified())

        assertEquals("同长度改写只能靠内容指纹（CRC32）发现", 0, bridge(2L).replayPendingStorage())
        assertEquals("BBBB", target.readText())
    }

    @Test
    fun replaySkipsPathsSupersededByANewerSession() {
        val name = "64.dat"
        val target = storedFile(name, "OLD")

        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"$name":"PENDING"},"deletes":[]}"""))
        // 文件本身没有变化（快照仍匹配），但账本表明更新的会话已经写过这个路径
        RpgMakerStorageWriteLedger.record(target.canonicalPath, 5L)

        assertEquals(0, bridge(2L).replayPendingStorage())
        assertEquals("OLD", storedFile(name).readText())
    }

    @Test
    fun discardingAnEntryKeepsADeletedKeyDeleted() {
        val removed = "81.dat"
        val kept = "82.dat"
        // 队列里存着两个键的待写值，目标文件此刻都还不存在（快照 = 不存在）
        assertTrue(
            bridge(1L).stagePendingStorage(
                """{"values":{"$removed":"PENDING","$kept":"KEPT"},"deletes":[]}""",
            ),
        )
        assertTrue(journalFile.isFile)

        // Storage.removeItem(removed)：删除意图 + 只丢弃该条目（期望值 null = 不比对内容）
        assertTrue(bridge(1L).discardPendingStorage("""{"$removed":null}"""))

        assertEquals("只应恢复未删除的那一条", 1, bridge(2L).replayPendingStorage())
        assertFalse("入队后被删除的键不能被重放写回来", File(storeDir, removed).exists())
        assertEquals("KEPT", File(storeDir, kept).readText())
    }

    @Test
    fun discardDoesNotDropAnEntryAnotherDocumentReplaced() {
        val name = "85.dat"
        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"$name":"A"},"deletes":[]}"""))
        // 另一个文档用新值覆盖了同名条目
        assertTrue(bridge(2L).stagePendingStorage("""{"values":{"$name":"B"},"deletes":[]}"""))
        // 旧文档按自己的内容丢弃：内容已不同，不能把新条目一起删掉
        assertTrue(bridge(2L).discardPendingStorage("""{"$name":"A"}"""))
        assertEquals(1, bridge(3L).replayPendingStorage())
        assertEquals("B", File(storeDir, name).readText())
    }

    @Test
    fun anOlderSessionCannotOverwriteANewerQueuedValue() {
        val name = "95.dat"
        assertTrue(bridge(2L).stagePendingStorage("""{"values":{"$name":"NEW"},"deletes":[]}"""))
        // 旧文档晚到的入队（同一文件名）：值与会话都更旧，必须让位
        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"$name":"OLD"},"deletes":[]}"""))
        assertEquals(1, bridge(3L).replayPendingStorage())
        assertEquals("NEW", File(storeDir, name).readText())
    }

    @Test
    fun anOlderSessionCannotDiscardANewerQueuedValue() {
        val name = "96.dat"
        assertTrue(bridge(2L).stagePendingStorage("""{"values":{"$name":"NEW"},"deletes":[]}"""))
        // 旧文档的 removeItem 不能作废新文档已入队的值
        assertTrue(bridge(1L).discardPendingStorage("""{"$name":null}"""))
        assertEquals(1, bridge(3L).replayPendingStorage())
        assertEquals("NEW", File(storeDir, name).readText())
    }

    @Test
    fun restagingTheSameValueKeepsTheFirstFingerprint() {
        val name = "94.dat"
        // 入队时目标文件不存在
        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"$name":"A"},"deletes":[]}"""))
        // 入队之后有人写了这个文件（快照不再是「不存在」）
        storedFile(name, "WRITTEN")
        // 再次入队同一个值：必须保留首次指纹，否则旧条目会「看起来仍然有效」
        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"$name":"A"},"deletes":[]}"""))
        assertEquals(0, bridge(2L).replayPendingStorage())
        assertEquals("WRITTEN", storedFile(name).readText())
    }

    @Test
    fun replayKeepsEntriesThatCannotBeWritten() {
        val ok = "97.dat"
        val broken = "98.dat"
        // 目标路径是非空目录：原子替换失败，该条必须留在队列里下次再试
        File(storeDir, broken).mkdirs()
        File(File(storeDir, broken), "child").writeText("x")
        assertTrue(
            bridge(1L).stagePendingStorage(
                """{"values":{"$ok":"OK","$broken":"BROKEN"},"deletes":[]}""",
            ),
        )

        assertEquals("能写的那条照常恢复", 1, bridge(2L).replayPendingStorage())
        assertEquals("OK", File(storeDir, ok).readText())
        assertTrue("失败条目必须保留到下次", journalFile.exists())
        val queued = JSONObject(journalFile.readText())
            .getJSONObject("entries").keys().asSequence().toList()
        assertEquals("队里只剩失败的那条", listOf(broken), queued)

        // 目标仍然不可写：继续留在队列里，且返回 0（没恢复成功）
        assertEquals(0, bridge(3L).replayPendingStorage())
        assertTrue(journalFile.exists())
    }

    @Test
    fun clearingTheQueueDropsEverything() {
        val first = "83.dat"
        val second = "84.dat"
        assertTrue(
            bridge(1L).stagePendingStorage("""{"values":{"$first":"A","$second":"B"},"deletes":[]}"""),
        )
        assertTrue(bridge(1L).clearPendingStorage())
        assertEquals(0, bridge(2L).replayPendingStorage())
        assertFalse(File(storeDir, first).exists())
        assertFalse(File(storeDir, second).exists())
    }

    @Test
    fun epochsArePerEntrySoANewProcessSessionIsNotMasked() {
        // 上一进程留下的条目期号很大（期号在每个进程内从 1 重新计数）
        assertTrue(bridge(9L).stagePendingStorage("""{"values":{"61.dat":"OLD_PROCESS"},"deletes":[]}"""))
        // 本进程 doc1 入队：每条只记自己的期号，不能被整份队列的 max 抬成 9
        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"62.dat":"NEW_SESSION"},"deletes":[]}"""))
        // doc2 清空存储（目录级墓碑，期号 2）：本进程 doc1 的条目必须让位
        assertTrue(bridge(2L).recordClear("AppData/Local Storage"))
        assertEquals("只有上一进程那条不受本进程墓碑约束", 1, bridge(2L).replayPendingStorage())
        assertFalse("本进程清除之前入队的键不能被重放写回", File(storeDir, "62.dat").exists())
    }

    @Test
    fun stagingMergesWithEntriesAlreadyQueued() {
        val first = "70.dat"
        val second = "71.dat"
        storedFile(first)
        storedFile(second)

        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"$first":"A"},"deletes":[]}"""))
        assertTrue(bridge(1L).stagePendingStorage("""{"values":{"$second":"B"},"deletes":[]}"""))

        assertEquals(2, bridge(2L).replayPendingStorage())
        assertEquals("A", storedFile(first).readText())
        assertEquals("B", storedFile(second).readText())
    }

    @Test
    fun invalidFileNamesNeverEscapeTheStoreDirectory() {
        // 恢复队列的文件名只可能是「键的十六进制 + .dat」或哈希名：其它形态一律拒绝整份
        // 载荷（返回 false，让宿主记到真实丢失数），否则队列本身就成了越界写入通道
        assertFalse(
            bridge(1L).stagePendingStorage(
                """{"values":{"../escape.dat":"X","../../etc/passwd":"X","ok/../../x.dat":"X"},"deletes":[]}""",
            ),
        )
        assertFalse("被拒载荷不应产生队列文件", journalFile.exists())
        assertEquals(0, bridge(2L).replayPendingStorage())
        assertFalse(File(root, "AppData/escape.dat").exists())
        assertFalse(File(root, "escape.dat").exists())
    }

    @Test
    fun emptyPayloadIsAcceptedWithoutTouchingTheJournal() {
        // JS 侧偶尔会传空表（例如删除意图已在新值写入时作废）：不能因此报失败
        assertTrue(bridge(1L).stagePendingStorage("""{"values":{},"deletes":[]}"""))
        assertFalse("没有待写项就不该产生队列文件", journalFile.exists())
        assertEquals(0, bridge(2L).replayPendingStorage())
    }

    @Test
    fun corruptJournalIsDiscardedInsteadOfCrashing() {
        storedFile("80.dat", "KEEP")
        journalFile.writeText("{ this is not json")
        assertEquals(0, bridge(2L).replayPendingStorage())
        assertTrue("损坏的队列不能拖垮启动", storedFile("80.dat").exists())
        assertFalse("读不出来的队列被删除，避免每次启动都重复解析与告警", journalFile.exists())
        // 删掉之后还能正常入队与重放
        assertTrue(bridge(2L).stagePendingStorage("""{"values":{"80.dat":"RECOVERED"},"deletes":[]}"""))
        assertEquals(1, bridge(3L).replayPendingStorage())
        assertEquals("RECOVERED", storedFile("80.dat").readText())
    }
}
