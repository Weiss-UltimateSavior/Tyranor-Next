package com.tyranor.next.core.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.tyranor.next.BuildConfig
import com.tyranor.next.core.net.MAX_JSON_CHARS
import com.tyranor.next.core.net.httpJsonResult
import com.tyranor.next.core.net.optNonBlankString
import com.tyranor.next.core.net.readTextCapped
import kotlinx.coroutines.CancellationException
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.max

/**
 * 刷新取令牌的类型化结论（与 [BangumiAccessTokenResult] 同构）：此前
 * [HikarinagiAuthService.getValidAccessToken] 把「凭据未配置」「needsReauth 已置位」
 * 「刷新传输失败」三态坍缩为 null，消费方只能按 needsReauth 位二次猜测——凭据未配置
 * （client id 或 refresh token 缺失）不是网络问题，重试刷新无意义，却被消费方误报成网络
 * 失败。本判别结构区分三态供调用方按档映射文案；未并入 [HikarinagiAuthError] 持久化
 * 错误码体系（CONFIG_MISSING 仅在结果中携带，不落 last_error）。
 */
sealed class HikarinagiAccessTokenResult {
    /** 快速路径命中或刷新成功写回的可用访问令牌。 */
    data class Success(val accessToken: String) : HikarinagiAccessTokenResult()

    /** 凭据未配置（client id 或 refresh token 缺失）：重试刷新无意义，调用方应引导授权。 */
    data object ConfigMissing : HikarinagiAccessTokenResult()

    /** 刷新未取回令牌（needsReauth 已置位、刷新被服务端拒绝或传输失败）；调用方按 needsReauth 位细分文案。 */
    data object RefreshFailed : HikarinagiAccessTokenResult()
}

object HikarinagiAuthService {
    const val EXTRA_AUTH_CANCELED = "extra_auth_canceled"
    private const val EXTRA_EXPECTED_GENERATION = "extra_expected_generation"

    private const val AUTHORIZE_URL = "https://id.hikarinagi.org/oidc/auth"
    private const val TOKEN_URL = "https://id.hikarinagi.org/oidc/token"
    private const val USER_ME_URL = "https://api.hikarinagi.org/v3/user/me"
    private const val REDIRECT_URI = "tyranornext://oauth/hikarinagi"
    private const val SCOPES = "openid catalog:read catalog:full user:read status:write offline_access"
    private const val REFRESH_SKEW_MS = 60_000L

    // 仅以 applicationContext 构造并按需 replaceAuthorizationService(null) 回收，不持有 Activity 引用
    @SuppressLint("StaticFieldLeak")
    private var authorizationService: AuthorizationService? = null

    /** 刷新去重协调器（单飞）：见 [SingleFlightRefresh]，网络 IO 始终在协调器临界区之外。 */
    private val refreshGate = SingleFlightRefresh()

    @VisibleForTesting
    internal fun refreshWaiterCount(): Int = refreshGate.waiterCount()

    /**
     * JVM 单元测试桩（与 [SecureAuthStore.testCipherDelegate] 同款缝隙，供并发与分档 JVM 回归）：
     * 仅替换刷新请求的 HTTP 往返（入参 client/refresh token，返回 (HTTP 状态码, 响应体)），
     * 单飞协调、快速路径 recheck、400/401 拒绝置位与写回守卫仍在生产路径执行。生产代码恒为 null；
     * 测试结束必须复位为 null（@After），否则残留桩会污染跨测试的后续刷新。
     */
    @VisibleForTesting
    @Volatile
    internal var testRefreshHttpRoundTrip: ((clientId: String, refreshToken: String) -> Pair<Int, String>)? = null

    /** JVM 单元测试 clientId 覆盖（使单测不依赖本地或 CI 的 BuildConfig OAuth 配置）。生产代码恒为 null。 */
    @VisibleForTesting
    @Volatile
    internal var testClientId: String? = null

    /** 预热：提前构造 [AuthorizationService]（含自定义标签页连接管理），消除点击到浏览器打开的延迟。 */
    fun prewarm(context: Context) {
        synchronized(this) {
            if (authorizationService == null) {
                authorizationService = AuthorizationService(context.applicationContext)
            }
        }
    }

    /**
     * 发起浏览器 OAuth 授权；失败返回类型化错误码（文案由 UI 经 `ui/auth/HikarinagiAuthMessages`
     * 映射，core 不携带本地化文案）。
     */
    fun startAuthorization(activity: Activity, callbackIntent: Intent): HikarinagiAuthError? {
        val clientId = clientId()
        if (clientId.isBlank()) return HikarinagiAuthError.CLIENT_ID_MISSING
        val appContext = activity.applicationContext

        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(AUTHORIZE_URL),
            Uri.parse(TOKEN_URL),
        )
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        )
            .setScope(SCOPES)
            .setPrompt("consent")
            .build()

        // AppAuth supplies the authorization result through a fill-in Intent, so
        // these PendingIntents must remain mutable. Restrict both copies to this
        // application before handing them to AppAuth; a package-scoped Intent is
        // explicit enough to avoid mutable implicit PendingIntent hijacking.
        val expectedGeneration = HikarinagiAuthStore.readCredentialGeneration(appContext)
        val completeCallback = Intent(callbackIntent)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_EXPECTED_GENERATION, expectedGeneration)
        val cancelCallback = Intent(callbackIntent)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_AUTH_CANCELED, true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        // requestCode 固定 0/1（与 BangumiAuthService 同形，BK-07 注释固化）：同一 provider
        // 同时只允许一个在途授权流程，FLAG_UPDATE_CURRENT 覆盖旧 PendingIntent 为预期行为
        // （发起新授权即放弃旧流程）；跨 provider 的 0/1 复用作用域为 Activity+requestCode，
        // 互不冲突。
        val completeIntent = PendingIntent.getActivity(
            activity,
            0,
            completeCallback,
            flags,
        )
        val cancelIntent = PendingIntent.getActivity(
            activity,
            1,
            cancelCallback,
            flags,
        )
        val service = synchronized(this) {
            authorizationService ?: AuthorizationService(appContext).also { authorizationService = it }
        }
        return try {
            service.performAuthorizationRequest(request, completeIntent, cancelIntent)
            null
        } catch (_: ActivityNotFoundException) {
            replaceAuthorizationService(null)
            HikarinagiAuthError.NO_BROWSER
        } catch (_: Exception) {
            replaceAuthorizationService(null)
            HikarinagiAuthError.LAUNCH_FAILED
        }
    }

    /**
     * 处理回调 Activity 收到的授权结果：取消/失败/成功均携带类型化错误码（成功为 null）与可选
     * AppAuth 异常原始描述 [detail]，文案由调用方经 `ui/auth/HikarinagiAuthMessages` 映射。
     */
    fun handleAuthorizationResponse(
        activity: Activity,
        intent: Intent,
        onResult: (success: Boolean, error: HikarinagiAuthError?, detail: String?) -> Unit,
    ) {
        replaceAuthorizationService(null)
        val appContext = activity.applicationContext
        if (intent.getBooleanExtra(EXTRA_AUTH_CANCELED, false)) {
            onResult(false, HikarinagiAuthError.CANCELLED, null)
            return
        }
        val exception = AuthorizationException.fromIntent(intent)
        if (exception != null) {
            onResult(false, HikarinagiAuthError.FAILED, exception.errorDescription ?: exception.message)
            return
        }
        val response = AuthorizationResponse.fromIntent(intent)
        if (response == null) {
            onResult(false, HikarinagiAuthError.INVALID_RESPONSE, null)
            return
        }
        // AuthorizationResponse.request 可空（前置已拦 response==null，request==null 当前不可达，
        // 防御补全 BK-26）：null 时与 response.state 不等 → STATE_MISMATCH，防御语义合理
        if (response.state != response.request?.state) {
            onResult(false, HikarinagiAuthError.STATE_MISMATCH, null)
            return
        }

        val expectedGeneration = if (intent.hasExtra(EXTRA_EXPECTED_GENERATION)) {
            intent.getLongExtra(EXTRA_EXPECTED_GENERATION, -1L)
        } else {
            null
        }
        val service = AuthorizationService(appContext)
        try {
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenException ->
                service.dispose()
                val token = tokenResponse
                if (tokenException != null || token == null) {
                    onResult(
                        false,
                        HikarinagiAuthError.TOKEN_EXCHANGE_FAILED,
                        tokenException?.errorDescription ?: tokenException?.message,
                    )
                    return@performTokenRequest
                }
                // request 可空防御（BK-26）：null 经 validateIdTokenNonceRaw 的 blank 短路等价跳过
                // nonce 校验，与「idToken/nonce 缺失即跳过」既有语义一致
                val nonceError = validateIdTokenNonceRaw(token.idToken, response.request?.nonce)
                if (nonceError != null) {
                    onResult(false, nonceError, null)
                    return@performTokenRequest
                }
                val accessToken = token.accessToken.orEmpty()
                val refreshToken = token.refreshToken.orEmpty()
                if (accessToken.isBlank() || refreshToken.isBlank()) {
                    onResult(false, HikarinagiAuthError.TOKEN_MISSING_FIELDS, null)
                    return@performTokenRequest
                }
                val expiresAt = token.accessTokenExpirationTime ?: (System.currentTimeMillis() + 3_600_000L)
                val error = completeOAuthTokenExchange(
                    context = appContext,
                    expectedGeneration = expectedGeneration,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMillis = expiresAt,
                )
                onResult(error == null, error, null)
            }
        } catch (_: Exception) {
            service.dispose()
            onResult(false, HikarinagiAuthError.TOKEN_EXCHANGE_FAILED, null)
        }
    }

    /**
     * 落盘 OAuth 换码结果（与 PendingIntent 回调解耦，JVM 可测）：仅当持久化凭据代次仍匹配
     * 发起授权时快照 [expectedGeneration] 才提交，防止滞留浏览器回调覆盖随后完成的登出或重登录。
     */
    internal fun completeOAuthTokenExchange(
        context: Context,
        expectedGeneration: Long?,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long,
    ): HikarinagiAuthError? {
        // 加密落盘失败或凭据代次漂移（Keystore 不可用或中途已换号/登出）均不得报告「授权成功」
        val savedEpoch = if (expectedGeneration != null) {
            HikarinagiAuthStore.saveTokensIfGenerationMatches(
                context,
                expectedGeneration,
                accessToken,
                refreshToken,
                expiresAtMillis,
            )
        } else {
            HikarinagiAuthStore.saveTokensReturningEpoch(
                context,
                accessToken,
                refreshToken,
                expiresAtMillis,
            )
        } ?: return HikarinagiAuthError.TOKEN_EXCHANGE_FAILED
        Thread {
            runCatching { fetchAndSaveUsername(context, accessToken, savedEpoch) }
        }.apply {
            isDaemon = true
            // core/auth 内仅有的两处裸线程之一（与 BangumiAuthService 对称），命名便于线程栈排障（BK-04）
            name = "hikarinagi-username-load"
        }.start()
        return null
    }

    /**
     * 返回可用的访问令牌：快速路径直接读已存令牌；过期/缺失时刷新。须在 IO 线程调用。
     * 刷新的网络 IO 不持有任何共享监视器：主线程的两处取锁点
     * （[startAuthorization] / [handleAuthorizationResponse] 经 [replaceAuthorizationService]
     * 与 prewarm 的 authorizationService 懒初始化/回收）不被最长 ~35s 的刷新阻塞。
     * 并发去重见 [refreshGate]。
     * 返回 null 表示未配置凭据、needsReauth 已置位（上次刷新被服务端明确拒绝）或本次刷新失败；
     * 需要区分这三种情形的调用方改用 [getValidAccessTokenResult]。
     */
    fun getValidAccessToken(context: Context): String? =
        (getValidAccessTokenResult(context) as? HikarinagiAccessTokenResult.Success)?.accessToken

    /** [getValidAccessToken] 的类型化变体：额外区分「凭据未配置」档（重试无意义，应引导授权）。 */
    fun getValidAccessTokenResult(context: Context): HikarinagiAccessTokenResult {
        validStoredAccessToken(context)?.let { return HikarinagiAccessTokenResult.Success(it) }
        return refreshAccessToken(context)
    }

    /** 快速路径：仅当存在未过期访问令牌且未置位 needsReauth 时返回；防止 401/加密失败后继续重放注定失效的令牌。 */
    private fun validStoredAccessToken(context: Context): String? {
        val snapshot = HikarinagiAuthStore.readAccessSnapshot(context)
        if (snapshot.needsReauth) return null
        return snapshot.accessToken.takeIf {
            it.isNotBlank() && System.currentTimeMillis() + REFRESH_SKEW_MS < snapshot.expiresAtMillis
        }
    }

    private fun refreshAccessToken(context: Context): HikarinagiAccessTokenResult {
        val clientId = clientId()
        val (initialRefreshToken, _) = HikarinagiAuthStore.readRefreshSnapshot(context)
        // 凭据未配置：独立分档（同返回 null 的传输失败语义不同，判别结构供调用方
        // 引导授权而非提示网络重试）
        if (clientId.isBlank() || initialRefreshToken.isBlank()) {
            return HikarinagiAccessTokenResult.ConfigMissing
        }
        // needsReauth 已置位（上次刷新被服务端 400/401 明确拒绝 / Keystore 落盘失败）：
        // 不再重放注定失败的刷新请求，离线不再按 connect 超时逐请求挂起
        // （与 BangumiAuthService 对齐；写入路径据此按令牌失效分档报错）。
        // needsReauth 仅承载令牌失效——读路径 403 的权限缺失写入独立 permission_error 位，
        // 不经本短路，令牌过期后的自动刷新自愈不被锁死。
        if (HikarinagiAuthStore.getStatus(context).needsReauth) {
            return HikarinagiAccessTokenResult.RefreshFailed
        }

        // 刷新去重：网络请求在协调器回调内执行，不处于任何共享监视器临界区；
        // 同一时刻至多一个刷新在途，并发调用方等待其结束后重走快速路径，不重复发起请求。
        val token = refreshGate.run(
            recheck = { validStoredAccessToken(context) },
            task = {
                // 进闸后再原子读取最新 refresh snapshot（防止等待期间已有新登录或轮换发生）
                if (HikarinagiAuthStore.getStatus(context).needsReauth) {
                    null
                } else {
                    val (latestRefresh, latestEpoch) = HikarinagiAuthStore.readRefreshSnapshot(context)
                    if (latestRefresh.isBlank()) {
                        null
                    } else {
                        performTokenRefresh(context, clientId, latestRefresh, latestEpoch)
                    }
                }
            },
        )
        return if (token != null) {
            HikarinagiAccessTokenResult.Success(token)
        } else {
            HikarinagiAccessTokenResult.RefreshFailed
        }
    }

    /**
     * 执行一次刷新令牌网络请求（须在不持有任何共享监视器的线程上调用）：
     * 成功写回新令牌并返回访问令牌；刷新被明确拒绝时置位 needsReauth（仅当 refresh token
     * 未被并发覆盖）；传输失败/异常体返回 null。
     */
    private fun performTokenRefresh(
        context: Context,
        clientId: String,
        refreshToken: String,
        expectedEpoch: Long,
    ): String? {
        // JVM 单元测试桩：仅替换 HTTP 往返，异常处理与生产路径一致
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
        var conn: HttpURLConnection? = null
        return try {
            val form = authFormBody(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
            )
            conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "TyranorNext/1.0")
            }
            conn.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            // 响应体经 readTextCapped 限长读取（BK-22）：超大响应触顶抛 IllegalStateException
            // → 被下方泛 catch 捕获按「刷新失败返回 null」兜底，替代原全量 readText 的 OOM 面
            val body = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readTextCapped(MAX_JSON_CHARS) }
            } else {
                conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readTextCapped(MAX_JSON_CHARS) }.orEmpty()
            }
            completeTokenRefresh(context, expectedEpoch, refreshToken, conn.responseCode, body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 刷新响应落地（与网络往返解耦，JVM 可测）：拒绝置位 needsReauth，成功写回新令牌。 */
    private fun completeTokenRefresh(
        context: Context,
        expectedEpoch: Long,
        refreshToken: String,
        status: Int,
        body: String,
    ): String? {
        if (status !in 200..299) {
            if (SecureAuthStore.isOAuthInvalidGrantRejection(status, body)) {
                // 仅服务端明确返回 OAuth invalid_grant 才置位 needsReauth；
                // 空体/非 JSON 401 及 invalid_request/invalid_client 等不锁死现存凭据
                HikarinagiAuthStore.markNeedsReauthIfMatching(
                    context,
                    expectedEpoch,
                    refreshToken,
                    HikarinagiAuthError.EXPIRED.name,
                )
            }
            return null
        }

        val json = JSONObject(body)
        val access = json.optNonBlankString("access_token").orEmpty()
        val nextRefresh = json.optNonBlankString("refresh_token") ?: refreshToken
        if (access.isBlank()) return null
        val expiresIn = max(json.optLong("expires_in", 3600L), 1L)
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        // 原子成功写回守卫：凭据代次与 refresh token 仍在同一 storeLock 下匹配时才提交，
        // 否则不覆盖新登录态、不把未落库的令牌返回给本次调用方（加密落盘失败同理）
        if (!HikarinagiAuthStore.saveTokensIfMatching(context, expectedEpoch, refreshToken, access, nextRefresh, expiresAt)) {
            return null
        }
        return access
    }

    /** 懒解析并缓存当前授权账号用户名（需在 IO 线程调用；失败/缺 user:read scope 时静默跳过）。 */
    fun ensureUsernameLoaded(context: Context) {
        val status = HikarinagiAuthStore.getStatus(context)
        if (!status.authorized || status.needsReauth || status.username.isNotBlank()) return
        val token = getValidAccessToken(context) ?: return
        val epoch = HikarinagiAuthStore.currentEpochIfAccessTokenMatches(context, token) ?: return
        fetchAndSaveUsername(context, token, epoch)
    }

    private fun fetchAndSaveUsername(context: Context, token: String, expectedEpoch: Long) {
        val result = httpJsonResult(USER_ME_URL, headers = mapOf("Authorization" to "Bearer $token"))
        if (result.status !in 200..299) return
        val json = result.json ?: return
        if (!json.optBoolean("success", false)) return
        val data = json.optJSONObject("data") ?: return
        val name = data.optNonBlankString("name").orEmpty()
        val nickname = data.optNonBlankString("nickname").orEmpty()
        val resolved = name.ifBlank { nickname }
        if (resolved.isNotBlank()) {
            HikarinagiAuthStore.saveUsernameIfEpochMatches(context, resolved, expectedEpoch)
        }
    }

    /** 纯校验（JVM 可测）：校验 OIDC ID Token 的 nonce 与预期一致；缺失任一侧则跳过。 */
    internal fun validateIdTokenNonceRaw(rawIdToken: String?, expectedNonce: String?): HikarinagiAuthError? {
        val idToken = rawIdToken?.trim().orEmpty()
        val nonce = expectedNonce?.trim().orEmpty()
        if (idToken.isBlank() || nonce.isBlank()) return null
        val parts = idToken.split(".")
        if (parts.size != 3) return HikarinagiAuthError.ID_TOKEN_INVALID_FORMAT
        return try {
            val payload = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            val actual = JSONObject(payload).optNonBlankString("nonce").orEmpty()
            if (MessageDigest.isEqual(actual.toByteArray(StandardCharsets.UTF_8), nonce.toByteArray(StandardCharsets.UTF_8))) {
                null
            } else {
                HikarinagiAuthError.ID_TOKEN_NONCE_FAILED
            }
        } catch (_: Exception) {
            HikarinagiAuthError.ID_TOKEN_PARSE_FAILED
        }
    }

    private fun clientId(): String {
        val override = testClientId
        if (override != null) check(BuildConfig.DEBUG) { "test stub must be null in release" }
        return (override ?: BuildConfig.HIKARINAGI_CLIENT_ID).trim()
    }

    /**
     * 及时回收授权服务（失败/回调路径即置 null 并 dispose 旧实例；BK-03 注释固化）：
     * 与 BangumiAuthService 的「AuthorizationService 进程内常驻、不随授权流程 dispose」
     * 不对称系各自取舍，均有意——本侧及时回收，勿向 Bangumi 侧对齐。
     */
    @Synchronized
    private fun replaceAuthorizationService(service: AuthorizationService?) {
        authorizationService?.dispose()
        authorizationService = service
    }
}
