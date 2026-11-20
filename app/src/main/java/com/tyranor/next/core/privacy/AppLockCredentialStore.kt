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

/** 图案校验结果：不可读取与凭据不存在必须与「图案错误」区分开，前者要给出恢复路径而不是静默失败。 */
internal enum class PatternVerifyResult {
    SUCCESS,
    MISMATCH,

    /** 凭据存在但当前读不出来（Keystore 瞬时异常）：保持锁定并允许重试 / 设备凭据恢复。 */
    UNREADABLE,

    /** 凭据确实不存在（备份恢复 / 数据被清）：不能永久锁死，调用方应回写关闭应用锁并放行。 */
    ABSENT,
}

/**
 * 应用锁图案凭据的加密存储：盐与哈希两个字段经 [KeystoreFieldCipher] 加密后存于
 * 独立 prefs 文件 app_lock_secure，Keystore 密钥按独立别名隔离（与 Hikarinagi 令牌互不可解）。
 *
 * 该 prefs 文件必须保持排除出云备份 / 设备迁移（Keystore 密钥换机不可解密）；
 * 换机后凭据自然失效，用户重新设置图案即可（与安全目标一致）。
 *
 * 写入（[savePattern] / [clearPattern]）是阻塞调用（Keystore 加密 + commit 落盘），
 * 调用方必须在后台线程执行。读写共用 [credentialLock] 串行化：`commit()` 先把新值写进
 * 内存再落盘，失败时内存已是新值——恢复旧密文与校验不能交错。读路径（[credentialState] /
 * [verifyPattern]）在同一把锁内做 Keystore 解密；锁门运行时只消费控制器缓存的三态，
 * 图案校验本身由 UI 层放到 IO 线程执行。锁序单向（控制器 → 凭据锁，反向没有获取），
 * 因此锁门校验与后台保存不会互相死锁。
 */
internal object AppLockCredentialStore {

    private const val PREFS = "app_lock_secure"
    private const val KEY_ALIAS = "tyranor_app_lock_pattern"

    private const val FIELD_SALT = "pattern_salt"
    private const val FIELD_HASH = "pattern_hash"

    /** 凭据读写的串行锁（见类型 KDoc）。 */
    private val credentialLock = Any()

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
    fun credentialState(context: Context): PatternCredentialState = synchronized(credentialLock) {
        val salt = readState(context, FIELD_SALT)
        val hash = readState(context, FIELD_HASH)
        if (salt is KeystoreFieldCipher.FieldRead.Failed ||
            hash is KeystoreFieldCipher.FieldRead.Failed
        ) {
            return@synchronized PatternCredentialState.UNREADABLE
        }
        val saltPresent = salt is KeystoreFieldCipher.FieldRead.Value
        val hashPresent = hash is KeystoreFieldCipher.FieldRead.Value
        if (saltPresent && hashPresent) {
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
     *
     * `commit()` 返回 false 时，SharedPreferences 的内存值**已经**更新为新凭据（AOSP 实现先
     * 写内存再落盘）；这里用提交前记录的旧密文原文把内存与磁盘一起恢复，否则旧图案在本进程
     * 内已经校验不过、要等重启才恢复。
     */
    fun savePattern(context: Context, points: List<Int>): Boolean = synchronized(credentialLock) {
        val salt = PatternHasher.newSalt()
        val hash = PatternHasher.hash(salt, points) ?: return@synchronized false
        val prefs = prefs(context)
        val previousSalt = prefs.getString(FIELD_SALT, null)
        val previousHash = prefs.getString(FIELD_HASH, null)
        val saved = KeystoreFieldCipher.writeFields(
            context,
            PREFS,
            KEY_ALIAS,
            mapOf(
                FIELD_SALT to Base64.encodeToString(salt, Base64.NO_WRAP),
                FIELD_HASH to hash,
            ),
        )
        if (!saved) {
            // 密文含 IV，可原样写回；旧字段不存在时移除，恢复成保存前的半套/空状态
            val restore = prefs.edit()
            if (previousSalt.isNullOrEmpty()) restore.remove(FIELD_SALT) else restore.putString(FIELD_SALT, previousSalt)
            if (previousHash.isNullOrEmpty()) restore.remove(FIELD_HASH) else restore.putString(FIELD_HASH, previousHash)
            restore.commit()
        }
        saved
    }

    /**
     * 校验图案；凭据不存在返回 [PatternVerifyResult.ABSENT]，读取失败返回
     * [PatternVerifyResult.UNREADABLE]，图案非法 / 比对不一致返回
     * [PatternVerifyResult.MISMATCH]。
     */
    fun verifyPattern(context: Context, points: List<Int>): PatternVerifyResult =
        synchronized(credentialLock) {
            val saltRead = readState(context, FIELD_SALT)
            val hashRead = readState(context, FIELD_HASH)
            if (saltRead is KeystoreFieldCipher.FieldRead.Failed ||
                hashRead is KeystoreFieldCipher.FieldRead.Failed
            ) {
                return@synchronized PatternVerifyResult.UNREADABLE
            }
            val saltValue = saltRead as? KeystoreFieldCipher.FieldRead.Value
            val hashValue = hashRead as? KeystoreFieldCipher.FieldRead.Value
            // 两个字段都不存在（或只剩半套）→ 凭据确实不存在；与「存在但读不出」区分开，
            // 否则备份恢复后的首帧校验会被当成普通图案错误而永久卡在锁门
            if (saltValue == null || hashValue == null) {
                return@synchronized PatternVerifyResult.ABSENT
            }
            val salt = runCatching {
                Base64.decode(saltValue.text, Base64.NO_WRAP)
            }.getOrNull() ?: return@synchronized PatternVerifyResult.MISMATCH
            val storedHash = hashValue.text
            if (salt.isEmpty() || storedHash.isEmpty()) {
                return@synchronized PatternVerifyResult.ABSENT
            }
            val candidate = PatternHasher.hash(salt, points)
                ?: return@synchronized PatternVerifyResult.MISMATCH
            // 常数时间比较：逐字节早退的 equals 会带来时序侧信道
            val matched = MessageDigest.isEqual(
                candidate.toByteArray(Charsets.US_ASCII),
                storedHash.toByteArray(Charsets.US_ASCII),
            )
            if (matched) PatternVerifyResult.SUCCESS else PatternVerifyResult.MISMATCH
        }

    /**
     * 清除凭据（应用锁关闭时由调用方决定是否一并清除）；两个字段在同一次提交中移除。
     * 返回是否成功落盘。与 [savePattern] 一样，`commit()` 失败时内存已被清空、磁盘可能仍是
     * 旧凭据——此时把旧密文写回，避免「清除假象」与重启后旧图案复活的不一致。
     */
    fun clearPattern(context: Context): Boolean = synchronized(credentialLock) {
        val prefs = prefs(context)
        val previousSalt = prefs.getString(FIELD_SALT, null)
        val previousHash = prefs.getString(FIELD_HASH, null)
        val cleared = KeystoreFieldCipher.writeFields(
            context,
            PREFS,
            KEY_ALIAS,
            mapOf(FIELD_SALT to "", FIELD_HASH to ""),
        )
        if (!cleared) {
            val restore = prefs.edit()
            if (previousSalt.isNullOrEmpty()) restore.remove(FIELD_SALT) else restore.putString(FIELD_SALT, previousSalt)
            if (previousHash.isNullOrEmpty()) restore.remove(FIELD_HASH) else restore.putString(FIELD_HASH, previousHash)
            restore.commit()
        }
        cleared
    }

    private fun readState(context: Context, field: String): KeystoreFieldCipher.FieldRead =
        KeystoreFieldCipher.readFieldState(context, PREFS, KEY_ALIAS, field)
}
