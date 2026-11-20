package com.tyranor.next.core.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bangumi 授权/校验错误码（core → ui 类型化错误协议，UI 侧映射；name 可持久化进 last_error）。
 * 与 [VndbAuthError] / [HikarinagiAuthError] 同构随各自 Store 定义（原随 BangumiAuthService，
 * 纯移动，同包零 import 变化）；Bangumi 侧 01c7896c 新增的 BangumiAccessTokenResult/
 * BangumiTokenVerification sealed 族仍留在 Service 文件、不扩散。
 */
enum class BangumiAuthError {
    CLIENT_ID_MISSING,
    NO_BROWSER,
    LAUNCH_FAILED,
    CANCELLED,
    FAILED,
    INVALID_RESPONSE,
    STATE_MISMATCH,
    TOKEN_EXCHANGE_FAILED,
    TOKEN_MISSING_FIELDS,
    EXPIRED,

    /** 令牌有效但权限缺失：读路径 403（持久化为独立 permission_error 位，不写 last_error）。 */
    PERMISSION,

    /** 个人令牌校验：服务端明确拒绝该令牌（/v0/me 返回 401/403）。 */
    TOKEN_INVALID,

    /** 个人令牌校验：传输失败或服务异常（不代表令牌无效，可重试）。 */
    NETWORK,
}

data class BangumiAuthStatus(
    val authorized: Boolean,
    /** 令牌失效（写路径 401 / 刷新被拒 / 加密落盘失败）；刷新短路仅对本位生效。 */
    val needsReauth: Boolean,
    /** 读路径 403（令牌有效但权限缺失）独立标记位；不参与刷新短路。 */
    val permissionDenied: Boolean,
    /** 0 表示授权端点未返回过期时间（Bangumi 允许长期令牌），视为不过期。 */
    val expiresAtMillis: Long,
    val username: String = "",
    val isPersonalToken: Boolean = false,
    val lastError: String,
    /** 发起授权的时间戳（毫秒）；0 表示没有进行中的授权。进程死亡丢失授权响应时，UI 据此提示重试。 */
    val authInProgressAtMillis: Long = 0L,
) {
    /**
     * [lastError] 的类型化视图（BK-17）：按 name 安全解析回 [BangumiAuthError]（等价
     * enumOrNull；禁用 valueOf——未知 name 会抛异常），未知/历史值与空串返回 null。
     * 计算属性非持久化：last_error 的持久化协议（写枚举 name 落盘）不变，UI 据此
     * 替代字符串形状比较后，扩员时由编译器穷尽 when 兜底。PERMISSION 不落 last_error
     * （写独立 permission_error 位，对照 HikarinagiAuthStore 同款分位），故本属性实际
     * 仅可能解析出 EXPIRED。
     */
    val lastErrorKind: BangumiAuthError?
        get() = BangumiAuthError.entries.firstOrNull { it.name == lastError }

    /** 是否为 Keystore 加密落盘失败哨兵；供 UI 分支使用，避免跨层复制哨兵字面量。 */
    val isSecureStorageError: Boolean
        get() = lastError == SecureAuthStore.SECURE_STORAGE_ERROR
}

/**
 * Bangumi OAuth 令牌存取门面：令牌存于 [SecureAuthStore]（AndroidKeyStore AES/GCM，
 * 独立 prefs 文件，排除出云备份/设备迁移）。与 HikarinagiAuthStore 分开维护：
 * Bangumi 无 OIDC id_token，可能不带 refresh_token 或过期时间。
 */
object BangumiAuthStore {
    private const val PREFS = "bangumi_auth_secure"
    private const val KEY_ALIAS = "tyranor_bangumi_token"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_LAST_ERROR = "last_error"
    // 读路径 403 的权限缺失标记（明文错误码 name，非敏感）：与令牌失效位分离承载，
    // 不进入 last_error —— 否则会与刷新短路交互，把令牌过期后的自动刷新自愈路径永久锁死
    private const val KEY_PERMISSION_ERROR = "permission_error"
    private const val KEY_AUTH_IN_PROGRESS_AT = "auth_in_progress_at"
    // 用户身份（非敏感，明文存储）：v0 API 的单条收藏读取必须走真实用户名（`me`/`-` 均不可用）
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    // 持久化凭据代次：跨进程死亡与 clear() 单调递增，用于 OAuth 换码写回防串台
    private const val KEY_CREDENTIAL_GENERATION = "credential_generation"

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
        val credentialEpoch: Long = 0L,
    )

    fun getStatus(context: Context): BangumiAuthStatus = synchronized(storeLock) {
        val p = SecureAuthStore.prefs(context, PREFS)
        val lastError = p.getString(KEY_LAST_ERROR, "").orEmpty()
        val refreshToken = decryptIfPresent(context, p, KEY_REFRESH_TOKEN)
        val expiresAt = p.getLong(KEY_EXPIRES_AT, 0L)
        val userId = p.getLong(KEY_USER_ID, 0L)
        // 数字 uid 兜底后 username 非空（BK-19，取舍由 AuthStatusPersistenceTest 钉住）：
        // 展示层显示数字 uid 属可接受降级；该兜底形态同时被 isBangumiIdentityResolved
        // 判为「身份未解析」，ensureIdentityLoaded / 游玩状态读回会再补一次 /v0/me 回查
        // 真实用户名（M2 整改后失败可重试，成功即幂等落盘收敛），本处不再重复展开
        val username = p.getString(KEY_USERNAME, "").orEmpty().trim()
            .ifBlank { if (userId > 0L) userId.toString() else "" }
        BangumiAuthStatus(
            // 瞬时解密失败（冷启动早期 Keystore 未就绪）时 authorized 短暂误显示「未授权」：
            // 属误显示而非登出，密文保留、下次成功解密即恢复（取舍见 SecureAuthStore.decryptField，
            // BK-28 注释固化）
            authorized = decryptIfPresent(context, p, KEY_ACCESS_TOKEN).isNotBlank() ||
                refreshToken.isNotBlank(),
            needsReauth = lastError.isNotBlank(),
            permissionDenied = p.getString(KEY_PERMISSION_ERROR, "").orEmpty().isNotBlank(),
            expiresAtMillis = expiresAt,
            username = username,
            // 弱启发式边缘（BK-21，注释固化）：OAuth 令牌若无 refresh_token 且端点未返回过期
            // 时间会误判为个人令牌；影响面=摘要文案档（个人令牌文案不承诺写权限），精确化
            // 方案=保存时落标志位（兼容回退启发式），触发时再扩
            isPersonalToken = refreshToken.isBlank() && expiresAt == 0L,
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
            credentialEpoch = mutableCredentialEpoch.value,
        )
    }

    /** 只读明文 `last_error` 判断是否需要重新授权，避免冗余 Keystore 解密。 */
    internal fun isNeedsReauth(context: Context): Boolean = synchronized(storeLock) {
        SecureAuthStore.prefs(context, PREFS).getString(KEY_LAST_ERROR, "").orEmpty().isNotBlank()
    }

    /** 当前授权账号的数字 uid；0 表示未解析。 */
    fun getUserId(context: Context): Long = synchronized(storeLock) {
        SecureAuthStore.prefs(context, PREFS).getLong(KEY_USER_ID, 0L)
    }

    /** 当前授权账号的用户名（读收藏的路径参数）；空串表示未解析。 */
    fun getUsername(context: Context): String = synchronized(storeLock) {
        SecureAuthStore.prefs(context, PREFS).getString(KEY_USERNAME, "").orEmpty()
    }

    /** 缓存授权账号身份（令牌换取/粘贴校验成功后调用）。 */
    fun saveUserIdentity(context: Context, id: Long, username: String) {
        synchronized(storeLock) {
            saveUserIdentityLocked(context, id, username)
        }
    }

    /** 仅当凭据代次仍匹配 [expectedEpoch] 时写入账号身份，避免异步身份回查覆写新账号。 */
    internal fun saveUserIdentityIfEpochMatches(
        context: Context,
        id: Long,
        username: String,
        expectedEpoch: Long,
    ): Boolean = synchronized(storeLock) {
        if (mutableCredentialEpoch.value != expectedEpoch) return false
        saveUserIdentityLocked(context, id, username)
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

    private fun saveUserIdentityLocked(context: Context, id: Long, username: String) {
        // 与 getStatus 同款数字 uid 兜底（BK-19）：落库即含兜底形态，读侧不再二次推导
        val resolved = username.trim().ifBlank { if (id > 0L) id.toString() else "" }
        val p = SecureAuthStore.prefs(context, PREFS)
        if (p.getLong(KEY_USER_ID, 0L) == id && p.getString(KEY_USERNAME, "").orEmpty() == resolved) return
        p.edit()
            .putLong(KEY_USER_ID, id)
            .putString(KEY_USERNAME, resolved)
            .apply()
        bump()
    }

    fun getAccessToken(context: Context): String = synchronized(storeLock) {
        decryptIfPresent(context, SecureAuthStore.prefs(context, PREFS), KEY_ACCESS_TOKEN)
    }

    fun getRefreshToken(context: Context): String = synchronized(storeLock) {
        decryptIfPresent(context, SecureAuthStore.prefs(context, PREFS), KEY_REFRESH_TOKEN)
    }

    fun getExpiresAtMillis(context: Context): Long = synchronized(storeLock) {
        SecureAuthStore.prefs(context, PREFS).getLong(KEY_EXPIRES_AT, 0L)
    }

    /**
     * 写入 OAuth 令牌对（加密 + 落盘）。返回是否全部成功：true = 两个令牌字段均加密成功；
     * false = 任一字段加密失败（此时已置 [SecureAuthStore.SECURE_STORAGE_ERROR] 哨兵令
     * UI 走重新授权路径，保留既有令牌与过期时间不被覆盖，调用方不得把本次保存当成功反馈）。
     *
     * 落盘原子性（BK-30）：加密字段经 [SecureAuthStore.encryptToMemory] 加密到内存后，
     * 与明文字段（expires_at / last_error 哨兵）收敛到单 editor 一次 apply——不再逐字段
     * 独立 apply，进程死亡不再留下「部分新令牌 + 旧 last_error/expires_at」跨字段中间态；
     * statusVersion 仍恰 bump 一次（BK-25 契约不变）。
     */
    fun saveTokens(context: Context, accessToken: String, refreshToken: String, expiresAtMillis: Long): Boolean =
        saveTokensReturningEpoch(context, accessToken, refreshToken, expiresAtMillis) != null

    internal fun readCredentialGeneration(context: Context): Long = synchronized(storeLock) {
        SecureAuthStore.prefs(context, PREFS).getLong(KEY_CREDENTIAL_GENERATION, 0L)
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
            // 加密失败不得覆写既有的有效令牌与过期时间：仅置 last_error 哨兵令 UI 走重新授权路径
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
            // 显式新登录（OAuth 换码 / 粘贴个人令牌）须在同一提交中清空旧账号身份、权限缺失
            // 标记与遗留授权时间戳；令牌刷新保留原身份（同账号权限态随读回结果维护）
            editor.remove(KEY_USER_ID)
                .remove(KEY_USERNAME)
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

    fun markNeedsReauth(context: Context, reason: String) {
        synchronized(storeLock) {
            markNeedsReauthLocked(context, reason)
        }
    }

    private fun markNeedsReauthLocked(context: Context, reason: String) {
        val normalized = reason.trim()
        val p = SecureAuthStore.prefs(context, PREFS)
        val current = p.getString(KEY_LAST_ERROR, "").orEmpty()
        if (current == SecureAuthStore.SECURE_STORAGE_ERROR && normalized != SecureAuthStore.SECURE_STORAGE_ERROR) return
        // 同值短路（与 HikarinagiAuthStore.markNeedsReauth / VndbAuthStore.markLastError 同款）：
        // 并发刷新同窗被拒等重放路径会以同一原因反复调用，避免每次重复 prefs 写 + bump 触发无谓重组
        if (current == normalized) return
        p.edit()
            .putString(KEY_LAST_ERROR, normalized)
            .apply()
        bump()
    }

    /**
     * 标记权限缺失（读路径 403，令牌本身有效，R9-A 分位语义）：写入独立 permission_error 位，
     * 不触碰 last_error/needsReauth —— 令牌过期后自动刷新仍可正常自愈。幂等短路同
     * [markNeedsReauth]；写路径 403 维持 BK-05A 只抛错不落档（不调用本方法）。
     */
    fun markPermissionDenied(context: Context, reason: String) {
        synchronized(storeLock) {
            val normalized = reason.trim()
            val p = SecureAuthStore.prefs(context, PREFS)
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

    /** 仅当已存 access token 仍匹配 [expectedAccessToken] 时置位权限缺失标记（防跨号误标）。 */
    internal fun markPermissionDeniedIfAccessTokenMatches(
        context: Context,
        expectedAccessToken: String,
        reason: String,
    ): Boolean = synchronized(storeLock) {
        if (getAccessToken(context) != expectedAccessToken) return false
        val normalized = reason.trim()
        val p = SecureAuthStore.prefs(context, PREFS)
        if (p.prefValueEquals(KEY_PERMISSION_ERROR, normalized)) return true
        p.edit()
            .putString(KEY_PERMISSION_ERROR, normalized)
            .apply()
        bump()
        true
    }

    internal fun clearLastErrorIfAccessTokenMatches(context: Context, expectedAccessToken: String): Boolean =
        synchronized(storeLock) {
            if (getAccessToken(context) != expectedAccessToken) return false
            clearLastErrorLocked(context)
        }

    internal fun clearPermissionErrorIfAccessTokenMatches(context: Context, expectedAccessToken: String): Boolean =
        synchronized(storeLock) {
            if (getAccessToken(context) != expectedAccessToken) return false
            clearPermissionErrorLocked(context)
            true
        }

    private fun clearLastErrorLocked(context: Context): Boolean {
        val p = SecureAuthStore.prefs(context, PREFS)
        val current = p.getString(KEY_LAST_ERROR, "").orEmpty()
        // 密钥加密失败哨兵仅允许成功加密写盘新令牌或 clear() 清除，并发读回成功不得擦除
        if (current == SecureAuthStore.SECURE_STORAGE_ERROR) return false
        if (current.isEmpty()) return true
        p.edit().putString(KEY_LAST_ERROR, "").apply()
        bump()
        return true
    }

    private fun clearPermissionErrorLocked(context: Context) {
        val p = SecureAuthStore.prefs(context, PREFS)
        if (p.getString(KEY_PERMISSION_ERROR, "").isNullOrEmpty()) return
        p.edit().putString(KEY_PERMISSION_ERROR, "").apply()
        bump()
    }

    fun clear(context: Context) {
        synchronized(storeLock) {
            val prefs = SecureAuthStore.prefs(context, PREFS)
            val nextGeneration = prefs.getLong(KEY_CREDENTIAL_GENERATION, 0L) + 1L
            prefs.edit()
                .clear()
                .putLong(KEY_CREDENTIAL_GENERATION, nextGeneration)
                .apply()
            SecureAuthStore.clearTestCipherFields(PREFS, KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN)
            bumpCredentialEpoch()
            bumpSessionEpoch()
            bump()
        }
    }

    /** 标记「授权进行中」（0 = 清除）；非敏感时间戳，与令牌同文件但不加密。 */
    fun setAuthInProgress(context: Context, atMillis: Long) {
        synchronized(storeLock) {
            val prefs = SecureAuthStore.prefs(context, PREFS)
            if (prefs.getLong(KEY_AUTH_IN_PROGRESS_AT, 0L) == atMillis) return
            prefs.edit()
                .putLong(KEY_AUTH_IN_PROGRESS_AT, atMillis)
                .apply()
            bump()
        }
    }

    /** 仅当凭据代次仍匹配 [expectedGeneration]（或为 null）时在同一 storeLock 内原子清除授权中标记。 */
    internal fun clearAuthInProgressIfGenerationMatches(context: Context, expectedGeneration: Long?) {
        synchronized(storeLock) {
            val prefs = SecureAuthStore.prefs(context, PREFS)
            if (expectedGeneration != null && prefs.getLong(KEY_CREDENTIAL_GENERATION, 0L) != expectedGeneration) return
            if (prefs.getLong(KEY_AUTH_IN_PROGRESS_AT, 0L) == 0L) return
            prefs.edit()
                .putLong(KEY_AUTH_IN_PROGRESS_AT, 0L)
                .apply()
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

    private fun decryptIfPresent(context: Context, prefs: SharedPreferences, field: String): String {
        if (prefs.getString(field, "").isNullOrBlank()) return ""
        return SecureAuthStore.decryptField(context, PREFS, KEY_ALIAS, field)
    }

    private fun bump() {
        // CAS 自增（非 `value += 1`）：并发写路径（刷新写回与授权回调等）不丢版本号递增
        mutableStatusVersion.update { it + 1 }
    }
}
