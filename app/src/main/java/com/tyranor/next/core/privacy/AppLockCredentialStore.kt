package com.tyranor.next.core.privacy

import android.content.Context
import android.util.Base64
import com.tyranor.next.core.auth.KeystoreFieldCipher
import java.security.MessageDigest

/** 图案凭据的三态：凭据不存在 / 可读取 / 存在但读取失败（Keystore 瞬时异常或密钥不可用）。 */
internal enum class PatternCredentialState {
    ABSENT,
    PRESENT,
    UNREADABLE,
}

/** 图案校验结果：不可读取必须与「图案错误」区分开，前者要给出恢复路径而不是静默失败。 */
internal enum class PatternVerifyResult {
    SUCCESS,
    MISMATCH,
    UNREADABLE,
}

/**
 * 应用锁图案凭据的加密存储：盐与哈希两个字段经 [KeystoreFieldCipher] 加密后存于
 * 独立 prefs 文件 app_lock_secure，Keystore 密钥按独立别名隔离（与 Hikarinagi 令牌互不可解）。
 *
 * 该 prefs 文件必须保持排除出云备份 / 设备迁移（Keystore 密钥换机不可解密）；
 * 换机后凭据自然失效，用户重新设置图案即可（与安全目标一致）。
 *
 * 写入（[savePattern] / [clearPattern]）是阻塞调用（Keystore 加密 + commit 落盘），
 * 调用方必须在后台线程执行。
 */
internal object AppLockCredentialStore {

    private const val PREFS = "app_lock_secure"
    private const val KEY_ALIAS = "tyranor_app_lock_pattern"

    private const val FIELD_SALT = "pattern_salt"
    private const val FIELD_HASH = "pattern_hash"

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 读取凭据状态（三态）。
     *
     * - 盐与哈希都可解密 → [PatternCredentialState.PRESENT]；
     * - 任一字段存在但读取失败 → [PatternCredentialState.UNREADABLE]（**不能**当成未设置：
     *   冷启动 Keystore 瞬时失败时按未设置处理会让应用锁 fail-open）；
     * - 两个字段都不存在（或只剩半套旧数据）→ [PatternCredentialState.ABSENT]。
     *
     * 半套凭据无法校验，按未设置处理，避免永久锁死；下次保存会整体覆盖。
     */
    fun credentialState(context: Context): PatternCredentialState {
        val salt = readState(context, FIELD_SALT)
        val hash = readState(context, FIELD_HASH)
        if (salt is KeystoreFieldCipher.FieldRead.Failed ||
            hash is KeystoreFieldCipher.FieldRead.Failed
        ) {
            return PatternCredentialState.UNREADABLE
        }
        val saltPresent = salt is KeystoreFieldCipher.FieldRead.Value
        val hashPresent = hash is KeystoreFieldCipher.FieldRead.Value
        return if (saltPresent && hashPresent) {
            PatternCredentialState.PRESENT
        } else {
            PatternCredentialState.ABSENT
        }
    }

    /** 是否已设置可用的图案凭据（读取失败不算已设置，供设置页决定「启用」还是重新设置）。 */
    fun hasPattern(context: Context): Boolean =
        credentialState(context) == PatternCredentialState.PRESENT

    /**
     * 保存新图案（覆盖旧凭据）。盐与哈希经**同一个 Editor 原子提交**：分两次落盘时进程若在
     * 中间被杀，会留下「新盐 + 旧哈希」，新旧图案都无法校验。任一字段加密失败则整体不写。
     */
    fun savePattern(context: Context, points: List<Int>): Boolean {
        val salt = PatternHasher.newSalt()
        val hash = PatternHasher.hash(salt, points) ?: return false
        return KeystoreFieldCipher.writeFields(
            context,
            PREFS,
            KEY_ALIAS,
            mapOf(
                FIELD_SALT to Base64.encodeToString(salt, Base64.NO_WRAP),
                FIELD_HASH to hash,
            ),
        )
    }

    /** 校验图案；未设置凭据 / 图案非法 / 比对不一致返回 [PatternVerifyResult.MISMATCH]。 */
    fun verifyPattern(context: Context, points: List<Int>): PatternVerifyResult {
        val saltRead = readState(context, FIELD_SALT)
        val hashRead = readState(context, FIELD_HASH)
        if (saltRead is KeystoreFieldCipher.FieldRead.Failed ||
            hashRead is KeystoreFieldCipher.FieldRead.Failed
        ) {
            return PatternVerifyResult.UNREADABLE
        }
        val salt = runCatching {
            Base64.decode(
                (saltRead as? KeystoreFieldCipher.FieldRead.Value)?.text.orEmpty(),
                Base64.NO_WRAP,
            )
        }.getOrNull() ?: return PatternVerifyResult.MISMATCH
        val storedHash = (hashRead as? KeystoreFieldCipher.FieldRead.Value)?.text.orEmpty()
        if (salt.isEmpty() || storedHash.isEmpty()) return PatternVerifyResult.MISMATCH
        val candidate = PatternHasher.hash(salt, points) ?: return PatternVerifyResult.MISMATCH
        // 常数时间比较：逐字节早退的 equals 会带来时序侧信道
        val matched = MessageDigest.isEqual(
            candidate.toByteArray(Charsets.US_ASCII),
            storedHash.toByteArray(Charsets.US_ASCII),
        )
        return if (matched) PatternVerifyResult.SUCCESS else PatternVerifyResult.MISMATCH
    }

    /** 清除凭据（应用锁关闭时由调用方决定是否一并清除）；两个字段在同一次提交中移除。 */
    fun clearPattern(context: Context) {
        KeystoreFieldCipher.writeFields(
            context,
            PREFS,
            KEY_ALIAS,
            mapOf(FIELD_SALT to "", FIELD_HASH to ""),
        )
    }

    private fun readState(context: Context, field: String): KeystoreFieldCipher.FieldRead =
        KeystoreFieldCipher.readFieldState(context, PREFS, KEY_ALIAS, field)
}
