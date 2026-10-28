package com.core.rpgmaker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import com.core.web.AsarChildrenIndex
import com.core.web.AsarChildrenMemoryProbe

class AsarChildrenLookupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deepFlatKeyDoesNotMultiplyHeapUsage() {
        val classpath = listOf(AsarChildrenIndex::class.java, AsarChildrenMemoryProbe::class.java, kotlin.Pair::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/" + if (File.separatorChar == '\\') "java.exe" else "java")
        val process = ProcessBuilder(java.path, "-Xmx24m", "-cp", classpath, AsarChildrenMemoryProbe::class.java.name)
            .redirectErrorStream(true).start()
        try {
            assertTrue("Deep-path probe timed out", process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains("DEEP_PATH_OK"))
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
