package com.tyranor.next.core.privacy

import android.content.Context
import android.util.Base64
import com.tyranor.next.core.auth.KeystoreFieldCipher
import java.security.MessageDigest

/**
 * 应用锁图案凭据的加密存储：盐与哈希两个字段经 [KeystoreFieldCipher] 加密后存于
 * 独立 prefs 文件 app_lock_secure，Keystore 密钥按独立别名隔离（与 Hikarinagi 令牌互不可解）。
 *
 * 该 prefs 文件必须保持排除出云备份 / 设备迁移（Keystore 密钥换机不可解密）；
 * 换机后凭据自然失效，用户重新设置图案即可（与安全目标一致）。
 */
internal object AppLockCredentialStore {

    private const val PREFS = "app_lock_secure"
    private const val KEY_ALIAS = "tyranor_app_lock_pattern"

    private const val FIELD_SALT = "pattern_salt"
    private const val FIELD_HASH = "pattern_hash"

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否已设置图案凭据（盐与哈希齐备才视为有效）。 */
    fun hasPattern(context: Context): Boolean =
        readField(context, FIELD_SALT).isNotEmpty() && readField(context, FIELD_HASH).isNotEmpty()

    /**
     * 保存新图案（覆盖旧凭据）。盐与哈希两个字段按顺序落盘；任一写入失败返回 false
     * 并清掉已写入的一半，避免留下「有盐无哈希」的半套凭据。
     */
    fun savePattern(context: Context, points: List<Int>): Boolean {
        val salt = PatternHasher.newSalt()
        val hash = PatternHasher.hash(salt, points) ?: return false
        // 保存前先记住旧盐：哈希写入失败时回滚到旧盐，否则新旧盐混用会让**已保存的旧图案**
        // 永久无法校验（写空串更糟：留下「有哈希无盐」的半套凭据）
        val previousSalt = readField(context, FIELD_SALT)
        val saltWritten = writeField(
            context,
            FIELD_SALT,
            Base64.encodeToString(salt, Base64.NO_WRAP),
        )
        if (!saltWritten) return false
        val hashWritten = writeField(context, FIELD_HASH, hash)
        if (!hashWritten) {
            writeField(context, FIELD_SALT, previousSalt)
            return false
        }
        return true
    }

    /** 校验图案；未设置凭据、图案非法或比对不一致一律返回 false。 */
    fun verifyPattern(context: Context, points: List<Int>): Boolean {
        val salt = runCatching {
            Base64.decode(readField(context, FIELD_SALT), Base64.NO_WRAP)
        }.getOrNull() ?: return false
        val storedHash = readField(context, FIELD_HASH)
        if (salt.isEmpty() || storedHash.isEmpty()) return false
        val candidate = PatternHasher.hash(salt, points) ?: return false
        // 常数时间比较：逐字节早退的 equals 会带来时序侧信道
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.US_ASCII),
            storedHash.toByteArray(Charsets.US_ASCII),
        )
    }

    /** 清除凭据（应用锁关闭时由调用方决定是否一并清除）。 */
    fun clearPattern(context: Context) {
        writeField(context, FIELD_SALT, "")
        writeField(context, FIELD_HASH, "")
    }

    private fun readField(context: Context, field: String): String =
        KeystoreFieldCipher.readField(context, PREFS, KEY_ALIAS, field)

    private fun writeField(context: Context, field: String, plain: String): Boolean =
        KeystoreFieldCipher.writeField(context, PREFS, KEY_ALIAS, field, plain)
}
