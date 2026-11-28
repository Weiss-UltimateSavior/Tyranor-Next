package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MZ 存档载荷编解码（明文 JSON ↔ deflate 流）的回归测试。
 *
 * 这两组的契约：**幂等**（已转码的再转一次不变）+ **坏数据拒绝**（解压失败返回 null，
 * 调用方据此计数失败而不是写出坏文件）。
 */
class RpgSaveFormatPayloadTest {

    private fun mzJson(): ByteArray = """{"version":1,"party":{"_gold":500},"note":"你好 MZ"}""".toByteArray()

    @Test
    fun onlyMzNeedsTranscode() {
        assertTrue(RpgSaveFormat.needsPayloadTranscode(EngineType.RPG_MZ))
        assertFalse(RpgSaveFormat.needsPayloadTranscode(EngineType.RPG_MV))
        assertFalse(RpgSaveFormat.needsPayloadTranscode(EngineType.TYRANO))
    }

    @Test
    fun encodeThenDecodeRoundTrips() {
        val plain = mzJson()
        val packed = RpgSaveFormat.encodeMzPayload(plain)
        // 转码后必须是真的 deflate 流：首字节不是 '{'（zlib 头为 0x78）
        assertEquals(0x78.toByte(), packed[0])
        assertArrayEquals(plain, RpgSaveFormat.decodeMzPayload(packed))
    }

    @Test
    fun encodeActuallyCompressesLargePayload() {
        // 小载荷加上 zlib 头/尾反而会变大（正常）；用真实量级的可压缩载荷验证确实变小
        val plain = buildString {
            append("""{"version":1,"party":{"actors":[""")
            repeat(200) { append("""{"_actorId":$it,"_name":"Actor$it","_level":99},""") }
            append("]}}")
        }.toByteArray()
        val packed = RpgSaveFormat.encodeMzPayload(plain)
        assertTrue("packed=${packed.size} plain=${plain.size}", packed.size < plain.size)
        assertArrayEquals(plain, RpgSaveFormat.decodeMzPayload(packed))
    }

    @Test
    fun encodeIsIdempotent() {
        val plain = mzJson()
        val once = RpgSaveFormat.encodeMzPayload(plain)
        val twice = RpgSaveFormat.encodeMzPayload(once)
        // 已经是压缩流时原样返回，不能再压一层（否则 PC 版读到的是"压缩过的 JSON"）
        assertArrayEquals(once, twice)
    }

    @Test
    fun decodeIsIdempotentOnPlainJson() {
        val plain = mzJson()
        // 旧版 Tyranor 导出的 .rmmzsave 内容其实是明文 JSON，必须直通
        assertArrayEquals(plain, RpgSaveFormat.decodeMzPayload(plain))
    }

    @Test
    fun emptyPayloadPassesThrough() {
        assertEquals(0, RpgSaveFormat.encodeMzPayload(ByteArray(0)).size)
        assertArrayEquals(ByteArray(0), RpgSaveFormat.decodeMzPayload(ByteArray(0)))
    }

    @Test
    fun corruptPayloadIsRejected() {
        // 首字节非 '{' 且不是合法 deflate → 必须返回 null，不能产出垃圾
        assertNull(RpgSaveFormat.decodeMzPayload(byteArrayOf(0x01, 0x02, 0x03, 0x04)))
        // 截断的 deflate 流同样拒绝（宁可失败也不写半个文件）
        val packed = RpgSaveFormat.encodeMzPayload(mzJson())
        assertNull(RpgSaveFormat.decodeMzPayload(packed.copyOf(packed.size / 2)))
    }

    @Test
    fun jsonStartingWithBraceIsDetectedAsPlain() {
        // JsonEx 输出以 '{' 起；deflate 首字节模 8 不可能等于 3（'{'=0x7B），判别无歧义
        val plain = """{"a":1}""".toByteArray()
        assertNotNull(RpgSaveFormat.decodeMzPayload(plain))
        assertArrayEquals(plain, RpgSaveFormat.decodeMzPayload(plain))
    }
}