package com.core.rpgmaker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import com.core.web.AsarChildrenIndex
import com.core.web.AsarChildrenMemoryProbe

class AsarChildrenLookupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deepFlatKeyDoesNotMultiplyHeapUsage() = runHeapProbe("deep", "DEEP_PATH_OK")

    @Test fun wideDirectoryFitsSmallHeap() = runHeapProbe("wide", "WIDE_TREE_OK")

    @Test(expected = IOException::class) fun pathologicalNodeCountFailsBeforeAllocatingUnboundedTree() {
        AsarChildrenIndex(listOf(("p/".repeat(300_000) + "leaf") to false))
    }

    @Test fun ordinaryArchiveReadsDoNotBuildAnUnusedDirectoryIndex() {
        val file = temporary.newFile("lazy.asar")
        val key = "p/".repeat(300_000) + "leaf"
        writeTestAsar(file, packed = mapOf(key to ""))
        AsarArchive(file).use { modern ->
            com.core.tyrano.AsarArchive(file).use { legacy ->
                assertTrue(modern.has(key))
                assertTrue(legacy.has(key))
                assertArrayEquals(byteArrayOf(), modern.read(key))
                assertArrayEquals(byteArrayOf(), legacy.read(key))
                for (children in listOf(modern::children, legacy::children)) {
                    try {
                        children("")
                        fail("A directory index exceeding its node budget must fail")
                    } catch (error: AsarChildrenIndex.LimitExceeded) {
                        // 打开归档和普通读取不需要索引；仅列目录触发预算检查。
                        try {
                            children("")
                            fail("An immutable archive over budget must retain its failure")
                        } catch (again: AsarChildrenIndex.LimitExceeded) {
                            assertSame("Repeated lookups must not rebuild an over-budget tree", error, again)
                        }
                    }
                }
            }
        }
    }

    @Test fun interruptedArchiveIndexCanBeRetriedOnAnActiveThread() {
        val file = temporary.newFile("cancelled-reader.asar")
        writeTestAsar(file, packed = mapOf("directory/file.txt" to ""))
        AsarArchive(file).use { modern ->
            com.core.tyrano.AsarArchive(file).use { legacy ->
                for (children in listOf(modern::children, legacy::children)) {
                    val failure = AtomicReference<Throwable?>()
                    val worker = Thread {
                        Thread.currentThread().interrupt()
                        try { children("") } catch (error: Throwable) { failure.set(error) }
                        finally { Thread.interrupted() }
                    }
                    worker.start()
                    worker.join(5_000)
                    if (worker.isAlive) { worker.interrupt(); fail("Cancelled archive index did not finish") }
                    assertTrue(failure.get() is InterruptedIOException)
                    assertEquals(listOf("directory" to true), children(""))
                    assertEquals(listOf("file.txt" to false), children("directory"))
                }
            }
        }
    }

    @Test fun interruptedTreeBuildStopsConsumingEntries() {
        val failure = AtomicReference<Throwable?>()
        var consumed = 0
        val worker = Thread {
            try {
                val entries = sequence {
                    repeat(1_000) { index ->
                        if (index == 1) Thread.currentThread().interrupt()
                        consumed++
                        yield("file$index" to false)
                    }
                }
                AsarChildrenIndex(entries.asIterable())
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                Thread.interrupted()
            }
        }
        worker.start()
        worker.join(5_000)
        if (worker.isAlive) { worker.interrupt(); fail("Interrupted tree build did not finish") }
        assertTrue("Expected cancellation, got ${failure.get()}", failure.get() is InterruptedIOException)
        assertEquals(2, consumed)
    }

    private fun runHeapProbe(mode: String, marker: String) {
        val classpath = listOf(AsarChildrenIndex::class.java, AsarChildrenMemoryProbe::class.java, kotlin.Pair::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/" + if (File.separatorChar == '\\') "java.exe" else "java")
        val outputFile = temporary.newFile("$mode-probe.log")
        val process = ProcessBuilder(java.path, "-Xmx24m", "-cp", classpath, AsarChildrenMemoryProbe::class.java.name, mode)
            .redirectErrorStream(true).redirectOutput(outputFile).start()
        try {
            assertTrue("$mode probe timed out", process.waitFor(30, TimeUnit.SECONDS))
            val output = outputFile.readText()
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains(marker))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @Test fun bothReadersPreserveImplicitDirectoriesAndDirectoryPriorityWithoutChangingMetadata() {
        val file = temporary.newFile("app.asar")
        writeTestAsar(file, packed = linkedMapOf(
            "implicit/child.txt" to "", "a" to "", "a/b.txt" to "", "ab/other.txt" to "",
        ), emptyDirectories = setOf("empty"))
        AsarArchive(file).use { modern ->
            com.core.tyrano.AsarArchive(file).use { legacy ->
                for (children in listOf(modern::children, legacy::children)) {
                    assertEquals(setOf("implicit" to true, "a" to true, "ab" to true, "empty" to true), children("").toSet())
                    assertEquals(listOf("child.txt" to false), children("/implicit/"))
                    assertEquals(listOf("b.txt" to false), children("a"))
                    assertTrue(children("empty").isEmpty())
                    assertTrue(children("absent").isEmpty())
                }
                assertFalse(modern.has("implicit"))
                assertFalse(legacy.has("implicit"))
                assertFalse(modern.isDirectory("a"))
                assertFalse(legacy.isDirectory("a"))
            }
        }
    }

    @Test fun malformedDoubleSlashKeepsEachReadersExistingEmptyNamePolicy() {
        val file = temporary.newFile("odd.asar")
        writeTestAsar(file, packed = mapOf("odd//hidden.txt" to ""))
        AsarArchive(file).use { assertTrue(it.children("odd").isEmpty()) }
        com.core.tyrano.AsarArchive(file).use { assertEquals(listOf("" to true), it.children("odd")) }
    }

    @Test fun repeatedDirectoryLookupsReturnOnlyTheirOwnFiles() {
        val file = temporary.newFile("many.asar")
        val entries = linkedMapOf<String, String>()
        repeat(200) { directory -> repeat(50) { index -> entries["d$directory/f$index.txt"] = "" } }
        writeTestAsar(file, packed = entries)
        AsarArchive(file).use { modern ->
            com.core.tyrano.AsarArchive(file).use { legacy ->
                val start = System.nanoTime()
                repeat(3) {
                    repeat(200) { directory ->
                        for (children in listOf(modern::children, legacy::children)) {
                            val names = children("d$directory").map { it.first }.toSet()
                            assertEquals(50, names.size)
                            assertTrue(names.contains("f0.txt"))
                            assertTrue(names.contains("f49.txt"))
                        }
                    }
                }
                // Diagnostic measurement, not a flaky machine-speed assertion.
                println("ASAR children: 10000 entries, 1200 lookups, elapsedMs=" + (System.nanoTime() - start) / 1_000_000)
            }
        }
    }
}
