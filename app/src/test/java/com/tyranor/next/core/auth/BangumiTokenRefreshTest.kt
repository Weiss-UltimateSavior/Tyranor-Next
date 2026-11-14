package com.tyranor.next.core.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bangumi 刷新单飞、needsReauth 短路与写回守卫行为（与 HikarinagiTokenRefreshTest 同款
 * 生产路径回归，此前 Bangumi 侧无 HTTP 桩与并发用例）：
 * 1. 快速路径命中时不发起刷新网络请求；
 * 2. 凭据未配置返回 CONFIG_MISSING 分档（判别结构），零网络、不重试；
 * 3. 并发调用共享同一次刷新：至多一次 HTTP 往返，等待方重走快速路径采纳写回结果；
 * 4. 刷新被 400/401 明确拒绝后置位 needsReauth，后续调用不再重放；
 * 5. 传输失败不误置 needsReauth（未被短路，可重试）；
 * 6. 刷新成功但 refresh token 已被并发重登覆盖（写回守卫命中）时不回写旧凭据，
 *    也不把未落库的 access token 返回本次调用方；
 * 7. 刷新任务抛异常不楔死单飞闸门（闸门复位，后续刷新可正常进行）；
 * 8. markNeedsReauth 同值短路：同原因重复调用不再 prefs 写 + statusVersion bump；
 * 9. statusVersion 并发 bump 不丢计数（CAS 自增）。
 *
 * HTTP 往返经 [BangumiAuthService.testRefreshHttpRoundTrip] 测试桩替换
 * （与 [SecureAuthStore.testCipherDelegate] 同款缝隙），其余逻辑全部走生产路径。
 */
class BangumiTokenRefreshTest {

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
        BangumiAuthService.testClientCredentials = "bgm_test_client_id" to "bgm_test_client_secret"
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
        BangumiAuthService.testRefreshHttpRoundTrip = null
        BangumiAuthService.testClientCredentials = null
        SecureAuthStore.testCipherDelegate = null
        storage.clear()
        memoryTokens.clear()
    }

    /** 存入已过期令牌（默认走刷新路径）；saveTokens 成功路径会清空 last_error。 */
    private fun storeExpiredTokens() {
        assertTrue(
            BangumiAuthStore.saveTokens(context, "bgm_stale_access", "bgm_refresh_1", expiresAtMillis = 1_000L),
        )
    }

    private fun stubHttp(block: (clientId: String, refreshToken: String) -> Pair<Int, String>) {
        BangumiAuthService.testRefreshHttpRoundTrip = { clientId, refreshToken ->
            httpCalls.incrementAndGet()
            block(clientId, refreshToken)
        }
    }

    @Test
    fun validStoredTokenIsReturnedWithoutRefresh() {
        BangumiAuthStore.saveTokens(
            context,
            "bgm_fresh_access",
            "bgm_refresh",
            System.currentTimeMillis() + 3_600_000L,
        )
        val versionBefore = BangumiAuthStore.statusVersion.value
        stubHttp { _, _ -> 400 to "" } // 若被调用即失败

        assertEquals("bgm_fresh_access", BangumiAuthService.getValidAccessToken(context))
        assertEquals(0, httpCalls.get())
        assertEquals(versionBefore, BangumiAuthStore.statusVersion.value)
    }

    @Test
    fun missingCredentialsReturnConfigMissingWithoutNetwork() {
        // 空库：无 access/refresh token，也未配置 client/refresh 凭据组合中的 refresh token
        stubHttp { _, _ -> 200 to """{"access_token":"bgm_should_not_appear"}""" }

        val result = BangumiAuthService.getValidAccessTokenResult(context)
        assertEquals(BangumiAccessTokenResult.ConfigMissing, result)
        assertEquals(0, httpCalls.get())
        // 旧 String? 契约保持：凭据未配置与刷新失败同样返回 null
        assertNull(BangumiAuthService.getValidAccessToken(context))
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun concurrentCallersShareOneRefreshAndAdoptItsResult() {
        storeExpiredTokens()
        val taskStarted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        stubHttp { _, _ ->
            taskStarted.countDown()
            releaseTask.await()
            200 to """{"access_token":"bgm_new_access","expires_in":3600}"""
        }

        val runnerResult = AtomicReference<BangumiAccessTokenResult?>(null)
        val runner = thread {
            runnerResult.set(BangumiAuthService.getValidAccessTokenResult(context))
        }
        val waiterResults = CopyOnWriteArrayList<BangumiAccessTokenResult?>()
        var waiters: List<Thread> = emptyList()
        try {
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS))
            // 在途期间并发到达的调用方：等待并重走快速路径，不得发起第二个刷新
            waiters = (1..4).map {
                thread { waiterResults.add(BangumiAuthService.getValidAccessTokenResult(context)) }
            }
            awaitRefreshWaiters(waiters.size)
        } finally {
            releaseTask.countDown()
            runner.join(TimeUnit.SECONDS.toMillis(5))
            waiters.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
        }

        assertEquals(1, httpCalls.get())
        assertEquals(
            BangumiAccessTokenResult.Success("bgm_new_access"),
            runnerResult.get(),
        )
        assertEquals(
            listOf(
                BangumiAccessTokenResult.Success("bgm_new_access"),
                BangumiAccessTokenResult.Success("bgm_new_access"),
                BangumiAccessTokenResult.Success("bgm_new_access"),
                BangumiAccessTokenResult.Success("bgm_new_access"),
            ),
            waiterResults.toList(),
        )
        // 刷新结果写回且清除失效标记
        assertEquals("bgm_new_access", BangumiAuthStore.getAccessToken(context))
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun rejectedRefreshMarksNeedsReauthAndStopsReplaying() {
        storeExpiredTokens()
        stubHttp { _, _ -> 400 to """{"error":"invalid_grant"}""" }

        assertNull(BangumiAuthService.getValidAccessToken(context))
        val statusAfterFirst = BangumiAuthStore.getStatus(context)
        assertTrue(statusAfterFirst.authorized)
        assertTrue(statusAfterFirst.needsReauth)
        assertEquals(BangumiAuthError.EXPIRED.name, statusAfterFirst.lastError)

        // 第二次调用：needsReauth 短路，不再重放注定失败的刷新（零网络）
        val versionAfterFirst = BangumiAuthStore.statusVersion.value
        val result = BangumiAuthService.getValidAccessTokenResult(context)
        assertEquals(BangumiAccessTokenResult.RefreshFailed, result)
        assertEquals(1, httpCalls.get())
        assertEquals(versionAfterFirst, BangumiAuthStore.statusVersion.value)
    }

    @Test
    fun nonInvalidGrant400ResponsesPreserveCredentialsAndAllowRetry() {
        for (response in listOf(
            400 to "",
            400 to "{malformed json",
            400 to """{"error":"invalid_client"}""",
        )) {
            storage.clear()
            memoryTokens.clear()
            httpCalls.set(0)
            storeExpiredTokens()
            stubHttp { _, _ -> response }

            assertEquals(BangumiAccessTokenResult.RefreshFailed, BangumiAuthService.getValidAccessTokenResult(context))
            val status = BangumiAuthStore.getStatus(context)
            assertTrue(status.authorized)
            assertFalse("response $response must not mark needsReauth", status.needsReauth)
            assertEquals("", status.lastError)
            assertEquals("bgm_refresh_1", BangumiAuthStore.getRefreshToken(context))

            // 服务恢复后允许重试并成功刷新
            stubHttp { _, _ ->
                200 to """{"access_token":"bgm_recovered","refresh_token":"bgm_next_r","expires_in":3600}"""
            }
            assertEquals(
                BangumiAccessTokenResult.Success("bgm_recovered"),
                BangumiAuthService.getValidAccessTokenResult(context),
            )
            assertEquals(2, httpCalls.get())
        }
    }

    @Test
    fun transportFailureReturnsRefreshFailedWithoutMarkingNeedsReauth() {
        storeExpiredTokens()
        stubHttp { _, _ -> 503 to "service unavailable" }

        val result = BangumiAuthService.getValidAccessTokenResult(context)
        assertEquals(BangumiAccessTokenResult.RefreshFailed, result)
        val status = BangumiAuthStore.getStatus(context)
        // 传输失败不得误置失效标记（调用方按 needsReauth 位细分文案）
        assertTrue(status.authorized)
        assertFalse(status.needsReauth)

        // 未置位即不被短路：重试仍会发起刷新
        assertNull(BangumiAuthService.getValidAccessToken(context))
        assertEquals(2, httpCalls.get())
    }

    @Test
    fun writeBackGuardMissKeepsConcurrentReloginAndReturnsNull() {
        storeExpiredTokens()
        stubHttp { _, _ ->
            // 并发重登轮转 refresh token（模拟另一线程在刷新在途期间完成重新授权）
            assertTrue(
                BangumiAuthStore.saveTokens(
                    context,
                    "bgm_relogin_access",
                    "bgm_relogin_refresh",
                    System.currentTimeMillis() + 3_600_000L,
                ),
            )
            200 to """{"access_token":"bgm_stale_access","refresh_token":"bgm_stale_refresh","expires_in":3600}"""
        }

        val result = BangumiAuthService.getValidAccessTokenResult(context)
        // 守卫命中：不把未落库的 access token 返回本次调用方（按刷新失败处理）
        assertEquals(BangumiAccessTokenResult.RefreshFailed, result)
        // 陈旧凭据未回写：新登录态保持原样
        assertEquals("bgm_relogin_refresh", BangumiAuthStore.getRefreshToken(context))
        assertEquals("bgm_relogin_access", BangumiAuthStore.getAccessToken(context))
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
    }

    @Test
    fun taskExceptionDoesNotWedgeTheSingleFlightGate() {
        storeExpiredTokens()
        val taskStarted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        stubHttp { _, _ ->
            taskStarted.countDown()
            releaseTask.await()
            throw IllegalStateException("stub transport crash")
        }

        val runnerResult = AtomicReference<BangumiAccessTokenResult?>(null)
        val runner = thread {
            runnerResult.set(BangumiAuthService.getValidAccessTokenResult(context))
        }
        val waiterResults = CopyOnWriteArrayList<BangumiAccessTokenResult?>()
        var waiters: List<Thread> = emptyList()
        try {
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS))
            // 在途任务抛异常期间的并发调用方：不得被楔死，任务结束后同步放弃
            waiters = (1..2).map {
                thread { waiterResults.add(BangumiAuthService.getValidAccessTokenResult(context)) }
            }
            awaitRefreshWaiters(waiters.size)
        } finally {
            releaseTask.countDown()
            runner.join(TimeUnit.SECONDS.toMillis(5))
            waiters.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
        }

        assertEquals(1, httpCalls.get())
        assertEquals(BangumiAccessTokenResult.RefreshFailed, runnerResult.get())
        assertEquals(
            listOf(BangumiAccessTokenResult.RefreshFailed, BangumiAccessTokenResult.RefreshFailed),
            waiterResults.toList(),
        )

        // 闸门已复位：下一次刷新可正常执行并被采纳
        stubHttp { _, _ -> 200 to """{"access_token":"bgm_recovered_access","expires_in":3600}""" }
        assertEquals("bgm_recovered_access", BangumiAuthService.getValidAccessToken(context))
    }

    @Test
    fun markNeedsReauthSameValueShortCircuit() {
        val versionStart = BangumiAuthStore.statusVersion.value

        BangumiAuthStore.markNeedsReauth(context, BangumiAuthError.EXPIRED.name)
        val versionAfterFirst = BangumiAuthStore.statusVersion.value
        assertEquals(versionStart + 1, versionAfterFirst)

        // 同值重复调用（并发刷新同窗被拒等重放路径的同原因置位）：不再 prefs 写 + bump
        BangumiAuthStore.markNeedsReauth(context, BangumiAuthError.EXPIRED.name)
        BangumiAuthStore.markNeedsReauth(context, "  EXPIRED  ")
        assertEquals(versionAfterFirst, BangumiAuthStore.statusVersion.value)

        // 值变化仍需写入（另一令牌失效档：加密落盘失败哨兵，同属 needsReauth 语义）
        BangumiAuthStore.markNeedsReauth(context, SecureAuthStore.SECURE_STORAGE_ERROR)
        assertEquals(versionAfterFirst + 1, BangumiAuthStore.statusVersion.value)
        assertEquals(SecureAuthStore.SECURE_STORAGE_ERROR, BangumiAuthStore.getStatus(context).lastError)
    }

    @Test
    fun concurrentTokenSavesDoNotLoseStatusVersionIncrements() {
        val threads = 8
        val savesPerThread = 25
        storeExpiredTokens()
        val versionBefore = BangumiAuthStore.statusVersion.value

        val errors = CopyOnWriteArrayList<Throwable>()
        val workers = (1..threads).map { index ->
            thread {
                try {
                    repeat(savesPerThread) { attempt ->
                        assertTrue(
                            BangumiAuthStore.saveTokens(
                                context,
                                "bgm_bump_access_$index",
                                "bgm_bump_refresh_${index}_$attempt",
                                System.currentTimeMillis() + 3_600_000L,
                            ),
                        )
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
        assertEquals(emptyList<Throwable>(), errors.toList())

        // 每次 saveTokens 恰好一次版本递增：并发写不丢计数（CAS 自增语义）
        assertEquals(versionBefore + threads * savesPerThread, BangumiAuthStore.statusVersion.value)
    }

    @Test
    fun missingClientConfigWithStoredRefreshTokenReturnsConfigMissingWithoutNetwork() {
        storeExpiredTokens()
        BangumiAuthService.testClientCredentials = "" to ""

        val result = BangumiAuthService.getValidAccessTokenResult(context)

        assertEquals(BangumiAccessTokenResult.ConfigMissing, result)
        assertEquals(0, httpCalls.get())
    }

    @Test
    fun rejectedRefreshGuardSkipsMarkingNeedsReauthAfterConcurrentRelogin() {
        storeExpiredTokens()
        stubHttp { _, _ ->
            assertTrue(
                BangumiAuthStore.saveTokens(
                    context,
                    "bgm_relogin_access",
                    "bgm_relogin_refresh",
                    System.currentTimeMillis() + 3_600_000L,
                ),
            )
            401 to """{"error":"invalid_grant"}"""
        }

        val result = BangumiAuthService.getValidAccessTokenResult(context)

        assertEquals(BangumiAccessTokenResult.RefreshFailed, result)
        assertFalse(BangumiAuthStore.getStatus(context).needsReauth)
        assertEquals("bgm_relogin_access", BangumiAuthStore.getAccessToken(context))
    }

    @Test
    fun staleIdentityEpochDoesNotOverwriteNewerAccountIdentity() {
        val oldEpoch = BangumiAuthStore.saveTokensReturningEpoch(context, "acc1", "ref1", 0L)!!
        BangumiAuthStore.saveUserIdentityIfEpochMatches(context, 100L, "alice", oldEpoch)

        val newEpoch = BangumiAuthStore.saveTokensReturningEpoch(context, "acc2", "ref2", 0L)!!
        assertTrue(BangumiAuthStore.saveUserIdentityIfEpochMatches(context, 200L, "bob", newEpoch))
        assertFalse(BangumiAuthStore.saveUserIdentityIfEpochMatches(context, 100L, "alice", oldEpoch))

        assertEquals(200L, BangumiAuthStore.getUserId(context))
        assertEquals("bob", BangumiAuthStore.getUsername(context))
    }

    @Test
    fun refreshFormBodyIncludesRedirectUriContract() {
        val form = BangumiAuthService.buildRefreshFormBody("cid_1", "csec_1", "ref_1")
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("client_id=cid_1"))
        assertTrue(form.contains("client_secret=csec_1"))
        assertTrue(form.contains("refresh_token=ref_1"))
        assertTrue(form.contains("redirect_uri=tyranornext%3A%2F%2Foauth%2Fbangumi"))
    }

    @Test
    fun freshLoginClearsStaleIdentityWhileRefreshPreservesIt() {
        val firstEpoch = BangumiAuthStore.saveTokensReturningEpoch(
            context,
            "bgm_old_acc",
            "bgm_old_ref",
            System.currentTimeMillis() - 1_000L,
        )!!
        assertTrue(BangumiAuthStore.saveUserIdentityIfEpochMatches(context, 100L, "alice", firstEpoch))

        // Token 刷新不得清除既有身份
        stubHttp { _, _ -> 200 to """{"access_token":"bgm_refreshed_acc","expires_in":3600}""" }
        assertEquals("bgm_refreshed_acc", BangumiAuthService.getValidAccessToken(context))
        assertEquals(100L, BangumiAuthStore.getUserId(context))
        assertEquals("alice", BangumiAuthStore.getUsername(context))

        // 全新登录（OAuth 换码或个人令牌写入）必须在同一次写入原子清除旧账号身份
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_new_acc", "bgm_new_ref", 0L))
        assertEquals(0L, BangumiAuthStore.getUserId(context))
        assertEquals("", BangumiAuthStore.getUsername(context))
    }

    @Test
    fun staleOAuthExchangeGenerationIsRejectedAfterReloginOrClear() {
        val initialGen = BangumiAuthStore.readCredentialGeneration(context)
        // 用户中途改用个人令牌登录，持久化凭据代次递增
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_personal_acc", "", 0L))
        BangumiAuthStore.saveUserIdentity(context, 777L, "personal_user")

        // 先前启动的 OAuth 回调滞后抵达：必须被拒绝，不得覆写个人令牌
        val errAfterRelogin = BangumiAuthService.completeOAuthTokenExchange(
            context = context,
            expectedGeneration = initialGen,
            accessToken = "bgm_stale_oauth",
            refreshToken = "bgm_stale_ref",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            userIdHint = 999L,
        )
        assertEquals(BangumiAuthError.TOKEN_EXCHANGE_FAILED, errAfterRelogin)
        assertEquals("bgm_personal_acc", BangumiAuthStore.getAccessToken(context))
        assertEquals("personal_user", BangumiAuthStore.getUsername(context))

        // 清空/登出也会持久化递增代次，使先前的 OAuth 回调即使跨 clear 后仍被拒绝
        val preClearGen = BangumiAuthStore.readCredentialGeneration(context)
        BangumiAuthStore.clear(context)
        val genAfterClear = BangumiAuthStore.readCredentialGeneration(context)
        assertTrue(genAfterClear > preClearGen)

        val errAfterClear = BangumiAuthService.completeOAuthTokenExchange(
            context = context,
            expectedGeneration = preClearGen,
            accessToken = "bgm_resurrected_oauth",
            refreshToken = "bgm_resurrected_ref",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
        )
        assertEquals(BangumiAuthError.TOKEN_EXCHANGE_FAILED, errAfterClear)
        assertFalse(BangumiAuthStore.getStatus(context).authorized)

        // 当前代次发起的新 OAuth 换码正常落盘
        val errCurrent = BangumiAuthService.completeOAuthTokenExchange(
            context = context,
            expectedGeneration = genAfterClear,
            accessToken = "bgm_fresh_oauth",
            refreshToken = "bgm_fresh_ref",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
        )
        assertNull(errCurrent)
        assertEquals("bgm_fresh_oauth", BangumiAuthStore.getAccessToken(context))
    }

    @Test
    fun needsReauthShortCircuitsUnexpiredAndNeverExpiringPersonalTokens() {
        // 1. 未过期 OAuth 令牌 + needsReauth（如写路径 401 吊销或加密落盘失败）：快路径必须短路
        BangumiAuthStore.saveTokens(
            context,
            "bgm_unexpired_revoked",
            "bgm_refresh_keep",
            System.currentTimeMillis() + 3_600_000L,
        )
        BangumiAuthStore.markNeedsReauth(context, BangumiAuthError.EXPIRED.name)
        stubHttp { _, _ -> 200 to """{"access_token":"should_not_refresh"}""" }

        assertEquals(BangumiAccessTokenResult.RefreshFailed, BangumiAuthService.getValidAccessTokenResult(context))
        assertNull(BangumiAuthService.getValidAccessToken(context))
        assertEquals(0, httpCalls.get())

        // 2. expiresAt == 0L 的个人令牌 + needsReauth：同样在快路径短路，不再返回已失效旧令牌
        BangumiAuthStore.saveTokens(context, "bgm_personal_revoked", "", 0L)
        BangumiAuthStore.markNeedsReauth(context, BangumiAuthError.EXPIRED.name)

        assertEquals(BangumiAccessTokenResult.RefreshFailed, BangumiAuthService.getValidAccessTokenResult(context))
        assertNull(BangumiAuthService.getValidAccessToken(context))
        assertEquals(0, httpCalls.get())
    }

    @Test
    fun jsonNullRefreshTokenPreservesExistingRefreshToken() {
        storeExpiredTokens()
        stubHttp { _, _ ->
            200 to """{"access_token":"bgm_new_access","refresh_token":null,"expires_in":3600}"""
        }

        val result = BangumiAuthService.getValidAccessTokenResult(context)
        assertEquals(BangumiAccessTokenResult.Success("bgm_new_access"), result)
        assertEquals("bgm_refresh_1", BangumiAuthStore.getRefreshToken(context))
    }

    private fun awaitRefreshWaiters(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (BangumiAuthService.refreshWaiterCount() < expected && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue(
            "expected $expected waiters in queue, got ${BangumiAuthService.refreshWaiterCount()}",
            BangumiAuthService.refreshWaiterCount() >= expected,
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
