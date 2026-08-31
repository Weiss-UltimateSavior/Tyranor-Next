package com.tyranor.next.core.auth

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

data class HikarinagiAuthStatus(
    val authorized: Boolean,
    val needsReauth: Boolean,
    val expiresAtMillis: Long,
    val lastError: String,
)

/**
 * Hikarinagi OAuth 令牌存取门面（迁移方案阶段 6）：令牌实际存储已迁到
 * [SecureAuthStore]（AndroidKeyStore AES/GCM 加密）；首次访问时把旧
 * hikarinagi_auth prefs 中的明文令牌迁移进加密存储并清空旧文件，
 * 公开 API 与迁移前完全一致。该文件与新加密 prefs 均排除出备份。
 */
object HikarinagiAuthStore {
    private const val TAG = "HikarinagiAuthStore"
    private const val LEGACY_PREFS = "hikarinagi_auth"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_LAST_ERROR = "last_error"

    /** Keystore 加密失败时置入 last_error，令 UI 走重新授权路径而非静默登出。 */
    private const val SECURE_STORAGE_ERROR = "secure storage unavailable"

    val statusVersion: MutableState<Int> = mutableStateOf(0)

    @Volatile
    private var migrationDone = false

    private fun legacyPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    /**
     * 一次性迁移：旧明文令牌加密入库并校验后才清空旧文件（校验失败保留旧文件下次重试）。
     * 快路径：持久化迁移标记或加密库已有令牌（如迁移失败后 saveTokens 已写入新令牌）时
     * 只清理旧文件，避免下次启动用陈旧明文覆盖新登录态。last_error/expires_at 非敏感，
     * 无条件迁移（保留「需要重新授权」状态）。
     */
    private fun ensureMigrated(context: Context) {
        if (migrationDone) return
        synchronized(this) {
            if (migrationDone) return
            try {
                val legacy = legacyPrefs(context)
                val secure = SecureAuthStore.prefs(context)
                val hasSecureTokens = secure.contains(SecureAuthStore.FIELD_ACCESS_TOKEN) ||
                    secure.contains(SecureAuthStore.FIELD_REFRESH_TOKEN)
                if (secure.getBoolean(SecureAuthStore.FIELD_MIGRATED, false)) {
                    // 已完成迁移（进程重启后的快速路径）：旧文件只剩过期明文，清理
                    legacy.edit().clear().apply()
                    migrationDone = true
                    return
                }
                if (hasSecureTokens) {
                    // 上次迁移可能在两步加密之间被中断。旧明文与加密库现有访问令牌一致
                    // ⇒ 同一令牌的续迁：落回主流程完整重加密；不一致 ⇒ 迁移失败后用户
                    // 已重新登录，压制旧明文（否则陈旧令牌会覆盖新登录态）。
                    val legacyAccess = legacy.getString(KEY_ACCESS_TOKEN, "").orEmpty()
                    val secureAccess = SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN)
                    if (!(legacyAccess.isNotBlank() && legacyAccess == secureAccess)) {
                        legacy.edit().clear().apply()
                        secure.edit().putBoolean(SecureAuthStore.FIELD_MIGRATED, true).apply()
                        migrationDone = true
                        return
                    }
                }
                val accessToken = legacy.getString(KEY_ACCESS_TOKEN, "").orEmpty()
                val refreshToken = legacy.getString(KEY_REFRESH_TOKEN, "").orEmpty()
                val expiresAt = legacy.getLong(KEY_EXPIRES_AT, 0L)
                val lastError = legacy.getString(KEY_LAST_ERROR, "").orEmpty()
                if (accessToken.isNotBlank() || refreshToken.isNotBlank()) {
                    SecureAuthStore.encryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN, accessToken)
                    SecureAuthStore.encryptField(context, SecureAuthStore.FIELD_REFRESH_TOKEN, refreshToken)
                    val migratedAccess = SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN)
                    val migratedRefresh = SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_REFRESH_TOKEN)
                    if (migratedAccess != accessToken || migratedRefresh != refreshToken) {
                        Log.e(TAG, "Token migration verification failed, legacy prefs kept for retry")
                        return
                    }
                    legacy.edit().clear().apply()
                    Log.i(TAG, "Legacy plain auth tokens migrated to encrypted storage")
                }
                val editor = secure.edit().putBoolean(SecureAuthStore.FIELD_MIGRATED, true)
                if (expiresAt != 0L || lastError.isNotBlank()) {
                    editor.putLong(SecureAuthStore.FIELD_EXPIRES_AT, expiresAt)
                        .putString(SecureAuthStore.FIELD_LAST_ERROR, lastError)
                }
                editor.apply()
                migrationDone = true
            } catch (t: Throwable) {
                // Keystore 异常不阻断登录态判断：下次访问重试迁移
                Log.e(TAG, "Auth token migration failed", t)
            }
        }
    }

    fun getStatus(context: Context): HikarinagiAuthStatus {
        ensureMigrated(context)
        val p = SecureAuthStore.prefs(context)
        val accessToken = p.getString(SecureAuthStore.FIELD_ACCESS_TOKEN, "").orEmpty()
            .let { if (it.isBlank()) "" else SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN) }
        val refreshToken = p.getString(SecureAuthStore.FIELD_REFRESH_TOKEN, "").orEmpty()
            .let { if (it.isBlank()) "" else SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_REFRESH_TOKEN) }
        val lastError = p.getString(SecureAuthStore.FIELD_LAST_ERROR, "").orEmpty()
        return HikarinagiAuthStatus(
            authorized = accessToken.isNotBlank() || refreshToken.isNotBlank(),
            needsReauth = lastError.isNotBlank(),
            expiresAtMillis = p.getLong(SecureAuthStore.FIELD_EXPIRES_AT, 0L),
            lastError = lastError,
        )
    }

    fun getAccessToken(context: Context): String {
        ensureMigrated(context)
        val p = SecureAuthStore.prefs(context)
        if (p.getString(SecureAuthStore.FIELD_ACCESS_TOKEN, "").isNullOrBlank()) return ""
        return SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN)
    }

    fun getRefreshToken(context: Context): String {
        ensureMigrated(context)
        val p = SecureAuthStore.prefs(context)
        if (p.getString(SecureAuthStore.FIELD_REFRESH_TOKEN, "").isNullOrBlank()) return ""
        return SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_REFRESH_TOKEN)
    }

    fun getExpiresAtMillis(context: Context): Long {
        ensureMigrated(context)
        return SecureAuthStore.prefs(context).getLong(SecureAuthStore.FIELD_EXPIRES_AT, 0L)
    }

    fun saveTokens(context: Context, accessToken: String, refreshToken: String, expiresAtMillis: Long) {
        ensureMigrated(context)
        val accessOk = SecureAuthStore.encryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN, accessToken)
        val refreshOk = SecureAuthStore.encryptField(context, SecureAuthStore.FIELD_REFRESH_TOKEN, refreshToken)
        // 加密失败不能静默登出：置 last_error 令 UI 走重新授权路径，用户可感知
        SecureAuthStore.prefs(context).edit()
            .putLong(SecureAuthStore.FIELD_EXPIRES_AT, expiresAtMillis)
            .putString(SecureAuthStore.FIELD_LAST_ERROR, if (accessOk && refreshOk) "" else SECURE_STORAGE_ERROR)
            .apply()
        bump()
    }

    fun markNeedsReauth(context: Context, reason: String) {
        ensureMigrated(context)
        SecureAuthStore.encryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN, "")
        SecureAuthStore.encryptField(context, SecureAuthStore.FIELD_REFRESH_TOKEN, "")
        SecureAuthStore.prefs(context).edit()
            .putLong(SecureAuthStore.FIELD_EXPIRES_AT, 0L)
            .putString(SecureAuthStore.FIELD_LAST_ERROR, reason.trim())
            .apply()
        bump()
    }

    fun clear(context: Context) {
        ensureMigrated(context)
        SecureAuthStore.prefs(context).edit().clear().apply()
        bump()
    }

    private fun bump() {
        statusVersion.value += 1
    }
}
