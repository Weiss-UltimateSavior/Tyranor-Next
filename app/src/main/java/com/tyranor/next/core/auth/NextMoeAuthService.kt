package com.tyranor.next.core.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.browser.customtabs.CustomTabsIntent
import com.tyranor.next.BuildConfig
import com.tyranor.next.core.net.MAX_JSON_CHARS
import com.tyranor.next.core.net.httpJsonResult
import com.tyranor.next.core.net.optNonBlankString
import com.tyranor.next.core.net.readTextCapped
import com.tyranor.next.core.net.urlEncode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 刷新取令牌的类型化结论（与 [HikarinagiAccessTokenResult] / [BangumiAccessTokenResult] 同构）。
 */
sealed class NextMoeAccessTokenResult {
    data class Success(val accessToken: String) : NextMoeAccessTokenResult()
    data object ConfigMissing : NextMoeAccessTokenResult()
    data object RefreshFailed : NextMoeAccessTokenResult()
}

/**
 * 环回授权浏览器返回页面文案（由 UI 层从 `strings.xml` 解析后传入，core 不持有本地化文案）。
 */
data class NextMoeLoopbackPageText(
    val title: String,
    val successMessage: String,
    val cancelledMessage: String,
    val errorMessage: String,
)

/**
 * 环回 OAuth 异步结果事件：由后台环回线程发布，UI 层 `LaunchedEffect(eventId)` 消费并弹提示。
 */
data class NextMoeAuthEvent(
    val eventId: Long,
    val success: Boolean,
    val error: NextMoeAuthError? = null,
    val detail: String? = null,
)

/** 环回回调 query 解析结果（纯函数，JVM 可测）。 */
internal sealed interface NextMoeLoopbackQueryResult {
    data class Success(val code: String) : NextMoeLoopbackQueryResult
    data class Failure(val error: NextMoeAuthError, val detail: String? = null) : NextMoeLoopbackQueryResult
}

/**
 * 未萌 NextMoe OAuth 2.0 授权与令牌刷新服务（Public Client + 强制 PKCE S256 + `127.0.0.1` 环回回调）：
 * - 服务端禁止自定义 scheme 与 `localhost`，仅接收 `http://127.0.0.1[:port]/callback`（RFC 8252 §7.3）；
 * - 授权端点：`https://account.nextmoe.com/api/v1/oauth/authorize`；
 * - 换码与刷新：`https://account.nextmoe.com/api/v1/oauth/token`（裸 RFC 6749 JSON，refresh_token 每次轮换）；
 * - 严禁调用第一方 `/api/v1/auth/refresh`（服务端拒绝 client-bound OAuth session）。
 */
object NextMoeAuthService {
    private const val AUTHORIZE_URL = "https://account.nextmoe.com/api/v1/oauth/authorize"
    private const val TOKEN_URL = "https://account.nextmoe.com/api/v1/oauth/token"
    private const val USERINFO_URL = "https://account.nextmoe.com/api/v1/oauth/userinfo"
    private const val LOOPBACK_PATH = "/callback"
    private const val SCOPES = "openid profile catalog:read"
    private const val REFRESH_SKEW_MS = 60_000L
    private const val LOOPBACK_TIMEOUT_MS = 300_000L
    private const val CLIENT_SOCKET_TIMEOUT_MS = 10_000
    private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
    private const val MAX_IGNORED_LOOPBACK_REQUESTS = 32

    private val random = SecureRandom()
    private val refreshGate = SingleFlightRefresh()
    private val loopbackLock = Any()
    private var activeLoopbackSocket: ServerSocket? = null
    private var activeFlowId: Long = 0L

    private val eventLock = Any()
    private var nextEventId = 0L
    private val mutableAuthEvent = MutableStateFlow<NextMoeAuthEvent?>(null)
    val authEvent: StateFlow<NextMoeAuthEvent?> = mutableAuthEvent.asStateFlow()

    @VisibleForTesting
    internal fun refreshWaiterCount(): Int = refreshGate.waiterCount()

    @VisibleForTesting
    @Volatile
    internal var testRefreshHttpRoundTrip: ((clientId: String, refreshToken: String) -> Pair<Int, String>)? = null

    @VisibleForTesting
    @Volatile
    internal var testTokenExchangeHttpRoundTrip: (
        (clientId: String, code: String, redirectUri: String, codeVerifier: String) -> Pair<Int, String>
    )? = null

    @VisibleForTesting
    @Volatile
    internal var testClientId: String? = null

    fun clearAuthEvent(eventId: Long) {
        synchronized(eventLock) {
            if (mutableAuthEvent.value?.eventId == eventId) {
                mutableAuthEvent.value = null
            }
        }
    }

    private fun emitAuthEvent(success: Boolean, error: NextMoeAuthError? = null, detail: String? = null) {
        synchronized(eventLock) {
            nextEventId += 1L
            mutableAuthEvent.value = NextMoeAuthEvent(
                eventId = nextEventId,
                success = success,
                error = error,
                detail = detail,
            )
        }
    }

    /**
     * 绑定 `127.0.0.1:0` 环回端口并拉起系统浏览器发起 PKCE 授权；
     * 同步返回发起阶段的失败码（null 表示浏览器已拉起，后续结果经 [authEvent] 回传）。
     */
    fun startAuthorization(activity: Activity, pageText: NextMoeLoopbackPageText): NextMoeAuthError? {
        val clientId = clientId()
        if (clientId.isBlank()) return NextMoeAuthError.CLIENT_ID_MISSING
        val appContext = activity.applicationContext

        val codeVerifier = generateRandomUrlSafe(32)
        val codeChallenge = computeCodeChallenge(codeVerifier)
        val state = generateRandomUrlSafe(16)
        val nonce = generateRandomUrlSafe(16)

        val serverSocket = try {
            ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        } catch (_: Exception) {
            return NextMoeAuthError.LAUNCH_FAILED
        }
        val redirectUri = "http://127.0.0.1:${serverSocket.localPort}$LOOPBACK_PATH"
        val authorizeUrl = buildAuthorizeUrl(
            clientId = clientId,
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            codeChallenge = codeChallenge,
        )

        val flowId: Long
        val expectedGeneration: Long
        synchronized(loopbackLock) {
            activeLoopbackSocket?.let { previous -> runCatching { previous.close() } }
            activeFlowId += 1L
            flowId = activeFlowId
            activeLoopbackSocket = serverSocket
            // 与 activeFlowId 同一临界区读取代次并置位进行中时间戳，防止旧流程提交与新流程快照/状态相互交错
            expectedGeneration = NextMoeAuthStore.readCredentialGeneration(appContext)
            NextMoeAuthStore.setAuthInProgress(appContext, System.currentTimeMillis())
        }

        return try {
            launchBrowser(activity, Uri.parse(authorizeUrl))
            Thread {
                runLoopbackFlow(
                    appContext = appContext,
                    flowId = flowId,
                    serverSocket = serverSocket,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    expectedState = state,
                    expectedNonce = nonce,
                    codeVerifier = codeVerifier,
                    expectedGeneration = expectedGeneration,
                    pageText = pageText,
                )
            }.apply {
                isDaemon = true
                name = "nextmoe-oauth-loopback"
            }.start()
            null
        } catch (_: ActivityNotFoundException) {
            NextMoeAuthStore.setAuthInProgress(appContext, 0L)
            abortLoopbackFlow(flowId, serverSocket)
            NextMoeAuthError.NO_BROWSER
        } catch (_: Exception) {
            NextMoeAuthStore.setAuthInProgress(appContext, 0L)
            abortLoopbackFlow(flowId, serverSocket)
            NextMoeAuthError.LAUNCH_FAILED
        }
    }

    private fun launchBrowser(activity: Activity, uri: Uri) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(activity, uri)
        } catch (_: ActivityNotFoundException) {
            // 不预先调用 resolveActivity（Android 11+ 无 <queries> https 声明时会被包可见性过滤误判），
            // 直接交由 startActivity 抛出 ActivityNotFoundException。
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            activity.startActivity(viewIntent)
        }
    }

    private fun abortLoopbackFlow(flowId: Long, serverSocket: ServerSocket) {
        synchronized(loopbackLock) {
            if (activeFlowId == flowId) {
                activeFlowId += 1L
                if (activeLoopbackSocket === serverSocket) {
                    activeLoopbackSocket = null
                }
            }
        }
        runCatching { serverSocket.close() }
    }

    private fun isCurrentFlow(flowId: Long): Boolean = synchronized(loopbackLock) {
        activeFlowId == flowId
    }

    internal fun registerFlowIdForTest(): Long = synchronized(loopbackLock) {
        activeFlowId += 1L
        activeFlowId
    }

    internal fun runLoopbackFlow(
        appContext: Context,
        flowId: Long,
        serverSocket: ServerSocket,
        clientId: String,
        redirectUri: String,
        expectedState: String,
        expectedNonce: String,
        codeVerifier: String,
        expectedGeneration: Long,
        pageText: NextMoeLoopbackPageText,
    ) {
        try {
            val callback = awaitLoopbackCallback(serverSocket, expectedState, pageText) ?: return
            synchronized(loopbackLock) {
                if (activeFlowId != flowId) return
                NextMoeAuthStore.setAuthInProgress(appContext, 0L)
                if (callback is NextMoeLoopbackQueryResult.Failure) {
                    emitAuthEvent(success = false, error = callback.error, detail = callback.detail)
                    return
                }
            }
            val successCallback = callback as NextMoeLoopbackQueryResult.Success
            val (error, detail) = exchangeAuthorizationCode(
                context = appContext,
                clientId = clientId,
                code = successCallback.code,
                redirectUri = redirectUri,
                codeVerifier = codeVerifier,
                expectedNonce = expectedNonce,
                expectedGeneration = expectedGeneration,
                shouldCommit = { activeFlowId == flowId },
                commitLock = loopbackLock,
            ) ?: return
            synchronized(loopbackLock) {
                if (activeFlowId == flowId) {
                    emitAuthEvent(success = error == null, error = error, detail = detail)
                }
            }
        } catch (_: SocketTimeoutException) {
            synchronized(loopbackLock) {
                if (activeFlowId == flowId) {
                    NextMoeAuthStore.setAuthInProgress(appContext, 0L)
                    emitAuthEvent(success = false, error = NextMoeAuthError.CANCELLED)
                }
            }
        } catch (_: Exception) {
            synchronized(loopbackLock) {
                if (activeFlowId == flowId) {
                    NextMoeAuthStore.setAuthInProgress(appContext, 0L)
                    emitAuthEvent(success = false, error = NextMoeAuthError.FAILED)
                }
            }
        } finally {
            abortLoopbackFlow(flowId, serverSocket)
        }
    }

    internal fun awaitLoopbackCallback(
        serverSocket: ServerSocket,
        expectedState: String,
        pageText: NextMoeLoopbackPageText,
        timeoutMs: Long = LOOPBACK_TIMEOUT_MS,
        clientSocketTimeoutMs: Int = CLIENT_SOCKET_TIMEOUT_MS,
        responseWriter: (java.net.Socket, Int, String, String, String) -> Unit = ::writeHttpResponse,
    ): NextMoeLoopbackQueryResult? {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var ignoredCount = 0
        while (ignoredCount < MAX_IGNORED_LOOPBACK_REQUESTS) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (remainingMs <= 0L) throw SocketTimeoutException("loopback timeout")
            serverSocket.soTimeout = remainingMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val client = serverSocket.accept()
            // 单条客户端连接的超时或提前断开（如 Chrome 预连接或 /favicon.ico 断开）不得中断整轮 300s 授权等待；
            // 一旦请求已通过 state 校验，响应回写变为尽力操作，写失败亦保留已验证回调。
            var verifiedResult: NextMoeLoopbackQueryResult? = null
            val parsedResult = try {
                client.use { socket ->
                    when (val lineResult = readHttpRequestLine(socket, deadlineNanos, clientSocketTimeoutMs)) {
                        RequestLineResult.HeaderTooLarge -> {
                            runCatching {
                                responseWriter(
                                    socket,
                                    431,
                                    "Request Header Fields Too Large",
                                    "text/plain; charset=utf-8",
                                    "Request Header Fields Too Large",
                                )
                            }
                            ignoredCount += 1
                            null
                        }
                        RequestLineResult.Empty -> {
                            runCatching {
                                responseWriter(socket, 400, "Bad Request", "text/plain; charset=utf-8", "Bad Request")
                            }
                            ignoredCount += 1
                            null
                        }
                        is RequestLineResult.Ok -> {
                            val parts = lineResult.line.trim().split(Regex("\\s+"))
                            if (parts.size < 2) {
                                runCatching {
                                    responseWriter(socket, 400, "Bad Request", "text/plain; charset=utf-8", "Bad Request")
                                }
                                ignoredCount += 1
                                null
                            } else {
                                val method = parts[0].uppercase()
                                val target = parts[1]
                                val path = target.substringBefore('?')
                                if (path != LOOPBACK_PATH) {
                                    runCatching {
                                        responseWriter(socket, 404, "Not Found", "text/plain; charset=utf-8", "Not Found")
                                    }
                                    ignoredCount += 1
                                    null
                                } else if (method != "GET") {
                                    runCatching {
                                        responseWriter(
                                            socket,
                                            405,
                                            "Method Not Allowed",
                                            "text/plain; charset=utf-8",
                                            "Method Not Allowed",
                                        )
                                    }
                                    ignoredCount += 1
                                    null
                                } else {
                                    val parsed = parseLoopbackCallbackQuery(target, expectedState)
                                    val stateMismatched =
                                        (parsed as? NextMoeLoopbackQueryResult.Failure)?.error == NextMoeAuthError.STATE_MISMATCH
                                    if (!stateMismatched) {
                                        verifiedResult = parsed
                                    }
                                    val bodyMessage = when (parsed) {
                                        is NextMoeLoopbackQueryResult.Success -> pageText.successMessage
                                        is NextMoeLoopbackQueryResult.Failure ->
                                            if (parsed.error == NextMoeAuthError.CANCELLED) {
                                                pageText.cancelledMessage
                                            } else {
                                                pageText.errorMessage
                                            }
                                    }
                                    runCatching {
                                        responseWriter(
                                            socket,
                                            if (stateMismatched) 400 else 200,
                                            if (stateMismatched) "Bad Request" else "OK",
                                            "text/html; charset=utf-8",
                                            buildLoopbackHtmlPage(pageText.title, bodyMessage),
                                        )
                                    }
                                    // 本机其它进程发送的伪造/错 state 请求视为无关请求继续等待，不得提前终止合法授权监听
                                    if (stateMismatched) {
                                        ignoredCount += 1
                                        null
                                    } else {
                                        parsed
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                verifiedResult ?: run {
                    if (System.nanoTime() >= deadlineNanos) throw e
                    ignoredCount += 1
                    null
                }
            } catch (_: IOException) {
                verifiedResult ?: run {
                    ignoredCount += 1
                    null
                }
            }
            if (parsedResult != null) return parsedResult
        }
        return NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.INVALID_RESPONSE)
    }

    private sealed interface RequestLineResult {
        data class Ok(val line: String) : RequestLineResult
        data object Empty : RequestLineResult
        data object HeaderTooLarge : RequestLineResult
    }

    private fun readHttpRequestLine(
        socket: java.net.Socket,
        deadlineNanos: Long,
        clientSocketTimeoutMs: Int,
    ): RequestLineResult {
        val buffered = BufferedInputStream(socket.getInputStream())
        val buffer = ByteArray(MAX_HTTP_HEADER_BYTES)
        var total = 0
        var headerTerminated = false
        while (total < MAX_HTTP_HEADER_BYTES) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw SocketTimeoutException("loopback timeout")
            if (buffered.available() == 0) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                if (remainingMs <= 0L) throw SocketTimeoutException("loopback timeout")
                socket.soTimeout = remainingMs
                    .coerceAtMost(clientSocketTimeoutMs.toLong())
                    .coerceAtLeast(1L)
                    .toInt()
            }
            val b = buffered.read()
            if (b < 0) break
            buffer[total++] = b.toByte()
            if (total >= 4 &&
                buffer[total - 4] == '\r'.code.toByte() &&
                buffer[total - 3] == '\n'.code.toByte() &&
                buffer[total - 2] == '\r'.code.toByte() &&
                buffer[total - 1] == '\n'.code.toByte()
            ) {
                headerTerminated = true
                break
            }
            if (total >= 2 &&
                buffer[total - 2] == '\n'.code.toByte() &&
                buffer[total - 1] == '\n'.code.toByte()
            ) {
                headerTerminated = true
                break
            }
        }
        if (total == 0) return RequestLineResult.Empty
        if (!headerTerminated && total >= MAX_HTTP_HEADER_BYTES) return RequestLineResult.HeaderTooLarge
        val text = String(buffer, 0, total, StandardCharsets.UTF_8)
        val firstLine = text.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return RequestLineResult.Empty
        return RequestLineResult.Ok(firstLine)
    }

    private fun writeHttpResponse(
        socket: java.net.Socket,
        status: Int,
        reason: String,
        contentType: String,
        body: String,
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        val out = socket.getOutputStream()
        out.write(header)
        out.write(bodyBytes)
        out.flush()
    }

    /** 构造环回回调响应页面（纯函数，JVM 可测）。 */
    internal fun buildLoopbackHtmlPage(title: String, message: String): String {
        val safeTitle = escapeHtml(title)
        val safeMessage = escapeHtml(message)
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$safeTitle</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;margin:0;padding:32px 20px;display:flex;justify-content:center;align-items:center;min-height:80vh;background:#f7f8fa;color:#1f2328}
.card{max-width:420px;background:#fff;border-radius:14px;padding:24px;box-shadow:0 4px 20px rgba(0,0,0,.08);text-align:center}
h1{font-size:18px;margin:0 0 12px}
p{font-size:15px;line-height:1.5;margin:0;color:#4a5568}
</style>
</head>
<body>
<div class="card">
<h1>$safeTitle</h1>
<p>$safeMessage</p>
</div>
</body>
</html>"""
    }

    private fun escapeHtml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    /** 解析环回回调 query 并先校验 `state`（防未验证本地请求伪造 error 取消授权），JVM 可测。 */
    internal fun parseLoopbackCallbackQuery(rawTarget: String, expectedState: String): NextMoeLoopbackQueryResult {
        val query = rawTarget.substringAfter('?', "")
        if (query.isBlank()) return NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.STATE_MISMATCH)
        val params = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val rawValue = if (idx >= 0) pair.substring(idx + 1) else ""
            val decodedKey = runCatching { URLDecoder.decode(key, StandardCharsets.UTF_8.name()) }.getOrDefault(key)
            val decodedValue = runCatching { URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name()) }.getOrDefault(rawValue)
            params[decodedKey] = decodedValue
        }
        val state = params["state"].orEmpty()
        if (state.isBlank() ||
            !MessageDigest.isEqual(state.toByteArray(StandardCharsets.UTF_8), expectedState.toByteArray(StandardCharsets.UTF_8))
        ) {
            return NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.STATE_MISMATCH)
        }
        val error = params["error"]?.trim().orEmpty()
        val errorDesc = params["error_description"]?.trim()?.takeIf { it.isNotEmpty() }
        if (error.isNotEmpty()) {
            val mapped = if (error == "access_denied") NextMoeAuthError.CANCELLED else NextMoeAuthError.FAILED
            return NextMoeLoopbackQueryResult.Failure(mapped, errorDesc)
        }
        val code = params["code"]?.trim().orEmpty()
        if (code.isBlank()) {
            return NextMoeLoopbackQueryResult.Failure(NextMoeAuthError.INVALID_RESPONSE)
        }
        return NextMoeLoopbackQueryResult.Success(code)
    }

    internal fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        nonce: String,
        codeChallenge: String,
    ): String = buildString {
        append(AUTHORIZE_URL)
        append("?client_id=").append(clientId.urlEncode())
        append("&redirect_uri=").append(redirectUri.urlEncode())
        append("&response_type=code")
        append("&scope=").append(SCOPES.urlEncode())
        append("&state=").append(state.urlEncode())
        append("&nonce=").append(nonce.urlEncode())
        append("&code_challenge=").append(codeChallenge.urlEncode())
        append("&code_challenge_method=S256")
    }

    internal fun computeCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun generateRandomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    internal fun exchangeAuthorizationCode(
        context: Context,
        clientId: String,
        code: String,
        redirectUri: String,
        codeVerifier: String,
        expectedNonce: String?,
        expectedGeneration: Long?,
        shouldCommit: () -> Boolean = { true },
        commitLock: Any? = null,
    ): Pair<NextMoeAuthError?, String?>? {
        val roundTrip = testTokenExchangeHttpRoundTrip
        val (status, body) = if (roundTrip != null) {
            check(BuildConfig.DEBUG) { "test stub must be null in release" }
            try {
                roundTrip(clientId, code, redirectUri, codeVerifier)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return NextMoeAuthError.TOKEN_EXCHANGE_FAILED to null
            }
        } else {
            postTokenForm(
                authFormBody(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "client_id" to clientId,
                    "code_verifier" to codeVerifier,
                ),
            ) ?: return NextMoeAuthError.TOKEN_EXCHANGE_FAILED to null
        }

        if (!shouldCommit()) return null

        if (status !in 200..299) {
            val detail = runCatching {
                JSONObject(body).optNonBlankString("error_description")
            }.getOrNull()
            return NextMoeAuthError.TOKEN_EXCHANGE_FAILED to detail
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return NextMoeAuthError.TOKEN_EXCHANGE_FAILED to null

        val idToken = json.optNonBlankString("id_token")
        val nonceError = validateIdTokenNonceRaw(idToken, expectedNonce)
        if (nonceError != null) return nonceError to null

        val accessToken = json.optNonBlankString("access_token").orEmpty()
        val refreshToken = json.optNonBlankString("refresh_token").orEmpty()
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            return NextMoeAuthError.TOKEN_MISSING_FIELDS to null
        }
        val expiresIn = max(json.optLong("expires_in", 900L), 1L)
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        val usernameFromIdToken = parseIdTokenUsernameRaw(idToken)
        // 当前流程校验与凭据落盘必须在同一 commitLock（即 loopbackLock）下原子完成，防止被新流程注册穿插
        val executeCommit: () -> Pair<Boolean, NextMoeAuthError?> = {
            if (!shouldCommit()) {
                false to null
            } else {
                true to completeOAuthTokenExchange(
                    context = context,
                    expectedGeneration = expectedGeneration,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMillis = expiresAt,
                    usernameHint = usernameFromIdToken,
                )
            }
        }
        val (committed, error) = if (commitLock != null) {
            synchronized(commitLock) { executeCommit() }
        } else {
            executeCommit()
        }
        if (!committed) return null
        return error to null
    }

    internal fun completeOAuthTokenExchange(
        context: Context,
        expectedGeneration: Long?,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long,
        usernameHint: String? = null,
    ): NextMoeAuthError? {
        val savedEpoch = if (expectedGeneration != null) {
            NextMoeAuthStore.saveTokensIfGenerationMatches(
                context,
                expectedGeneration,
                accessToken,
                refreshToken,
                expiresAtMillis,
            )
        } else {
            NextMoeAuthStore.saveTokensReturningEpoch(
                context,
                accessToken,
                refreshToken,
                expiresAtMillis,
            )
        } ?: return NextMoeAuthError.TOKEN_EXCHANGE_FAILED

        val hint = usernameHint?.trim()?.takeIf { it.isNotBlank() }
        if (hint != null) {
            NextMoeAuthStore.saveUsernameIfEpochMatches(context, hint, savedEpoch)
        } else {
            Thread {
                runCatching { fetchAndSaveUsername(context, accessToken, savedEpoch) }
            }.apply {
                isDaemon = true
                name = "nextmoe-username-load"
            }.start()
        }
        return null
    }

    fun getValidAccessToken(context: Context): String? =
        (getValidAccessTokenResult(context) as? NextMoeAccessTokenResult.Success)?.accessToken

    fun getValidAccessTokenResult(context: Context): NextMoeAccessTokenResult {
        validStoredAccessToken(context)?.let { return NextMoeAccessTokenResult.Success(it) }
        return refreshAccessToken(context)
    }

    /** 快速路径：仅当存在未过期访问令牌且未置位 needsReauth 时返回；防止 401/加密失败后继续发送注定失效的令牌。 */
    private fun validStoredAccessToken(context: Context): String? {
        val snapshot = NextMoeAuthStore.readAccessSnapshot(context)
        if (snapshot.needsReauth) return null
        return snapshot.accessToken.takeIf {
            it.isNotBlank() && System.currentTimeMillis() + REFRESH_SKEW_MS < snapshot.expiresAtMillis
        }
    }

    private fun refreshAccessToken(context: Context): NextMoeAccessTokenResult {
        val clientId = clientId()
        val (initialRefreshToken, _) = NextMoeAuthStore.readRefreshSnapshot(context)
        if (clientId.isBlank() || initialRefreshToken.isBlank()) {
            return NextMoeAccessTokenResult.ConfigMissing
        }
        if (NextMoeAuthStore.getStatus(context).needsReauth) {
            return NextMoeAccessTokenResult.RefreshFailed
        }
        val token = refreshGate.run(
            recheck = { validStoredAccessToken(context) },
            task = {
                // 进闸后再原子读取最新 refresh snapshot（防止等待期间已有新登录或轮换发生）
                if (NextMoeAuthStore.getStatus(context).needsReauth) {
                    null
                } else {
                    val (latestRefresh, latestEpoch) = NextMoeAuthStore.readRefreshSnapshot(context)
                    if (latestRefresh.isBlank()) {
                        null
                    } else {
                        performTokenRefresh(context, clientId, latestRefresh, latestEpoch)
                    }
                }
            },
        )
        return if (token != null) {
            NextMoeAccessTokenResult.Success(token)
        } else {
            NextMoeAccessTokenResult.RefreshFailed
        }
    }

    private fun performTokenRefresh(
        context: Context,
        clientId: String,
        refreshToken: String,
        expectedEpoch: Long,
    ): String? {
        testRefreshHttpRoundTrip?.let { delegate ->
            check(BuildConfig.DEBUG) { "test stub must be null in release" }
            return try {
                val (status, body) = delegate(clientId, refreshToken)
                completeTokenRefresh(context, expectedEpoch, refreshToken, status, body)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val (status, body) = postTokenForm(
            authFormBody(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
            ),
        ) ?: return null
        return try {
            completeTokenRefresh(context, expectedEpoch, refreshToken, status, body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun postTokenForm(formBody: String): Pair<Int, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                useCaches = false
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "TyranorNext/1.0")
            }
            conn.outputStream.use { it.write(formBody.toByteArray(StandardCharsets.UTF_8)) }
            val status = conn.responseCode
            val body = if (status in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readTextCapped(MAX_JSON_CHARS) }
            } else {
                conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readTextCapped(MAX_JSON_CHARS) }.orEmpty()
            }
            status to body
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun completeTokenRefresh(
        context: Context,
        expectedEpoch: Long,
        refreshToken: String,
        status: Int,
        body: String,
    ): String? {
        if (status !in 200..299) {
            if (SecureAuthStore.isOAuthInvalidGrantRejection(status, body)) {
                NextMoeAuthStore.markNeedsReauthIfMatching(
                    context,
                    expectedEpoch,
                    refreshToken,
                    NextMoeAuthError.EXPIRED.name,
                )
            }
            return null
        }

        val json = JSONObject(body)
        val access = json.optNonBlankString("access_token").orEmpty()
        val nextRefresh = json.optNonBlankString("refresh_token") ?: refreshToken
        if (access.isBlank()) return null
        val expiresIn = max(json.optLong("expires_in", 900L), 1L)
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        if (!NextMoeAuthStore.saveTokensIfMatching(context, expectedEpoch, refreshToken, access, nextRefresh, expiresAt)) {
            return null
        }
        return access
    }

    /** 懒解析并缓存当前授权账号用户名（需在 IO 线程调用；失败时静默跳过）。 */
    fun ensureUsernameLoaded(context: Context) {
        val status = NextMoeAuthStore.getStatus(context)
        if (!status.authorized || status.needsReauth || status.username.isNotBlank()) return
        val token = getValidAccessToken(context) ?: return
        val epoch = NextMoeAuthStore.currentEpochIfAccessTokenMatches(context, token) ?: return
        fetchAndSaveUsername(context, token, epoch)
    }

    private fun fetchAndSaveUsername(context: Context, token: String, expectedEpoch: Long) {
        val result = httpJsonResult(USERINFO_URL, headers = mapOf("Authorization" to "Bearer $token"))
        if (result.status !in 200..299) return
        val resolved = parseUserinfoName(result.json) ?: return
        NextMoeAuthStore.saveUsernameIfEpochMatches(context, resolved, expectedEpoch)
    }

    internal fun parseUserinfoName(json: JSONObject?): String? {
        if (json == null) return null
        val candidates = listOf("name", "preferred_username", "nickname", "username", "sub")
        for (key in candidates) {
            val value = json.optNonBlankString(key)
            if (value != null) return value
        }
        return null
    }

    internal fun parseIdTokenUsernameRaw(rawIdToken: String?): String? {
        val idToken = rawIdToken?.trim().orEmpty()
        if (idToken.isBlank()) return null
        val parts = idToken.split(".")
        if (parts.size != 3) return null
        return runCatching {
            val payload = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            parseUserinfoName(JSONObject(payload))
        }.getOrNull()
    }

    /** 纯校验（JVM 可测）：校验 OIDC ID Token 的 nonce 与预期一致；缺失任一侧则跳过。 */
    internal fun validateIdTokenNonceRaw(rawIdToken: String?, expectedNonce: String?): NextMoeAuthError? {
        val idToken = rawIdToken?.trim().orEmpty()
        val nonce = expectedNonce?.trim().orEmpty()
        if (idToken.isBlank() || nonce.isBlank()) return null
        val parts = idToken.split(".")
        if (parts.size != 3) return NextMoeAuthError.ID_TOKEN_INVALID_FORMAT
        return try {
            val payload = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            val actual = JSONObject(payload).optNonBlankString("nonce").orEmpty()
            if (MessageDigest.isEqual(actual.toByteArray(StandardCharsets.UTF_8), nonce.toByteArray(StandardCharsets.UTF_8))) {
                null
            } else {
                NextMoeAuthError.ID_TOKEN_NONCE_FAILED
            }
        } catch (_: Exception) {
            NextMoeAuthError.ID_TOKEN_PARSE_FAILED
        }
    }

    private fun clientId(): String {
        val override = testClientId
        if (override != null) check(BuildConfig.DEBUG) { "test stub must be null in release" }
        return (override ?: BuildConfig.NEXTMOE_CLIENT_ID).trim()
    }
}
