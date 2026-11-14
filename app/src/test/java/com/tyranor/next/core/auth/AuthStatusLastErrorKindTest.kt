package com.tyranor.next.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BK-17A：四套 AuthStatus DTO 的 lastErrorKind 类型化视图（计算属性，非持久化）。
 * 钉住旧值兼容口径：last_error 持久化协议不变（枚举 name 落盘 + 加密落盘失败哨兵），
 * 解析侧对历史落盘值集合封闭 {EXPIRED, TOKEN_INVALID, PERMISSION, 哨兵, 空}：
 * 已知 name 还原枚举、哨兵「secure storage unavailable」/未知 name/空串恒返 null
 * （禁用 valueOf——未知 name 会抛异常），老设备存量值零迁移。
 */
class AuthStatusLastErrorKindTest {

    private val sentinel = SecureAuthStore.SECURE_STORAGE_ERROR

    @Test
    fun bangumiLastErrorKindResolvesKnownNamesAndNullsForLegacyValues() {
        // 已知 name：还原枚举（写点持久化值）
        val expired = BangumiAuthStatus(true, false, 0L, lastError = BangumiAuthError.EXPIRED.name)
        assertEquals(BangumiAuthError.EXPIRED, expired.lastErrorKind)
        val tokenInvalid = BangumiAuthStatus(false, true, 0L, lastError = BangumiAuthError.TOKEN_INVALID.name)
        assertEquals(BangumiAuthError.TOKEN_INVALID, tokenInvalid.lastErrorKind)

        // 哨兵（加密落盘失败）：null → UI isSecureStorageError 分支，与哨兵文案分支既有语义一致
        val secureError = BangumiAuthStatus(false, true, 0L, lastError = sentinel)
        assertNull(secureError.lastErrorKind)
        assertTrue(secureError.isSecureStorageError)
        assertFalse(expired.isSecureStorageError)
        // 未知/历史 name 与空串：恒 null 不抛
        assertNull(BangumiAuthStatus(false, true, 0L, lastError = "legacy_gone_kind").lastErrorKind)
        assertNull(BangumiAuthStatus(true, false, 0L, lastError = "").lastErrorKind)
    }

    @Test
    fun hikarinagiLastErrorKindResolvesExpiredAndNullsOthers() {
        // 实际写点：last_error 仅承载 EXPIRED（PERMISSION 走独立 permission_error 位）
        val expired = HikarinagiAuthStatus(true, true, false, 0L, lastError = HikarinagiAuthError.EXPIRED.name)
        assertEquals(HikarinagiAuthError.EXPIRED, expired.lastErrorKind)

        // 历史落盘值兼容：拆分前版本的 PERMISSION last_error 已由 getStatus 一次性归位，
        // 残留值若被读到也按枚举还原（该值不再产生 needsReauth 语义冲突）
        val legacyPermission = HikarinagiAuthStatus(true, true, false, 0L, lastError = HikarinagiAuthError.PERMISSION.name)
        assertEquals(HikarinagiAuthError.PERMISSION, legacyPermission.lastErrorKind)

        val secureError = HikarinagiAuthStatus(false, true, false, 0L, lastError = sentinel)
        assertNull(secureError.lastErrorKind)
        assertTrue(secureError.isSecureStorageError)
        assertFalse(expired.isSecureStorageError)
        assertNull(HikarinagiAuthStatus(false, true, false, 0L, lastError = "unknown_kind").lastErrorKind)
        assertNull(HikarinagiAuthStatus(true, false, false, 0L, lastError = "").lastErrorKind)
    }

    @Test
    fun vndbLastErrorKindResolvesKnownNamesAndNullsForSentinel() {
        // 已知 name：TOKEN_INVALID（401）/ PERMISSION（403）
        val invalid = VndbAuthStatus(true, true, "u1", lastError = VndbAuthError.TOKEN_INVALID.name)
        assertEquals(VndbAuthError.TOKEN_INVALID, invalid.lastErrorKind)
        val permission = VndbAuthStatus(true, true, "u1", lastError = VndbAuthError.PERMISSION.name)
        assertEquals(VndbAuthError.PERMISSION, permission.lastErrorKind)

        // 哨兵「secure storage unavailable」：null → UI isSecureStorageError 分支按既有文案档承接
        val secureError = VndbAuthStatus(false, true, "u1", lastError = sentinel)
        assertNull(secureError.lastErrorKind)
        assertTrue(secureError.isSecureStorageError)
        assertFalse(invalid.isSecureStorageError)
        assertNull(VndbAuthStatus(false, true, "u1", lastError = "legacy_kind").lastErrorKind)
        assertNull(VndbAuthStatus(true, false, "u1", lastError = "").lastErrorKind)
    }

    @Test
    fun nextMoeLastErrorKindResolvesKnownNamesAndNullsForSentinel() {
        val expired = NextMoeAuthStatus(true, true, false, 0L, lastError = NextMoeAuthError.EXPIRED.name)
        assertEquals(NextMoeAuthError.EXPIRED, expired.lastErrorKind)
        val permission = NextMoeAuthStatus(true, false, true, 0L, lastError = NextMoeAuthError.PERMISSION.name)
        assertEquals(NextMoeAuthError.PERMISSION, permission.lastErrorKind)

        val secureError = NextMoeAuthStatus(false, true, false, 0L, lastError = sentinel)
        assertNull(secureError.lastErrorKind)
        assertTrue(secureError.isSecureStorageError)
        assertFalse(expired.isSecureStorageError)
        assertNull(NextMoeAuthStatus(false, true, false, 0L, lastError = "unknown_kind").lastErrorKind)
        assertNull(NextMoeAuthStatus(true, false, false, 0L, lastError = "").lastErrorKind)
    }
}
