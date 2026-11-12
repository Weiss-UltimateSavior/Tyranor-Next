package com.core.nativeplugin

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtemisVorbisRepairTest {
    @get:Rule val temp = TemporaryFolder()
    private val libs = File("src/main/nativeplugins/artemis/arm64-v8a")

    @Test fun repairsAllKnownOldKernelsWithoutChangingSources() {
        val counts = mapOf("libartemis.so" to 7, "libartemis-compatible.so" to 14)
        for ((name, count) in counts) {
            val source = File(libs, name)
            val before = source.readBytes()
            val cache = temp.newFolder(name)
            val output = ArtemisVorbisRepair.prepare(source, cache)
            assertNotEquals(source, output)
            assertArrayEquals(before, source.readBytes())
            val after = output.readBytes()
            assertEquals(before.size, after.size)
            val changed = before.indices.filter { before[it] != after[it] }
            assertEquals(count, changed.size)
            for (offset in changed) {
                assertEquals(3, offset % 4)
                val old = word(before, offset - 3)
                val new = word(after, offset - 3)
                assertEquals(0xeb00001f.toInt(), old and 0xff20001f.toInt())
                assertEquals(old and Int.MAX_VALUE, new)
                assertExecutableFileOffset(before, offset - 3)
            }
            assertEquals(output, ArtemisVorbisRepair.prepare(source, cache))
            assertFalse(Files.getPosixFilePermissions(output.toPath()).any { it.name.endsWith("WRITE") })
        }
    }

    @Test fun fixedCleanAndUnknownLibrariesPassThrough() {
        for (name in listOf("libartemis-v6.so", "libartemis-clean.so")) {
            val source = File(libs, name)
            assertEquals(source, ArtemisVorbisRepair.prepare(source, temp.newFolder(name)))
        }
        val changed = temp.newFile("unknown.so")
        changed.writeBytes(File(libs, "libartemis.so").readBytes().also { it[100] = (it[100].toInt() xor 1).toByte() })
        assertEquals(changed, ArtemisVorbisRepair.prepare(changed, temp.newFolder("unknown")))
    }

    @Test fun corruptPublishedCopyFailsClosed() {
        val source = File(libs, "libartemis.so")
        val cache = temp.newFolder()
        val result = ArtemisVorbisRepair.prepare(source, cache)
        assertTrue(result.setWritable(true, true))
        result.writeText("broken")
        assertThrows(IOException::class.java) { ArtemisVorbisRepair.prepare(source, cache) }
        assertEquals("broken", result.readText())
    }

    @Test fun rejectsSymlinkSourceAndCache() {
        val source = File(libs, "libartemis.so").canonicalFile
        val link = File(temp.root, "link.so")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        assertThrows(IOException::class.java) { ArtemisVorbisRepair.prepare(link, temp.newFolder()) }
        val cache = File(temp.root, "cache-link")
        Files.createSymbolicLink(cache.toPath(), temp.newFolder().toPath())
        assertThrows(IOException::class.java) { ArtemisVorbisRepair.prepare(source, cache) }
    }

    @Test fun allowsPlatformParentAliasesButRejectsRedirectedOutputs() {
        val real = temp.newFolder("real")
        val alias = File(temp.root, "platform-alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val source = File(libs, "libartemis.so")
        val output = ArtemisVorbisRepair.prepare(source, File(alias, "derived"))
        assertTrue(output.isFile)
        assertEquals(File(real, "derived").canonicalFile, output.parentFile.parentFile.canonicalFile)
        Files.delete(output.toPath())
        Files.createSymbolicLink(output.toPath(), source.canonicalFile.toPath())
        assertThrows(IOException::class.java) { ArtemisVorbisRepair.prepare(source, File(alias, "derived")) }
    }

    @Test fun bundledNewKernelsAlreadyUse32BitSerialComparisons() {
        for ((name, sites) in mapOf(
            "libartemis-v6.so" to listOf(0x5c6cc8, 0x5c7a78, 0x5c7e68, 0x5c86e4, 0x5c8bd0, 0x5c8ce4, 0x5c8dc4),
        )) {
            val bytes = File(libs, name).readBytes()
            for (offset in sites) {
                assertEquals("$name @ ${offset.toString(16)}", 0x6b00001f, word(bytes, offset) and 0xff20001f.toInt())
                assertExecutableFileOffset(bytes, offset)
            }
        }
    }

    @Test fun concurrentPreparationPublishesOneCompleteCopy() {
        val cache = temp.newFolder()
        val source = File(libs, "libartemis.so")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll(List(8) { Callable { ArtemisVorbisRepair.prepare(source, cache) } })
                .map { it.get() }
            assertEquals(1, results.toSet().size)
            assertFalse(results.first().parentFile.listFiles()!!.any { it.name.startsWith(".vorbis-") })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun allHostsRegisterTheSameLibraryAsTheBootstrap() {
        val dir = File("src/main/java/com/akira/tyranoemu/remote")
        for (host in listOf("V1", "V2", "V6", "Clean")) {
            val text = File(dir, "ArtemisActivity$host.java").readText()
            assertTrue(text.contains("loadResolvedEngineLibrary("))
            assertFalse(text.contains("artemisLibPath("))
        }
        assertTrue(File("src/main/cpp/artemis_loader.cpp").readText().contains("\"resolveArtemisEngineLibraryPath\""))
        assertTrue(File("consumer-rules.pro").readText().contains("com.akira.tyranoemu.remote.**"))
    }

    private fun word(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN).int

    private fun assertExecutableFileOffset(bytes: ByteArray, offset: Int) {
        val elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x464c457f, elf.getInt(0))
        assertEquals(2, bytes[4].toInt())
        assertEquals(183, elf.getShort(18).toInt())
        val table = elf.getLong(32).toInt()
        val stride = elf.getShort(54).toInt()
        val count = elf.getShort(56).toInt()
        assertTrue((0 until count).any {
            val p = table + it * stride
            val start = elf.getLong(p + 8)
            elf.getInt(p) == 1 && elf.getInt(p + 4) and 1 != 0 &&
                offset >= start && offset + 4 <= start + elf.getLong(p + 32)
        })
    }
}
