package com.tyranor.next.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore 内 AES/GCM 密钥的字段级加密读写原语（internal，供本模块各安全存储复用）：
 * 密文 + IV 存于调用方指定的独立 prefs 文件，密钥按 keyAlias 隔离。
 *
 * 说明：androidx.security-crypto 在当前构建环境不可离线解析，且 EncryptedSharedPreferences
 * 已被官方弃用，故直接使用 Keystore 等价实现（同样的安全目标：凭据不落明文）。
 * 使用方必须把对应 prefs 文件保持排除出云备份 / 设备迁移（Keystore 密钥换机不可解密）。
 *
 * 写入一律用同步落盘的 `commit()`（`apply()` 只是内存提交，返回值不代表已落盘）；因此
 * [writeField] / [writeFields] 会阻塞当前线程，**必须在后台线程调用**。
 */
internal object KeystoreFieldCipher {

    private const val TAG = "KeystoreFieldCipher"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** 单个字段的读取结果：区分「字段不存在」与「存在但解密失败」（后者可重试）。 */
    sealed interface FieldRead {
        /** 解密成功（非空明文）。 */
        data class Value(val text: String) : FieldRead

        /** 字段不存在或为空（从未写入 / 已被移除）。 */
        data object Missing : FieldRead

        /** 字段存在但解密失败：Keystore 瞬时异常或密钥已不可用，可稍后重试。 */
        data object Failed : FieldRead
    }

    /**
     * 解密一个字段；缺失返回 [FieldRead.Missing]、解密失败返回 [FieldRead.Failed]。
     * 失败时**不删除密文**：Keystore 瞬时异常（如冷启动早期）不应造成凭据不可逆丢失，
     * 下次成功解密即可恢复。
     */
    fun readFieldState(context: Context, prefsName: String, keyAlias: String, field: String): FieldRead {
        val encoded = context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(field, null)
        if (encoded.isNullOrBlank()) return FieldRead.Missing
        return runCatching {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size > GCM_IV_BYTES) { "ciphertext too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(keyAlias),
                GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES),
            )
            String(cipher.doFinal(data, GCM_IV_BYTES, data.size - GCM_IV_BYTES), Charsets.UTF_8)
        }.fold(
            onSuccess = { FieldRead.Value(it) },
            onFailure = { throwable ->
                Log.e(TAG, "Field decrypt failed, keep ciphertext for retry", throwable)
                FieldRead.Failed
            },
        )
    }

    /**
     * 解密一个字段；缺失 / 解密失败一律返回空串。供只需要「有值就用」的读取方使用；
     * 需要区分「不存在」与「读取失败」的调用方（如应用锁凭据）改用 [readFieldState]。
     */
    fun readField(context: Context, prefsName: String, keyAlias: String, field: String): String =
        (readFieldState(context, prefsName, keyAlias, field) as? FieldRead.Value)?.text ?: ""

    /**
     * 加密写入多个字段：全部字段先加密成功，再用**同一个 Editor 原子提交**（一次 commit）。
     * 任一字段加密失败则整体不写。用于「盐 + 哈希」这类必须同时生效的成对字段——
     * 分两次提交时进程若在中间被杀，会留下新旧混搭的半套凭据。
     *
     * plain 为空串的字段被移除。阻塞调用，需在后台线程执行。
     */
    fun writeFields(
        context: Context,
        prefsName: String,
        keyAlias: String,
        values: Map<String, String>,
    ): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for ((field, plain) in values) {
            if (plain.isEmpty()) {
                editor.remove(field)
                continue
            }
            val encoded = runCatching { encrypt(keyAlias, plain) }
                .onFailure { Log.e(TAG, "Field encrypt failed", it) }
                .getOrNull() ?: return false
            editor.putString(field, encoded)
        }
        return runCatching { editor.commit() }
            .onFailure { Log.e(TAG, "Field commit failed", it) }
            .getOrDefault(false)
    }

    /** 加密写入一个字段；plain 为空串时移除该字段。阻塞调用，需在后台线程执行。 */
    fun writeField(
        context: Context,
        prefsName: String,
        keyAlias: String,
        field: String,
        plain: String,
    ): Boolean = writeFields(context, prefsName, keyAlias, mapOf(field to plain))

    /** 加密单个明文，返回 iv || ciphertext 的 Base64。 */
    private fun encrypt(keyAlias: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(keyAlias))
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "unexpected GCM IV size" }
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(out)
        ciphertext.copyInto(out, iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun secretKey(keyAlias: String): SecretKey = synchronized(this) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }
}
