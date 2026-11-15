package com.tyranor.next.core.auth

import android.content.Context

/**
 * Hikarinagi OAuth 令牌加密存储（迁移方案阶段 6）：AndroidKeyStore 内 AES/GCM 密钥，
 * 密文 + IV 存于独立 prefs 文件 hikarinagi_auth_secure，字段级加解密经 [KeystoreFieldCipher]。
 *
 * 该 prefs 文件必须保持排除出云备份/设备迁移（Keystore 密钥换机不可解密）。
 */
internal object SecureAuthStore {

    private const val PREFS = "hikarinagi_auth_secure"
    private const val KEY_ALIAS = "tyranor_hikarinagi_token"

    const val FIELD_ACCESS_TOKEN = "access_token"
    const val FIELD_REFRESH_TOKEN = "refresh_token"
    const val FIELD_EXPIRES_AT = "expires_at"
    const val FIELD_LAST_ERROR = "last_error"
    const val FIELD_MIGRATED = "legacy_migrated"

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 解密一个字段；空/缺失/解密失败一律返回空串，失败时保留密文以便下次重试。 */
    fun decryptField(context: Context, field: String): String =
        KeystoreFieldCipher.readField(context, PREFS, KEY_ALIAS, field)

    /** 加密写入一个字段；plain 为空串时移除该字段。返回是否成功落盘。 */
    fun encryptField(context: Context, field: String, plain: String): Boolean =
        KeystoreFieldCipher.writeField(context, PREFS, KEY_ALIAS, field, plain)
}
