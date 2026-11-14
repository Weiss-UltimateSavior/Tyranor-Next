package com.tyranor.next.core.auth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hikarinagi 授权错误码（core → ui 类型化错误协议，UI 侧经 `ui/auth/HikarinagiAuthMessages` 映射）：
 * 其中仅 [EXPIRED]（写入 `last_error`）与 [PERMISSION]（写入独立 `permission_error` 位）参与持久化；
 * 其余成员为 OAuth 授权发起/回调链路一次性错误码，不落 `last_error`。
 */
enum class HikarinagiAuthError {
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

    /** 令牌失效：刷新被服务端 400/401 明确拒绝（持久化为 last_error 的错误码 name）。 */
    EXPIRED,

    /** 令牌有效但权限缺失：读路径 403（持久化为独立 permission_error 位，不写 last_error）。 */
    PERMISSION,
}

// 凭据未配置（client id 或 refresh token 缺失）不入本枚举：它不是持久化的授权错误
// （不落 last_error），仅由刷新取令牌的类型化结果 [HikarinagiAccessTokenResult.ConfigMissing]
// 携带，供调用方按「需重新授权」档映射文案。

data class HikarinagiAuthStatus(
    val authorized: Boolean,
    /** 令牌失效（刷新被服务端 400/401 明确拒绝或加密落盘失败）；刷新短路仅对本位生效。 */
    val needsReauth: Boolean,
    /** 读路径 403（令牌有效但权限缺失）独立标记位；不参与刷新短路。 */
    val permissionDenied: Boolean,
    val expiresAtMillis: Long,
    val username: String = "",
    val lastError: String,
) {
    /**
     * [lastError] 的类型化视图（BK-17）：按 name 安全解析回 [HikarinagiAuthError]（等价
     * enumOrNull；禁用 valueOf——未知 name 会抛异常），未知/历史值与空串返回 null。
     * 计算属性非持久化：last_error 的持久化协议不变。PERMISSION 不落 last_error
     * （写独立 permission_error 位），故本属性实际仅可能解析出 EXPIRED。
     */
    val lastErrorKind: HikarinagiAuthError?
        get() = HikarinagiAuthError.entries.firstOrNull { it.name == lastError }

    /** 是否为 Keystore 加密落盘失败哨兵；供 UI 分支使用，避免跨层复制哨兵字面量。 */
    val isSecureStorageError: Boolean
        get() = lastError == SecureAuthStore.SECURE_STORAGE_ERROR
}

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
    // 读路径 403 的权限缺失标记（明文错误码 name，非敏感）：与令牌失效位分离承载，
    // 不进入 last_error —— 否则会与刷新短路交互，把令牌过期后的自动刷新自愈路径永久锁死
    private const val KEY_PERMISSION_ERROR = "permission_error"
    // 用户身份（非敏感，明文存同一加密文件）：仅用于设置页展示账号名
    private const val KEY_USERNAME = "username"
    // 持久化凭据代次：跨进程死亡与 clear() 单调递增，用于 OAuth 换码写回防串台
    private const val KEY_CREDENTIAL_GENERATION = "credential_generation"

    private val storeLock = Any()
    private val mutableStatusVersion = MutableStateFlow(0)
    private val mutableCredentialEpoch = MutableStateFlow(0L)
    private val mutableSessionEpoch = MutableStateFlow(0L)

    /** 凭据替换/清空代次：成功保存（含同账号令牌刷新）或清空令牌时递增，供并发写回校验与缓存刷新键。 */
    val credentialEpoch: StateFlow<Long> = mutableCredentialEpoch.asStateFlow()

    /** 登录会话代次：仅在显式新登录或登出时递增，同账号令牌刷新保持不变，供游玩状态弹窗区分换号与刷新。 */
    val sessionEpoch: StateFlow<Long> = mutableSessionEpoch.asStateFlow()

    /** 授权状态写版本号：任何持久化状态变化递增，UI collectAsState 触发重组；对外只读。 */
    val statusVersion: StateFlow<Int> = mutableStatusVersion.asStateFlow()

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
                    val accessCipher = secure.getString(SecureAuthStore.FIELD_ACCESS_TOKEN, null)
                    val secureAccess = SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN)
                    val accessDecryptable = !accessCipher.isNullOrBlank() && secureAccess.isNotBlank()
                    if (!accessDecryptable && legacyAccess.isNotBlank()) {
                        // 密文缺失或解密瞬时失败（冷启动早期 Keystore 未就绪）：无法确认
                        // 「已重新登录」，保留 legacy 明文下次重试，避免不可逆丢失登录态。
                        Log.w(TAG, "Secure access token unavailable, legacy prefs kept for retry")
                        return
                    }
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

    internal data class AccessSnapshot(
        val accessToken: String,
        val expiresAtMillis: Long,
        val needsReauth: Boolean,
    )

    fun getStatus(context: Context): HikarinagiAuthStatus = synchronized(storeLock) {
        ensureMigrated(context)
        separatePermissionErrorFromLastErrorLocked(context)
        val p = SecureAuthStore.prefs(context)
        val accessToken = p.getString(SecureAuthStore.FIELD_ACCESS_TOKEN, "").orEmpty()
            .let { if (it.isBlank()) "" else SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN) }
        val refreshToken = p.getString(SecureAuthStore.FIELD_REFRESH_TOKEN, "").orEmpty()
            .let { if (it.isBlank()) "" else SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_REFRESH_TOKEN) }
        val lastError = p.getString(SecureAuthStore.FIELD_LAST_ERROR, "").orEmpty()
        HikarinagiAuthStatus(
            authorized = accessToken.isNotBlank() || refreshToken.isNotBlank(),
            needsReauth = lastError.isNotBlank(),
            permissionDenied = p.getString(KEY_PERMISSION_ERROR, "").orEmpty().isNotBlank(),
            expiresAtMillis = p.getLong(SecureAuthStore.FIELD_EXPIRES_AT, 0L),
            username = p.getString(KEY_USERNAME, "").orEmpty(),
            lastError = lastError,
        )
    }

    internal fun readAccessSnapshot(context: Context): AccessSnapshot = synchronized(storeLock) {
        ensureMigrated(context)
        separatePermissionErrorFromLastErrorLocked(context)
        val p = SecureAuthStore.prefs(context)
        val accessToken = p.getString(SecureAuthStore.FIELD_ACCESS_TOKEN, "").orEmpty()
            .let { if (it.isBlank()) "" else SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN) }
        AccessSnapshot(
            accessToken = accessToken,
            expiresAtMillis = p.getLong(SecureAuthStore.FIELD_EXPIRES_AT, 0L),
            needsReauth = p.getString(SecureAuthStore.FIELD_LAST_ERROR, "").orEmpty().isNotBlank(),
        )
    }

    /**
     * 一次性归位（令牌失效 / 权限缺失语义拆分）：拆分前的版本曾把读路径 403 的权限缺失写进
     * last_error（needsReauth 位），与刷新短路交互后会把令牌过期后的自动刷新永久锁死。
     * 把残留的 PERMISSION 档 last_error 迁到独立 [KEY_PERMISSION_ERROR] 位，恢复刷新自愈；
     * 令牌失效档（EXPIRED）与加密落盘失败哨兵不受影响。幂等：归位后条件不再命中。
     */
    private fun separatePermissionErrorFromLastErrorLocked(context: Context) {
        val p = SecureAuthStore.prefs(context)
        if (p.getString(SecureAuthStore.FIELD_LAST_ERROR, "").orEmpty() != HikarinagiAuthError.PERMISSION.name) {
            return
        }
        p.edit()
            .putString(KEY_PERMISSION_ERROR, HikarinagiAuthError.PERMISSION.name)
            .putString(SecureAuthStore.FIELD_LAST_ERROR, "")
            .apply()
        bump()
    }

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

    /** 若登录会话代次仍等于 [expectedSessionEpoch]，在同一把 [storeLock] 下原子读取有效访问令牌。 */
    internal fun readAccessTokenIfSessionMatches(context: Context, expectedSessionEpoch: Long): String? =
        synchronized(storeLock) {
            if (mutableSessionEpoch.value != expectedSessionEpoch) return null
            getAccessToken(context).takeIf { it.isNotBlank() }
        }

    private fun saveUsernameLocked(context: Context, username: String) {
        ensureMigrated(context)
        val trimmed = username.trim()
        val p = SecureAuthStore.prefs(context)
        if (p.getString(KEY_USERNAME, "").orEmpty() == trimmed) return
        p.edit().putString(KEY_USERNAME, trimmed).apply()
        bump()
    }

    fun clearLastError(context: Context) {
        synchronized(storeLock) {
            clearLastErrorLocked(context)
        }
    }

    fun getAccessToken(context: Context): String = synchronized(storeLock) {
        ensureMigrated(context)
        val p = SecureAuthStore.prefs(context)
        if (p.getString(SecureAuthStore.FIELD_ACCESS_TOKEN, "").isNullOrBlank()) return@synchronized ""
        SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_ACCESS_TOKEN)
    }

    fun getRefreshToken(context: Context): String = synchronized(storeLock) {
        ensureMigrated(context)
        val p = SecureAuthStore.prefs(context)
        if (p.getString(SecureAuthStore.FIELD_REFRESH_TOKEN, "").isNullOrBlank()) return@synchronized ""
        SecureAuthStore.decryptField(context, SecureAuthStore.FIELD_REFRESH_TOKEN)
    }

    fun getExpiresAtMillis(context: Context): Long = synchronized(storeLock) {
        ensureMigrated(context)
        SecureAuthStore.prefs(context).getLong(SecureAuthStore.FIELD_EXPIRES_AT, 0L)
    }

    /**
     * 写入 OAuth 令牌对（加密 + 落盘）。返回是否全部成功：true = 两个令牌字段均加密成功；
     * false = 任一字段加密失败（此时已置 [SecureAuthStore.SECURE_STORAGE_ERROR] 哨兵令
     * UI 走重新授权路径，调用方不得把本次保存当成功反馈；BK-24 契约保持不变）。
     *
     * 落盘原子性（BK-30）：加密字段经 [SecureAuthStore.encryptToMemory] 加密到内存后，
     * 与明文字段（expires_at / last_error 哨兵）收敛到单 editor 一次 apply——不再逐字段
     * 独立 apply，进程死亡不再留下「部分新令牌 + 旧 last_error/expires_at」跨字段中间态；
     * statusVersion 仍恰 bump 一次（BK-25 契约不变）。
     */
    fun saveTokens(context: Context, accessToken: String, refreshToken: String, expiresAtMillis: Long): Boolean =
        saveTokensReturningEpoch(context, accessToken, refreshToken, expiresAtMillis) != null

    internal fun readCredentialGeneration(context: Context): Long = synchronized(storeLock) {
        ensureMigrated(context)
        SecureAuthStore.prefs(context).getLong(KEY_CREDENTIAL_GENERATION, 0L)
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
        ensureMigrated(context)
        if (SecureAuthStore.prefs(context).getLong(KEY_CREDENTIAL_GENERATION, 0L) != expectedGeneration) {
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
        ensureMigrated(context)
        val normalized = reason.trim()
        val p = SecureAuthStore.prefs(context)
        if (p.prefValueEquals(KEY_PERMISSION_ERROR, normalized)) return true
        p.edit()
            .putString(KEY_PERMISSION_ERROR, normalized)
            .apply()
        bump()
        true
    }

    private fun saveTokensLocked(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long,
        clearIdentity: Boolean,
    ): Long? {
        ensureMigrated(context)
        val access = SecureAuthStore.encryptToMemory(context, SecureAuthStore.FIELD_ACCESS_TOKEN, accessToken)
        val refresh = SecureAuthStore.encryptToMemory(context, SecureAuthStore.FIELD_REFRESH_TOKEN, refreshToken)
        val allOk = access !is SecureAuthStore.PendingCipherField.Failed &&
            refresh !is SecureAuthStore.PendingCipherField.Failed
        val prefs = SecureAuthStore.prefs(context)
        val editor = prefs.edit()
        if (!allOk) {
            // 加密失败不得覆写既有的有效令牌与过期时间：仅置 last_error 哨兵令 UI 走重新授权路径
            editor.putString(SecureAuthStore.FIELD_LAST_ERROR, SecureAuthStore.SECURE_STORAGE_ERROR).apply()
            bump()
            return null
        }
        when (access) {
            is SecureAuthStore.PendingCipherField.Put ->
                editor.putString(SecureAuthStore.FIELD_ACCESS_TOKEN, access.ciphertext)
            SecureAuthStore.PendingCipherField.Remove -> editor.remove(SecureAuthStore.FIELD_ACCESS_TOKEN)
            SecureAuthStore.PendingCipherField.Failed -> Unit
        }
        when (refresh) {
            is SecureAuthStore.PendingCipherField.Put ->
                editor.putString(SecureAuthStore.FIELD_REFRESH_TOKEN, refresh.ciphertext)
            SecureAuthStore.PendingCipherField.Remove -> editor.remove(SecureAuthStore.FIELD_REFRESH_TOKEN)
            SecureAuthStore.PendingCipherField.Failed -> Unit
        }
        if (clearIdentity) {
            // 显式新登录须在同一提交中清空旧账号用户名，避免换号回查前界面混显旧账号名
            editor.remove(KEY_USERNAME)
                .remove(KEY_PERMISSION_ERROR)
        }
        val nextGeneration = prefs.getLong(KEY_CREDENTIAL_GENERATION, 0L) + 1L
        editor.putLong(SecureAuthStore.FIELD_EXPIRES_AT, expiresAtMillis)
            .putString(SecureAuthStore.FIELD_LAST_ERROR, "")
            .putLong(KEY_CREDENTIAL_GENERATION, nextGeneration)
            .apply()
        val epoch = bumpCredentialEpoch()
        if (clearIdentity) {
            bumpSessionEpoch()
        }
        bump()
        return epoch
    }

    /**
     * 标记令牌失效（写入 last_error → needsReauth 位；语义收紧后本位**仅承载令牌失效**：
     * 刷新被服务端 400/401 明确拒绝、加密落盘失败等，令牌保留但自动刷新被短路直至重新授权）。
     * 读路径 403 的权限缺失走 [markPermissionDenied]，不得写入本位。
     */
    fun markNeedsReauth(context: Context, reason: String) {
        synchronized(storeLock) {
            markNeedsReauthLocked(context, reason)
        }
    }

    private fun markNeedsReauthLocked(context: Context, reason: String) {
        ensureMigrated(context)
        val normalized = reason.trim()
        val p = SecureAuthStore.prefs(context)
        // 同值短路（与 VndbAuthStore.markLastError 同款）：失效令牌的逐请求
        // 重放路径会以同一原因反复调用，避免每次 prefs 写 + statusVersion bump 触发无谓重组
        if (p.prefValueEquals(SecureAuthStore.FIELD_LAST_ERROR, normalized)) return
        p.edit()
            .putString(SecureAuthStore.FIELD_LAST_ERROR, normalized)
            .apply()
        bump()
    }

    /**
     * 标记权限缺失（读路径 403，令牌本身有效）：写入独立 permission_error 位，
     * 不触碰 last_error/needsReauth —— 令牌过期后自动刷新仍可正常自愈。幂等短路同
     * [markNeedsReauth]。
     */
    fun markPermissionDenied(context: Context, reason: String) {
        synchronized(storeLock) {
            ensureMigrated(context)
            val normalized = reason.trim()
            val p = SecureAuthStore.prefs(context)
            if (p.prefValueEquals(KEY_PERMISSION_ERROR, normalized)) return
            p.edit()
                .putString(KEY_PERMISSION_ERROR, normalized)
                .apply()
            bump()
        }
    }

    /** 清除权限缺失标记（读路径成功证明当前令牌权限可回读）。空值时短路。 */
    fun clearPermissionError(context: Context) {
        synchronized(storeLock) {
            clearPermissionErrorLocked(context)
        }
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

    private fun clearLastErrorLocked(context: Context) {
        ensureMigrated(context)
        val p = SecureAuthStore.prefs(context)
        if (p.getString(SecureAuthStore.FIELD_LAST_ERROR, "").isNullOrEmpty()) return
        p.edit().putString(SecureAuthStore.FIELD_LAST_ERROR, "").apply()
        bump()
    }

    private fun clearPermissionErrorLocked(context: Context) {
        ensureMigrated(context)
        val p = SecureAuthStore.prefs(context)
        if (p.getString(KEY_PERMISSION_ERROR, "").isNullOrEmpty()) return
        p.edit().putString(KEY_PERMISSION_ERROR, "").apply()
        bump()
    }

    fun clear(context: Context) {
        synchronized(storeLock) {
            ensureMigrated(context)
            val prefs = SecureAuthStore.prefs(context)
            val nextGeneration = prefs.getLong(KEY_CREDENTIAL_GENERATION, 0L) + 1L
            prefs.edit()
                .clear()
                .putBoolean(SecureAuthStore.FIELD_MIGRATED, true)
                .putLong(KEY_CREDENTIAL_GENERATION, nextGeneration)
                .apply()
            bumpCredentialEpoch()
            bumpSessionEpoch()
            bump()
        }
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
        // CAS 自增（非 `value += 1`）：并发写路径（刷新写回与授权回调等）不丢版本号递增
        mutableStatusVersion.update { it + 1 }
    }
}
