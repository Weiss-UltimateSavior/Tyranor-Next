package com.tyranor.next.core.unpack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** PF6 封包器回归：回环、明文性、表头金标准、取消清理（PF8/XP3 走 Rust core，见 cargo test）。 */
class ArtemisPf6PackerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pf6RoundTripPreservesAllBytes() {
        val src = temporaryFolder.newFolder("src")
        src.resolve("system.ini").writeText("[SYSTEM]\nWIDTH = 1280\n")
        src.resolve("sub").mkdir()
        src.resolve("sub/scene.iet").writeBytes(ByteArray(300) { it.toByte() })
        src.resolve("movie.dat").writeBytes(ByteArray(5000) { (it * 7).toByte() })

        val out = temporaryFolder.newFile("out.pfs")
        val stats = ArtemisPf6Packer.pack(src, out)
        assertEquals(3, stats.entryCount)

        val entries = readTable(out)
        assertEquals(
            "entries",
            setOf("system.ini", "sub\\scene.iet", "movie.dat"),
            entries.keys,
        )
        assertArrayEquals("ini entry", src.resolve("system.ini").readBytes(), entries.getValue("system.ini"))
        assertArrayEquals("sub entry", src.resolve("sub/scene.iet").readBytes(), entries.getValue("sub\\scene.iet"))
        assertArrayEquals("binary entry", src.resolve("movie.dat").readBytes(), entries.getValue("movie.dat"))
    }

    @Test
    fun pf6WritesPf6MagicPlaintext() {
        val src = temporaryFolder.newFolder("src")
        val payload = "plain-body-for-pf6".toByteArray()
        src.resolve("a.txt").writeBytes(payload)

        val out = temporaryFolder.newFile("out.pfs")
        ArtemisPf6Packer.pack(src, out)

        RandomAccessFile(out, "r").use { raf ->
            assertEquals("magic p", 0x70, raf.read())
            assertEquals("magic f", 0x66, raf.read())
            assertEquals("version 6", ArtemisPfsFormat.VERSION_PF6, raf.read())
        }
        // PF6 数据区必须与原文逐字节相同（无 XOR）。
        assertArrayEquals(payload, readTable(out).getValue("a.txt"))
        // 且按 PF8 密钥解密后必然不再相等（排除“恰好都对”的假阳性）。
        val table = readRawTable(out)
        val xored = table.second.copyOf()
        ArtemisPfsFormat.xorInPlace(xored, xored.size, ArtemisPfsFormat.deriveKey(table.first), 0L)
        assertFalse(payload.contentEquals(xored))
    }

    @Test
    fun pf6TinyEntriesRoundTrip() {
        val src = temporaryFolder.newFolder("src")
        src.resolve("one.txt").writeBytes("a".toByteArray())
        src.resolve("seven.txt").writeBytes("1234567".toByteArray())

        val out = temporaryFolder.newFile("out.pfs")
        ArtemisPf6Packer.pack(src, out)

        val entries = readTable(out)
        assertArrayEquals("1B entry", "a".toByteArray(), entries.getValue("one.txt"))
        assertArrayEquals("7B entry", "1234567".toByteArray(), entries.getValue("seven.txt"))
    }

    @Test
    fun singleFileHeaderMatchesGoldenLayout() {
        val src = temporaryFolder.newFolder("src")
        src.resolve("one.txt").writeBytes("0123456789".toByteArray())
        val out = temporaryFolder.newFile("out.pfs")
        ArtemisPf6Packer.pack(src, out)

        RandomAccessFile(out, "r").use { raf ->
            assertEquals(0x70, raf.read())
            assertEquals(0x66, raf.read())
            assertEquals(0x36, raf.read()) // '6' = PF6
            val indexSize = readLe32(raf)
            val count = readLe32(raf)
            assertEquals(1, count)
            // index_size = 4(count) + [4+7+4+4+4](entry) + 4(fsc)
            //   + 1*8(offsets) + 8(end marker) + 4(count offset) = 51
            assertEquals(51, indexSize)
            assertEquals("nameLen", 7, readLe32(raf))
            val name = ByteArray(7)
            raf.readFully(name)
            assertEquals("one.txt", String(name))
            assertEquals("reserved", 0, readLe32(raf))
            val offset = readLe32(raf)
            assertEquals("data starts right after header", 7 + indexSize, offset)
            assertEquals("size", 10, readLe32(raf))
            assertEquals("filesize_count = count + 1", 2, readLe32(raf))
            // filesize offset[0] = size-field absolute pos minus 0x0F:
            // 11 + 4 + 7 + 4 + 4 = 30 -> 30 - 15 = 15
            assertEquals(15L, readLe64(raf))
            assertEquals("end marker", 0L, readLe64(raf))
            // filesize_count absolute pos = 11 + 23 = 34 -> 34 - 7 = 27
            assertEquals(27, readLe32(raf))
            assertEquals(7L + indexSize, raf.filePointer)
        }
        assertArrayEquals("0123456789".toByteArray(), readTable(out).getValue("one.txt"))
    }

    @Test
    fun packRejectsEmptyDirectory() {
        val src = temporaryFolder.newFolder("empty")
        try {
            ArtemisPf6Packer.pack(src, temporaryFolder.newFile("o.pfs"))
            fail("expected IOException")
        } catch (error: IOException) {
            // expected
        }
    }

    @Test
    fun cancelledPackDeletesPartialOutput() {
        val src = temporaryFolder.newFolder("src")
        src.resolve("big.bin").writeBytes(ByteArray(300000) { it.toByte() })
        val out = temporaryFolder.root.resolve("cancelled.pfs")
        var calls = 0
        try {
            ArtemisPf6Packer.pack(
                src,
                out,
                onProgress = { _, _, _ -> calls++ },
                // 首条目回调前即取消：onProgress 必须一次都不触发。
                isCancelled = { true },
            )
            fail("expected IOException")
        } catch (error: IOException) {
            // expected
        }
        assertEquals("no progress callback may fire after instant cancel", 0, calls)
        assertFalse("partial output must be cleaned up", out.exists())
    }

    // ---- minimal independent table reader (mirrors the documented layout) ----

    private fun readTable(archive: File): Map<String, ByteArray> {
        val (_, entries) = readRawTableWithEntries(archive)
        return entries.associate { (name, offset, size) ->
            RandomAccessFile(archive, "r").use { raf ->
                raf.seek(offset)
                val data = ByteArray(size.toInt())
                raf.readFully(data)
                name to data
            }
        }
    }

    private fun readRawTable(archive: File): Pair<ByteArray, ByteArray> {
        // 返回 (表区字节, 首个条目数据)，供 XOR 反向断言。
        val (_, entries) = readRawTableWithEntries(archive)
        val (name, offset, size) = entries.first()
        RandomAccessFile(archive, "r").use { raf ->
            raf.seek(3)
            val indexSize = readLe32(raf)
            raf.seek(7)
            val table = ByteArray(indexSize)
            raf.readFully(table)
            raf.seek(offset)
            val data = ByteArray(size.toInt())
            raf.readFully(data)
            check(name.isNotEmpty())
            return table to data
        }
    }

    private fun readRawTableWithEntries(archive: File): Pair<ByteArray, List<Triple<String, Long, Long>>> {
        RandomAccessFile(archive, "r").use { raf ->
            require(raf.read() == 0x70 && raf.read() == 0x66)
            raf.read() // version
            val indexSize = readLe32(raf)
            val tableStart = raf.filePointer
            val table = ByteArray(indexSize)
            raf.readFully(table)
            raf.seek(tableStart)
            val count = readLe32(raf)
            val entries = mutableListOf<Triple<String, Long, Long>>()
            repeat(count) {
                val nameLen = readLe32(raf)
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                raf.skipBytes(4)
                val offset = readLe32(raf).toLong()
                val size = readLe32(raf).toLong()
                entries.add(Triple(String(nameBytes, Charsets.UTF_8), offset, size))
            }
            return table to entries
        }
    }

    private fun readLe32(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readLe64(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (b[i].toLong() and 0xFF)
        return value
    }
}
