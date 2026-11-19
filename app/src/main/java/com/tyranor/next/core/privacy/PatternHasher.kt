package com.tyranor.next.core.privacy

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 图案凭据的哈希与盐生成（纯 JVM，便于单测）。
 *
 * 图案是低熵凭据，哈希必须带盐：盐与哈希一起存储（两者再经 Keystore 加密保管，
 * 见 [AppLockCredentialStore]），盐本身仅用于阻止对固定哈希值的预计算比对。
 *
 * **按按下顺序精确匹配，不做形状归一化**：只要求「形状相同」会让跳点、回扫等变体
 * 也被当成同一凭据，削弱应用锁；因此 0→2 与 0→1→2 是两个不同的图案。快速划过时
 * 未命中的中间点不会自动补入，用户需要完整经过要包含的每个点。
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
     * 点序是否可保存 / 校验：点数 >= [MIN_POINTS]、下标在 0..8 且无重复。
     * **不补中间点、不重排**，点序就是用户实际按下的顺序。
     */
    fun isValidPattern(points: List<Int>): Boolean =
        points.size >= MIN_POINTS &&
            points.all { it in 0 until PATTERN_POINT_COUNT } &&
            points.size == points.distinct().size

    /**
     * 计算图案哈希：SHA-256(salt || 按下顺序点序)。
     * 点数不足 [MIN_POINTS]、越界或重复的点序一律返回 null，调用方不得把空结果当作合法凭据。
     */
    fun hash(salt: ByteArray, points: List<Int>): String? {
        if (salt.isEmpty()) return null
        if (!isValidPattern(points)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(points.joinToString(separator = ".").toByteArray(Charsets.US_ASCII))
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
