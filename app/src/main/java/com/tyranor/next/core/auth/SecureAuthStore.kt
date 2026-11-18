package com.tyranor.next.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tyranor.next.BuildConfig
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
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
    internal const val PREFS = "hikarinagi_auth_secure"
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

    private val aliasLocks = ConcurrentHashMap<String, Any>()
    private val keyCache = ConcurrentHashMap<String, SecretKey>()

    private fun aliasLock(alias: String): Any = aliasLocks.computeIfAbsent(alias) { Any() }

    @VisibleForTesting
    internal fun clearKeyCacheForTest() {
        keyCache.clear()
    }

    /** 清理测试内存密文桩内指定 prefs 下的指定字段（与 SharedPreferences clear 对齐）。 */
    internal fun clearTestCipherFields(prefsName: String, vararg fields: String) {
        val delegate = testCipherDelegate ?: return
        check(BuildConfig.DEBUG) { "test stub must be null in release" }
        fields.forEach { field -> delegate("$prefsName:$field", "") }
    }

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
            return delegate("$prefsName:$field", null) ?: ""
        }
        val prefs = prefs(context, prefsName)
        val encoded = prefs.getString(field, null) ?: return ""
        if (encoded.isBlank()) return ""
        var resolvedKey: SecretKey? = null
        return runCatching {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size > GCM_IV_BYTES) { "ciphertext too short" }
            val key = secretKey(keyAlias).also { resolvedKey = it }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES),
            )
            String(cipher.doFinal(data, GCM_IV_BYTES, data.size - GCM_IV_BYTES), Charsets.UTF_8)
        }.onFailure { throwable ->
            if (throwable is InvalidKeyException || throwable is UnrecoverableKeyException) {
                resolvedKey?.let { keyCache.remove(keyAlias, it) }
            }
            Log.e(TAG, "Token field decrypt failed, keep ciphertext for retry", throwable)
        }.getOrDefault("")
    }

    /**
     * 加密写入一个字段；plain 为空串时移除该字段。返回是否成功落盘。
     * 单字段独立 apply——需要跨字段一次落盘的调用方（四 store 的 saveTokens/saveToken）
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
     * 加密一个字段到内存、**不落盘**（单 editor 批量提交的前半步，BK-30）：四 store 的
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
            delegate("$prefsName:$field", plain)
            return if (plain.isEmpty()) PendingCipherField.Remove else PendingCipherField.Put(plain)
        }
        if (plain.isEmpty()) return PendingCipherField.Remove
        return try {
            encryptPlaintext(keyAlias, plain)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: InvalidKeyException) {
            recoverKeyAndEncrypt(keyAlias, plain, invalid)
        } catch (unrecoverable: UnrecoverableKeyException) {
            recoverKeyAndEncrypt(keyAlias, plain, unrecoverable)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Token field encrypt failed", throwable)
            PendingCipherField.Failed
        }
    }

    private fun encryptPlaintext(keyAlias: String, plain: String): PendingCipherField {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(keyAlias))
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "unexpected GCM IV size" }
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(out)
        ciphertext.copyInto(out, iv.size)
        return PendingCipherField.Put(Base64.encodeToString(out, Base64.NO_WRAP))
    }

    /** Keystore 别名永久失效时按别名分段加锁删除旧别名并重建密钥后重试一次，避免阻塞其它别名的读取或重新授权持续落入 Failed。 */
    private fun recoverKeyAndEncrypt(keyAlias: String, plain: String, cause: Throwable): PendingCipherField =
        synchronized(aliasLock(keyAlias)) {
            // Double-checked Retry：若并发调用方已在等待期间重建该别名密钥，直接复用而不再误删新密钥
            val alreadyRecovered = try {
                encryptPlaintext(keyAlias, plain)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InvalidKeyException) {
                null
            } catch (_: UnrecoverableKeyException) {
                null
            } catch (unexpected: Throwable) {
                Log.e(TAG, "Token field encrypt failed before key recovery", unexpected)
                return@synchronized PendingCipherField.Failed
            }
            if (alreadyRecovered != null) return@synchronized alreadyRecovered

            keyCache.remove(keyAlias)
            Log.w(TAG, "Keystore key invalidated, deleting and regenerating alias: $keyAlias", cause)
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (keyStore.containsAlias(keyAlias)) {
                    keyStore.deleteEntry(keyAlias)
                }
            }.onFailure { deleteError ->
                Log.w(TAG, "Keystore entry delete failed: $keyAlias", deleteError)
            }
            return@synchronized try {
                encryptPlaintext(keyAlias, plain)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryError: Throwable) {
                Log.e(TAG, "Token field encrypt failed after key recovery", retryError)
                PendingCipherField.Failed
            }
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

    /** Keystore 中每个别名一把独立密钥；首次使用时按别名分段加锁加载/生成并缓存。 */
    private fun secretKey(alias: String): SecretKey {
        keyCache[alias]?.let { return it }
        return synchronized(aliasLock(alias)) {
            keyCache[alias]?.let { return@synchronized it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val resolved = (keyStore.getKey(alias, null) as? SecretKey) ?: run {
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
            keyCache[alias] = resolved
            resolved
        }
    }

    private const val MAX_OAUTH_EXPIRES_IN_SECONDS = 365L * 24L * 3600L * 10L

    /**
     * 计算不过溢出的 OAuth 访问令牌过期绝对时间（毫秒）。
     * `expiresInSeconds <= 0L` 保留为长期/无明确过期时间（0L）；其余上限钳制在 10 年，
     * 防止服务端返回超大 `expires_in`（如 `Long.MAX_VALUE`）在 `* 1000L` 时整数溢出。
     */
    internal fun oauthExpiresAtMillis(
        expiresInSeconds: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        val clampedSeconds = expiresInSeconds.coerceIn(0L, MAX_OAUTH_EXPIRES_IN_SECONDS)
        if (clampedSeconds == 0L) return 0L
        val deltaMillis = clampedSeconds * 1000L
        return if (nowMillis > Long.MAX_VALUE - deltaMillis) Long.MAX_VALUE else nowMillis + deltaMillis
    }

    /**
     * [SingleFlightRefresh] 等待方结果采纳：仅当在途领导者返回了有效令牌（非 null）且当前
     * 存储未置位 needsReauth/未清空时放行。若存储令牌仍在常规预刷新窗口之外，直接采纳；
     * 若服务端返回 1..60s 极短有效期导致落入 REFRESH_SKEW_MS 窗口，只要存储中仍是领导者刚刚
     * 写回的那枚令牌且绝对过期时间尚未越界，等待方同样采纳，避免与领导者结果分裂。
     */
    internal fun adoptSingleFlightAccessToken(
        leaderToken: String?,
        needsReauth: Boolean,
        storedAccessToken: String,
        storedExpiresAtMillis: Long,
        allowZeroExpiry: Boolean,
        refreshSkewMs: Long,
        credentialEpochChanged: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? {
        if (needsReauth || storedAccessToken.isBlank()) return null
        val withinFastPath = (allowZeroExpiry && storedExpiresAtMillis == 0L) ||
            nowMillis + refreshSkewMs < storedExpiresAtMillis
        if (leaderToken == null) {
            // 领导者未带回令牌（如刷新途中并发完成新登录，导致旧代次刷新回写被拒）：
            // 仅当凭据代次已推进且存储内已就绪常规快路径有效令牌时才采纳，避免普通刷新失败时误采纳临期旧令牌
            return storedAccessToken.takeIf { credentialEpochChanged && withinFastPath }
        }
        if (withinFastPath) return storedAccessToken
        val validWithoutSkew = (allowZeroExpiry && storedExpiresAtMillis == 0L) ||
            nowMillis < storedExpiresAtMillis
        return storedAccessToken.takeIf { validWithoutSkew }
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
