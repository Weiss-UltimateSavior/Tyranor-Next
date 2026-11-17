package com.tyranor.next.core.auth

import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * OAuth 换码、环回流程与身份懒加载回归：
 * 1. Bangumi / Hikarinagi `completeOAuthTokenExchange` 写回与过期代次拒绝；
 * 2. NextMoe `runLoopbackFlow` 真实本地环回 Socket 接收回调并完成换码与 `authEvent` 发布；
 * 3. 三端 `ensureIdentityLoaded` / `ensureUsernameLoaded` 回填与已存在用户名跳过。
 */
class OAuthExchangeAndIdentityTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule(installInMemoryCipher = true)

    private lateinit var context: InMemoryPreferencesContext

    @Before
    fun setUp() {
        context = InMemoryPreferencesContext()
        BangumiAuthService.testClientCredentials = "bgm_id" to "bgm_secret"
        HikarinagiAuthService.testClientId = "hika_id"
        NextMoeAuthService.testClientId = "nm_id"
    }

    @Test
    fun bangumiAndHikarinagiCompleteOAuthTokenExchangePersistAndGuardGeneration() {
        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(404, null) }
        val bgmGen = BangumiAuthStore.readCredentialGeneration(context)
        val bgmError = BangumiAuthService.completeOAuthTokenExchange(
            context = context,
            expectedGeneration = bgmGen,
            accessToken = "bgm_oauth_access",
            refreshToken = "bgm_oauth_refresh",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            userIdHint = 4242L,
        )
        assertNull(bgmError)
        assertEquals("bgm_oauth_access", BangumiAuthStore.getAccessToken(context))
        assertEquals(4242L, BangumiAuthStore.getUserId(context))
        assertEquals("4242", BangumiAuthStore.getUsername(context))

        // 过期 generation 被拒绝，不覆盖已保存令牌
        val bgmStaleError = BangumiAuthService.completeOAuthTokenExchange(
            context = context,
            expectedGeneration = bgmGen,
            accessToken = "bgm_stale",
            refreshToken = "bgm_stale_r",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
        )
        assertEquals(BangumiAuthError.TOKEN_EXCHANGE_FAILED, bgmStaleError)
        assertEquals("bgm_oauth_access", BangumiAuthStore.getAccessToken(context))

        val hikaGen = HikarinagiAuthStore.readCredentialGeneration(context)
        val hikaError = HikarinagiAuthService.completeOAuthTokenExchange(
            context = context,
            expectedGeneration = hikaGen,
            accessToken = "hika_oauth_access",
            refreshToken = "hika_oauth_refresh",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
        )
        assertNull(hikaError)
        assertEquals("hika_oauth_access", HikarinagiAuthStore.getAccessToken(context))
    }

    @Test
    fun nextMoeRunLoopbackFlowCompletesCodeExchangeViaLocalSocketAndEmitsSuccessEvent() {
        val expectedGen = NextMoeAuthStore.readCredentialGeneration(context)
        val flowId = NextMoeAuthService.registerFlowIdForTest()
        val serverSocket = ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val port = serverSocket.localPort
        val idToken = unsignedIdToken(JSONObject().put("nonce", "nonce_loopback").put("name", "NextUser"))

        NextMoeAuthService.testTokenExchangeHttpRoundTrip = { clientId, code, redirectUri, verifier ->
            assertEquals("nm_id", clientId)
            assertEquals("auth_code_123", code)
            assertTrue(redirectUri.endsWith(":$port/callback"))
            assertEquals("verifier_xyz", verifier)
            200 to JSONObject()
                .put("access_token", "nm_loopback_access")
                .put("refresh_token", "nm_loopback_refresh")
                .put("expires_in", 900)
                .put("id_token", idToken)
                .toString()
        }

        val flowThread = thread {
            NextMoeAuthService.runLoopbackFlow(
                appContext = context,
                flowId = flowId,
                serverSocket = serverSocket,
                clientId = "nm_id",
                redirectUri = "http://127.0.0.1:$port/callback",
                expectedState = "state_abc",
                expectedNonce = "nonce_loopback",
                codeVerifier = "verifier_xyz",
                expectedGeneration = expectedGen,
                pageText = NextMoeLoopbackPageText("Title", "OK", "Cancelled", "Error"),
            )
        }
        try {
            Socket("127.0.0.1", port).use { client ->
                val req = "GET /callback?code=auth_code_123&state=state_abc HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                client.getOutputStream().write(req.toByteArray(StandardCharsets.US_ASCII))
                client.getOutputStream().flush()
                val responseText = client.getInputStream().bufferedReader(StandardCharsets.UTF_8).readText()
                assertTrue(responseText.contains("200 OK"))
                assertTrue(responseText.contains("OK"))
            }
        } finally {
            runCatching { serverSocket.close() }
            flowThread.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("loopback flow thread must finish cleanly", flowThread.isAlive)
        }

        val event = NextMoeAuthService.authEvent.value
        assertNotNull(event)
        try {
            assertTrue(event!!.success)
            assertNull(event.error)
            assertEquals("nm_loopback_access", NextMoeAuthStore.getAccessToken(context))
            assertEquals("NextUser", NextMoeAuthStore.getStatus(context).username)
        } finally {
            if (event != null) NextMoeAuthService.clearAuthEvent(event.eventId)
        }
    }

    @Test
    fun ensureIdentityAndUsernameLoadedBackfillsBlankUsernamesAndSkipsWhenAlreadyPopulated() {
        val future = System.currentTimeMillis() + 3_600_000L
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_tok", "bgm_ref", future))
        assertTrue(HikarinagiAuthStore.saveTokens(context, "hika_tok", "hika_ref", future))
        assertTrue(NextMoeAuthStore.saveTokens(context, "nm_tok", "nm_ref", future))

        var httpCount = 0
        testHttpRoundTrip = { url, _, _, _ ->
            httpCount += 1
            when {
                url.contains("api.bgm.tv/v0/me") ->
                    HttpJsonResult(200, JSONObject().put("id", 777).put("username", "bgm_user"))
                url.contains("api.hikarinagi.org/v3/user/me") ->
                    HttpJsonResult(200, JSONObject().put("success", true).put("data", JSONObject().put("name", "hika_user")))
                url.contains("account.nextmoe.com/api/v1/oauth/userinfo") ->
                    HttpJsonResult(200, JSONObject().put("preferred_username", "nm_user"))
                else -> HttpJsonResult(404, null)
            }
        }

        BangumiAuthService.ensureIdentityLoaded(context)
        HikarinagiAuthService.ensureUsernameLoaded(context)
        NextMoeAuthService.ensureUsernameLoaded(context)

        assertEquals(3, httpCount)
        assertEquals("bgm_user", BangumiAuthStore.getUsername(context))
        assertEquals("hika_user", HikarinagiAuthStore.getStatus(context).username)
        assertEquals("nm_user", NextMoeAuthStore.getStatus(context).username)

        // 用户名均已存在后再次调用不得再发起网络请求
        BangumiAuthService.ensureIdentityLoaded(context)
        HikarinagiAuthService.ensureUsernameLoaded(context)
        NextMoeAuthService.ensureUsernameLoaded(context)
        assertEquals(3, httpCount)
    }

    @Test
    fun isBangumiIdentityResolvedTreatsBlankAndUidPlaceholderAsUnresolved() {
        // 空白 username、数字 uid 占位形态 = 身份未解析；真实用户名（含 userId=0 时与 uid
        // 同形的真实用户名字符串）= 已解析
        assertFalse(isBangumiIdentityResolved("", 0L))
        assertFalse(isBangumiIdentityResolved("", 42L))
        assertFalse(isBangumiIdentityResolved("42", 42L))
        assertTrue(isBangumiIdentityResolved("tester", 42L))
        assertTrue(isBangumiIdentityResolved("42", 0L))
        assertTrue(isBangumiIdentityResolved("tester", 0L))
    }

    @Test
    fun ensureIdentityLoadedForResultRetriesUidPlaceholderAfterFailedLookup() {
        // M2 整改：uid 占位账号不再永久跳过身份回查——/v0/me 失败返回 false（可重试），
        // 恢复后回查成功落库真实用户名，再调用走快速路径零网络
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_tok", "bgm_ref", System.currentTimeMillis() + 3_600_000L))
        // OAuth 换码同款 uid 占位落库（username = uid 串，BK-19 兜底形态）
        BangumiAuthStore.saveUserIdentity(context, 42L, "")

        var meCalls = 0
        fun stubMe(status: Int, body: JSONObject?) {
            testHttpRoundTrip = { url, _, _, _ ->
                if (url.contains("api.bgm.tv/v0/me")) {
                    meCalls += 1
                    if (body != null) HttpJsonResult(status, body) else HttpJsonResult(status, null)
                } else {
                    HttpJsonResult(404, null)
                }
            }
        }

        // 第一次回查：/v0/me 服务异常 → false，占位形态保留
        stubMe(500, null)
        assertFalse(BangumiAuthService.ensureIdentityLoadedForResult(context))
        assertEquals(1, meCalls)
        assertEquals("42", BangumiAuthStore.getUsername(context))

        // 第二次回查：/v0/me 恢复 → true，真实用户名落库
        stubMe(200, JSONObject().put("id", 42).put("username", "bgm_user"))
        assertTrue(BangumiAuthService.ensureIdentityLoadedForResult(context))
        assertEquals(2, meCalls)
        assertEquals("bgm_user", BangumiAuthStore.getUsername(context))

        // 已解析：快速路径返回 true，零网络
        stubMe(200, JSONObject().put("id", 42).put("username", "bgm_user"))
        assertTrue(BangumiAuthService.ensureIdentityLoadedForResult(context))
        assertEquals(2, meCalls)
    }

    private fun unsignedIdToken(claims: JSONObject): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray(StandardCharsets.UTF_8))
        val payload = encoder.encodeToString(claims.toString().toByteArray(StandardCharsets.UTF_8))
        return "$header.$payload.sig"
    }
}
