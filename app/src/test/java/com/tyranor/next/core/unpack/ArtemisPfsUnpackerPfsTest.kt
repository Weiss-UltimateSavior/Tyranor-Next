package com.tyranor.next.core.unpack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 解包器格式回归：PF6 明文包必须原文取出（曾无条件 XOR 导致损坏），PF8 包仍正常解密。
 *
 * system.ini 故意带齐 [ANDROID] 全部托管键，使 base-patch 的后处理成为 no-op，
 * 断言可以精确到逐字节相等。
 *
 * PINNED: 预置 [ANDROID] 全部托管键（WIDTH/HEIGHT/SIDECUT/BOOT/FONT_CACHE_SIZE），
 * 若托管键集合变化，此用例需同步增补，否则后处理改写会导致断言失败。
 */
class ArtemisPfsUnpackerPfsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val systemIni = """
        [SYSTEM]
        WIDTH = 1280
        HEIGHT = 720
        CHARSET = UTF-8

        [ANDROID]
        WIDTH = 1280
        HEIGHT = 720
        SIDECUT = 0
        BOOT = system/first.iet
        FONT_CACHE_SIZE = 8388608
    """.trimIndent()

    @Test
    fun pf6SystemIniIsExtractedVerbatim() {
        val gameDir = temporaryFolder.newFolder("game-pf6")
        gameDir.resolve("system.ini").writeText(systemIni)
        val archive = temporaryFolder.newFile("root.pfs")
        ArtemisPf6Packer.pack(gameDir, archive)
        gameDir.resolve("system.ini").delete()
        gameDir.resolve("root.pfs").writeBytes(archive.readBytes())

        assertTrue(ArtemisPfsUnpacker.needsBasePatch(gameDir.absolutePath))
        assertTrue(ArtemisPfsUnpacker.applyBasePatch(gameDir.absolutePath))

        assertArrayEquals(
            "PF6 plaintext must survive unpack without XOR damage",
            systemIni.toByteArray(Charsets.UTF_8),
            gameDir.resolve("system.ini").readBytes(),
        )
    }

    @Test
    fun pf8SystemIniIsStillDecrypted() {
        val gameDir = temporaryFolder.newFolder("game-pf8")
        gameDir.resolve("system.ini").writeText(systemIni)
        // PF8 由测试内最小 writer 直写（XOR 经共享格式原语），不依赖生产封包器。
        writeRawPf8(
            gameDir.resolve("root.pfs"),
            listOf("system.ini" to systemIni.toByteArray(Charsets.UTF_8)),
        )
        gameDir.resolve("system.ini").delete()

        assertTrue(ArtemisPfsUnpacker.needsBasePatch(gameDir.absolutePath))
        assertTrue(ArtemisPfsUnpacker.applyBasePatch(gameDir.absolutePath))

        assertArrayEquals(
            "PF8 entries must still be XOR-decrypted on unpack",
            systemIni.toByteArray(Charsets.UTF_8),
            gameDir.resolve("system.ini").readBytes(),
        )
    }

    /**
     * 最小 PF8 writer（单目录层级、ASCII 名）：表头按文档版式组装后整表 SHA-1
     * 派生密钥，数据区逐条目从偏移 0 起 XOR，与生产解包器互为独立实现。
     *
     * 独立性边界声明：密钥派生与 XOR 原语复用了 ArtemisPfsFormat（与被测代码同源），
     * 若派生范围/下标同错会双绿；表头组装、index_size 回填、数据布局为本测试独立实现，
     * 真包级语义仍以 Rust 侧 cargo test + 真包逐字节验证为准。
     */
    private fun writeRawPf8(archive: java.io.File, files: List<Pair<String, ByteArray>>) {
        val names = files.map { it.first.replace('/', '\\').toByteArray(Charsets.UTF_8) }
        val count = files.size
        fun u32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
        fun u64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        val header = mutableListOf<Byte>()
        header.addAll(byteArrayOf(0x70, 0x66, 0x38).toList())
        // index_size 回填：先占位。
        header.addAll(u32(0).toList())
        header.addAll(u32(count.toLong()).toList())
        var headerPos = 11L
        var dataOffset = 0L // 待回填，先按表头长度预留
        val sizeFieldPos = mutableListOf<Long>()
        // 先算表头长度以确定 dataOffset。
        val tableBody = 4L + files.indices.sumOf { 4L + names[it].size + 4L + 4L + 4L } +
            4L + (count + 1L) * 8L + 4L
        dataOffset = 7L + tableBody
        files.forEachIndexed { i, (_, data) ->
            header.addAll(u32(names[i].size.toLong()).toList())
            header.addAll(names[i].toList())
            header.addAll(ByteArray(4).toList())
            header.addAll(u32(dataOffset).toList())
            header.addAll(u32(data.size.toLong()).toList())
            sizeFieldPos.add(headerPos + 4L + names[i].size + 4L + 4L)
            headerPos += 4L + names[i].size + 4L + 4L + 4L
            dataOffset += data.size
        }
        val fscPos = headerPos
        header.addAll(u32((count + 1).toLong()).toList())
        sizeFieldPos.forEach { header.addAll(u64(it - 0x0F).toList()) }
        header.addAll(ByteArray(8).toList())
        header.addAll(u32(fscPos - 0x07).toList())
        val headerBytes = header.toByteArray()
        // 回填 index_size。
        val indexSize = (headerBytes.size - 7).toLong()
        u32(indexSize).copyInto(headerBytes, 3)
        val key = ArtemisPfsFormat.deriveKey(headerBytes.copyOfRange(7, 7 + indexSize.toInt()))
        archive.outputStream().use { out ->
            out.write(headerBytes)
            files.forEach { (_, data) ->
                val encrypted = data.copyOf()
                ArtemisPfsFormat.xorInPlace(encrypted, encrypted.size, key, 0L)
                out.write(encrypted)
            }
        }
    }
}
