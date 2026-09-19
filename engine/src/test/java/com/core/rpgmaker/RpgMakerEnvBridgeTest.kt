package com.core.rpgmaker

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生环境桥的纯 JVM 行为锁定。
 *
 * 这些能力直接决定插件能否正确解密/校验游戏数据，且 PBKDF2/Zlib 有自研成分，
 * 因此用标准实现（JDK 的 MessageDigest/Mac/Cipher、公开测试向量）交叉验证。
 * 注意：android.util.Base64 在 JVM 单测里不可用，这里只测不依赖它的方法。
 */
class RpgMakerEnvBridgeTest {

    private val bridge = RpgMakerEnvBridge()

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun digestMatchesJdkForCommonAlgorithms() {
        val data = "hello 日本語".toByteArray(Charsets.UTF_8)
        listOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512").forEach { (name, jdk) ->
            val expected = MessageDigest.getInstance(jdk).digest(data)
                .joinToString("") { "%02x".format(it) }
            assertEquals("digest($name)", expected, bridge.digest(name, b64(data), "hex"))
        }
    }

    @Test
    fun digestSupportsBase64Output() {
        val data = "abc".toByteArray(Charsets.UTF_8)
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data))
        assertEquals(expected, bridge.digest("sha256", b64(data), "base64"))
    }

    @Test
    fun hmacMatchesJdk() {
        val key = "secret-key".toByteArray(Charsets.UTF_8)
        val data = "message".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val expected = mac.doFinal(data).joinToString("") { "%02x".format(it) }
        assertEquals(expected, bridge.hmac("sha256", b64(key), b64(data), "hex"))
    }

    /** RFC 6070 的 PBKDF2-HMAC-SHA1 测试向量（自研实现必须对得上）。 */
    @Test
    fun pbkdf2MatchesRfc6070Vectors() {
        val password = "password".toByteArray(Charsets.UTF_8)
        val salt = "salt".toByteArray(Charsets.UTF_8)
        assertEquals(
            "0c60c80f961f0e71f3a9b524af6012062fe037a6",
            bridge.pbkdf2(b64(password), b64(salt), 1, 20, "sha1").let(::hexOfBase64),
        )
        assertEquals(
            "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957",
            bridge.pbkdf2(b64(password), b64(salt), 2, 20, "sha1").let(::hexOfBase64),
        )
        assertEquals(
            "4b007901b765489abead49d926f721d065a429c1",
            bridge.pbkdf2(b64(password), b64(salt), 4096, 20, "sha1").let(::hexOfBase64),
        )
    }

    @Test
    fun pbkdf2SupportsSha256AndLength() {
        val derived = bridge.pbkdf2(b64("pw".toByteArray()), b64("salt".toByteArray()), 100, 32, "sha256")
        assertEquals(32, Base64.getDecoder().decode(derived).size)
    }

    @Test
    fun timingSafeEqualComparesContentNotIdentity() {
        assertTrue(bridge.timingSafeEqual(b64("abc".toByteArray()), b64("abc".toByteArray())))
        assertFalse(bridge.timingSafeEqual(b64("abc".toByteArray()), b64("abd".toByteArray())))
        assertFalse(bridge.timingSafeEqual(b64("abc".toByteArray()), b64("abcd".toByteArray())))
    }

    @Test
    fun randomBytesProducesRequestedLengthAndVaries() {
        val first = Base64.getDecoder().decode(bridge.randomBytes(16))
        val second = Base64.getDecoder().decode(bridge.randomBytes(16))
        assertEquals(16, first.size)
        assertFalse("randomBytes 两次结果不应相同", first.contentEquals(second))
        assertEquals("", bridge.randomBytes(0))
    }

    @Test
    fun randomUuidHasExpectedShape() {
        val uuid = bridge.randomUuid()
        assertTrue("uuid=$uuid", Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(uuid))
    }

    @Test
    fun cipherRoundTripsAndMatchesJdk() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it * 3).toByte() }
        val plain = "存档内容 secret".toByteArray(Charsets.UTF_8)

        val encrypted = bridge.cipher("aes-256-cbc", b64(key), b64(iv), b64(plain), true, true)
        val jdkCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        jdkCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        assertEquals(b64(jdkCipher.doFinal(plain)), encrypted)

        val decrypted = bridge.cipher("aes-256-cbc", b64(key), b64(iv), encrypted, false, true)
        assertEquals(String(Base64.getDecoder().decode(decrypted), Charsets.UTF_8), String(plain, Charsets.UTF_8))
    }

    @Test
    fun cipherRejectsWrongKeySizeInsteadOfReturningGarbage() {
        // 16 字节密钥配 aes-256 必须被拒绝（返回空串由 JS 侧抛错），不能静默产出错值
        assertEquals("", bridge.cipher("aes-256-cbc", b64(ByteArray(16)), b64(ByteArray(16)), b64("x".toByteArray()), true, true))
    }

    @Test
    fun cipherSupportsEcbWithoutIv() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val plain = "blockdata16bytes".toByteArray(Charsets.UTF_8)
        val encrypted = bridge.cipher("aes-128-ecb", b64(key), "", b64(plain), true, true)
        val jdkCipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        jdkCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        assertEquals(b64(jdkCipher.doFinal(plain)), encrypted)
    }

    @Test
    fun zlibRoundTripsAllContainers() {
        val text = "圧縮テスト".repeat(20).toByteArray(Charsets.UTF_8)
        listOf("deflate" to "inflate", "deflateRaw" to "inflateRaw", "gzip" to "gunzip").forEach { (compress, decompress) ->
            val packed = bridge.zlib(compress, b64(text), -1)
            assertTrue("$compress 应产出数据", packed.isNotEmpty())
            assertEquals("$decompress", b64(text), bridge.zlib(decompress, packed, -1))
        }
        // unzip 自动识别三种容器
        listOf("deflate", "deflateRaw", "gzip").forEach { compress ->
            val packed = bridge.zlib(compress, b64(text), -1)
            assertEquals("unzip 识别 $compress", b64(text), bridge.zlib("unzip", packed, -1))
        }
    }

    @Test
    fun zlibCompressesAndRejectsCorruptInput() {
        val text = "a".repeat(500).toByteArray(Charsets.UTF_8)
        val packed = bridge.zlib("deflate", b64(text), -1)
        assertTrue("重复文本应显著压缩", Base64.getDecoder().decode(packed).size < text.size / 2)
        // 损坏数据必须返回空串（JS 侧抛错），而不是静默给错值
        assertEquals("", bridge.zlib("inflate", b64("not-zlib-data".toByteArray()), -1))
    }

    @Test
    fun crc32MatchesKnownValue() {
        // "123456789" 的标准 CRC-32 结果为 0xcbf43926
        assertEquals("cbf43926", bridge.crc32(b64("123456789".toByteArray())))
    }

    @Test
    fun zlibRejectsTruncatedStreamInsteadOfReturningPartialData() {
        // 截断/损坏的数据必须失败：返回半截结果会让调用方把坏数据当有效数据用
        val text = "x".repeat(5000).toByteArray(Charsets.UTF_8)
        val packed = Base64.getDecoder().decode(bridge.zlib("deflate", b64(text), -1))
        val truncated = Base64.getEncoder().encodeToString(packed.copyOf(packed.size - 10))
        assertEquals("截断的 deflate 流必须被拒绝", "", bridge.zlib("inflate", truncated, -1))

        val corrupted = packed.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2] + 1).toByte()
        assertEquals(
            "损坏的 deflate 流必须被拒绝",
            "",
            bridge.zlib("inflate", Base64.getEncoder().encodeToString(corrupted), -1),
        )
    }

    @Test
    fun hmacRejectsUnsupportedAlgorithmInsteadOfSilentlyUsingSha256() {
        // 静默降级会让调用方拿到「算法不对但格式正确」的摘要
        val key = b64("k".toByteArray())
        val data = b64("m".toByteArray())
        assertEquals("ripemd160 不受支持", "", bridge.hmac("ripemd160", key, data, "hex"))

        // 支持的名字必须真的用对应算法（sha224 与 sha256 结果不同）
        val sha224 = bridge.hmac("sha224", key, data, "hex")
        val sha256 = bridge.hmac("sha256", key, data, "hex")
        assertTrue("sha224 应产出结果", sha224.isNotEmpty())
        assertTrue("sha224 不能等于 sha256", sha224 != sha256)
    }

    @Test
    fun digestSupportsBase64UrlAndLatin1Output() {
        val data = b64("x".toByteArray())
        val expectedUrl = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest("x".toByteArray(Charsets.UTF_8)))
        assertEquals(expectedUrl, bridge.digest("sha256", data, "base64url"))
        // latin1 输出：字节 → 单字符映射
        val latin1 = bridge.digest("sha256", data, "latin1")
        assertEquals(32, latin1.length)
    }

    @Test
    fun memoryReportingIsSane() {
        assertTrue(bridge.maxMemory() > 0)
        assertTrue(bridge.totalMemory() > 0)
        assertTrue(bridge.freeMemory() >= 0)
    }

    @Test
    fun argvIsNonEmptyLikeNwjsDoes() {
        // 部分游戏校验 argv 存在才继续；JoiPlay 同样返回固定随机串
        val argv = bridge.argv()
        assertTrue("argv=$argv", argv.startsWith("[") && argv.length > 5)
        assertEquals(argv, bridge.argv()) // 同一实例内稳定
    }

    private fun hexOfBase64(value: String): String =
        Base64.getDecoder().decode(value).joinToString("") { "%02x".format(it) }
}
