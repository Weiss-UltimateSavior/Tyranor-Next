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
 * Hikarinagi OAuth 令牌加密存储（迁移方案阶段 6）：AndroidKeyStore 内 AES/GCM 密钥，
 * 密文 + IV 存于独立 prefs 文件 hikarinagi_auth_secure。
 *
 * 说明：androidx.security-crypto 在当前构建环境不可离线解析，且 EncryptedSharedPreferences
 * 已被官方弃用，故直接使用 Keystore 等价实现（同样的安全目标：令牌不落明文）。
 * 该 prefs 文件必须保持排除出云备份/设备迁移（Keystore 密钥换机不可解密）。
 */
internal object SecureAuthStore {

    private const val TAG = "SecureAuthStore"
    private const val PREFS = "hikarinagi_auth_secure"
    private const val KEY_ALIAS = "tyranor_hikarinagi_token"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    const val FIELD_ACCESS_TOKEN = "access_token"
    const val FIELD_REFRESH_TOKEN = "refresh_token"
    const val FIELD_EXPIRES_AT = "expires_at"
    const val FIELD_LAST_ERROR = "last_error"
    const val FIELD_MIGRATED = "legacy_migrated"

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 解密一个字段；空/缺失/解密失败一律返回空串。失败时**不删除密文**：Keystore 瞬时
     * 异常（如冷启动早期）不应造成令牌不可逆丢失，下次成功解密即可恢复登录态。
     */
    fun decryptField(context: Context, field: String): String {
        val prefs = prefs(context)
        val encoded = prefs.getString(field, null) ?: return ""
        if (encoded.isBlank()) return ""
        return runCatching {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size > GCM_IV_BYTES) { "ciphertext too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES),
            )
            String(cipher.doFinal(data, GCM_IV_BYTES, data.size - GCM_IV_BYTES), Charsets.UTF_8)
        }.onFailure { throwable ->
            Log.e(TAG, "Token field decrypt failed, keep ciphertext for retry", throwable)
        }.getOrDefault("")
    }

    /** 加密写入一个字段；plain 为空串时移除该字段。返回是否成功落盘。 */
    fun encryptField(context: Context, field: String, plain: String): Boolean {
        val prefs = prefs(context)
        if (plain.isEmpty()) {
            prefs.edit().remove(field).apply()
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            require(iv.size == GCM_IV_BYTES) { "unexpected GCM IV size" }
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ciphertext.size)
            iv.copyInto(out)
            ciphertext.copyInto(out, iv.size)
            prefs.edit().putString(field, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
            true
        }.onFailure { throwable ->
            Log.e(TAG, "Token field encrypt failed", throwable)
        }.getOrDefault(false)
    }

    private fun secretKey(): SecretKey = synchronized(this) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
