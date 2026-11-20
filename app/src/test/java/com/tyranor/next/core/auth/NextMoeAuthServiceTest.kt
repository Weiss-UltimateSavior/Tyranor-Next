package com.tyranor.next.core.auth

import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 未萌 NextMoe 授权与令牌刷新回归：
 * 1. PKCE S256、授权 URL 构造与环回回调 query 解析（成功 / access_denied / state 不匹配 / 缺 code）；
 * 2. 换码路径：id_token nonce 校验、username 提取、credentialGeneration 防串台；
 * 3. 刷新单飞、needsReauth 短路、invalid_grant 拒收置位、传输失败不锁死、refresh_token 轮换；
 * 4. 独立 permission_error 与 last_error 分位，以及显式 clear 重置状态。
 */
class NextMoeAuthServiceTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule(installInMemoryCipher = true)

    private val httpCalls = AtomicInteger(0)
    private lateinit var context: InMemoryPreferencesContext

    @Before
    fun setUp() {
        httpCalls.set(0)
        context = InMemoryPreferencesContext()
        NextMoeAuthService.testClientId = "nm_test_client_id"
    }

    @Test
    fun parseLoopbackCallbackQueryHandlesSuccessAndErrors() {
        val ok = NextMoeAuthService.parseLoopbackCallbackQuery(
            "/callback?code=auth_code_1&state=expected_state",
            "expected_state",
        )
        assertEquals(NextMoeLoopbackQueryResult.Success("auth_code_1"), ok)

        val denied = NextMoeAuthService.parseLoopbackCallbackQuery(
            "/callback?error=access_denied&error_description=User%20declined&state=expected_state",
            "expected_state",
        )
        assertEquals(
            NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.CANCELLED),
            denied,
        )

        val failed = NextMoeAuthService.parseLoopbackCallbackQuery(
            "/callback?error=invalid_scope&error_description=bad%20scope&state=expected_state",
            "expected_state",
        )
        assertEquals(
            NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.FAILED),
            failed,
        )

        // 未携带或 state 不匹配的 error 伪造请求必须先被 STATE_MISMATCH 拒绝
        val spoofedError = NextMoeAuthService.parseLoopbackCallbackQuery(
            "/callback?error=access_denied&error_description=spoofed",
            "expected_state",
        )
        assertEquals(NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.STATE_MISMATCH), spoofedError)

        val mismatch = NextMoeAuthService.parseLoopbackCallbackQuery(
            "/callback?code=auth_code_1&state=wrong_state",
            "expected_state",
        )
        assertEquals(NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.STATE_MISMATCH), mismatch)

        val emptyQuery = NextMoeAuthService.parseLoopbackCallbackQuery(
            "/callback",
            "expected_state",
        )
        assertEquals(NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.STATE_MISMATCH), emptyQuery)

        val missingCode = NextMoeAuthService.parseLoopbackCallbackQuery(
            "/callback?state=expected_state",
            "expected_state",
        )
        assertEquals(NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.INVALID_RESPONSE), missingCode)
    }

    @Test
    fun authorizeUrlAndCodeChallengeFollowPkceContract() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = NextMoeAuthService.computeCodeChallenge(verifier)
        // RFC 7636 Appendix B test vector
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)

        val url = NextMoeAuthService.buildAuthorizeUrl(
            clientId = "nm_client",
            redirectUri = "http://127.0.0.1:53682/callback",
            state = "st_123",
            nonce = "nc_456",
            codeChallenge = challenge,
        )
        assertTrue(url.startsWith("https://account.nextmoe.com/api/v1/oauth/authorize?"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A53682%2Fcallback"))
        assertTrue(url.contains("code_challenge=$challenge"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("scope=openid+profile+catalog%3Aread"))
    }

    @Test
    fun exchangeAuthorizationCodeValidatesNonceSavesTokensAndChecksGeneration() {
        val idToken = fakeJwt(JSONObject().put("nonce", "nonce_ok").put("name", "NextMoeUser"))
        NextMoeAuthService.testTokenExchangeHttpRoundTrip = { clientId, code, redirectUri, verifier ->
            assertEquals("nm_test_client_id", clientId)
            assertEquals("code_1", code)
            assertEquals("http://127.0.0.1:41234/callback", redirectUri)
            assertEquals("verifier_1", verifier)
            200 to JSONObject()
                .put("access_token", "nm_access_1")
                .put("refresh_token", "nm_refresh_1")
                .put("expires_in", 900)
                .put("id_token", idToken)
                .toString()
        }
        val gen = NextMoeAuthStore.readCredentialGeneration(context)
        val result = NextMoeAuthService.exchangeAuthorizationCode(
            context = context,
            clientId = "nm_test_client_id",
            code = "code_1",
            redirectUri = "http://127.0.0.1:41234/callback",
            codeVerifier = "verifier_1",
            expectedNonce = "nonce_ok",
            expectedGeneration = gen,
        )
        assertEquals(NextMoeCodeExchangeResult.Success, result)
        val status = NextMoeAuthStore.getStatus(context)
        assertTrue(status.authorized)
        assertFalse(status.needsReauth)
        assertEquals("NextMoeUser", status.username)
        assertEquals("nm_access_1", NextMoeAuthStore.getAccessToken(context))
        assertEquals("nm_refresh_1", NextMoeAuthStore.getRefreshToken(context))

        // 过时 generation 不允许覆盖新凭据
        val staleResult = NextMoeAuthService.exchangeAuthorizationCode(
            context = context,
            clientId = "nm_test_client_id",
            code = "code_1",
            redirectUri = "http://127.0.0.1:41234/callback",
            codeVerifier = "verifier_1",
            expectedNonce = "nonce_ok",
            expectedGeneration = gen,
        )
        assertEquals(NextMoeCodeExchangeResult.Failure(NextMoeAuthError.TOKEN_EXCHANGE_FAILED), staleResult)
        assertEquals("nm_access_1", NextMoeAuthStore.getAccessToken(context))
    }

    @Test
    fun exchangeAuthorizationCodeRejectsMismatchedNonce() {
        val badIdToken = fakeJwt(JSONObject().put("nonce", "wrong_nonce").put("name", "Mallory"))
        NextMoeAuthService.testTokenExchangeHttpRoundTrip = { _, _, _, _ ->
            200 to JSONObject()
                .put("access_token", "nm_access_bad")
                .put("refresh_token", "nm_refresh_bad")
                .put("expires_in", 900)
                .put("id_token", badIdToken)
                .toString()
        }
        val result = NextMoeAuthService.exchangeAuthorizationCode(
            context = context,
            clientId = "nm_test_client_id",
            code = "code_1",
            redirectUri = "http://127.0.0.1:41234/callback",
            codeVerifier = "verifier_1",
            expectedNonce = "expected_nonce",
            expectedGeneration = NextMoeAuthStore.readCredentialGeneration(context),
        )
        assertEquals(NextMoeCodeExchangeResult.Failure(NextMoeAuthError.ID_TOKEN_NONCE_FAILED), result)
        assertFalse(NextMoeAuthStore.getStatus(context).authorized)
    }

    @Test
    fun concurrentRefreshRotatesOnceAndShortCircuitsOnInvalidGrant() {
        NextMoeAuthStore.saveTokens(context, "stale_access", "refresh_1", expiresAtMillis = 1_000L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NextMoeAuthService.testRefreshHttpRoundTrip = { _, refreshToken ->
            httpCalls.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            assertEquals("refresh_1", refreshToken)
            200 to JSONObject()
                .put("access_token", "fresh_access")
                .put("refresh_token", "refresh_2")
                .put("expires_in", 900)
                .toString()
        }

        val runnerResult = AtomicReference<String?>("unset")
        val runner = thread {
            runnerResult.set(NextMoeAuthService.getValidAccessToken(context))
        }
        val results = ConcurrentHashMap<Int, String?>()
        var waiters: List<Thread> = emptyList()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            waiters = (0 until 3).map { idx ->
                thread {
                    results[idx] = NextMoeAuthService.getValidAccessToken(context)
                }
            }
            awaitRefreshWaiters(waiters.size)
        } finally {
            release.countDown()
            runner.join(5_000L)
            waiters.forEach { it.join(5_000L) }
        }

        assertEquals(1, httpCalls.get())
        assertEquals("fresh_access", runnerResult.get())
        assertEquals(List(3) { "fresh_access" }, (0 until 3).map { results[it] })
        assertEquals("refresh_2", NextMoeAuthStore.getRefreshToken(context))

        // 过期后模拟 400 invalid_grant：置位 needsReauth 并短路后续刷新
        NextMoeAuthStore.saveTokens(context, "stale_access_2", "refresh_3", expiresAtMillis = 1_000L)
        httpCalls.set(0)
        NextMoeAuthService.testRefreshHttpRoundTrip = { _, _ ->
            httpCalls.incrementAndGet()
            400 to JSONObject().put("error", "invalid_grant").toString()
        }
        assertEquals(NextMoeAccessTokenResult.RefreshFailed, NextMoeAuthService.getValidAccessTokenResult(context))
        assertTrue(NextMoeAuthStore.getStatus(context).needsReauth)
        assertEquals(NextMoeAccessTokenResult.RefreshFailed, NextMoeAuthService.getValidAccessTokenResult(context))
        assertEquals(1, httpCalls.get())

        NextMoeAuthStore.clear(context)
        assertFalse(NextMoeAuthStore.getStatus(context).authorized)
        assertEquals(NextMoeAccessTokenResult.ConfigMissing, NextMoeAuthService.getValidAccessTokenResult(context))
    }

    @Test
    fun needsReauthShortCircuitsEvenWhenAccessTokenUnexpiredAndNullRefreshDoesNotCorrupt() {
        NextMoeAuthStore.saveTokens(
            context,
            "unexpired_access",
            "refresh_keep",
            expiresAtMillis = System.currentTimeMillis() + 600_000L,
        )
        // 即使 access_token 尚未过期，一旦置位 needsReauth（如 401 吊销），快速路径必须短路
        NextMoeAuthStore.markNeedsReauth(context, NextMoeAuthError.EXPIRED.name)
        assertEquals(NextMoeAccessTokenResult.RefreshFailed, NextMoeAuthService.getValidAccessTokenResult(context))
        assertEquals(0, httpCalls.get())

        // 模拟刷新响应中 refresh_token 为 JSON null：不得将已存 refresh_token 覆写为字面量 "null"
        NextMoeAuthStore.saveTokens(context, "stale", "refresh_keep", expiresAtMillis = 1_000L)
        NextMoeAuthService.testRefreshHttpRoundTrip = { _, _ ->
            200 to JSONObject()
                .put("access_token", "new_access")
                .put("refresh_token", JSONObject.NULL)
                .put("expires_in", 900)
                .toString()
        }
        assertEquals("new_access", NextMoeAuthService.getValidAccessToken(context))
        assertEquals("refresh_keep", NextMoeAuthStore.getRefreshToken(context))
    }

    @Test
    fun supersededFlowSkipsTokenCommit() {
        NextMoeAuthService.testTokenExchangeHttpRoundTrip = { _, _, _, _ ->
            200 to JSONObject()
                .put("access_token", "superseded_access")
                .put("refresh_token", "superseded_refresh")
                .put("expires_in", 900)
                .toString()
        }
        val result = NextMoeAuthService.exchangeAuthorizationCode(
            context = context,
            clientId = "nm_test_client_id",
            code = "code_1",
            redirectUri = "http://127.0.0.1:41234/callback",
            codeVerifier = "verifier_1",
            expectedNonce = null,
            expectedGeneration = NextMoeAuthStore.readCredentialGeneration(context),
            shouldCommit = { false },
        )
        assertNull(result)
        assertFalse(NextMoeAuthStore.getStatus(context).authorized)
    }

    @Test
    fun parseUserinfoAndHtmlEscapingHandleEdgeCases() {
        assertEquals(
            "Alice",
            NextMoeAuthService.parseUserinfoName(JSONObject().put("name", "Alice").put("sub", "1001")),
        )
        assertEquals(
            "1001",
            NextMoeAuthService.parseUserinfoName(JSONObject().put("name", JSONObject.NULL).put("sub", "1001")),
        )
        val html = NextMoeAuthService.buildLoopbackHtmlPage("<Title>", "Hello & \"world\"")
        assertTrue(html.contains("&lt;Title&gt;"))
        assertTrue(html.contains("Hello &amp; &quot;world&quot;"))
    }

    @Test
    fun awaitLoopbackCallbackIgnoresStateMismatchUntilValidCallback() {
        val pageText = NextMoeLoopbackPageText(
            title = "NextMoe",
            successMessage = "OK",
            cancelledMessage = "Cancelled",
            errorMessage = "Error",
        )
        ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val resultRef = AtomicReference<NextMoeLoopbackQueryResult?>(null)
            val listener = thread {
                resultRef.set(
                    NextMoeAuthService.awaitLoopbackCallback(server, "expected_state", pageText),
                )
            }
            try {
                // 1) 无查询参数的裸 /callback 与未携带 state 的伪造 access_denied 请求 → 均应返回 400 并被忽略，继续监听
                val emptyQueryStatus = sendLoopbackGet(server.localPort, "/callback")
                assertTrue("expected 400 status line for bare /callback, got $emptyQueryStatus", emptyQueryStatus.startsWith("HTTP/1.1 400"))

                val firstStatus = sendLoopbackGet(server.localPort, "/callback?error=access_denied")
                assertTrue("expected 400 status line, got $firstStatus", firstStatus.startsWith("HTTP/1.1 400"))

                // 2) 合法回调随后到达 → 成功唤醒并结束监听
                val secondStatus = sendLoopbackGet(server.localPort, "/callback?code=valid_code&state=expected_state")
                assertTrue("expected 200 status line, got $secondStatus", secondStatus.startsWith("HTTP/1.1 200"))
                listener.join(5_000L)
                assertFalse(listener.isAlive)
                assertEquals(NextMoeLoopbackQueryResult.Success("valid_code"), resultRef.get())
            } finally {
                runCatching { server.close() }
                listener.join(1_000L)
            }
        }
    }

    @Test
    fun awaitLoopbackCallbackReturnsVerifiedResultEvenIfResponseWriteThrows() {
        val pageText = NextMoeLoopbackPageText(
            title = "NextMoe",
            successMessage = "OK",
            cancelledMessage = "Cancelled",
            errorMessage = "Error",
        )
        ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val resultRef = AtomicReference<NextMoeLoopbackQueryResult?>(null)
            val listener = thread {
                resultRef.set(
                    NextMoeAuthService.awaitLoopbackCallback(
                        serverSocket = server,
                        expectedState = "expected_state",
                        pageText = pageText,
                        responseWriter = { _, _, _, _, _ -> throw IOException("broken pipe") },
                    ),
                )
            }
            try {
                Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), server.localPort).use { client ->
                    val out = client.getOutputStream()
                    out.write(
                        "GET /callback?code=valid_code&state=expected_state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                            .toByteArray(StandardCharsets.UTF_8),
                    )
                    out.flush()
                    listener.join(5_000L)
                }
                assertFalse(listener.isAlive)
                assertEquals(NextMoeLoopbackQueryResult.Success("valid_code"), resultRef.get())
            } finally {
                runCatching { server.close() }
                listener.join(1_000L)
            }
        }
    }

    @Test
    fun awaitLoopbackCallbackIgnoresAbruptDisconnectAndAcceptsLaterValidCallback() {
        // ROB-1 对偶面（连接层异常分支、verifiedResult == null）：请求读取中途连接异常断开
        // （带 RST 关闭 → 服务端读抛 IOException）按「无关请求」忽略并继续监听，合法回调
        // 照常唤醒；已通过 state 校验的回调在 catch 路径显式保留（实现与 KDoc「写失败亦保留
        // 已验证回调」一致）
        val pageText = NextMoeLoopbackPageText(
            title = "NextMoe",
            successMessage = "OK",
            cancelledMessage = "Cancelled",
            errorMessage = "Error",
        )
        ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val resultRef = AtomicReference<NextMoeLoopbackQueryResult?>(null)
            val listener = thread {
                resultRef.set(
                    NextMoeAuthService.awaitLoopbackCallback(server, "expected_state", pageText),
                )
            }
            try {
                // 连接后不发请求即带 RST 断开：服务端读请求行抛 IOException → 忽略、继续等待
                Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), server.localPort).use { abrupt ->
                    abrupt.setSoLinger(true, 0)
                    abrupt.close()
                }
                val validStatus = sendLoopbackGet(server.localPort, "/callback?code=valid_code&state=expected_state")
                assertTrue("expected 200 status line, got $validStatus", validStatus.startsWith("HTTP/1.1 200"))
                listener.join(5_000L)
                assertFalse(listener.isAlive)
                assertEquals(NextMoeLoopbackQueryResult.Success("valid_code"), resultRef.get())
            } finally {
                runCatching { server.close() }
                listener.join(1_000L)
            }
        }
    }

    @Test
    fun awaitLoopbackCallbackEnforcesRoundDeadlineAcrossSlowTrickleHeader() {
        val pageText = NextMoeLoopbackPageText(
            title = "NextMoe",
            successMessage = "OK",
            cancelledMessage = "Cancelled",
            errorMessage = "Error",
        )
        ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val thrownRef = AtomicReference<Throwable?>(null)
            val listener = thread {
                try {
                    NextMoeAuthService.awaitLoopbackCallback(
                        serverSocket = server,
                        expectedState = "expected_state",
                        pageText = pageText,
                        timeoutMs = 150L,
                        clientSocketTimeoutMs = 5_000,
                    )
                } catch (t: Throwable) {
                    thrownRef.set(t)
                }
            }
            val startNanos = System.nanoTime()
            try {
                Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), server.localPort).use { client ->
                    val out = client.getOutputStream()
                    // 持续每 50ms 发送 1 字节（远低于单次 read 5000ms 超时），仍必须在整轮 150ms 截止时间处超时退出
                    repeat(15) {
                        if (!listener.isAlive) return@repeat
                        runCatching {
                            out.write('G'.code)
                            out.flush()
                        }
                        Thread.sleep(50)
                    }
                    listener.join(2_000L)
                }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                assertFalse(listener.isAlive)
                assertTrue("expected SocketTimeoutException, got ${thrownRef.get()}", thrownRef.get() is SocketTimeoutException)
                assertTrue("slow trickle exceeded round deadline budget unreasonably: ${elapsedMs}ms", elapsedMs < 2_000L)
            } finally {
                runCatching { server.close() }
                listener.join(1_000L)
            }
        }
    }

    @Test
    fun runLoopbackFlowSupersessionPreventsTokenCommitAtomically() {
        val pageText = NextMoeLoopbackPageText(
            title = "NextMoe",
            successMessage = "OK",
            cancelledMessage = "Cancelled",
            errorMessage = "Error",
        )
        val exchangeEntered = CountDownLatch(1)
        val releaseExchange = CountDownLatch(1)
        NextMoeAuthService.testTokenExchangeHttpRoundTrip = { _, _, _, _ ->
            exchangeEntered.countDown()
            assertTrue(releaseExchange.await(5, TimeUnit.SECONDS))
            200 to JSONObject()
                .put("access_token", "stale_flow_access")
                .put("refresh_token", "stale_flow_refresh")
                .put("id_token", fakeJwt(JSONObject().put("nonce", "n1")))
                .put("expires_in", 900)
                .toString()
        }
        val initialEvent = NextMoeAuthService.authEvent.value
        val flow1 = NextMoeAuthService.registerFlowIdForTest()
        val generation1 = NextMoeAuthStore.readCredentialGeneration(context)
        ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val runner = thread {
                NextMoeAuthService.runLoopbackFlow(
                    appContext = context,
                    flowId = flow1,
                    serverSocket = server,
                    clientId = "nm_test_client_id",
                    redirectUri = "http://127.0.0.1:${server.localPort}/callback",
                    expectedState = "s1",
                    expectedNonce = "n1",
                    codeVerifier = "v1",
                    expectedGeneration = generation1,
                    pageText = pageText,
                )
            }
            try {
                sendLoopbackGet(server.localPort, "/callback?code=c1&state=s1")
                assertTrue(exchangeEntered.await(5, TimeUnit.SECONDS))
                // 换码期间新流程注册（同一 loopbackLock 下递增 activeFlowId，此时尚未改写 credentialGeneration）
                NextMoeAuthService.registerFlowIdForTest()
                releaseExchange.countDown()
                runner.join(5_000L)
                assertFalse(runner.isAlive)
                // 旧流程在 commitLock 下重新校验 flowId 失败，不得落盘也不得覆盖事件
                assertFalse(NextMoeAuthStore.getStatus(context).authorized)
                assertEquals(initialEvent, NextMoeAuthService.authEvent.value)
            } finally {
                releaseExchange.countDown()
                runCatching { server.close() }
                runner.join(1_000L)
            }
        }
    }

    @Test
    fun validateIdTokenNonceRawRejectsMissingIdTokenWhenNonceExpected() {
        assertEquals(
            NextMoeAuthError.ID_TOKEN_INVALID_FORMAT,
            NextMoeAuthService.validateIdTokenNonceRaw(null, "expected_nonce"),
        )
        assertEquals(
            NextMoeAuthError.ID_TOKEN_INVALID_FORMAT,
            NextMoeAuthService.validateIdTokenNonceRaw("  ", "expected_nonce"),
        )
        assertNull(NextMoeAuthService.validateIdTokenNonceRaw(null, null))
        assertNull(NextMoeAuthService.validateIdTokenNonceRaw(null, "   "))
    }

    @Test
    fun runLoopbackFlowPropagatesCancellationExceptionWithoutEmittingFailed() {
        val pageText = NextMoeLoopbackPageText(
            title = "NextMoe",
            successMessage = "OK",
            cancelledMessage = "Cancelled",
            errorMessage = "Error",
        )
        val cancellation = CancellationException("cancelled in token exchange")
        NextMoeAuthService.testTokenExchangeHttpRoundTrip = { _, _, _, _ ->
            throw cancellation
        }
        val initialEvent = NextMoeAuthService.authEvent.value
        val flow1 = NextMoeAuthService.registerFlowIdForTest()
        NextMoeAuthStore.setAuthInProgress(context, 123_456L)
        val generation1 = NextMoeAuthStore.readCredentialGeneration(context)
        ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val sender = thread {
                runCatching { sendLoopbackGet(server.localPort, "/callback?code=c1&state=s1") }
            }
            var caught: Throwable? = null
            try {
                NextMoeAuthService.runLoopbackFlow(
                    appContext = context,
                    flowId = flow1,
                    serverSocket = server,
                    clientId = "nm_test_client_id",
                    redirectUri = "http://127.0.0.1:${server.localPort}/callback",
                    expectedState = "s1",
                    expectedNonce = "n1",
                    codeVerifier = "v1",
                    expectedGeneration = generation1,
                    pageText = pageText,
                )
            } catch (t: Throwable) {
                caught = t
            } finally {
                sender.join(5_000L)
            }
            assertSame(cancellation, caught)
            assertEquals(0L, NextMoeAuthStore.getStatus(context).authInProgressAtMillis)
            assertEquals(initialEvent, NextMoeAuthService.authEvent.value)
        }
    }

    @Test
    fun awaitLoopbackCallbackStopsAfter32IgnoredRequests() {
        val pageText = NextMoeLoopbackPageText("NextMoe", "OK", "Cancelled", "Error")
        ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val resultRef = AtomicReference<NextMoeLoopbackQueryResult?>(null)
            val listener = thread {
                resultRef.set(
                    NextMoeAuthService.awaitLoopbackCallback(
                        serverSocket = server,
                        expectedState = "expected_state",
                        pageText = pageText,
                        timeoutMs = 10_000L,
                    ),
                )
            }
            try {
                repeat(32) {
                    val status = sendLoopbackGet(server.localPort, "/favicon.ico")
                    assertTrue(status.contains("404"))
                }
            } finally {
                runCatching { server.close() }
                listener.join(5_000L)
            }
            assertFalse(listener.isAlive)
            assertEquals(
                NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.INVALID_RESPONSE),
                resultRef.get(),
            )
        }
    }

    @Test
    fun awaitLoopbackCallbackRejectsOver16KbHeaderWith431ThenAcceptsValidCallback() {
        val pageText = NextMoeLoopbackPageText("NextMoe", "OK", "Cancelled", "Error")
        ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val resultRef = AtomicReference<NextMoeLoopbackQueryResult?>(null)
            val listener = thread {
                resultRef.set(
                    NextMoeAuthService.awaitLoopbackCallback(
                        serverSocket = server,
                        expectedState = "expected_state",
                        pageText = pageText,
                        timeoutMs = 10_000L,
                    ),
                )
            }
            try {
                val oversizedStatus = Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), server.localPort).use { client ->
                    client.soTimeout = 5_000
                    val out = client.getOutputStream()
                    val padding = "X-Pad: " + "a".repeat(16 * 1024 + 128) + "\r\n"
                    out.write(("GET /callback?code=too_big HTTP/1.1\r\n" + padding + "\r\n").toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                    client.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine().orEmpty()
                }
                assertTrue("expected 431, got: $oversizedStatus", oversizedStatus.contains("431"))

                val okStatus = sendLoopbackGet(server.localPort, "/callback?code=ok_after_431&state=expected_state")
                assertTrue(okStatus.contains("200"))
            } finally {
                runCatching { server.close() }
                listener.join(5_000L)
            }
            assertFalse(listener.isAlive)
            assertEquals(NextMoeLoopbackQueryResult.Success("ok_after_431"), resultRef.get())
        }
    }

    @Test
    fun supersededLoopbackSocketCloseDoesNotClearNewerFlowInProgressOrEmitEvent() {
        val pageText = NextMoeLoopbackPageText("NextMoe", "OK", "Cancelled", "Error")
        val initialEvent = NextMoeAuthService.authEvent.value
        val flow1 = NextMoeAuthService.registerFlowIdForTest()
        val generation1 = NextMoeAuthStore.readCredentialGeneration(context)
        ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val flowThread = thread {
                NextMoeAuthService.runLoopbackFlow(
                    appContext = context,
                    flowId = flow1,
                    serverSocket = server,
                    clientId = "nm_test_client_id",
                    redirectUri = "http://127.0.0.1:${server.localPort}/callback",
                    expectedState = "state_1",
                    expectedNonce = "nonce_1",
                    codeVerifier = "verifier_1",
                    expectedGeneration = generation1,
                    pageText = pageText,
                )
            }
            try {
                // 第二次发起授权：抢占 activeFlowId 并设置新的授权进行中时间戳，同时关闭旧 socket
                val flow2 = NextMoeAuthService.registerFlowIdForTest()
                assertTrue(flow2 > flow1)
                NextMoeAuthStore.setAuthInProgress(context, 99_999L)
            } finally {
                runCatching { server.close() }
                flowThread.join(5_000L)
            }
            assertFalse(flowThread.isAlive)
            assertEquals(99_999L, NextMoeAuthStore.getStatus(context).authInProgressAtMillis)
            assertEquals(initialEvent, NextMoeAuthService.authEvent.value)
        }
    }

    @Test
    fun parseIdTokenIgnoresSubAndEnsureUsernameLoadedBackfillsFromUserinfo() {
        val idTokenWithOnlySub = fakeJwt(JSONObject().put("nonce", "n1").put("sub", "123456789"))
        assertNull(NextMoeAuthService.parseIdTokenUsernameRaw(idTokenWithOnlySub))

        val idTokenWithName = fakeJwt(JSONObject().put("nonce", "n1").put("sub", "123456789").put("name", "Alice"))
        assertEquals("Alice", NextMoeAuthService.parseIdTokenUsernameRaw(idTokenWithName))

        NextMoeAuthStore.saveTokens(
            context = context,
            accessToken = "nm_access_u",
            refreshToken = "nm_refresh_u",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
        )
        com.tyranor.next.core.net.testHttpRoundTrip = { url, method, _, headers ->
            assertEquals("GET", method)
            assertTrue(url.endsWith("/oauth/userinfo"))
            assertEquals("Bearer nm_access_u", headers["Authorization"])
            com.tyranor.next.core.net.HttpJsonResult(
                200,
                JSONObject().put("preferred_username", "UserFromInfo"),
            )
        }
        try {
            NextMoeAuthService.ensureUsernameLoaded(context)
        } finally {
            com.tyranor.next.core.net.testHttpRoundTrip = null
        }
        assertEquals("UserFromInfo", NextMoeAuthStore.getStatus(context).username)
    }

    @Test
    fun authEventFreshnessAndShortTtlConcurrentWaitersAndMaxExpiresIn() {
        val freshEvent = NextMoeAuthEvent(
            eventId = 1L,
            success = true,
            error = null,
            createdAtMillis = 10_000L,
        )
        assertTrue(freshEvent.isFresh(10_000L + NextMoeAuthEvent.AUTH_EVENT_TTL_MS))
        assertFalse(freshEvent.isFresh(10_000L + NextMoeAuthEvent.AUTH_EVENT_TTL_MS + 1L))

        NextMoeAuthStore.saveTokens(context, "stale_nm", "refresh_short", expiresAtMillis = 1_000L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NextMoeAuthService.testRefreshHttpRoundTrip = { _, _ ->
            httpCalls.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            200 to JSONObject()
                .put("access_token", "nm_short_ttl")
                .put("refresh_token", "refresh_short_2")
                .put("expires_in", 30)
                .toString()
        }

        val runnerResult = AtomicReference<NextMoeAccessTokenResult?>(null)
        val runner = thread { runnerResult.set(NextMoeAuthService.getValidAccessTokenResult(context)) }
        val waiterResults = ConcurrentHashMap<Int, NextMoeAccessTokenResult?>()
        var waiters: List<Thread> = emptyList()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            waiters = (0 until 3).map { idx ->
                thread { waiterResults[idx] = NextMoeAuthService.getValidAccessTokenResult(context) }
            }
            awaitRefreshWaiters(waiters.size)
        } finally {
            release.countDown()
            runner.join(5_000L)
            waiters.forEach { it.join(5_000L) }
        }

        assertEquals(1, httpCalls.get())
        assertEquals(NextMoeAccessTokenResult.Success("nm_short_ttl"), runnerResult.get())
        assertEquals(
            List(3) { NextMoeAccessTokenResult.Success("nm_short_ttl") },
            (0 until 3).map { waiterResults[it] },
        )

        // 超大 expires_in 不溢出为负时间戳
        NextMoeAuthStore.saveTokens(context, "stale_nm_2", "refresh_max", expiresAtMillis = 1_000L)
        NextMoeAuthService.testRefreshHttpRoundTrip = { _, _ ->
            httpCalls.incrementAndGet()
            200 to JSONObject()
                .put("access_token", "nm_clamped")
                .put("refresh_token", "refresh_max_2")
                .put("expires_in", Long.MAX_VALUE)
                .toString()
        }
        assertEquals(NextMoeAccessTokenResult.Success("nm_clamped"), NextMoeAuthService.getValidAccessTokenResult(context))
        assertEquals(NextMoeAccessTokenResult.Success("nm_clamped"), NextMoeAuthService.getValidAccessTokenResult(context))
        assertEquals(2, httpCalls.get())
    }

    private fun awaitRefreshWaiters(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (NextMoeAuthService.refreshWaiterCount() < expected && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue(
            "expected $expected waiters in queue, got ${NextMoeAuthService.refreshWaiterCount()}",
            NextMoeAuthService.refreshWaiterCount() >= expected,
        )
    }

    private fun sendLoopbackGet(port: Int, target: String): String =
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { client ->
            client.soTimeout = 5_000
            val out = client.getOutputStream()
            out.write("GET $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            client.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine().orEmpty()
        }

    private fun fakeJwt(payload: JSONObject): String {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray(StandardCharsets.UTF_8))
        val body = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toString().toByteArray(StandardCharsets.UTF_8))
        return "$header.$body.signature"
    }

    @Test
    fun waiterAdoptsNewLoginTokenWhenConcurrentReloginOccursDuringInFlightRefresh() {
        assertTrue(NextMoeAuthStore.saveTokens(context, "nm_stale_access", "nm_refresh_1", 1_000L))
        val taskStarted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        NextMoeAuthService.testRefreshHttpRoundTrip = { _, _ ->
            taskStarted.countDown()
            releaseTask.await()
            200 to """{"access_token":"nm_stale_new","refresh_token":"nm_refresh_stale","expires_in":900}"""
        }

        val runnerResult = AtomicReference<NextMoeAccessTokenResult?>(null)
        val waiterResult = AtomicReference<NextMoeAccessTokenResult?>(null)
        val runner = thread { runnerResult.set(NextMoeAuthService.getValidAccessTokenResult(context)) }
        var waiter: Thread? = null
        try {
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS))
            waiter = thread { waiterResult.set(NextMoeAuthService.getValidAccessTokenResult(context)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (NextMoeAuthService.refreshWaiterCount() < 1 && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue(NextMoeAuthService.refreshWaiterCount() >= 1)
            assertTrue(
                NextMoeAuthStore.saveTokens(
                    context,
                    "nm_relogin_access",
                    "nm_relogin_refresh",
                    System.currentTimeMillis() + 900_000L,
                ),
            )
        } finally {
            releaseTask.countDown()
            runner.join(TimeUnit.SECONDS.toMillis(5))
            waiter?.join(TimeUnit.SECONDS.toMillis(5))
        }

        assertEquals(NextMoeAccessTokenResult.RefreshFailed, runnerResult.get())
        assertEquals(NextMoeAccessTokenResult.Success("nm_relogin_access"), waiterResult.get())
    }
}
