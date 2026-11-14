package com.tyranor.next.core.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** VNDB 授权错误码（core → ui 类型化错误协议，UI 侧映射；name 可持久化进 last_error）。 */
enum class VndbAuthError {
    /** token 无效（401）。 */
    TOKEN_INVALID,

    /** token 有效但缺少 listread / listwrite 权限（403）。 */
    PERMISSION,
}

data class VndbAuthStatus(
    val authorized: Boolean,
    /** 上次校验/保存失败原因（如 authinfo 失败）；非空时 UI 提示重新授权。 */
    val needsReauth: Boolean,
    val username: String,
    val permissions: List<String> = emptyList(),
    val lastError: String,
) {
    val permissionTier: VndbPermissionTier get() = evaluateVndbPermissionTier(permissions)

    /**
     * [lastError] 的类型化视图（BK-17）：按 name 安全解析回 [VndbAuthError]（等价
     * enumOrNull；禁用 valueOf——未知 name 会抛异常），加密落盘失败哨兵
     * [SecureAuthStore.SECURE_STORAGE_ERROR]、未知/历史值与空串均返回 null。
     * 计算属性非持久化：last_error 的持久化协议不变。
     */
    val lastErrorKind: VndbAuthError?
        get() = VndbAuthError.entries.firstOrNull { it.name == lastError }

    /** 是否为 Keystore 加密落盘失败哨兵；供 UI 分支使用，避免跨层复制哨兵字面量。 */
    val isSecureStorageError: Boolean
        get() = lastError == SecureAuthStore.SECURE_STORAGE_ERROR
}

/**
 * VNDB token 存取门面（VNDB 无 OAuth，用户在站点生成 token 后粘贴，经 /authinfo
 * 校验后入库）：token 与解析出的用户身份存于 [SecureAuthStore]（AndroidKeyStore
 * AES/GCM，独立 prefs 文件，排除出云备份/设备迁移）。
 */
object VndbAuthStore {
    private const val PREFS = "vndb_auth_secure"
    private const val KEY_ALIAS = "tyranor_vndb_token"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    // token 权限列表（逗号分隔，如 listread,listwrite；空 = 未知/未解析）
    private const val KEY_PERMISSIONS = "permissions"
    private const val KEY_LAST_ERROR = "last_error"

    private val storeLock = Any()
    private val mutableStatusVersion = MutableStateFlow(0)
    private val mutableCredentialEpoch = MutableStateFlow(0L)

    /** 授权状态写版本号：任何持久化状态变化递增，UI collectAsState 触发重组；对外只读。 */
    val statusVersion: StateFlow<Int> = mutableStatusVersion.asStateFlow()

    /** 凭据替换/清空代次：成功保存或清空令牌时递增，用于区分换号后的缓存变体。 */
    val credentialEpoch: StateFlow<Long> = mutableCredentialEpoch.asStateFlow()

    /** 登录会话代次（VNDB 无后台刷新轮转，与 [credentialEpoch] 一致）。 */
    val sessionEpoch: StateFlow<Long> get() = credentialEpoch

    fun getStatus(context: Context): VndbAuthStatus = synchronized(storeLock) {
        val p = SecureAuthStore.prefs(context, PREFS)
        val lastError = p.getString(KEY_LAST_ERROR, "").orEmpty()
        val permissions = p.getString(KEY_PERMISSIONS, "").orEmpty()
            .split(',').filter { it.isNotBlank() }
        VndbAuthStatus(
            authorized = decryptIfPresent(context, p, KEY_TOKEN).isNotBlank(),
            needsReauth = lastError.isNotBlank(),
            username = p.getString(KEY_USERNAME, "").orEmpty(),
            permissions = permissions,
            lastError = lastError,
        )
    }

    fun getToken(context: Context): String = synchronized(storeLock) {
        decryptIfPresent(context, SecureAuthStore.prefs(context, PREFS), KEY_TOKEN)
    }

    /** 若登录会话代次仍等于 [expectedSessionEpoch]，在同一把 [storeLock] 下原子读取当前 token。 */
    internal fun readTokenIfSessionMatches(context: Context, expectedSessionEpoch: Long): String? =
        synchronized(storeLock) {
            if (mutableCredentialEpoch.value != expectedSessionEpoch) return null
            getToken(context).takeIf { it.isNotBlank() }
        }

    /** 已解析的 VNDB 用户 id（形如 u17），供 /ulist 的 user 过滤器使用；空串表示未知。 */
    fun getUserId(context: Context): String = synchronized(storeLock) {
        SecureAuthStore.prefs(context, PREFS).getString(KEY_USER_ID, "").orEmpty()
    }

    /**
     * 写入 token 与解析出的身份字段（加密 + 落盘）。返回是否全部成功：true = token 字段加密
     * 成功；false = 加密失败（此时已置 [SecureAuthStore.SECURE_STORAGE_ERROR] 哨兵令 UI 走重新授权路径，
     * 调用方不得把本次保存当成功反馈；BK-24 契约保持不变）。
     *
     * 落盘原子性（BK-30）：加密字段经 [SecureAuthStore.encryptToMemory] 加密到内存后，
     * 与明文字段（身份/权限/last_error 哨兵）收敛到单 editor 一次 apply——不再逐字段
     * 独立 apply，进程死亡不再留下「新 token + 旧身份/权限」跨字段中间态；
     * statusVersion 仍恰 bump 一次（BK-25 契约不变）。
     */
    fun saveToken(
        context: Context,
        token: String,
        userId: String,
        username: String,
        permissions: List<String> = emptyList(),
    ): Boolean = synchronized(storeLock) {
        val pending = SecureAuthStore.encryptToMemory(context, PREFS, KEY_ALIAS, KEY_TOKEN, token)
        val tokenOk = pending !is SecureAuthStore.PendingCipherField.Failed
        val editor = SecureAuthStore.prefs(context, PREFS).edit()
        if (!tokenOk) {
            // 加密失败不得覆盖既有的 token、身份与权限：仅置 last_error 哨兵令 UI 走重新授权路径
            editor.putString(KEY_LAST_ERROR, SecureAuthStore.SECURE_STORAGE_ERROR).apply()
            bump()
            return@synchronized false
        }
        when (pending) {
            is SecureAuthStore.PendingCipherField.Put -> editor.putString(KEY_TOKEN, pending.ciphertext)
            SecureAuthStore.PendingCipherField.Remove -> editor.remove(KEY_TOKEN)
            SecureAuthStore.PendingCipherField.Failed -> Unit
        }
        editor.putString(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PERMISSIONS, permissions.joinToString(","))
            .putString(KEY_LAST_ERROR, "")
            .apply()
        mutableCredentialEpoch.update { it + 1L }
        bump()
        true
    }

    /** 缓存 token 权限列表（/authinfo 解析结果，用户在网页更新权限后随下次校验刷新）。 */
    fun savePermissions(context: Context, permissions: List<String>) {
        synchronized(storeLock) {
            SecureAuthStore.prefs(context, PREFS).edit()
                .putString(KEY_PERMISSIONS, permissions.joinToString(","))
                .apply()
            bump()
        }
    }

    /** 仅当已存 token 仍匹配 [expectedToken] 时回写权限缓存，避免并发换号/清空后串写旧权限。 */
    internal fun savePermissionsIfTokenMatches(
        context: Context,
        expectedToken: String,
        permissions: List<String>,
    ): Boolean = synchronized(storeLock) {
        if (getToken(context) != expectedToken) return false
        SecureAuthStore.prefs(context, PREFS).edit()
            .putString(KEY_PERMISSIONS, permissions.joinToString(","))
            .apply()
        bump()
        true
    }

    /**
     * 记录最近一次校验/请求失败原因。**保留已存 token**：权限不足（403）与失效（401）
     * 都可通过用户重新粘贴修复，不自动清除授权（实测反馈：权限错误不应表现为「解绑」）。
     */
    fun markLastError(context: Context, reason: String) {
        synchronized(storeLock) {
            val p = SecureAuthStore.prefs(context, PREFS)
            val normalized = reason.trim()
            // 同值短路：读取成功路径反复调用 markLastError("")，同一原因不重复写 prefs——
            // 每次 putString + apply + bump 都会触发一次无谓的持久化与 UI 重组；原因变化时仍正常写入
            if (p.getString(KEY_LAST_ERROR, "").orEmpty() == normalized) return
            p.edit()
                .putString(KEY_LAST_ERROR, normalized)
                .apply()
            bump()
        }
    }

    internal fun markLastErrorIfTokenMatches(context: Context, expectedToken: String, reason: String) {
        synchronized(storeLock) {
            if (getToken(context) != expectedToken) return
            val p = SecureAuthStore.prefs(context, PREFS)
            val normalized = reason.trim()
            if (p.getString(KEY_LAST_ERROR, "").orEmpty() == normalized) return
            p.edit()
                .putString(KEY_LAST_ERROR, normalized)
                .apply()
            bump()
        }
    }

    fun clear(context: Context) {
        synchronized(storeLock) {
            SecureAuthStore.prefs(context, PREFS).edit().clear().apply()
            mutableCredentialEpoch.update { it + 1L }
            bump()
        }
    }

    private fun decryptIfPresent(context: Context, prefs: android.content.SharedPreferences, field: String): String {
        if (prefs.getString(field, "").isNullOrBlank()) return ""
        return SecureAuthStore.decryptField(context, PREFS, KEY_ALIAS, field)
    }

    private fun bump() {
        // CAS 自增（非 `value += 1`）：并发写路径不丢版本号递增
        mutableStatusVersion.update { it + 1 }
    }
}
