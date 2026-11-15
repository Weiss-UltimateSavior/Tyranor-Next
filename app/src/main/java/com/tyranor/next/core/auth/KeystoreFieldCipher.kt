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
 */
internal object KeystoreFieldCipher {

    private const val TAG = "KeystoreFieldCipher"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /**
     * 解密一个字段；空/缺失/解密失败一律返回空串。失败时**不删除密文**：Keystore 瞬时
     * 异常（如冷启动早期）不应造成凭据不可逆丢失，下次成功解密即可恢复。
     */
    fun readField(context: Context, prefsName: String, keyAlias: String, field: String): String {
        val encoded = context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(field, null) ?: return ""
        if (encoded.isBlank()) return ""
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
        }.onFailure { throwable ->
            Log.e(TAG, "Field decrypt failed, keep ciphertext for retry", throwable)
        }.getOrDefault("")
    }

    /** 加密写入一个字段；plain 为空串时移除该字段。返回是否成功落盘。 */
    fun writeField(
        context: Context,
        prefsName: String,
        keyAlias: String,
        field: String,
        plain: String,
    ): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (plain.isEmpty()) {
            prefs.edit().remove(field).apply()
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(keyAlias))
            val iv = cipher.iv
            require(iv.size == GCM_IV_BYTES) { "unexpected GCM IV size" }
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ciphertext.size)
            iv.copyInto(out)
            ciphertext.copyInto(out, iv.size)
            prefs.edit().putString(field, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
            true
        }.onFailure { throwable ->
            Log.e(TAG, "Field encrypt failed", throwable)
        }.getOrDefault(false)
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
