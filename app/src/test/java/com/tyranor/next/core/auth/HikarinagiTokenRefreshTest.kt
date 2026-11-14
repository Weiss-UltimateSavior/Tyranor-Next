package com.tyranor.next.core.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tyranor.next.core.status.HikarinagiStatuses
import com.tyranor.next.core.status.PlayStatusService
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hikarinagi 刷新单飞与 needsReauth 短路行为：
 * 1. 快速路径命中时不发起刷新网络请求；
 * 2. needsReauth 已置位时不重放注定失败的刷新（零网络、零 prefs 写放大）；
 * 3. 并发调用共享同一次刷新：至多一次 HTTP 往返，等待方重走快速路径采纳写回结果；
 * 4. 刷新被 401 明确拒绝后置位 needsReauth，后续调用不再重放；
 * 5. 传输失败不误置 needsReauth（未被短路，可重试）；
 * 6. markNeedsReauth 同值短路：同原因重复调用不再 prefs 写 + statusVersion bump；
 * 7. 权限缺失与令牌失效分位：读路径 403 的 permissionDenied 独立位
 *    不置 needsReauth，令牌过期后刷新仍可自愈；真令牌失效仍被 needsReauth 短路；
 * 8. 拆分前版本遗留的 PERMISSION 档 last_error 经 getStatus 一次性归位到独立位，
 *    已被锁死的设备升级后恢复刷新自愈。
 *
 * HTTP 往返经 [HikarinagiAuthService.testRefreshHttpRoundTrip] 测试桩替换
 * （与 [SecureAuthStore.testCipherDelegate] 同款缝隙），其余逻辑全部走生产路径。
 */
class HikarinagiTokenRefreshTest {

    private val storage = ConcurrentHashMap<String, MutableMap<String, Any>>()
    private val memoryTokens = ConcurrentHashMap<String, String>()
    private val httpCalls = AtomicInteger(0)

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val store = storage.computeIfAbsent(name) { ConcurrentHashMap() }
            return createMemoryPreferences(store)
        }
    }

    @Before
    fun setUp() {
        storage.clear()
        memoryTokens.clear()
        httpCalls.set(0)
        HikarinagiAuthService.testClientId = "hika_test_client_id"
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
        HikarinagiAuthService.testRefreshHttpRoundTrip = null
        HikarinagiAuthService.testClientId = null
        SecureAuthStore.testCipherDelegate = null
        storage.clear()
        memoryTokens.clear()
    }

    /** 存入已过期令牌（默认走刷新路径）；saveTokens 成功路径会清空 last_error。 */
    private fun storeExpiredTokens() {
        HikarinagiAuthStore.saveTokens(context, "hika_stale_access", "hika_refresh_1", expiresAtMillis = 1_000L)
    }

    private fun stubHttp(block: (clientId: String, refreshToken: String) -> Pair<Int, String>) {
        HikarinagiAuthService.testRefreshHttpRoundTrip = { clientId, refreshToken ->
            httpCalls.incrementAndGet()
            block(clientId, refreshToken)
        }
    }

    @Test
    fun validStoredTokenIsReturnedWithoutRefresh() {
        HikarinagiAuthStore.saveTokens(
            context,
            "hika_fresh_access",
            "hika_refresh",
            System.currentTimeMillis() + 3_600_000L,
        )
        val versionBefore = HikarinagiAuthStore.statusVersion.value
        stubHttp { _, _ -> 400 to "" } // 若被调用即失败

        assertEquals("hika_fresh_access", HikarinagiAuthService.getValidAccessToken(context))
        assertEquals(0, httpCalls.get())
        assertEquals(versionBefore, HikarinagiAuthStore.statusVersion.value)
    }

    @Test
    fun needsReauthShortCircuitSkipsDoomedRefresh() {
        storeExpiredTokens()
        HikarinagiAuthStore.markNeedsReauth(context, HikarinagiAuthError.EXPIRED.name)
        stubHttp { _, _ -> 400 to "" } // 若被调用即失败：短路必须发生在任何刷新尝试之前
        val versionBefore = HikarinagiAuthStore.statusVersion.value

        assertNull(HikarinagiAuthService.getValidAccessToken(context))

        // 注定失败的刷新未被发起（零网络），也无伴随的 prefs 写/bump 重放副作用
        assertEquals(0, httpCalls.get())
        assertEquals(versionBefore, HikarinagiAuthStore.statusVersion.value)
        val status = HikarinagiAuthStore.getStatus(context)
        // markNeedsReauth 语义保持：令牌保留、授权状态不回退
        assertTrue(status.authorized)
        assertTrue(status.needsReauth)
        assertEquals("hika_stale_access", HikarinagiAuthStore.getAccessToken(context))
    }

    @Test
    fun concurrentCallersShareOneRefreshAndAdoptItsResult() {
        storeExpiredTokens()
        val taskStarted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        stubHttp { _, _ ->
            taskStarted.countDown()
            releaseTask.await(5, TimeUnit.SECONDS)
            200 to """{"access_token":"hika_new_access","expires_in":3600}"""
        }

        val runnerResult = AtomicReference<String?>("unset")
        val runner = thread {
            runnerResult.set(HikarinagiAuthService.getValidAccessToken(context))
        }
        val waiterResults = CopyOnWriteArrayList<String?>()
        var waiters: List<Thread> = emptyList()
        try {
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS))
            // 在途期间并发到达的调用方：等待并重走快速路径，不得发起第二个刷新
            waiters = (1..4).map {
                thread { waiterResults.add(HikarinagiAuthService.getValidAccessToken(context)) }
            }
            awaitRefreshWaiters(waiters.size)
        } finally {
            releaseTask.countDown()
            runner.join(TimeUnit.SECONDS.toMillis(5))
            waiters.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
        }

        assertEquals(1, httpCalls.get())
        assertEquals("hika_new_access", runnerResult.get())
        assertEquals(
            listOf("hika_new_access", "hika_new_access", "hika_new_access", "hika_new_access"),
            waiterResults.toList(),
        )
        // 刷新结果写回且清除失效标记
        assertEquals("hika_new_access", HikarinagiAuthStore.getAccessToken(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun rejectedRefreshMarksNeedsReauthAndStopsReplaying() {
        storeExpiredTokens()
        stubHttp { _, _ -> 401 to """{"error":"invalid_grant"}""" }

        assertNull(HikarinagiAuthService.getValidAccessToken(context))
        val statusAfterFirst = HikarinagiAuthStore.getStatus(context)
        assertTrue(statusAfterFirst.authorized)
        assertTrue(statusAfterFirst.needsReauth)
        assertEquals(HikarinagiAuthError.EXPIRED.name, statusAfterFirst.lastError)

        // 第二次调用：needsReauth 短路，不再重放注定失败的刷新
        val versionAfterFirst = HikarinagiAuthStore.statusVersion.value
        assertNull(HikarinagiAuthService.getValidAccessToken(context))
        assertEquals(1, httpCalls.get())
        assertEquals(versionAfterFirst, HikarinagiAuthStore.statusVersion.value)
    }

    @Test
    fun transportFailureReturnsNullWithoutMarkingNeedsReauth() {
        storeExpiredTokens()
        stubHttp { _, _ -> 503 to "service unavailable" }

        assertNull(HikarinagiAuthService.getValidAccessToken(context))
        val status = HikarinagiAuthStore.getStatus(context)
        // 传输失败不得误置失效标记（调用方按 NETWORK 档报错）
        assertTrue(status.authorized)
        assertFalse(status.needsReauth)

        // 未置位即不被短路：重试仍会发起刷新
        assertNull(HikarinagiAuthService.getValidAccessToken(context))
        assertEquals(2, httpCalls.get())
    }

    @Test
    fun markNeedsReauthSameValueShortCircuit() {
        val versionStart = HikarinagiAuthStore.statusVersion.value

        HikarinagiAuthStore.markNeedsReauth(context, HikarinagiAuthError.EXPIRED.name)
        val versionAfterFirst = HikarinagiAuthStore.statusVersion.value
        assertEquals(versionStart + 1, versionAfterFirst)

        // 同值重复调用（逐请求重放路径的同原因置位）：不再 prefs 写 + bump
        HikarinagiAuthStore.markNeedsReauth(context, HikarinagiAuthError.EXPIRED.name)
        HikarinagiAuthStore.markNeedsReauth(context, "  EXPIRED  ")
        assertEquals(versionAfterFirst, HikarinagiAuthStore.statusVersion.value)

        // 值变化仍需写入（另一令牌失效档：加密落盘失败哨兵，同属 needsReauth 语义）
        HikarinagiAuthStore.markNeedsReauth(context, "secure storage unavailable")
        assertEquals(versionAfterFirst + 1, HikarinagiAuthStore.statusVersion.value)
        assertEquals("secure storage unavailable", HikarinagiAuthStore.getStatus(context).lastError)
    }

    @Test
    fun permissionDeniedDoesNotBlockRefreshSelfHeal() {
        storeExpiredTokens()
        // 模拟读路径 403（令牌失效/权限缺失分位后）：权限缺失写入独立位，不置 needsReauth
        HikarinagiAuthStore.markPermissionDenied(context, HikarinagiAuthError.PERMISSION.name)
        val status = HikarinagiAuthStore.getStatus(context)
        assertTrue(status.authorized)
        assertTrue(status.permissionDenied)
        // 权限缺失不得置位 needsReauth，否则令牌过期后的刷新自愈被锁死（回归约束）
        assertFalse(status.needsReauth)

        stubHttp { _, _ -> 200 to """{"access_token":"hika_new_access","expires_in":3600}""" }
        assertEquals("hika_new_access", HikarinagiAuthService.getValidAccessToken(context))
        assertEquals(1, httpCalls.get())

        // 刷新自愈成功：令牌失效位未被误置；权限观察位保持（刷新不改变授权范围）
        val healed = HikarinagiAuthStore.getStatus(context)
        assertFalse(healed.needsReauth)
        assertTrue(healed.permissionDenied)
    }

    @Test
    fun tokenInvalidityStillShortCircuitsDespitePermissionDenied() {
        storeExpiredTokens()
        HikarinagiAuthStore.markPermissionDenied(context, HikarinagiAuthError.PERMISSION.name)
        // 权限缺失与真令牌失效并存：刷新被 invalid_grant 明确拒绝 → 置位 EXPIRED
        stubHttp { _, _ -> 401 to """{"error":"invalid_grant"}""" }
        assertNull(HikarinagiAuthService.getValidAccessToken(context))
        val statusAfterRejected = HikarinagiAuthStore.getStatus(context)
        assertTrue(statusAfterRejected.needsReauth)
        assertEquals(HikarinagiAuthError.EXPIRED.name, statusAfterRejected.lastError)

        // 真令牌失效仍被 needsReauth 短路：不重放（即使后续桩已换成可成功的响应）
        stubHttp { _, _ -> 200 to """{"access_token":"hika_new_access","expires_in":3600}""" }
        val versionBefore = HikarinagiAuthStore.statusVersion.value
        assertNull(HikarinagiAuthService.getValidAccessToken(context))
        assertEquals(1, httpCalls.get())
        assertEquals(versionBefore, HikarinagiAuthStore.statusVersion.value)
    }

    @Test
    fun legacyPermissionLastErrorIsSeparatedAndRefreshSelfHeals() {
        storeExpiredTokens()
        // 模拟语义拆分前版本的遗留状态：读路径 403 曾把 PERMISSION 写进 last_error，
        // 与刷新短路交互后会把过期令牌的自动刷新永久锁死
        HikarinagiAuthStore.markNeedsReauth(context, HikarinagiAuthError.PERMISSION.name)
        assertTrue(HikarinagiAuthStore.statusVersion.value > 0)

        // getStatus 一次性归位：PERMISSION 移入独立位、last_error 清空，刷新不再被锁死
        val status = HikarinagiAuthStore.getStatus(context)
        assertFalse(status.needsReauth)
        assertTrue(status.permissionDenied)

        stubHttp { _, _ -> 200 to """{"access_token":"hika_new_access","expires_in":3600}""" }
        assertEquals("hika_new_access", HikarinagiAuthService.getValidAccessToken(context))
        assertEquals(1, httpCalls.get())
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun missingClientIdWithStoredRefreshTokenReturnsConfigMissingWithoutNetwork() {
        storeExpiredTokens()
        HikarinagiAuthService.testClientId = ""

        val result = HikarinagiAuthService.getValidAccessTokenResult(context)

        assertEquals(HikarinagiAccessTokenResult.ConfigMissing, result)
        assertEquals(0, httpCalls.get())
    }

    @Test
    fun nonGrantOAuth400ErrorsKeepCredentialsWithoutMarkingNeedsReauth() {
        storeExpiredTokens()
        stubHttp { _, _ -> 400 to """{"error":"invalid_client"}""" }

        assertNull(HikarinagiAuthService.getValidAccessToken(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)

        stubHttp { _, _ -> 400 to """{"error":"invalid_grant"}""" }
        assertNull(HikarinagiAuthService.getValidAccessToken(context))
        assertTrue(HikarinagiAuthStore.getStatus(context).needsReauth)
        assertEquals(HikarinagiAuthError.EXPIRED.name, HikarinagiAuthStore.getStatus(context).lastError)
    }

    @Test
    fun writeBackAndRejectionGuardsKeepConcurrentRelogin() {
        storeExpiredTokens()
        stubHttp { _, _ ->
            assertTrue(
                HikarinagiAuthStore.saveTokens(
                    context,
                    "hika_relogin_access",
                    "hika_relogin_refresh",
                    System.currentTimeMillis() + 3_600_000L,
                ),
            )
            200 to """{"access_token":"hika_stale_access","refresh_token":"hika_stale_refresh","expires_in":3600}"""
        }

        val result = HikarinagiAuthService.getValidAccessTokenResult(context)
        assertEquals(HikarinagiAccessTokenResult.RefreshFailed, result)
        assertEquals("hika_relogin_access", HikarinagiAuthStore.getAccessToken(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun blankOrMalformed401RefreshErrorsKeepCredentialsWithoutMarkingNeedsReauth() {
        storeExpiredTokens()
        stubHttp { _, _ -> 401 to "" }
        assertEquals(HikarinagiAccessTokenResult.RefreshFailed, HikarinagiAuthService.getValidAccessTokenResult(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)
        assertEquals("hika_stale_access", HikarinagiAuthStore.getAccessToken(context))

        stubHttp { _, _ -> 401 to "not-json" }
        assertEquals(HikarinagiAccessTokenResult.RefreshFailed, HikarinagiAuthService.getValidAccessTokenResult(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)

        stubHttp { _, _ -> 401 to """{"error":"invalid_client"}""" }
        assertEquals(HikarinagiAccessTokenResult.RefreshFailed, HikarinagiAuthService.getValidAccessTokenResult(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun rejectedStaleRefreshSkipsMarkingNeedsReauthAfterReloginOrClear() {
        storeExpiredTokens()
        stubHttp { _, _ ->
            assertTrue(
                HikarinagiAuthStore.saveTokens(
                    context,
                    "hika_relogin_acc",
                    "hika_relogin_ref",
                    System.currentTimeMillis() + 3_600_000L,
                ),
            )
            401 to """{"error":"invalid_grant"}"""
        }
        assertEquals(HikarinagiAccessTokenResult.RefreshFailed, HikarinagiAuthService.getValidAccessTokenResult(context))
        assertEquals("hika_relogin_acc", HikarinagiAuthStore.getAccessToken(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)

        // 注销/清空后的滞后刷新响应（无论 200 成功写回还是 401 invalid_grant 拒绝）都不得复活凭据或置 needsReauth
        storeExpiredTokens()
        stubHttp { _, _ ->
            HikarinagiAuthStore.clear(context)
            200 to """{"access_token":"hika_ghost","refresh_token":"hika_ghost_ref","expires_in":3600}"""
        }
        assertEquals(HikarinagiAccessTokenResult.RefreshFailed, HikarinagiAuthService.getValidAccessTokenResult(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).authorized)
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)

        storeExpiredTokens()
        stubHttp { _, _ ->
            HikarinagiAuthStore.clear(context)
            401 to """{"error":"invalid_grant"}"""
        }
        assertEquals(HikarinagiAccessTokenResult.RefreshFailed, HikarinagiAuthService.getValidAccessTokenResult(context))
        assertFalse(HikarinagiAuthStore.getStatus(context).authorized)
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun freshLoginClearsStaleUsernameWhileRefreshPreservesIt() {
        val epoch = HikarinagiAuthStore.saveTokensReturningEpoch(
            context,
            "hika_old_acc",
            "hika_old_ref",
            System.currentTimeMillis() - 1_000L,
        )!!
        assertTrue(HikarinagiAuthStore.saveUsernameIfEpochMatches(context, "old_user", epoch))

        // 刷新保留用户名
        stubHttp { _, _ -> 200 to """{"access_token":"hika_refreshed_acc","expires_in":3600}""" }
        assertEquals("hika_refreshed_acc", HikarinagiAuthService.getValidAccessToken(context))
        assertEquals("old_user", HikarinagiAuthStore.getStatus(context).username)

        // 全新登录同步清除旧用户名
        assertTrue(
            HikarinagiAuthStore.saveTokens(
                context,
                "hika_new_acc",
                "hika_new_ref",
                System.currentTimeMillis() + 3_600_000L,
            ),
        )
        assertEquals("", HikarinagiAuthStore.getStatus(context).username)
    }

    @Test
    fun staleOAuthExchangeGenerationIsRejectedAfterReloginOrClear() {
        val gen0 = HikarinagiAuthStore.readCredentialGeneration(context)
        assertTrue(HikarinagiAuthStore.saveTokens(context, "hika_user_a", "ref_a", System.currentTimeMillis() + 3_600_000L))
        HikarinagiAuthStore.saveUsername(context, "alice")

        // 先前启动的 OAuth 换码被拒绝，不得覆写 A 的凭据或身份
        val err = HikarinagiAuthService.completeOAuthTokenExchange(
            context = context,
            expectedGeneration = gen0,
            accessToken = "hika_stale",
            refreshToken = "ref_stale",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
        )
        assertEquals(HikarinagiAuthError.TOKEN_EXCHANGE_FAILED, err)
        assertEquals("hika_user_a", HikarinagiAuthStore.getAccessToken(context))
        assertEquals("alice", HikarinagiAuthStore.getStatus(context).username)

        // clear 后持久化代次递增，旧代次 OAuth 换码同样拒绝
        val genBeforeClear = HikarinagiAuthStore.readCredentialGeneration(context)
        HikarinagiAuthStore.clear(context)
        val genAfterClear = HikarinagiAuthStore.readCredentialGeneration(context)
        assertTrue(genAfterClear > genBeforeClear)

        assertEquals(
            HikarinagiAuthError.TOKEN_EXCHANGE_FAILED,
            HikarinagiAuthService.completeOAuthTokenExchange(
                context = context,
                expectedGeneration = genBeforeClear,
                accessToken = "hika_ghost",
                refreshToken = "ref_ghost",
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            ),
        )
        assertFalse(HikarinagiAuthStore.getStatus(context).authorized)
    }

    @Test
    fun finishHikarinagiStatusReadGuardsErrorClearsByAccessToken() {
        assertTrue(HikarinagiAuthStore.saveTokens(context, "hika_tok_new", "ref_new", System.currentTimeMillis() + 3_600_000L))
        HikarinagiAuthStore.markNeedsReauth(context, HikarinagiAuthError.EXPIRED.name)
        HikarinagiAuthStore.markPermissionDenied(context, HikarinagiAuthError.PERMISSION.name)

        // 旧令牌发起的读请求迟到返回（无论是 2xx 数据还是 404 null）均不得清掉新令牌的错误位
        val okJson = JSONObject().put("success", true).put("data", JSONObject().put("status", HikarinagiStatuses.GOING))
        PlayStatusService.finishHikarinagiStatusRead(context, "hika_tok_old", okJson)
        assertTrue(HikarinagiAuthStore.getStatus(context).needsReauth)
        assertTrue(HikarinagiAuthStore.getStatus(context).permissionDenied)

        PlayStatusService.finishHikarinagiStatusRead(context, "hika_tok_old", null)
        assertTrue(HikarinagiAuthStore.getStatus(context).needsReauth)
        assertTrue(HikarinagiAuthStore.getStatus(context).permissionDenied)

        // 当前令牌在 404 null 与 2xx JSON 两条路径均可正常清位
        val notFound = PlayStatusService.finishHikarinagiStatusRead(context, "hika_tok_new", null)
        assertFalse(notFound.exists)
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)
        assertFalse(HikarinagiAuthStore.getStatus(context).permissionDenied)

        HikarinagiAuthStore.markNeedsReauth(context, HikarinagiAuthError.EXPIRED.name)
        HikarinagiAuthStore.markPermissionDenied(context, HikarinagiAuthError.PERMISSION.name)
        val rated = PlayStatusService.finishHikarinagiStatusRead(context, "hika_tok_new", okJson)
        assertTrue(rated.exists)
        assertEquals(HikarinagiStatuses.GOING, rated.status)
        assertFalse(HikarinagiAuthStore.getStatus(context).needsReauth)
        assertFalse(HikarinagiAuthStore.getStatus(context).permissionDenied)
    }

    @Test
    fun needsReauthShortCircuitsEvenWhenAccessTokenUnexpired() {
        HikarinagiAuthStore.saveTokens(
            context,
            "hika_unexpired_revoked",
            "hika_refresh_keep",
            System.currentTimeMillis() + 3_600_000L,
        )
        HikarinagiAuthStore.markNeedsReauth(context, HikarinagiAuthError.EXPIRED.name)
        stubHttp { _, _ -> 200 to """{"access_token":"should_not_refresh"}""" }

        assertEquals(HikarinagiAccessTokenResult.RefreshFailed, HikarinagiAuthService.getValidAccessTokenResult(context))
        assertNull(HikarinagiAuthService.getValidAccessToken(context))
        assertEquals(0, httpCalls.get())
    }

    @Test
    fun jsonNullRefreshTokenPreservesExistingRefreshToken() {
        storeExpiredTokens()
        stubHttp { _, _ ->
            200 to """{"access_token":"hika_new_access","refresh_token":null,"expires_in":3600}"""
        }

        val result = HikarinagiAuthService.getValidAccessTokenResult(context)
        assertEquals(HikarinagiAccessTokenResult.Success("hika_new_access"), result)
        assertEquals("hika_refresh_1", HikarinagiAuthStore.getRefreshToken(context))
    }

    private fun awaitRefreshWaiters(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (HikarinagiAuthService.refreshWaiterCount() < expected && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue(
            "expected $expected waiters in queue, got ${HikarinagiAuthService.refreshWaiterCount()}",
            HikarinagiAuthService.refreshWaiterCount() >= expected,
        )
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
        val removedSentinel = Any()
        val pendingChanges = ConcurrentHashMap<String, Any>()
        var clearPending = false
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> { pendingChanges[args[0] as String] = args[1] ?: removedSentinel; editor }
                "putLong" -> { pendingChanges[args[0] as String] = args[1]; editor }
                "putInt" -> { pendingChanges[args[0] as String] = args[1]; editor }
                "putBoolean" -> { pendingChanges[args[0] as String] = args[1]; editor }
                "remove" -> { pendingChanges[args[0] as String] = removedSentinel; editor }
                "clear" -> { clearPending = true; pendingChanges.clear(); editor }
                "apply", "commit" -> {
                    if (clearPending) store.clear()
                    for ((k, v) in pendingChanges) {
                        if (v === removedSentinel) store.remove(k) else store[k] = v
                    }
                    if (method.name == "commit") true else null
                }
                else -> null
            }
        } as SharedPreferences.Editor
        return editor
    }
}
