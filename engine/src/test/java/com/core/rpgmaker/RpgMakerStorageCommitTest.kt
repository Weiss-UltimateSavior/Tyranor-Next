package com.core.rpgmaker

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** 生命周期事件全部缺席时，Storage 已接收的修改仍然属于原生持久状态。 */
class RpgMakerStorageCommitTest {
    private val root = java.nio.file.Files.createTempDirectory("storage-commit").toFile()
    private val store = File(root, "AppData/Local Storage")
    private fun bridge(session: Long) = RpgMakerFsBridge(root, root, session = session)
    private fun commit(target: RpgMakerFsBridge, value: String) =
        target.commitStorageMutation("""{"values":{"61.dat":"$value"},"deletes":[]}""")

    @After fun tearDown() {
        RpgMakerStorageWriteLedger.reset()
        root.deleteRecursively()
    }

    @Test fun acceptedValueSurvivesWithoutAnyExitCallback() {
        assertTrue(commit(bridge(1), "ACCEPTED"))
        assertFalse(File(store, "61.dat").exists())
        assertEquals(1, bridge(2).replayPendingStorage())
        assertEquals("ACCEPTED", File(store, "61.dat").readText())
    }

    @Test fun failedAcceptanceLeavesPreviousQueuedValueIntact() {
        val target = bridge(1)
        assertTrue(commit(target, "OLD"))
        assertFalse(target.commitStorageMutation("""{"values":{"../escape.dat":"NEW"}}"""))
        assertEquals(1, bridge(2).replayPendingStorage())
        assertEquals("OLD", File(store, "61.dat").readText())
    }

    @Test fun clearCoversValuesWhichHaveNeverBeenMaterialized() {
        assertTrue(commit(bridge(1), "PENDING"))
        assertTrue(bridge(2).commitStorageMutation("""{"clear":true}"""))
        RpgMakerStorageWriteLedger.reset() // 模拟进程结束后账本清空
        assertTrue(bridge(3).replayPendingStorage() >= 0)
        assertFalse(File(store, "61.dat").exists())
    }

    @Test fun anOldBridgeCannotConsumeANewerAcceptedValue() {
        assertTrue(commit(bridge(2), "NEW"))
        assertEquals(-1, bridge(1).replayPendingStorage())
        assertEquals(1, bridge(3).replayPendingStorage())
        assertEquals("NEW", File(store, "61.dat").readText())
    }

    @Test fun aStaleTimerCannotMaterializeOverANewerValue() {
        val target = bridge(1)
        assertTrue(commit(target, "OLD"))
        assertTrue(commit(target, "NEW"))
        assertTrue(target.flushStorageMutation("61.dat", "OLD"))
        assertFalse(File(store, "61.dat").exists())
        assertEquals(1, bridge(2).replayPendingStorage())
        assertEquals("NEW", File(store, "61.dat").readText())
    }

    @Test fun sameValueInANewerSessionTakesOwnership() {
        assertTrue(commit(bridge(1), "SAME"))
        assertTrue(commit(bridge(2), "SAME"))
        assertFalse(commit(bridge(1), "STALE"))
        assertEquals(1, bridge(3).replayPendingStorage())
        assertEquals("SAME", File(store, "61.dat").readText())
    }

    @Test fun replayFailureIsExplicitAndKeepsTheAcceptedIntent() {
        val directory = File(store, "61.dat").apply { mkdirs() }
        File(directory, "child").writeText("block replacement")
        assertTrue(commit(bridge(1), "ACCEPTED"))
        assertEquals(-1, bridge(2).replayPendingStorage())
        assertTrue(File(store, RpgMakerStoragePendingJournal.FILE_NAME).isFile)
        directory.deleteRecursively()
        assertEquals(1, bridge(3).replayPendingStorage())
        assertEquals("ACCEPTED", File(store, "61.dat").readText())
    }

    @Test fun acceptedAbaUpdateSurvivesBeforeTheLastMaterialization() {
        val target = bridge(1)
        assertTrue(commit(target, "A"))
        assertTrue(target.flushStorageMutation("61.dat", "A"))
        assertTrue(commit(target, "B"))
        assertTrue(target.flushStorageMutation("61.dat", "B"))
        assertTrue(commit(target, "A"))
        RpgMakerStorageWriteLedger.reset()
        assertEquals(1, bridge(2).replayPendingStorage())
        assertEquals("A", File(store, "61.dat").readText())
    }

    @Test fun anAcknowledgedDeleteCannotBeRevivedByAnOldTimer() {
        val target = bridge(1)
        assertTrue(commit(target, "OLD"))
        assertTrue(target.flushStorageMutation("61.dat", "OLD"))
        assertTrue(target.commitStorageMutation("""{"deletes":["61.dat"]}"""))
        assertTrue(target.flushStorageMutation("61.dat", null))
        assertTrue(target.flushStorageMutation("61.dat", "OLD"))
        assertFalse(File(store, "61.dat").exists())
    }

    @Test fun aStaleTimerAfterAcknowledgementIsANoOp() {
        val target = bridge(1)
        assertTrue(commit(target, "OLD"))
        assertTrue(commit(target, "NEW"))
        assertTrue(target.flushStorageMutation("61.dat", "NEW"))
        assertTrue(target.flushStorageMutation("61.dat", "OLD"))
        assertEquals("NEW", File(store, "61.dat").readText())
    }

    @Test fun malformedJsonEntryCannotBecomeAnEmptyAcceptedValue() {
        store.mkdirs()
        val target = File(store, "61.dat").apply { writeText("KEEP") }
        val journal = File(store, RpgMakerStoragePendingJournal.FILE_NAME)
        val malformed = """{"entries":{"61.dat":{"a":true}}}"""
        journal.writeText(malformed)
        assertEquals(-1, bridge(1).replayPendingStorage())
        assertEquals("KEEP", target.readText())
        assertEquals(malformed, journal.readText())
        assertFalse(commit(bridge(1), "REPLACEMENT"))
    }

    @Test fun retiredClaimCannotResurrectAfterAcknowledgementAndRestart() {
        val target = bridge(1)
        assertTrue(commit(target, "OLD"))
        assertNotNull(RpgMakerStoragePendingJournal.claim(store))
        assertTrue(commit(target, "NEW"))
        assertTrue(target.flushStorageMutation("61.dat", "NEW"))
        RpgMakerStorageWriteLedger.reset()
        assertEquals(0, bridge(2).replayPendingStorage())
        assertEquals("NEW", File(store, "61.dat").readText())
    }

    @Test fun aDifferentJvmRunIsClearedByTheCurrentSession() {
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
        val journal = File(store, RpgMakerStoragePendingJournal.FILE_NAME)
        val previousRun = org.json.JSONObject(journal.readText()).getString("run")
        assertTrue(bridge(1).commitStorageMutation("""{"values":{"62.dat":"CURRENT"}}"""))
        val currentRun = org.json.JSONObject(journal.readText()).getString("run")
        assertNotEquals("两个实际 JVM 必须持有不同 run", previousRun, currentRun)
        assertTrue(bridge(2).commitStorageMutation("""{"clear":true}"""))
        RpgMakerStorageWriteLedger.reset()
        assertTrue(bridge(3).replayPendingStorage() >= 0)
        assertFalse(File(store, "61.dat").exists())
        assertFalse(File(store, "62.dat").exists())
    }

    object ProcessProbe {
        @JvmStatic fun main(args: Array<String>) {
            val root = File(args[0])
            check(RpgMakerFsBridge(root, root, session = 9).commitStorageMutation(
                """{"values":{"61.dat":"PREVIOUS"}}""",
            ))
        }
    }

    @Test fun twoBridgeReplaysShareTheStoreLock() {
        val first = bridge(1)
        val second = bridge(2)
        assertTrue(commit(first, "ACCEPTED"))
        val entered = CountDownLatch(1)
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
            try { second.replayPendingStorage() } catch (error: Throwable) { failure.set(error) }
            finally { secondDone.countDown() }
        }
        secondThread.start()
        try { assertFalse(secondDone.await(100, TimeUnit.MILLISECONDS)) }
        finally { release.countDown(); firstThread.join(5000); secondThread.join(5000) }
        assertNull(failure.get())
        assertFalse(firstThread.isAlive)
        assertFalse(secondThread.isAlive)
        assertEquals("ACCEPTED", File(store, "61.dat").readText())
    }
}
