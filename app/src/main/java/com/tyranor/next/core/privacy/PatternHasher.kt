package com.tyranor.next.core.privacy

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 图案凭据的哈希与盐生成（纯 JVM，便于单测）。
 *
 * 图案是低熵凭据，哈希必须带盐：盐与哈希一起存储（两者再经 Keystore 加密保管，
 * 见 [AppLockCredentialStore]），盐本身仅用于阻止对固定哈希值的预计算比对。
 */
internal object PatternHasher {

    /** 图案盘点数（3×3）。 */
    const val PATTERN_POINT_COUNT = 9

    /** 合法图案的最少点数（与系统图案锁一致）。 */
    const val MIN_POINTS = 4

    /** 盐长度（字节）。 */
    const val SALT_BYTES = 16

    /** 生成新盐。 */
    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * 计算图案哈希：SHA-256(salt || 按下顺序点序)。
     * 点数不足 [MIN_POINTS]、越界或重复的点序一律返回 null，调用方不得把空结果当作合法凭据。
     */
    fun hash(salt: ByteArray, points: List<Int>): String? {
        if (salt.isEmpty()) return null
        val normalized = normalize(points) ?: return null
        if (normalized.size < MIN_POINTS) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(normalized.joinToString(separator = ".").toByteArray(Charsets.US_ASCII))
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    /**
     * 点序归一化：相邻两点若同行 / 同列 / 同对角且中间隔着一点（如 0→2、0→6、0→8），
     * 把中间点补进去。系统图案锁同样会途经中间点，用户手指划过时也可能不精确经过中心，
     * 不归一化会让同一形状产生不同点序，导致保存与校验不一致而误拒。
     *
     * 保存与校验共用本函数。非法输入（越界 / 重复点）返回 null。
     */
    fun normalize(points: List<Int>): List<Int>? {
        if (points.isEmpty()) return null
        if (points.any { it !in 0 until PATTERN_POINT_COUNT }) return null
        if (points.size != points.distinct().size) return null
        val result = ArrayList<Int>(points.size + 2)
        result += points.first()
        for (index in 1 until points.size) {
            val from = points[index - 1]
            val to = points[index]
            val mid = middlePoint(from, to)
            if (mid != null && mid !in result) result += mid
            result += to
        }
        return result
    }

    /** 两点同心同列 / 同行 / 同对角线且相隔一格时，返回其中间点；否则返回 null。 */
    private fun middlePoint(from: Int, to: Int): Int? {
        val fx = from % 3
        val fy = from / 3
        val tx = to % 3
        val ty = to / 3
        val dx = tx - fx
        val dy = ty - fy
        // 只在「整两格」且共线（行 / 列 / 对角）时才有中间点
        if (kotlin.math.abs(dx) != 2 && kotlin.math.abs(dy) != 2) return null
        if (dx != 0 && dy != 0 && kotlin.math.abs(dx) != kotlin.math.abs(dy)) return null
        val mx = (fx + tx) / 2
        val my = (fy + ty) / 2
        return my * 3 + mx
    }
}
