package com.tyranor.next.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tyranor.next.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * OAuth/令牌加密存储（迁移方案阶段 6）：AndroidKeyStore 内 AES/GCM 密钥，
 * 密文 + IV 存于独立 prefs 文件。
 *
 * 说明：androidx.security-crypto 在当前构建环境不可离线解析，且 EncryptedSharedPreferences
 * 已被官方弃用，故直接使用 Keystore 等价实现（同样的安全目标：令牌不落明文）。
 * 每个 prefs 文件必须保持排除出云备份/设备迁移（Keystore 密钥换机不可解密）。
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

    /**
     * Keystore 加密落盘失败时置入 last_error 的哨兵值（单一事实源）：UI 据此走重新授权
     * 路径而非静默登出。注意：各 AuthStatus 经类型化属性（如 `isSecureStorageError`）识别，
     * 改动字面量等于改持久化协议。
     */
    const val SECURE_STORAGE_ERROR = "secure storage unavailable"

    /**
     * JVM 单元测试桩：提供内存加解密，避免 AndroidKeyStore 缺失导致测试不可跑。
     * 测试结束必须复位为 null（@After），否则残留桩会污染跨测试的后续加解密
     * （既有使用方 AuthStatusPersistenceTest / BangumiTokenRefreshTest / HikarinagiTokenRefreshTest
     * 均已在 tearDown 复位）；使用点分支另有 BuildConfig.DEBUG check 防 release 误留桩。
     */
    @VisibleForTesting
    @Volatile
    internal var testCipherDelegate: ((field: String, plain: String?) -> String?)? = null

    @Volatile
    internal var testForceEncryptFail: Boolean = false

    /** 无参重载固定走 Hikarinagi 的 prefs 文件与 Keystore 别名；其它服务传入各自的独立值。 */
    fun prefs(context: Context): SharedPreferences = prefs(context, PREFS)

    fun prefs(context: Context, prefsName: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * 解密一个字段；空/缺失/解密失败一律返回空串。失败时**不删除密文**：Keystore 瞬时
     * 异常（如冷启动早期）不应造成令牌不可逆丢失，下次成功解密即可恢复登录态。
     *
     * 瞬时解密失败期间，依赖解密结果的 authorized 判定在各 store 会短暂显示「未授权」：
     * 属误显示而非登出（密文保留，下次成功解密即恢复）。不新增独立解密异常位——独立信号
     * 需扩三域状态面，仅服务冷启动早期毫秒级窗口（BK-28 既有取舍，注释固化）。
     */
    fun decryptField(context: Context, field: String): String =
        decryptField(context, PREFS, KEY_ALIAS, field)

    fun decryptField(context: Context, prefsName: String, keyAlias: String, field: String): String {
        testCipherDelegate?.let { delegate ->
            check(BuildConfig.DEBUG) { "test stub must be null in release" }
            return delegate(field, null) ?: ""
        }
        val prefs = prefs(context, prefsName)
        val encoded = prefs.getString(field, null) ?: return ""
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
            Log.e(TAG, "Token field decrypt failed, keep ciphertext for retry", throwable)
        }.getOrDefault("")
    }

    /**
     * 加密写入一个字段；plain 为空串时移除该字段。返回是否成功落盘。
     * 单字段独立 apply——需要跨字段一次落盘的调用方（三 store 的 saveTokens/saveToken）
     * 改用 [encryptToMemory] 加密到内存后自持单 editor 批量 apply（BK-30）。
     */
    fun encryptField(context: Context, field: String, plain: String): Boolean =
        encryptField(context, PREFS, KEY_ALIAS, field, plain)

    fun encryptField(
        context: Context,
        prefsName: String,
        keyAlias: String,
        field: String,
        plain: String,
    ): Boolean {
        val prefs = prefs(context, prefsName)
        return when (val pending = encryptToMemory(context, prefsName, keyAlias, field, plain)) {
            is PendingCipherField.Put -> {
                prefs.edit().putString(field, pending.ciphertext).apply()
                true
            }
            PendingCipherField.Remove -> {
                prefs.edit().remove(field).apply()
                true
            }
            PendingCipherField.Failed -> false
        }
    }

    /**
     * 加密一个字段到内存、**不落盘**（单 editor 批量提交的前半步，BK-30）：三 store 的
     * saveTokens/saveToken 用它把多个加密字段连同明文字段（expires_at/last_error 哨兵等）
     * 收敛到调用方持有的单 editor 一次 apply，进程死亡不再留下「部分新令牌 + 旧元数据」
     * 的跨字段中间态（此前逐字段独立 apply 的窄窗口非原子性由此消除）。
     */
    internal fun encryptToMemory(context: Context, field: String, plain: String): PendingCipherField =
        encryptToMemory(context, PREFS, KEY_ALIAS, field, plain)

    /** 全参变体：其它服务传入各自的独立 prefs 文件与 Keystore 别名。 */
    internal fun encryptToMemory(
        context: Context,
        prefsName: String,
        keyAlias: String,
        field: String,
        plain: String,
    ): PendingCipherField {
        if (testForceEncryptFail) {
            check(BuildConfig.DEBUG) { "test stub must be null in release" }
            return PendingCipherField.Failed
        }
        testCipherDelegate?.let { delegate ->
            check(BuildConfig.DEBUG) { "test stub must be null in release" }
            delegate(field, plain)
            return if (plain.isEmpty()) PendingCipherField.Remove else PendingCipherField.Put(plain)
        }
        if (plain.isEmpty()) return PendingCipherField.Remove
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(keyAlias))
            val iv = cipher.iv
            require(iv.size == GCM_IV_BYTES) { "unexpected GCM IV size" }
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ciphertext.size)
            iv.copyInto(out)
            ciphertext.copyInto(out, iv.size)
            PendingCipherField.Put(Base64.encodeToString(out, Base64.NO_WRAP))
        }.onFailure { throwable ->
            Log.e(TAG, "Token field encrypt failed", throwable)
        }.getOrDefault(PendingCipherField.Failed)
    }

    /**
     * [encryptToMemory] 的三态结论（BK-30 单 editor 批量提交的载荷）：
     * 调用方据此在同一 editor 上 put / remove / 跳过该字段。
     */
    internal sealed interface PendingCipherField {
        /** 加密成功，写入该密文。 */
        data class Put(val ciphertext: String) : PendingCipherField

        /** plain 为空串：调用方应从 editor 移除该字段（与 [encryptField] 空值语义一致）。 */
        data object Remove : PendingCipherField

        /** 加密失败（Keystore 不可用）：调用方不得写入该字段，按 BK-24 契约置哨兵并以 Boolean 上报。 */
        data object Failed : PendingCipherField
    }

    /** Keystore 中每个别名一把独立密钥；首次使用时生成。 */
    private fun secretKey(alias: String): SecretKey = synchronized(this) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    /**
     * 判断 OAuth refresh 失败响应是否为服务端明确拒收刷新令牌（RFC 6749 `invalid_grant`）。
     * 空体、非 JSON 响应或 `invalid_client` / `invalid_request` 等配置/临时错误返回 false，
     * 避免误置持久化 needsReauth 锁死后续自愈重试。
     */
    internal fun isOAuthInvalidGrantRejection(status: Int, body: String): Boolean {
        if (status != 400 && status != 401) return false
        val oauthError = runCatching {
            body.takeIf { it.isNotBlank() }?.let { JSONObject(it).optString("error", "").trim() }
        }.getOrNull().orEmpty()
        return oauthError == "invalid_grant"
    }
}
