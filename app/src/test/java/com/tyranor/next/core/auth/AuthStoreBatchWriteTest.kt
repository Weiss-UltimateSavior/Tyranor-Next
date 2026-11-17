package com.tyranor.next.core.auth

import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * BK-30 单 editor 批量提交回归：三 store 的 saveTokens/saveToken 由「逐字段独立 apply」
 * 收敛为单 editor 一次 apply（明文字段与加密字段同批），以 apply 计数钉住原子性形状：
 * 1. Bangumi/Hikarinagi saveTokens 恰 1 次 editor apply（原 3 次）；Vndb saveToken 恰 1 次（原 2 次）；
 * 2. BK-24 Boolean 返回语义不变（成功 true、落库内容可读、last_error 清空）；
 * 3. BK-25 statusVersion 契约不变：每次保存恰 bump 一次；
 * 4. Bangumi 空 refresh token 的 Remove 路径在批量 editor 下同样生效。
 */
class AuthStoreBatchWriteTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule(installInMemoryCipher = true)

    private val applyCount = AtomicInteger(0)
    private lateinit var context: InMemoryPreferencesContext

    private fun storageSnapshot(name: String): Map<String, Any> = context.prefSnapshot(name)

    @Before
    fun setUp() {
        applyCount.set(0)
        context = InMemoryPreferencesContext(onCommit = { applyCount.incrementAndGet() })
    }

    @Test
    fun bangumiSaveTokensAppliesSingleEditorBatch() {
        warmUp { BangumiAuthStore.saveTokens(context, "warmup_access", "warmup_refresh", 0L) }
        val versionBefore = BangumiAuthStore.statusVersion.value
        applyCount.set(0)

        val ok = BangumiAuthStore.saveTokens(context, "bgm_access", "bgm_refresh", 1234L)

        assertTrue(ok)
        // BK-30：加密字段 + expires_at + last_error 全部在单 editor 一次 apply（原 3 次 apply）
        assertEquals(1, applyCount.get())
        assertEquals("bgm_access", storageSnapshot("bangumi_auth_secure")[SecureAuthStore.FIELD_ACCESS_TOKEN])
        assertEquals("bgm_refresh", storageSnapshot("bangumi_auth_secure")[SecureAuthStore.FIELD_REFRESH_TOKEN])
        assertEquals("bgm_access", BangumiAuthStore.getAccessToken(context))
        assertEquals("bgm_refresh", BangumiAuthStore.getRefreshToken(context))
        assertEquals(1234L, BangumiAuthStore.getExpiresAtMillis(context))
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
        // BK-25：每次保存恰 bump 一次
        assertEquals(versionBefore + 1, BangumiAuthStore.statusVersion.value)
    }

    @Test
    fun bangumiEmptyRefreshTokenRemovesFieldWithinSameBatch() {
        warmUp { BangumiAuthStore.saveTokens(context, "warmup_access", "warmup_refresh", 0L) }
        applyCount.set(0)

        val ok = BangumiAuthStore.saveTokens(context, "bgm_personal", "", 0L)

        assertTrue(ok)
        assertEquals(1, applyCount.get())
        assertEquals("bgm_personal", storageSnapshot("bangumi_auth_secure")[SecureAuthStore.FIELD_ACCESS_TOKEN])
        assertFalse(storageSnapshot("bangumi_auth_secure").containsKey(SecureAuthStore.FIELD_REFRESH_TOKEN))
        assertEquals("bgm_personal", BangumiAuthStore.getAccessToken(context))
        assertEquals("", BangumiAuthStore.getRefreshToken(context))
        assertTrue(BangumiAuthStore.getStatus(context).isPersonalToken)
    }

    @Test
    fun hikarinagiSaveTokensAppliesSingleEditorBatch() {
        // 首次访问触发 ensureMigrated 的迁移标记写入：先预热，再对增量计数
        warmUp { HikarinagiAuthStore.saveTokens(context, "warmup_access", "warmup_refresh", 0L) }
        val versionBefore = HikarinagiAuthStore.statusVersion.value
        applyCount.set(0)

        val ok = HikarinagiAuthStore.saveTokens(context, "hika_access", "hika_refresh", 4321L)

        assertTrue(ok)
        assertEquals(1, applyCount.get())
        assertEquals("hika_access", storageSnapshot("hikarinagi_auth_secure")[SecureAuthStore.FIELD_ACCESS_TOKEN])
        assertEquals("hika_refresh", storageSnapshot("hikarinagi_auth_secure")[SecureAuthStore.FIELD_REFRESH_TOKEN])
        assertEquals("hika_access", HikarinagiAuthStore.getAccessToken(context))
        assertEquals("hika_refresh", HikarinagiAuthStore.getRefreshToken(context))
        assertEquals(4321L, HikarinagiAuthStore.getExpiresAtMillis(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)
        assertEquals(versionBefore + 1, HikarinagiAuthStore.statusVersion.value)

        HikarinagiAuthStore.clear(context)
        assertFalse(storageSnapshot("hikarinagi_auth_secure").containsKey(SecureAuthStore.FIELD_ACCESS_TOKEN))
        assertFalse(storageSnapshot("hikarinagi_auth_secure").containsKey(SecureAuthStore.FIELD_REFRESH_TOKEN))
    }

    @Test
    fun vndbSaveTokenAppliesSingleEditorBatch() {
        val versionBefore = VndbAuthStore.statusVersion.value
        applyCount.set(0)

        val ok = VndbAuthStore.saveToken(context, "vndb_token", "u17", "vndb_user", listOf("listread"))

        assertTrue(ok)
        // BK-30：token 字段 + 身份/权限/last_error 全部在单 editor 一次 apply（原 2 次 apply）
        assertEquals(1, applyCount.get())
        assertEquals("vndb_token", storageSnapshot("vndb_auth_secure")["token"])
        assertEquals("vndb_token", VndbAuthStore.getToken(context))
        val status = VndbAuthStore.getStatus(context)
        assertTrue(status.authorized)
        assertEquals("vndb_user", status.username)
        assertEquals(listOf("listread"), status.permissions)
        assertFalse(status.needsReauth)
        assertEquals(versionBefore + 1, VndbAuthStore.statusVersion.value)

        VndbAuthStore.clear(context)
        assertFalse(storageSnapshot("vndb_auth_secure").containsKey("token"))
    }

    @Test
    fun bangumiFailedEncryptionPreservesExistingTokensAndExpiry() {
        warmUp { BangumiAuthStore.saveTokens(context, "old_bgm_access", "old_bgm_refresh", 9999L) }
        SecureAuthStore.testForceEncryptFail = true

        val ok = BangumiAuthStore.saveTokens(context, "new_bgm_access", "new_bgm_refresh", 1234L)

        assertFalse(ok)
        assertEquals("old_bgm_access", BangumiAuthStore.getAccessToken(context))
        assertEquals("old_bgm_refresh", BangumiAuthStore.getRefreshToken(context))
        assertEquals(9999L, BangumiAuthStore.getExpiresAtMillis(context))
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, BangumiAuthStore.getStatus(context).lastError)
        assertTrue(BangumiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun hikarinagiFailedEncryptionPreservesExistingTokensAndExpiry() {
        warmUp { HikarinagiAuthStore.saveTokens(context, "old_hika_access", "old_hika_refresh", 8888L) }
        SecureAuthStore.testForceEncryptFail = true

        val ok = HikarinagiAuthStore.saveTokens(context, "new_hika_access", "new_hika_refresh", 4321L)

        assertFalse(ok)
        assertEquals("old_hika_access", HikarinagiAuthStore.getAccessToken(context))
        assertEquals("old_hika_refresh", HikarinagiAuthStore.getRefreshToken(context))
        assertEquals(8888L, HikarinagiAuthStore.getExpiresAtMillis(context))
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, HikarinagiAuthStore.getStatus(context).lastError)
        assertTrue(HikarinagiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun vndbFailedEncryptionPreservesExistingTokenAndIdentity() {
        warmUp {
            VndbAuthStore.saveToken(context, "old_vndb_token", "u10", "old_user", listOf("listread", "listwrite"))
        }
        SecureAuthStore.testForceEncryptFail = true

        val ok = VndbAuthStore.saveToken(context, "new_vndb_token", "u99", "new_user", listOf("listread"))

        assertFalse(ok)
        assertEquals("old_vndb_token", VndbAuthStore.getToken(context))
        assertEquals("u10", VndbAuthStore.getUserId(context))
        val status = VndbAuthStore.getStatus(context)
        assertTrue(status.authorized)
        assertEquals("old_user", status.username)
        assertEquals(listOf("listread", "listwrite"), status.permissions)
        assertTrue(status.needsReauth)
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, status.lastError)
    }

    @Test
    fun vndbSavePermissionsIfTokenMatchesIgnoresStaleToken() {
        assertTrue(VndbAuthStore.saveToken(context, "vndb_tok_1", "u1", "alice", listOf("listread")))
        // 并发换号写入新 token（无权限）
        assertTrue(VndbAuthStore.saveToken(context, "vndb_tok_2", "u2", "bob", emptyList()))

        // 旧 token 的权限回查结果晚到：必须忽略，不得污染新 token 的权限缓存
        assertFalse(VndbAuthStore.savePermissionsIfTokenMatches(context, "vndb_tok_1", listOf("listread", "listwrite")))
        assertEquals(emptyList<String>(), VndbAuthStore.getStatus(context).permissions)

        // 当前 token 正常写入
        assertTrue(VndbAuthStore.savePermissionsIfTokenMatches(context, "vndb_tok_2", listOf("listread", "listwrite")))
        assertEquals(listOf("listread", "listwrite"), VndbAuthStore.getStatus(context).permissions)

        // 同值短路：重复写入相同权限不触发 prefs apply 与 statusVersion 递增
        val appliesBefore = applyCount.get()
        val versionBefore = VndbAuthStore.statusVersion.value
        assertTrue(VndbAuthStore.savePermissionsIfTokenMatches(context, "vndb_tok_2", listOf("listread", "listwrite")))
        assertEquals(appliesBefore, applyCount.get())
        assertEquals(versionBefore, VndbAuthStore.statusVersion.value)
    }

    @Test
    fun hikarinagiLegacyPermissionMigrationMovesToPermissionDeniedUnderLock() {
        warmUp { HikarinagiAuthStore.saveTokens(context, "hika_a", "hika_r", 0L) }
        SecureAuthStore.prefs(context).edit()
            .putString(SecureAuthStore.FIELD_LAST_ERROR, HikarinagiAuthError.PERMISSION.name)
            .apply()

        val status = HikarinagiAuthStore.getStatus(context)
        assertFalse(status.needsReauth)
        assertTrue(status.permissionDenied)
        assertEquals("", status.lastError)
    }

    @Test
    fun nextMoeSaveTokensAppliesSingleEditorBatch() {
        val versionBefore = NextMoeAuthStore.statusVersion.value
        applyCount.set(0)

        val ok = NextMoeAuthStore.saveTokens(context, "nm_access", "nm_refresh", 5678L)

        assertTrue(ok)
        assertEquals(1, applyCount.get())
        assertEquals("nm_access", storageSnapshot("nextmoe_auth_secure")["access_token"])
        assertEquals("nm_refresh", storageSnapshot("nextmoe_auth_secure")["refresh_token"])
        assertEquals("nm_access", NextMoeAuthStore.getAccessToken(context))
        assertEquals("nm_refresh", NextMoeAuthStore.getRefreshToken(context))
        assertEquals(5678L, NextMoeAuthStore.getExpiresAtMillis(context))
        assertFalse(NextMoeAuthStore.getStatus(context).needsReauth)
        assertEquals(versionBefore + 1, NextMoeAuthStore.statusVersion.value)

        NextMoeAuthStore.clear(context)
        assertFalse(storageSnapshot("nextmoe_auth_secure").containsKey("access_token"))
        assertFalse(storageSnapshot("nextmoe_auth_secure").containsKey("refresh_token"))
    }

    @Test
    fun nextMoeFailedEncryptionPreservesExistingTokensAndExpiry() {
        warmUp { NextMoeAuthStore.saveTokens(context, "old_nm_access", "old_nm_refresh", 7777L) }
        SecureAuthStore.testForceEncryptFail = true

        val ok = NextMoeAuthStore.saveTokens(context, "new_nm_access", "new_nm_refresh", 1234L)

        assertFalse(ok)
        assertEquals("old_nm_access", NextMoeAuthStore.getAccessToken(context))
        assertEquals("old_nm_refresh", NextMoeAuthStore.getRefreshToken(context))
        assertEquals(7777L, NextMoeAuthStore.getExpiresAtMillis(context))
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, NextMoeAuthStore.getStatus(context).lastError)
        assertTrue(NextMoeAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun bangumiSaveTokensWithClearIdentityResetsAuthInProgressTimestamp() {
        BangumiAuthStore.setAuthInProgress(context, atMillis = 123_456L)
        assertEquals(123_456L, BangumiAuthStore.getStatus(context).authInProgressAtMillis)

        assertTrue(
            BangumiAuthStore.saveTokens(
                context = context,
                accessToken = "bgm_pat",
                refreshToken = "",
                expiresAtMillis = 0L,
            ),
        )

        assertEquals(0L, BangumiAuthStore.getStatus(context).authInProgressAtMillis)
    }

    /** 预热一次并不计数的保存（吸收 Hikarinagi 迁移首写等一次性副作用）。 */
    private fun warmUp(block: () -> Unit) {
        val before = applyCount.get()
        block()
        applyCount.set(before)
    }
}
