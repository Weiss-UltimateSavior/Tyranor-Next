package com.tyranor.next.core.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 验证授权凭据与登录态持久化行为：
 * 1. 发生错误 / markNeedsReauth / markLastError 时，保留已存令牌，保持 authorized = true，不重置为未授权。
 * 2. 只有显式 clear 时才会清空令牌并将 authorized 设为 false。
 *
 * Bangumi 来源行标题拼装（bangumiSourceTitle）的映射断言在
 * `com.tyranor.next.ui.cover.BangumiSourceTitleTest`：core/auth 测试不反向 import ui 层。
 */
class AuthStatusPersistenceTest {

    private val storage = mutableMapOf<String, MutableMap<String, Any>>()
    private val memoryTokens = mutableMapOf<String, String>()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val store = storage.getOrPut(name) { mutableMapOf() }
            return createMemoryPreferences(store)
        }
    }

    @Before
    fun setUp() {
        storage.clear()
        memoryTokens.clear()
        SecureAuthStore.testCipherDelegate = { field, plain ->
            if (plain != null) {
                if (plain.isEmpty()) memoryTokens.remove(field)
                else memoryTokens[field] = plain
                null
            } else {
                memoryTokens[field]
            }
        }
    }

    @After
    fun tearDown() {
        SecureAuthStore.testCipherDelegate = null
        storage.clear()
        memoryTokens.clear()
    }

    @Test
    fun vndbAuthStoreKeepsTokenAndAuthorizedStateOnError() {
        assertFalse(VndbAuthStore.getStatus(context).authorized)

        // 保存 token（加密 + 落盘全部成功时返回 true，契约与 Bangumi/Hikarinagi saveTokens 一致）
        assertTrue(VndbAuthStore.saveToken(context, "vndb_test_token_123", "u100", "testuser"))
        val status = VndbAuthStore.getStatus(context)
        assertTrue(status.authorized)
        assertFalse(status.needsReauth)
        assertEquals("testuser", status.username)
        assertEquals("vndb_test_token_123", VndbAuthStore.getToken(context))

        // 模拟错误：记录错误（如只读 token 写失败或权限不足）
        VndbAuthStore.markLastError(context, VndbAuthError.PERMISSION.name)
        val statusAfterError = VndbAuthStore.getStatus(context)
        // 关键断言：写状态或校验失败后，不重置为未授权，仍保持 authorized = true
        assertTrue("Error must not reset authorized state to false", statusAfterError.authorized)
        assertTrue(statusAfterError.needsReauth)
        assertEquals(VndbAuthError.PERMISSION.name, statusAfterError.lastError)
        assertEquals("vndb_test_token_123", VndbAuthStore.getToken(context))

        // 仅显式 clear 才重置为未授权
        VndbAuthStore.clear(context)
        val clearedStatus = VndbAuthStore.getStatus(context)
        assertFalse(clearedStatus.authorized)
        assertFalse(clearedStatus.needsReauth)
        assertEquals("", VndbAuthStore.getToken(context))
    }

    @Test
    fun vndbAuthStoreReflectsPermissionTierChangesForSameToken() {
        val token = "vndb_same_token_abc"
        // 1. 最初仅保存只读权限（或空权限）
        VndbAuthStore.saveToken(context, token, "u100", "testuser", listOf("listread"))
        val statusReadOnly = VndbAuthStore.getStatus(context)
        assertTrue(statusReadOnly.authorized)
        assertEquals(listOf("listread"), statusReadOnly.permissions)
        assertEquals(VndbPermissionTier.READ_PRIVATE, statusReadOnly.permissionTier)

        // 2. 在网站改权限后，使用同一 token 重新授权，变为读写权限
        VndbAuthStore.saveToken(context, token, "u100", "testuser", listOf("listread", "listwrite"))
        val statusReadWrite = VndbAuthStore.getStatus(context)
        assertTrue(statusReadWrite.authorized)
        assertEquals(listOf("listread", "listwrite"), statusReadWrite.permissions)
        assertEquals(VndbPermissionTier.WRITE, statusReadWrite.permissionTier)

        // 关键断言：同 token 下权限变更必须改变 VndbAuthStatus 的相等性，确保 Compose 界面即时重组更新
        assertNotEquals(statusReadOnly, statusReadWrite)
    }

    @Test
    fun bangumiAuthStoreKeepsTokenAndAuthorizedStateOnMarkNeedsReauth() {
        assertFalse(BangumiAuthStore.getStatus(context).authorized)

        // 保存 tokens（加密 + 落盘全部成功时返回 true）
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_access_token_abc", "bgm_refresh_token_xyz", 99999999L))
        BangumiAuthStore.saveUserIdentity(context, 12345L, "bgm_user")
        val status = BangumiAuthStore.getStatus(context)
        assertTrue(status.authorized)
        assertFalse(status.needsReauth)
        assertFalse(status.isPersonalToken)
        assertEquals("bgm_access_token_abc", BangumiAuthStore.getAccessToken(context))
        assertEquals("bgm_refresh_token_xyz", BangumiAuthStore.getRefreshToken(context))
        assertEquals("bgm_user", BangumiAuthStore.getUsername(context))

        // markNeedsReauth: 令牌失效时记录错误，但不清空令牌，保持 authorized = true
        val versionBeforeReauth = BangumiAuthStore.statusVersion.value
        BangumiAuthStore.markNeedsReauth(context, BangumiAuthError.EXPIRED.name)
        assertEquals(versionBeforeReauth + 1, BangumiAuthStore.statusVersion.value)
        val statusAfterReauth = BangumiAuthStore.getStatus(context)
        // 关键断言：不重置为未授权
        assertTrue("markNeedsReauth must not reset authorized state to false", statusAfterReauth.authorized)
        assertTrue(statusAfterReauth.needsReauth)
        assertEquals(BangumiAuthError.EXPIRED.name, statusAfterReauth.lastError)
        assertEquals("bgm_access_token_abc", BangumiAuthStore.getAccessToken(context))

        // 同值短路（与 Hikarinagi / Vndb 同款）：同一原因重复置位不再 prefs 写 + bump
        BangumiAuthStore.markNeedsReauth(context, BangumiAuthError.EXPIRED.name)
        BangumiAuthStore.markNeedsReauth(context, "  EXPIRED  ")
        assertEquals(versionBeforeReauth + 1, BangumiAuthStore.statusVersion.value)

        // 仅显式 clear 才清空
        BangumiAuthStore.clear(context)
        val clearedStatus = BangumiAuthStore.getStatus(context)
        assertFalse(clearedStatus.authorized)
        assertFalse(clearedStatus.needsReauth)
        assertEquals("", BangumiAuthStore.getAccessToken(context))
    }

    @Test
    fun bangumiAuthInProgressMarkerPersistsUntilCleared() {
        // startAuthorization 发起浏览器前置位标记「授权中」；回调抵达（完成/取消）或
        // 发起失败即归零。此处验证 store 侧标记生命周期（时间戳持久化 + 归零）。
        assertTrue(BangumiAuthStore.getStatus(context).authInProgressAtMillis == 0L)

        BangumiAuthStore.setAuthInProgress(context, 1_700_000_000_000L)
        val inProgress = BangumiAuthStore.getStatus(context)
        assertEquals(1_700_000_000_000L, inProgress.authInProgressAtMillis)

        // 回调抵达：归零
        BangumiAuthStore.setAuthInProgress(context, 0L)
        assertEquals(0L, BangumiAuthStore.getStatus(context).authInProgressAtMillis)

        // 发起后进程死亡 → 时间戳保留；显式登出（clear）整体清空
        BangumiAuthStore.setAuthInProgress(context, 1_700_000_000_000L)
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_marker_access", "bgm_marker_refresh", 0L))
        assertTrue(BangumiAuthStore.getStatus(context).authInProgressAtMillis > 0L)
        BangumiAuthStore.clear(context)
        assertEquals(0L, BangumiAuthStore.getStatus(context).authInProgressAtMillis)
    }

    @Test
    fun bangumiIdentityPersistsWithNumericUserIdFallback() {
        BangumiAuthStore.saveTokens(context, "bgm_access_num", "", 0L)
        BangumiAuthStore.saveUserIdentity(context, 987654L, "")
        val fallbackStatus = BangumiAuthStore.getStatus(context)
        assertEquals("987654", fallbackStatus.username)

        // 数字字符串用户名不被改写（解析层已把缺名用户回退为 uid 串，store 只缓存）
        BangumiAuthStore.saveUserIdentity(context, 987654L, "987654")
        val numericStatus = BangumiAuthStore.getStatus(context)
        assertEquals("987654", numericStatus.username)

        BangumiAuthStore.saveUserIdentity(context, 987654L, "bgm_user")
        val customStatus = BangumiAuthStore.getStatus(context)
        assertEquals("bgm_user", customStatus.username)

        assertEquals(
            BangumiAuthService.BangumiTokenIdentity(987654L, "987654"),
            BangumiAuthService.parseBangumiMeIdentity(JSONObject("""{"id":987654,"username":"987654"}""")),
        )
        assertEquals(
            BangumiAuthService.BangumiTokenIdentity(987654L, "987654"),
            BangumiAuthService.parseBangumiMeIdentity(JSONObject("""{"id":987654,"username":null}""")),
        )
        assertEquals(
            BangumiAuthService.BangumiTokenIdentity(987654L, "bgm_user"),
            BangumiAuthService.parseBangumiMeIdentity(JSONObject("""{"id":987654,"username":"bgm_user"}""")),
        )
    }

    @Test
    fun hikarinagiAuthStoreKeepsTokenAndAuthorizedStateOnMarkNeedsReauth() {
        assertFalse(HikarinagiAuthStore.getStatus(context).authorized)

        assertTrue(HikarinagiAuthStore.saveTokens(context, "hikari_access", "hikari_refresh", 88888888L))
        HikarinagiAuthStore.saveUsername(context, "hikari_user")
        val status = HikarinagiAuthStore.getStatus(context)
        assertTrue(status.authorized)
        assertFalse(status.needsReauth)
        assertEquals("hikari_user", status.username)

        HikarinagiAuthStore.markNeedsReauth(context, HikarinagiAuthError.EXPIRED.name)
        val statusAfter = HikarinagiAuthStore.getStatus(context)
        assertTrue("Hikarinagi markNeedsReauth must keep authorized state", statusAfter.authorized)
        assertTrue(statusAfter.needsReauth)
        assertEquals(HikarinagiAuthError.EXPIRED.name, statusAfter.lastError)
        assertEquals("hikari_user", statusAfter.username)

        // 权限缺失与令牌失效分位：读路径 403 走独立 permission_error 位，
        // 不置 needsReauth —— 令牌过期后的自动刷新自愈不被锁死
        val versionBeforeDenied = HikarinagiAuthStore.statusVersion.value
        HikarinagiAuthStore.markPermissionDenied(context, HikarinagiAuthError.PERMISSION.name)
        val statusDenied = HikarinagiAuthStore.getStatus(context)
        assertTrue(statusDenied.authorized)
        assertTrue(statusDenied.needsReauth)
        assertTrue(statusDenied.permissionDenied)
        assertEquals(HikarinagiAuthError.EXPIRED.name, statusDenied.lastError)

        // markPermissionDenied 同值短路（与 markNeedsReauth 同款）：重复置位不再 bump
        HikarinagiAuthStore.markPermissionDenied(context, HikarinagiAuthError.PERMISSION.name)
        assertEquals(versionBeforeDenied + 1, HikarinagiAuthStore.statusVersion.value)

        HikarinagiAuthStore.clearLastError(context)
        HikarinagiAuthStore.clearPermissionError(context)
        val statusRecovered = HikarinagiAuthStore.getStatus(context)
        assertFalse(statusRecovered.needsReauth)
        assertFalse(statusRecovered.permissionDenied)
        assertEquals("", statusRecovered.lastError)

        HikarinagiAuthStore.clear(context)
        val cleared = HikarinagiAuthStore.getStatus(context)
        assertFalse(cleared.authorized)
        assertFalse(cleared.permissionDenied)
        assertEquals("", cleared.username)
    }

    private fun createMemoryPreferences(store: MutableMap<String, Any>): SharedPreferences {
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> store.containsKey(args[0])
                "getString" -> store[args[0]] as? String ?: args.getOrNull(1) as? String
                "getLong" -> store[args[0]] as? Long ?: args.getOrNull(1) as? Long ?: 0L
                "getInt" -> store[args[0]] as? Int ?: args.getOrNull(1) as? Int ?: 0
                "getBoolean" -> store[args[0]] as? Boolean ?: args.getOrNull(1) as? Boolean ?: false
                "getAll" -> store.toMap()
                "edit" -> createMemoryEditor(store)
                else -> null
            }
        } as SharedPreferences
    }

    private fun createMemoryEditor(store: MutableMap<String, Any>): SharedPreferences.Editor {
        val pendingChanges = mutableMapOf<String, Any?>()
        var clearPending = false
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> { pendingChanges[args[0] as String] = args[1]; editor }
                "putLong" -> { pendingChanges[args[0] as String] = args[1]; editor }
                "putInt" -> { pendingChanges[args[0] as String] = args[1]; editor }
                "putBoolean" -> { pendingChanges[args[0] as String] = args[1]; editor }
                "remove" -> { pendingChanges[args[0] as String] = null; editor }
                "clear" -> { clearPending = true; pendingChanges.clear(); editor }
                "apply", "commit" -> {
                    if (clearPending) store.clear()
                    for ((k, v) in pendingChanges) {
                        if (v == null) store.remove(k) else store[k] = v
                    }
                    if (method.name == "commit") true else null
                }
                else -> null
            }
        } as SharedPreferences.Editor
        return editor
    }
}
