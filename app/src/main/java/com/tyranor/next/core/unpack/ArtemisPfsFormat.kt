package com.tyranor.next.core.unpack

import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
/**
 * Artemis PFS 表格式的共享常量与编解码原语（解包器/封包器/单测同源）。
 *
 * 版式经真包逐字节验证（PF6/PF8 双样本：魔数、index_size、表项、表尾全一致）。
 * 刻意不收敛的配额类常量（解包侧防爆上限、指纹侧只读上限）各保留在自家文件，
 * 此处只放格式本身：意图不同的上限共享会导致误收紧或误放松。
 */
internal object ArtemisPfsFormat {
    const val MAGIC_0 = 0x70 // 'p'
    const val MAGIC_1 = 0x66 // 'f'
    const val VERSION_PF6 = 0x36 // '6'：明文包，全条目不加密
    const val VERSION_PF8 = 0x38 // '8'：加密包，条目经表密钥 XOR

    /** 加解密长度门限：解包/封包两侧对齐，小于此的条目按明文存放。 */
    const val MIN_ENCRYPTED_LEN = 8

    /** 表项名上限（字节），与解包器/指纹检测器同限。 */
    const val MAX_NAME_BYTES = 4096

    /** 表头基址：index 数据区起始（魔数 3 + index_size 4）。 */
    const val HEADER_BASE = 0x07

    /** 表尾基址：filesize 偏移相对该地址记录。 */
    const val TRAILER_BASE = 0x0F

    /** 读无符号 LE u32（旧 readM 的 `and 0x7fffffff` 会截断 >=2G 的 offset/size，已废弃）。 */
    fun readU32LE(raf: RandomAccessFile): Long {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("pfs truncated")
        return (b0.toLong() and 0xFF) or
            ((b1.toLong() and 0xFF) shl 8) or
            ((b2.toLong() and 0xFF) shl 16) or
            ((b3.toLong() and 0xFF) shl 24)
    }

    /** 表密钥 = SHA-1(表区 [HEADER_BASE, HEADER_BASE + indexSize))。 */
    fun deriveKey(table: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(table)

    /** 原地 XOR 加解密（对称），[baseOffset] 为数据在所属条目内的起始偏移。 */
    fun xorInPlace(data: ByteArray, length: Int, key: ByteArray, baseOffset: Long) {
        require(key.isNotEmpty()) { "pfs xor key must not be empty" }
        for (i in 0 until length) {
            data[i] = (data[i].toInt() xor key[((baseOffset + i) % key.size).toInt()].toInt()).toByte()
        }
    }

    /** 写无符号 LE u32，超限抛错（封包侧 4GB 格式上限守卫）。 */
    fun checkU32(value: Long, what: String): Int {
        if (value < 0 || value > 0xFFFFFFFFL) throw IOException("PFS pack: $what out of range: $value")
        return value.toInt()
    }
}
