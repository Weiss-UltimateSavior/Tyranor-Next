package com.tyranor.next.core.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 未萌 NextMoe 授权错误码（core → ui 类型化错误协议，UI 侧经 `ui/auth/NextMoeAuthMessages` 映射）：
 * 其中仅 [EXPIRED]（写入 `last_error`）与 [PERMISSION]（写入独立 `permission_error` 位）参与持久化；
 * 其余成员为 OAuth 授权发起 / 环回回调链路一次性错误码，不落 `last_error`。
 */
enum class NextMoeAuthError {
    CLIENT_ID_MISSING,
    NO_BROWSER,
    LAUNCH_FAILED,
    CANCELLED,
    FAILED,
    INVALID_RESPONSE,
    STATE_MISMATCH,
    TOKEN_EXCHANGE_FAILED,
    TOKEN_MISSING_FIELDS,
    ID_TOKEN_INVALID_FORMAT,
    ID_TOKEN_NONCE_FAILED,
    ID_TOKEN_PARSE_FAILED,

    /** 令牌失效：刷新被服务端 400/401 明确拒绝或读接口返回 401（持久化为 last_error 的错误码 name）。 */
    EXPIRED,

    /** 令牌有效但权限缺失：403 响应（持久化为独立 permission_error 位，不写 last_error）。 */
    PERMISSION,
}

data class NextMoeAuthStatus(
    val authorized: Boolean,
    /** 令牌失效（刷新被服务端 400/401 明确拒绝、读接口 401 或加密落盘失败）；刷新短路仅对本位生效。 */
    val needsReauth: Boolean,
    /** 403（令牌有效但权限缺失）独立标记位；不参与刷新短路。 */
    val permissionDenied: Boolean,
    val expiresAtMillis: Long,
    val username: String = "",
    val lastError: String,
    /** 发起授权的时间戳（毫秒）；0 表示没有进行中的授权。进程死亡丢失环回连接时，UI 据此提示重试。 */
    val authInProgressAtMillis: Long = 0L,
) {
    val lastErrorKind: NextMoeAuthError?
        get() = NextMoeAuthError.entries.firstOrNull { it.name == lastError }

    /** 是否为 Keystore 加密落盘失败哨兵；供 UI 分支使用，避免跨层复制哨兵字面量。 */
    val isSecureStorageError: Boolean
        get() = lastError == SecureAuthStore.SECURE_STORAGE_ERROR
}

/**
 * 未萌 NextMoe OAuth 令牌存取门面：令牌存于 [SecureAuthStore]（AndroidKeyStore AES/GCM，
 * 独立 prefs 文件 `nextmoe_auth_secure`，排除出云备份/设备迁移）。
 */
object NextMoeAuthStore {
    private const val PREFS = "nextmoe_auth_secure"
    private const val KEY_ALIAS = "tyranor_nextmoe_token"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_PERMISSION_ERROR = "permission_error"
    private const val KEY_USERNAME = "username"
    private const val KEY_CREDENTIAL_GENERATION = "credential_generation"
    private const val KEY_AUTH_IN_PROGRESS_AT = "auth_in_progress_at"

    private val storeLock = Any()
    private val mutableStatusVersion = MutableStateFlow(0)
    private val mutableCredentialEpoch = MutableStateFlow(0L)
    private val mutableSessionEpoch = MutableStateFlow(0L)

    /** 授权状态写版本号：任何持久化状态变化递增，UI collectAsState 触发重组；对外只读。 */
    val statusVersion: StateFlow<Int> = mutableStatusVersion.asStateFlow()

    /** 凭据替换/清空代次：成功写入（含同账号令牌刷新）或清空令牌时递增，供并发写回校验与缓存分键。 */
    val credentialEpoch: StateFlow<Long> = mutableCredentialEpoch.asStateFlow()

    /** 登录会话代次：仅在显式新登录或登出时递增，同账号令牌刷新保持不变，供游玩状态弹窗区分换号与刷新。 */
    val sessionEpoch: StateFlow<Long> = mutableSessionEpoch.asStateFlow()

    internal data class AccessSnapshot(
        val accessToken: String,
        val expiresAtMillis: Long,
        val needsReauth: Boolean,
    )

    fun getStatus(context: Context): NextMoeAuthStatus = synchronized(storeLock) {
        val p = SecureAuthStore.prefs(context, PREFS)
        val accessToken = decryptIfPresent(context, p, KEY_ACCESS_TOKEN)
        val refreshToken = decryptIfPresent(context, p, KEY_REFRESH_TOKEN)
        val lastError = p.getString(KEY_LAST_ERROR, "").orEmpty()
        NextMoeAuthStatus(
            authorized = accessToken.isNotBlank() || refreshToken.isNotBlank(),
            needsReauth = lastError.isNotBlank(),
            permissionDenied = p.getString(KEY_PERMISSION_ERROR, "").orEmpty().isNotBlank(),
            expiresAtMillis = p.getLong(KEY_EXPIRES_AT, 0L),
            username = p.getString(KEY_USERNAME, "").orEmpty().trim(),
            lastError = lastError,
            authInProgressAtMillis = p.getLong(KEY_AUTH_IN_PROGRESS_AT, 0L),
        )
    }

    internal fun readAccessSnapshot(context: Context): AccessSnapshot = synchronized(storeLock) {
        val p = SecureAuthStore.prefs(context, PREFS)
        AccessSnapshot(
            accessToken = decryptIfPresent(context, p, KEY_ACCESS_TOKEN),
            expiresAtMillis = p.getLong(KEY_EXPIRES_AT, 0L),
            needsReauth = p.getString(KEY_LAST_ERROR, "").orEmpty().isNotBlank(),
        )
    }

    fun getAccessToken(context: Context): String = synchronized(storeLock) {
        val p = SecureAuthStore.prefs(context, PREFS)
        decryptIfPresent(context, p, KEY_ACCESS_TOKEN)
    }

    fun getRefreshToken(context: Context): String = synchronized(storeLock) {
        val p = SecureAuthStore.prefs(context, PREFS)
        decryptIfPresent(context, p, KEY_REFRESH_TOKEN)
    }

    fun getExpiresAtMillis(context: Context): Long = synchronized(storeLock) {
        SecureAuthStore.prefs(context, PREFS).getLong(KEY_EXPIRES_AT, 0L)
    }

    fun saveTokens(context: Context, accessToken: String, refreshToken: String, expiresAtMillis: Long): Boolean =
        saveTokensReturningEpoch(context, accessToken, refreshToken, expiresAtMillis) != null

    fun saveUsername(context: Context, username: String) {
        synchronized(storeLock) {
            saveUsernameLocked(context, username)
        }
    }

    internal fun saveUsernameIfEpochMatches(
        context: Context,
        username: String,
        expectedEpoch: Long,
    ): Boolean = synchronized(storeLock) {
        if (mutableCredentialEpoch.value != expectedEpoch) return false
        saveUsernameLocked(context, username)
        true
    }

    internal fun currentEpochIfAccessTokenMatches(context: Context, token: String): Long? = synchronized(storeLock) {
        if (getAccessToken(context) != token) return null
        mutableCredentialEpoch.value
    }

    internal fun readRefreshSnapshot(context: Context): Pair<String, Long> = synchronized(storeLock) {
        getRefreshToken(context) to mutableCredentialEpoch.value
    }

    internal fun readAccessTokenIfSessionMatches(context: Context, expectedSessionEpoch: Long): String? =
        synchronized(storeLock) {
            if (mutableSessionEpoch.value != expectedSessionEpoch) return null
            getAccessToken(context).takeIf { it.isNotBlank() }
        }

    internal fun readCredentialGeneration(context: Context): Long = synchronized(storeLock) {
        SecureAuthStore.prefs(context, PREFS).getLong(KEY_CREDENTIAL_GENERATION, 0L)
    }

    internal fun setAuthInProgress(context: Context, startedAtMillis: Long) = synchronized(storeLock) {
        val prefs = SecureAuthStore.prefs(context, PREFS)
        if (prefs.getLong(KEY_AUTH_IN_PROGRESS_AT, 0L) == startedAtMillis) return@synchronized
        prefs.edit().putLong(KEY_AUTH_IN_PROGRESS_AT, startedAtMillis).apply()
        bump()
    }

    internal fun saveTokensReturningEpoch(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long,
    ): Long? = synchronized(storeLock) {
        saveTokensLocked(context, accessToken, refreshToken, expiresAtMillis, clearIdentity = true)
    }

    internal fun saveTokensIfGenerationMatches(
        context: Context,
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long,
    ): Long? = synchronized(storeLock) {
        if (SecureAuthStore.prefs(context, PREFS).getLong(KEY_CREDENTIAL_GENERATION, 0L) != expectedGeneration) {
            return null
        }
        saveTokensLocked(context, accessToken, refreshToken, expiresAtMillis, clearIdentity = true)
    }

    internal fun saveTokensIfMatching(
        context: Context,
        expectedEpoch: Long,
        expectedRefreshToken: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long,
    ): Boolean = synchronized(storeLock) {
        if (mutableCredentialEpoch.value != expectedEpoch || getRefreshToken(context) != expectedRefreshToken) {
            return false
        }
        saveTokensLocked(context, accessToken, refreshToken, expiresAtMillis, clearIdentity = false) != null
    }

    internal fun markNeedsReauthIfMatching(
        context: Context,
        expectedEpoch: Long,
        expectedRefreshToken: String,
        reason: String,
    ): Boolean = synchronized(storeLock) {
        if (mutableCredentialEpoch.value != expectedEpoch || getRefreshToken(context) != expectedRefreshToken) {
            return false
        }
        markNeedsReauthLocked(context, reason)
        true
    }

    internal fun markNeedsReauthIfAccessTokenMatches(
        context: Context,
        expectedAccessToken: String,
        reason: String,
    ): Boolean = synchronized(storeLock) {
        if (getAccessToken(context) != expectedAccessToken) return false
        markNeedsReauthLocked(context, reason)
        true
    }

    internal fun markPermissionDeniedIfAccessTokenMatches(
        context: Context,
        expectedAccessToken: String,
        reason: String,
    ): Boolean = synchronized(storeLock) {
        if (getAccessToken(context) != expectedAccessToken) return false
        val normalized = reason.trim()
        val p = SecureAuthStore.prefs(context, PREFS)
        if (p.prefValueEquals(KEY_PERMISSION_ERROR, normalized)) return true
        p.edit().putString(KEY_PERMISSION_ERROR, normalized).apply()
        bump()
        true
    }

    internal fun clearLastErrorIfAccessTokenMatches(context: Context, expectedAccessToken: String): Boolean =
        synchronized(storeLock) {
            if (getAccessToken(context) != expectedAccessToken) return false
            clearLastErrorLocked(context)
            true
        }

    internal fun clearPermissionErrorIfAccessTokenMatches(context: Context, expectedAccessToken: String): Boolean =
        synchronized(storeLock) {
            if (getAccessToken(context) != expectedAccessToken) return false
            clearPermissionErrorLocked(context)
            true
        }

    fun markNeedsReauth(context: Context, reason: String) {
        synchronized(storeLock) {
            markNeedsReauthLocked(context, reason)
        }
    }

    fun markPermissionDenied(context: Context, reason: String) {
        synchronized(storeLock) {
            val normalized = reason.trim()
            val p = SecureAuthStore.prefs(context, PREFS)
            if (p.prefValueEquals(KEY_PERMISSION_ERROR, normalized)) return
            p.edit().putString(KEY_PERMISSION_ERROR, normalized).apply()
            bump()
        }
    }

    fun clearPermissionError(context: Context) {
        synchronized(storeLock) {
            clearPermissionErrorLocked(context)
        }
    }

    fun clearLastError(context: Context) {
        synchronized(storeLock) {
            clearLastErrorLocked(context)
        }
    }

    fun clear(context: Context) {
        synchronized(storeLock) {
            val prefs = SecureAuthStore.prefs(context, PREFS)
            val nextGeneration = prefs.getLong(KEY_CREDENTIAL_GENERATION, 0L) + 1L
            prefs.edit()
                .clear()
                .putLong(KEY_CREDENTIAL_GENERATION, nextGeneration)
                .apply()
            bumpCredentialEpoch()
            bumpSessionEpoch()
            bump()
        }
    }

    private fun saveUsernameLocked(context: Context, username: String) {
        val trimmed = username.trim()
        val p = SecureAuthStore.prefs(context, PREFS)
        if (p.getString(KEY_USERNAME, "").orEmpty() == trimmed) return
        p.edit().putString(KEY_USERNAME, trimmed).apply()
        bump()
    }

    private fun saveTokensLocked(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long,
        clearIdentity: Boolean,
    ): Long? {
        val access = SecureAuthStore.encryptToMemory(context, PREFS, KEY_ALIAS, KEY_ACCESS_TOKEN, accessToken)
        val refresh = SecureAuthStore.encryptToMemory(context, PREFS, KEY_ALIAS, KEY_REFRESH_TOKEN, refreshToken)
        val allOk = access !is SecureAuthStore.PendingCipherField.Failed &&
            refresh !is SecureAuthStore.PendingCipherField.Failed
        val prefs = SecureAuthStore.prefs(context, PREFS)
        val editor = prefs.edit()
        if (!allOk) {
            editor.putString(KEY_LAST_ERROR, SecureAuthStore.SECURE_STORAGE_ERROR).apply()
            bump()
            return null
        }
        when (access) {
            is SecureAuthStore.PendingCipherField.Put -> editor.putString(KEY_ACCESS_TOKEN, access.ciphertext)
            SecureAuthStore.PendingCipherField.Remove -> editor.remove(KEY_ACCESS_TOKEN)
            SecureAuthStore.PendingCipherField.Failed -> Unit
        }
        when (refresh) {
            is SecureAuthStore.PendingCipherField.Put -> editor.putString(KEY_REFRESH_TOKEN, refresh.ciphertext)
            SecureAuthStore.PendingCipherField.Remove -> editor.remove(KEY_REFRESH_TOKEN)
            SecureAuthStore.PendingCipherField.Failed -> Unit
        }
        if (clearIdentity) {
            editor.remove(KEY_USERNAME)
                .remove(KEY_PERMISSION_ERROR)
                .putLong(KEY_AUTH_IN_PROGRESS_AT, 0L)
        }
        val nextGeneration = prefs.getLong(KEY_CREDENTIAL_GENERATION, 0L) + 1L
        editor.putLong(KEY_EXPIRES_AT, expiresAtMillis)
            .putString(KEY_LAST_ERROR, "")
            .putLong(KEY_CREDENTIAL_GENERATION, nextGeneration)
            .apply()
        val epoch = bumpCredentialEpoch()
        if (clearIdentity) {
            bumpSessionEpoch()
        }
        bump()
        return epoch
    }

    private fun markNeedsReauthLocked(context: Context, reason: String) {
        val normalized = reason.trim()
        val p = SecureAuthStore.prefs(context, PREFS)
        if (p.prefValueEquals(KEY_LAST_ERROR, normalized)) return
        p.edit().putString(KEY_LAST_ERROR, normalized).apply()
        bump()
    }

    private fun clearLastErrorLocked(context: Context) {
        val p = SecureAuthStore.prefs(context, PREFS)
        if (p.getString(KEY_LAST_ERROR, "").isNullOrEmpty()) return
        p.edit().putString(KEY_LAST_ERROR, "").apply()
        bump()
    }

    private fun clearPermissionErrorLocked(context: Context) {
        val p = SecureAuthStore.prefs(context, PREFS)
        if (p.getString(KEY_PERMISSION_ERROR, "").isNullOrEmpty()) return
        p.edit().putString(KEY_PERMISSION_ERROR, "").apply()
        bump()
    }

    private fun decryptIfPresent(
        context: Context,
        prefs: android.content.SharedPreferences,
        key: String,
    ): String {
        if (prefs.getString(key, "").isNullOrBlank()) return ""
        return SecureAuthStore.decryptField(context, PREFS, KEY_ALIAS, key)
    }

    private fun bumpCredentialEpoch(): Long {
        val next = mutableCredentialEpoch.value + 1L
        mutableCredentialEpoch.value = next
        return next
    }

    private fun bumpSessionEpoch() {
        mutableSessionEpoch.update { it + 1L }
    }

    private fun bump() {
        mutableStatusVersion.update { it + 1 }
    }
}
