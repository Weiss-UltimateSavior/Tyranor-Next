package com.core.rpgmaker

import android.util.Log
import android.webkit.JavascriptInterface
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RPG Maker MV/MZ v2 会话的 Node/NW.js 环境桥：浏览器没有的原生能力交给 Android。
 *
 * 与 [RpgMakerFsBridge] 的分工：那个管文件，这个管「计算」——哈希、HMAC、随机数、
 * KDF、对称加密、zlib 压缩。这些能力用纯 JS 复刻要么不可行（AES/zlib 体积庞大），
 * 要么容易写出静默错值（自研哈希），因此统一走原生实现。
 *
 * 所有二进制参数以 base64 传递（JavaBridge 只能传字符串），返回值同样。
 * 失败统一返回空串（JS 侧转成带 code 的 Error 抛出），不静默给错值。
 */
internal class RpgMakerEnvBridge {

    private val secureRandom = SecureRandom()

    /** NW.js 的 App.argv：部分游戏会校验其存在与形态。 */
    @JavascriptInterface
    fun argv(): String = "[\"--${argvToken}\"]"

    /**
     * 摘要。algorithm 接受 node 风格名（sha256/md5/sha1/...）；
     * outputEncoding 为 "hex" 或 "base64"（默认 hex）。
     */
    @JavascriptInterface
    fun digest(algorithm: String?, dataBase64: String?, outputEncoding: String?): String {
        val input = decode(dataBase64) ?: return ""
        return try {
            val md = MessageDigest.getInstance(javaName(algorithm))
            encode(md.digest(input), outputEncoding)
        } catch (error: Throwable) {
            Log.w(TAG, "digest failed algo=$algorithm", error)
            ""
        }
    }

    @JavascriptInterface
    fun hmac(algorithm: String?, keyBase64: String?, dataBase64: String?, outputEncoding: String?): String {
        val key = decode(keyBase64) ?: return ""
        val data = decode(dataBase64) ?: return ""
        val javaName = hmacJavaName(algorithm)
        if (javaName == null) {
            Log.w(TAG, "hmac rejected (unsupported algorithm): $algorithm")
            return ""
        }
        return try {
            val mac = Mac.getInstance(javaName)
            mac.init(SecretKeySpec(key, mac.algorithm))
            encode(mac.doFinal(data), outputEncoding)
        } catch (error: Throwable) {
            Log.w(TAG, "hmac failed algo=$algorithm", error)
            ""
        }
    }

    /** 密码学安全随机数（base64）。 */
    @JavascriptInterface
    fun randomBytes(count: Int): String {
        if (count <= 0 || count > MAX_BYTES) return ""
        val bytes = ByteArray(count)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    @JavascriptInterface
    fun randomUuid(): String = UUID.randomUUID().toString()

    /**
     * PBKDF2（Node 的 crypto.pbkdf2Sync）。
     *
     * 手工实现而非走 SecretKeyFactory：`PBEKeySpec` 只收 char[]，provider 按 UTF-8
     * 重新编码口令，二进制口令与长度不足的口令都会与 Node 产生不同派生结果；
     * 这里直接对原始字节做 PRF 迭代，与 RFC 2898 及 Node 逐字节一致。
     */
    @JavascriptInterface
    fun pbkdf2(
        passwordBase64: String?,
        saltBase64: String?,
        iterations: Int,
        keyLength: Int,
        digestAlgorithm: String?,
    ): String {
        val password = decode(passwordBase64) ?: return ""
        val salt = decode(saltBase64) ?: return ""
        if (iterations <= 0 || keyLength <= 0 || keyLength > MAX_BYTES) return ""
        return try {
            val macAlgorithm = hmacJavaName(digestAlgorithm) ?: "HmacSHA1"
            val mac = Mac.getInstance(macAlgorithm)
            mac.init(SecretKeySpec(password, mac.algorithm))
            val hmacLen = mac.macLength
            val blocks = (keyLength + hmacLen - 1) / hmacLen
            val output = ByteArray(blocks * hmacLen)
            val blockInput = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, blockInput, 0, salt.size)
            for (block in 1..blocks) {
                // INT_32_BE(block)
                blockInput[salt.size] = (block ushr 24).toByte()
                blockInput[salt.size + 1] = (block ushr 16).toByte()
                blockInput[salt.size + 2] = (block ushr 8).toByte()
                blockInput[salt.size + 3] = block.toByte()
                var u = mac.doFinal(blockInput)
                val accumulator = u.copyOf()
                for (round in 2..iterations) {
                    u = mac.doFinal(u)
                    for (index in accumulator.indices) {
                        accumulator[index] = (accumulator[index].toInt() xor u[index].toInt()).toByte()
                    }
                }
                System.arraycopy(accumulator, 0, output, (block - 1) * hmacLen, hmacLen)
            }
            Base64.getEncoder().encodeToString(output.copyOf(keyLength))
        } catch (error: Throwable) {
            Log.w(TAG, "pbkdf2 failed digest=$digestAlgorithm", error)
            ""
        }
    }

    @JavascriptInterface
    fun timingSafeEqual(aBase64: String?, bBase64: String?): Boolean {
        val a = decode(aBase64) ?: return false
        val b = decode(bBase64) ?: return false
        return MessageDigest.isEqual(a, b)
    }

    /**
     * 对称加解密（Node 的 createCipheriv/createDecipheriv 底层）。
     * algorithm 形如 "aes-256-cbc"/"aes-128-ecb"/"aes-256-ctr"；
     * encrypt=true 为加密。iv 为 CBC/CTR 时必需。
     */
    @JavascriptInterface
    fun cipher(
        algorithm: String?,
        keyBase64: String?,
        ivBase64: String?,
        dataBase64: String?,
        encrypt: Boolean,
        autoPadding: Boolean,
    ): String {
        val key = decode(keyBase64) ?: return ""
        val data = decode(dataBase64) ?: ByteArray(0)
        val spec = cipherSpec(algorithm) ?: return ""
        if (key.size != spec.keyBytes) {
            Log.w(TAG, "cipher key size mismatch: ${key.size} != ${spec.keyBytes} ($algorithm)")
            return ""
        }
        return try {
            val cipher = Cipher.getInstance(spec.transformationFor(autoPadding))
            val keySpec = SecretKeySpec(key, spec.base)
            val iv = decode(ivBase64)
            if (spec.ivBytes > 0) {
                if (iv == null || iv.size != spec.ivBytes) {
                    Log.w(TAG, "cipher iv invalid for $algorithm")
                    return ""
                }
                cipher.init(
                    if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    keySpec,
                    IvParameterSpec(iv),
                )
            } else {
                cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
            }
            Base64.getEncoder().encodeToString(cipher.doFinal(data))
        } catch (error: Throwable) {
            Log.w(TAG, "cipher failed algo=$algorithm encrypt=$encrypt", error)
            ""
        }
    }

    /**
     * zlib 同步入口。mode：
     *   inflate     RFC1950 zlib 容器
     *   inflateRaw  RFC1951 裸 deflate
     *   gunzip      gzip 容器
     *   unzip       自动识别（gzip / zlib / raw 依次尝试）
     *   deflate / deflateRaw / gzip 为对应压缩
     */
    @JavascriptInterface
    fun zlib(mode: String?, dataBase64: String?, level: Int): String {
        val input = decode(dataBase64) ?: return ""
        return try {
            when (mode) {
                "inflate" -> inflate(input, nowrap = false)
                "inflateRaw" -> inflate(input, nowrap = true)
                "gunzip" -> gunzipBounded(input)
                "unzip" -> autoInflate(input)
                "deflate" -> deflate(input, wrap = Wrap.ZLIB, level = level)
                "deflateRaw" -> deflate(input, wrap = Wrap.RAW, level = level)
                "gzip" -> deflate(input, wrap = Wrap.GZIP, level = level)
                else -> return ""
            }.let { Base64.getEncoder().encodeToString(it) }
        } catch (error: Throwable) {
            // 数据损坏是常态（游戏可能拿它做特征探测），只记 debug 级
            Log.d(TAG, "zlib $mode failed: ${error.javaClass.simpleName}")
            ""
        }
    }

    @JavascriptInterface
    fun crc32(dataBase64: String?): String {
        val input = decode(dataBase64) ?: return ""
        val crc = CRC32()
        crc.update(input)
        return java.lang.Long.toHexString(crc.value).padStart(8, '0')
    }

    @JavascriptInterface
    fun totalMemory(): Long = Runtime.getRuntime().totalMemory()

    @JavascriptInterface
    fun maxMemory(): Long = Runtime.getRuntime().maxMemory()

    @JavascriptInterface
    fun freeMemory(): Long = Runtime.getRuntime().freeMemory()

    // ---- 内部实现 ----

    private enum class Wrap { ZLIB, RAW, GZIP }

    private fun inflate(input: ByteArray, nowrap: Boolean): ByteArray {
        val inflater = Inflater(nowrap)
        inflater.setInput(input)
        val out = ByteArrayOutputStream(input.size.coerceAtLeast(64) * 2)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, count)
                if (out.size() > MAX_BYTES) throw IllegalStateException("inflate output too large")
            }
            // 数据被截断（needsInput）或字典缺失时，Inflater 不会 finished。
            // 返回半截结果会让调用方把损坏数据当有效数据用（Node 在此抛错），
            // 因此必须显式失败。
            if (!inflater.finished()) {
                throw IllegalStateException("unexpected end of file / incomplete zlib stream")
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    /** 依次尝试 gzip → zlib → raw，用于 Node 的 zlib.unzip 语义。 */
    private fun autoInflate(input: ByteArray): ByteArray {
        if (input.size >= 2 && input[0] == 0x1f.toByte() && input[1] == 0x8b.toByte()) {
            return gunzipBounded(input)
        }
        runCatching { return inflate(input, nowrap = false) }
        return inflate(input, nowrap = true)
    }

    /**
     * 有界 gzip 解压。
     *
     * `GZIPInputStream.readBytes()` 会无界读到流结束——gzip 的膨胀比可达千倍
     * （实测 100MB 全零压缩后仅约 100KB），一个几十 KB 的载荷就能撑爆引擎进程堆。
     * 这里边读边计长度，超过上限立即失败（与 [inflate] 的 MAX_BYTES 语义一致）。
     */
    private fun gunzipBounded(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size.coerceAtLeast(64) * 2)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        GZIPInputStream(input.inputStream()).use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (out.size() + count > MAX_BYTES) {
                    throw IllegalStateException("gzip output too large (exceeds ${MAX_BYTES} bytes)")
                }
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray()
    }

    private fun deflate(input: ByteArray, wrap: Wrap, level: Int): ByteArray {
        val effective = if (level in 0..9) level else Deflater.DEFAULT_COMPRESSION
        val out = ByteArrayOutputStream(input.size.coerceAtLeast(64))
        when (wrap) {
            // GZIPOutputStream 自带 deflate 与 gzip 头/尾；不能再自己写一份 deflate
            // 数据进去，否则产出「gzip 头 + 裸 deflate」的畸形流（gunzip 会失败）。
            Wrap.GZIP -> GZIPOutputStream(out).use { it.write(input) }
            Wrap.ZLIB -> DeflaterOutputStream(out, Deflater(effective)).use { it.write(input) }
            Wrap.RAW -> DeflaterOutputStream(out, Deflater(effective, true)).use { it.write(input) }
        }
        return out.toByteArray()
    }

    private class CipherSpec(
        val base: String,
        val keyBytes: Int,
        val ivBytes: Int,
        private val transformationPadded: String,
        private val transformationRaw: String,
    ) {
        /** Node 的 autoPadding=false 对应 NoPadding 变换，需换一个 transformation。 */
        fun transformationFor(autoPadding: Boolean): String =
            if (autoPadding) transformationPadded else transformationRaw
    }

    private fun cipherSpec(algorithm: String?): CipherSpec? {
        val raw = algorithm?.trim()?.lowercase() ?: return null
        val parts = raw.split("-")
        if (parts.size < 3 || parts[0] != "aes") return null
        val keyBytes = when (parts[1]) {
            "128" -> 16
            "192" -> 24
            "256" -> 32
            else -> return null
        }
        return when (parts[2]) {
            "cbc" -> CipherSpec("AES", keyBytes, 16, "AES/CBC/PKCS5Padding", "AES/CBC/NoPadding")
            "ecb" -> CipherSpec("AES", keyBytes, 0, "AES/ECB/PKCS5Padding", "AES/ECB/NoPadding")
            "ctr" -> CipherSpec("AES", keyBytes, 16, "AES/CTR/NoPadding", "AES/CTR/NoPadding")
            "cfb" -> CipherSpec("AES", keyBytes, 16, "AES/CFB/NoPadding", "AES/CFB/NoPadding")
            "ofb" -> CipherSpec("AES", keyBytes, 16, "AES/OFB/NoPadding", "AES/OFB/NoPadding")
            else -> null
        }
    }

    private fun javaName(algorithm: String?): String = when (algorithm?.trim()?.lowercase()?.replace("-", "")) {
        "md5" -> "MD5"
        "sha1" -> "SHA-1"
        "sha256" -> "SHA-256"
        "sha384" -> "SHA-384"
        "sha512" -> "SHA-512"
        "sha3224" -> "SHA3-224"
        "sha3256" -> "SHA3-256"
        "sha3384" -> "SHA3-384"
        "sha3512" -> "SHA3-512"
        else -> algorithm?.trim().orEmpty().ifEmpty { "SHA-256" }
    }

    /**
     * HMAC 算法名映射。未知算法返回 null 由调用方抛错——
     * 静默降级为 SHA256 会让调用方拿到「格式正确但算法错误」的摘要。
     */
    private fun hmacJavaName(algorithm: String?): String? = when (algorithm?.trim()?.lowercase()?.replace("-", "")) {
        "md5" -> "HmacMD5"
        "sha1" -> "HmacSHA1"
        "sha224" -> "HmacSHA224"
        "sha256" -> "HmacSHA256"
        "sha384" -> "HmacSHA384"
        "sha512" -> "HmacSHA512"
        else -> null
    }

    /**
     * 摘要输出编码。必须支持 Node 的 base64url / latin1 / binary，
     * 否则调用方会拿到 hex 却被当成别的编码使用（静默错值）。
     */
    private fun encode(bytes: ByteArray, outputEncoding: String?): String = when (outputEncoding?.trim()?.lowercase()) {
        "base64" -> Base64.getEncoder().encodeToString(bytes)
        "base64url" -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        "latin1", "binary" -> String(bytes, Charsets.ISO_8859_1)
        "utf8", "utf-8" -> String(bytes, StandardCharsets.UTF_8)
        else -> bytes.joinToString("") { "%02x".format(it) }
    }

    private fun decode(value: String?): ByteArray? {
        if (value == null) return null
        return try {
            decodeBase64Lenient(value)
        } catch (error: Throwable) {
            Log.w(TAG, "base64 decode failed", error)
            null
        }
    }

    /**
     * 宽松 base64 解码：容忍换行/空白。游戏数据里的 base64 常带换行，
     * 严格解码器会直接失败（MimeDecoder 与原先 android.util.Base64.DEFAULT 语义一致）。
     */
    private fun decodeBase64Lenient(value: String): ByteArray =
        Base64.getMimeDecoder().decode(value)

    private val argvToken: String by lazy {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "YukiRpgMaker"
        private const val MAX_BYTES = 64 * 1024 * 1024
    }
}
