package com.core.rpgmaker

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生产契约：Storage 修改在返回前完成持久接收，退出/重建/导航都不再参与数据保全。
 *
 * 这里走真实桥（不经过 WebView），覆盖：无任何退出回调也能恢复、接收失败不改镜像、
 * 物化失败只影响该键、同会话 ABA、旧定时器空操作、跨进程 run、双桥串行。
 */
class RpgMakerStorageCommitTest {

    private val root: File = java.nio.file.Files.createTempDirectory("storage-commit").toFile()
    private val store = File(root, "AppData/Local Storage")

    private fun bridge(session: Long) = RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = session)
    private fun target(name: String = "61.dat") = File(store, name)
    private fun journal() = File(store, RpgMakerStoragePendingJournal.FILE_NAME)

    /** 接收并断言已落盘（正常路径的返回码是 1）。 */
    private fun accept(bridge: RpgMakerFsBridge, name: String, value: String) {
        assertEquals(1, bridge.commitCode(mapOf(name to value)))
    }

    @After
    fun tearDown() {
        RpgMakerStorageWriteLedger.reset()
        root.deleteRecursively()
    }

    @Test
    fun acceptedValueIsOnDiskBeforeTheCallReturns() {
        val bridge = bridge(1)
        assertEquals(1, bridge.commitCode(mapOf("61.dat" to "ACCEPTED")))
        assertEquals("ACCEPTED", target().readText())
        assertFalse("正常路径不留下队列", journal().exists())
    }

    @Test
    fun rejectedMutationLeavesThePreviousValueIntact() {
        val bridge = bridge(1)
        accept(bridge, "61.dat", "OLD")
        assertEquals("越界文件名整份拒绝", 0, bridge.commitCode(mapOf("../escape.dat" to "NEW")))
        assertEquals("OLD", target().readText())
        assertEquals("队列为空，重放无事可做", 0, bridge(2).replayedCount())
    }

    @Test
    fun clearRemovesValuesWhichWereNeverMaterialized() {
        val blocked = File(store, "62.dat").apply { mkdirs() }
        File(blocked, "child").writeText("x")
        val bridge = bridge(1)
        assertEquals("目标不可写 → 已接收待物化", 2, bridge.commitCode(mapOf("62.dat" to "PENDING")))
        assertTrue(journal().exists())

        // clear 覆盖了这条尚未物化的意图：删除同样被目标目录挡住，但队列里只剩删除
        assertEquals(2, bridge(2).commitCode(emptyMap(), clear = true))
        blocked.deleteRecursively()
        assertEquals(1, bridge(3).replayedCount())
        assertFalse("clear 之后不该再有该值", target("62.dat").exists())
        assertFalse(journal().exists())
    }

    @Test
    fun anOlderSessionCannotConsumeANewerAcceptedValue() {
        val blocked = File(store, "61.dat").apply { mkdirs() }
        File(blocked, "child").writeText("x")
        assertEquals(2, bridge(2).commitCode(mapOf("61.dat" to "NEW")))
        // 旧文档重放新会话的条目：不能静默消费（否则新值永久丢失）
        assertFalse("单条失败不是致命错误", bridge(1).replayFatal())
        assertEquals(0, bridge(1).replayedCount())
        assertEquals("条目必须交还队列", listOf("61.dat"), bridge(1).replayKept())

        blocked.deleteRecursively()
        assertEquals(1, bridge(3).replayedCount())
        assertEquals("NEW", target().readText())
    }

    @Test
    fun aStaleTimerAfterAcknowledgementIsANoOp() {
        val bridge = bridge(1)
        accept(bridge, "61.dat", "OLD")
        assertTrue("队列已空：旧定时器只能空操作", bridge.flushStorageMutation("61.dat", "OLD"))
        assertEquals("OLD", target().readText())
    }

    @Test
    fun sameValueInANewerSessionTakesOwnership() {
        accept(bridge(1), "61.dat", "SAME")
        accept(bridge(2), "61.dat", "SAME")
        assertEquals("旧文档的修改被拒绝，而不是覆盖新会话", 0, bridge(1).commitCode(mapOf("61.dat" to "STALE")))
        assertEquals("SAME", target().readText())
    }

    @Test
    fun acceptedAbaUpdateSurvivesBeforeTheLastMaterialization() {
        val blocked = File(store, "61.dat").apply { mkdirs() }
        File(blocked, "child").writeText("x")
        val bridge = bridge(1)
        assertEquals(2, bridge.commitCode(mapOf("61.dat" to "A")))
        assertEquals(2, bridge.commitCode(mapOf("61.dat" to "B")))
        assertEquals(2, bridge.commitCode(mapOf("61.dat" to "A")))
        blocked.deleteRecursively()
        assertEquals("队列里只有最后一次接收的 A", 1, bridge(2).replayedCount())
        assertEquals("A", target().readText())
    }

    @Test
    fun anAcknowledgedDeleteCannotBeRevivedByAnOldTimer() {
        val bridge = bridge(1)
        accept(bridge, "61.dat", "OLD")
        assertEquals(1, bridge.commitCode(emptyMap(), deletes = listOf("61.dat")))
        assertFalse(target().exists())
        assertTrue("队列已空：旧写入定时器只能空操作", bridge.flushStorageMutation("61.dat", "OLD"))
        assertFalse(target().exists())
    }

    @Test
    fun replayFailureKeepsOnlyThatKeyBlocked() {
        val blocked = File(store, "61.dat").apply { mkdirs() }
        File(blocked, "child").writeText("x")
        assertEquals(2, bridge(1).commitCode(mapOf("61.dat" to "ACCEPTED")))

        val report = bridge(2)
        assertFalse("单条失败不能整体致命", report.replayFatal())
        assertEquals(0, report.replayedCount())
        assertEquals(listOf("61.dat"), report.replayKept())

        blocked.deleteRecursively()
        assertEquals(1, bridge(3).replayedCount())
        assertEquals("ACCEPTED", target().readText())
    }

    @Test
    fun corruptJournalIsQuarantinedSoStorageStaysUsable() {
        store.mkdirs()
        val damaged = """{"entries":{"61.dat":null}}"""
        journal().writeText(damaged)

        // 不可解析的队列改名留证，本次会话按空队列继续：一次损坏不该让 Storage 永久失效
        assertEquals(0, bridge(1).replayedCount())
        assertFalse("原文件已改名", journal().exists())
        val quarantined = store.listFiles()?.filter { it.name.startsWith(RpgMakerStoragePendingJournal.FILE_NAME + ".corrupt.") }
        assertEquals("保留取证副本", 1, quarantined?.size)
        assertEquals(damaged, quarantined!!.first().readText())

        assertEquals(1, bridge(2).commitCode(mapOf("61.dat" to "RECOVERED")))
        assertEquals("RECOVERED", target().readText())
    }

    @Test
    fun differentProcessRunsAreClearedByTheCurrentSession() {
        // 子进程把 63.dat 写进队列（目标被非空目录占住 → 只能待物化），再退出
        val blocked = File(store, "63.dat").apply { mkdirs() }
        File(blocked, "child").writeText("x")
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$javaName")
        val classpath = listOf(
            ProcessProbe::class.java, RpgMakerFsBridge::class.java, org.json.JSONObject::class.java,
            kotlin.Unit::class.java, android.util.Log::class.java, org.junit.Test::class.java,
        ).map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct().joinToString(File.pathSeparator)
        val output = File(root, "previous-process.log")
        val process = ProcessBuilder(java.path, "-cp", classpath, ProcessProbe::class.java.name, root.path)
            .redirectErrorStream(true).redirectOutput(output).start()
        val exited = process.waitFor(15, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        assertTrue("独立 JVM 未完成: ${output.readText()}", exited)
        assertEquals(output.readText(), 0, process.exitValue())

        val previousRun = org.json.JSONObject(journal().readText()).getString("run")
        assertEquals(1, bridge(1).commitCode(mapOf("62.dat" to "CURRENT")))
        assertNotEquals("两个实际 JVM 必须持有不同 run", previousRun, org.json.JSONObject(journal().readText()).getString("run"))

        // 本进程的 clear 必须覆盖上一进程留下的条目（跨进程期号按最旧处理）
        bridge(2).commitCode(emptyMap(), clear = true)
        blocked.deleteRecursively()
        RpgMakerStorageWriteLedger.reset()
        bridge(3).replayedCount()
        assertFalse("上一进程的待写值被本进程的清除覆盖", target("63.dat").exists())
        assertFalse(target("62.dat").exists())
    }

    object ProcessProbe {
        @JvmStatic
        fun main(args: Array<String>) {
            val root = File(args[0])
            check(RpgMakerFsBridge(root, root, null, RpgMakerDirIndex(), session = 9)
                .commitCode(mapOf("63.dat" to "PREVIOUS")) != 0)
        }
    }

    @Test
    fun twoBridgeReplaysShareTheStoreLock() {
        val blocked = File(store, "61.dat").apply { mkdirs() }
        File(blocked, "child").writeText("x")
        assertEquals(2, bridge(1).commitCode(mapOf("61.dat" to "ACCEPTED")))
        blocked.deleteRecursively()

        val first = bridge(1)
        val second = bridge(2)
        val entered = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val firstThread = Thread {
            try {
                RpgMakerStorageWriteLedger.withStorageLock(store.path) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    first.replayPendingStorage()
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        firstThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val secondThread = Thread {
            try {
                secondStarted.countDown()
                second.replayPendingStorage()
            } catch (error: Throwable) { failure.set(error) } finally { secondDone.countDown() }
        }
        secondThread.start()
        try {
            // 先确认第二个重放真的启动了，再断言它被目录锁挡住（否则冷启动也能"通过"）
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse("第二个重放必须等第一个释放目录锁", secondDone.await(200, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            firstThread.join(5000)
            secondThread.join(5000)
        }
        assertNull(failure.get())
        assertFalse(firstThread.isAlive)
        assertFalse(secondThread.isAlive)
        assertEquals("ACCEPTED", target().readText())
    }

    @Test
    fun queuedDeleteIsAppliedOnTheNextDocument() {
        val blocked = File(store, "61.dat").apply { mkdirs() }
        File(blocked, "child").writeText("x")
        assertEquals("值写不进去 → 已接收待物化", 2, bridge(1).commitCode(mapOf("61.dat" to "OLD")))
        assertEquals(2, bridge(1).commitCode(emptyMap(), deletes = listOf("61.dat")))
        assertTrue("删除落盘失败时留在队列里", journal().exists())

        blocked.deleteRecursively()
        assertEquals(1, bridge(2).replayedCount())
        assertFalse(target().exists())
        assertFalse(journal().exists())
    }

    @Test
    fun clearAlsoRemovesLegacyNamedLeftovers() {
        store.mkdirs()
        val legacy = File(store, "legacy_%20name.dat").apply { writeText("OLD") }
        assertEquals(1, bridge(1).commitCode(emptyMap(), clear = true))
        assertFalse("clear 之后磁盘上不该留历史命名的 .dat", legacy.exists())
    }

    @Test
    fun aCommittedValueIgnoresAnExternalRewriteOfTheDat() {
        // 物化失败 → 条目留在队列（accepted 意图）；随后有人改了同名 .dat，
        // 重放仍必须写入**已接收**的值，而不是把它当"过期条目"丢掉。
        val blocked = File(store, "61.dat").apply { mkdirs() }
        File(blocked, "child").writeText("x")
        assertEquals(2, bridge(1).commitCode(mapOf("61.dat" to "RECEIVED")))
        blocked.deleteRecursively()
        target().writeText("EXTERNAL")

        assertEquals(1, bridge(2).replayedCount())
        assertEquals("RECEIVED", target().readText())
    }

    @Test
    fun journalIsEmptyAfterTheHappyPath() {
        val bridge = bridge(1)
        // 文件名必须是键的十六进制形态（协议只接受这种名字），这里直接用 0..f 当文件名
        repeat(16) { index -> accept(bridge, "${index.toString(16)}.dat", "v$index") }
        assertFalse("每次接收后立即物化，队列不应随写入次数增长", journal().exists())
        assertEquals("v15", target("f.dat").readText())
    }
}
