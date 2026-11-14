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
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.max

/**
 * 个人令牌校验结论（AGENTS 错误协议：类型化失败结果，core 不携带本地化文案）：
 * 成功携带身份；失败携带 [BangumiAuthError] 错误码，文案由 UI 经 `ui/auth/BangumiAuthMessages`
 * 映射。「服务端明确拒绝」与「网络/服务不可达」分档，避免离线或服务波动时把校验失败
 * 一律误报成「令牌无效」、误导用户反复重粘令牌。
 */
sealed class BangumiTokenVerification {
    data class Success(val identity: BangumiAuthService.BangumiTokenIdentity) : BangumiTokenVerification()
    data class Failure(val error: BangumiAuthError) : BangumiTokenVerification()
}

/**
 * 刷新取令牌的类型化结论：此前 [BangumiAuthService.getValidAccessToken] 把「凭据未配置」
 * 「needsReauth 已置位」「刷新传输失败」三态坍缩为 null，消费方只能按 needsReauth 位二次
 * 猜测——凭据未配置（client id/secret 或 refresh token 缺失）不是网络问题，重试刷新无意义，
 * 却被消费方误报成网络失败。本判别结构区分三态供调用方按档映射文案；**未并入
 * [BangumiAuthError] 枚举**：该枚举的 UI 文案映射（`ui/auth/BangumiAuthMessages`）为穷尽
 * when，增员需同步扩 UI 词表，判别结构不携带文案、避免跨域耦合。
 */
sealed class BangumiAccessTokenResult {
    /** 快速路径命中或刷新成功写回的可用访问令牌。 */
    data class Success(val accessToken: String) : BangumiAccessTokenResult()

    /** 凭据未配置（client id/secret 或 refresh token 缺失）：重试刷新无意义，调用方应引导授权。 */
    data object ConfigMissing : BangumiAccessTokenResult()

    /** 刷新未取回令牌（needsReauth 已置位、刷新被服务端拒绝或传输失败）；调用方按 needsReauth 位细分文案。 */
    data object RefreshFailed : BangumiAccessTokenResult()
}

/**
 * 纯映射（JVM 可测）：/v0/me 非 2xx 状态码 → 校验错误码。
 * 401/403 = 服务端明确拒绝（TOKEN_INVALID）；传输失败（status=0）与其余非 2xx
 * 保守按 NETWORK 处理。403 的具体含义（权限不足 vs 拒绝访问）未经实机证实，
 * 以「服务端明确拒绝该令牌」这一可判定事实为准绳，文案统一引导重新生成令牌。
 */
internal fun bangumiMeStatusToAuthError(status: Int): BangumiAuthError =
    if (status == 401 || status == 403) BangumiAuthError.TOKEN_INVALID else BangumiAuthError.NETWORK

object BangumiAuthService {
    const val EXTRA_AUTH_CANCELED = "extra_auth_canceled"
    private const val EXTRA_EXPECTED_GENERATION = "extra_expected_generation"

    private const val AUTHORIZE_URL = "https://bgm.tv/oauth/authorize"
    private const val TOKEN_URL = "https://bgm.tv/oauth/access_token"
    private const val REDIRECT_URI = "tyranornext://oauth/bangumi"
    private const val REFRESH_SKEW_MS = 60_000L

    // 仅以 applicationContext 构造并进程内常驻复用 CustomTabs 连接，不持有 Activity 引用
    @SuppressLint("StaticFieldLeak")
    private var authorizationService: AuthorizationService? = null

    /** 刷新去重协调器（单飞）：见 [SingleFlightRefresh]，网络 IO 始终在协调器临界区之外。 */
    private val refreshGate = SingleFlightRefresh()

    @VisibleForTesting
    internal fun refreshWaiterCount(): Int = refreshGate.waiterCount()

    data class BangumiTokenIdentity(val id: Long, val username: String)

    /**
     * 预热：提前构造 [AuthorizationService]（含自定义标签页连接管理），消除点击到浏览器打开的延迟。
     *
     * 生命周期声明（BK-03 注释固化）：AuthorizationService 进程内常驻（单实例，复用连接管理），
     * 不随授权流程 dispose；与 HikarinagiAuthService 的「失败/回调即回收」不对称系各自取舍，
     * 均有意（泄漏上限恒为进程内 1 个对象，prewarm 收益大于对齐回收）。
     */
    fun prewarm(context: Context) {
        synchronized(this) {
            if (authorizationService == null) {
                authorizationService = AuthorizationService(context.applicationContext)
            }
        }
    }

    /**
     * 发起浏览器 OAuth 授权；失败返回类型化错误码（文案由 UI 经 `ui/auth/BangumiAuthMessages`
     * 映射，core 不携带本地化文案）。
     */
    fun startAuthorization(activity: Activity, callbackIntent: Intent): BangumiAuthError? {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val clientId = clientId()
        val clientSecret = clientSecret()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return BangumiAuthError.CLIENT_ID_MISSING
        }
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
        ).build()

        // AppAuth supplies the authorization result through a fill-in Intent, so
        // these PendingIntents must remain mutable. Restrict both copies to this
        // application before handing them to AppAuth; a package-scoped Intent is
        // explicit enough to avoid mutable implicit PendingIntent hijacking.
        val expectedGeneration = BangumiAuthStore.readCredentialGeneration(appContext)
        val completeCallback = Intent(callbackIntent)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_EXPECTED_GENERATION, expectedGeneration)
        val cancelCallback = Intent(callbackIntent)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_AUTH_CANCELED, true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        // requestCode 固定 0/1（与 Hikarinagi 侧同形，BK-07 注释固化）：同一 provider 同时只
        // 允许一个在途授权流程，FLAG_UPDATE_CURRENT 覆盖旧 PendingIntent 为预期行为（发起新
        // 授权即放弃旧流程）；跨 provider 的 0/1 复用作用域为 Activity+requestCode，互不冲突。
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
            // 发起浏览器前置位标记「授权中」；进程死亡丢失回调时，UI 可据陈旧时间戳提示重试
            BangumiAuthStore.setAuthInProgress(appContext, System.currentTimeMillis())
            service.performAuthorizationRequest(request, completeIntent, cancelIntent)
            android.util.Log.i("BangumiAuth", "browser launched in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
            null
        } catch (_: ActivityNotFoundException) {
            BangumiAuthStore.setAuthInProgress(appContext, 0L)
            BangumiAuthError.NO_BROWSER
        } catch (_: Exception) {
            BangumiAuthStore.setAuthInProgress(appContext, 0L)
            BangumiAuthError.LAUNCH_FAILED
        }
    }

    /**
     * 处理回调 Activity 收到的授权结果：取消/失败/成功均携带类型化错误码（成功为 null），
     * 文案由调用方经 `ui/auth/BangumiAuthMessages` 映射。
     */
    fun handleAuthorizationResponse(
        activity: Activity,
        intent: Intent,
        onResult: (success: Boolean, error: BangumiAuthError?) -> Unit,
    ) {
        val appContext = activity.applicationContext
        // 回调已抵达（无论完成还是取消）：清除 startAuthorization 置位的「授权中」标记
        BangumiAuthStore.setAuthInProgress(appContext, 0L)
        if (intent.getBooleanExtra(EXTRA_AUTH_CANCELED, false)) {
            onResult(false, BangumiAuthError.CANCELLED)
            return
        }
        val exception = AuthorizationException.fromIntent(intent)
        if (exception != null) {
            onResult(false, BangumiAuthError.FAILED)
            return
        }
        val response = AuthorizationResponse.fromIntent(intent)
        if (response == null) {
            onResult(false, BangumiAuthError.INVALID_RESPONSE)
            return
        }
        // AuthorizationResponse.request 可空（前置已拦 response==null，request==null 当前不可达，
        // 防御补全 BK-26）：null 时与 response.state 不等 → STATE_MISMATCH，防御语义合理
        if (response.state != response.request?.state) {
            onResult(false, BangumiAuthError.STATE_MISMATCH)
            return
        }
        val secret = clientSecret()
        if (secret.isBlank()) {
            onResult(false, BangumiAuthError.CLIENT_ID_MISSING)
            return
        }

        val expectedGeneration = if (intent.hasExtra(EXTRA_EXPECTED_GENERATION)) {
            intent.getLongExtra(EXTRA_EXPECTED_GENERATION, -1L)
        } else {
            null
        }
        val service = synchronized(this) {
            authorizationService ?: AuthorizationService(appContext).also { authorizationService = it }
        }
        try {
            service.performTokenRequest(response.createTokenExchangeRequest(), ClientSecretPost(secret)) { tokenResponse, tokenException ->
                val token = tokenResponse
                if (tokenException != null || token == null) {
                    onResult(false, BangumiAuthError.TOKEN_EXCHANGE_FAILED)
                    return@performTokenRequest
                }
                val accessToken = token.accessToken.orEmpty()
                if (accessToken.isBlank()) {
                    onResult(false, BangumiAuthError.TOKEN_MISSING_FIELDS)
                    return@performTokenRequest
                }
                val expiresAt = token.accessTokenExpirationTime ?: 0L
                val error = completeOAuthTokenExchange(
                    context = appContext,
                    expectedGeneration = expectedGeneration,
                    accessToken = accessToken,
                    refreshToken = token.refreshToken.orEmpty(),
                    expiresAtMillis = expiresAt,
                    userIdHint = token.additionalParameters["user_id"]?.toLongOrNull(),
                )
                onResult(error == null, error)
            }
        } catch (_: Exception) {
            onResult(false, BangumiAuthError.TOKEN_EXCHANGE_FAILED)
        }
    }

    /**
     * 落盘 OAuth 换码结果（与 PendingIntent 回调解耦，JVM 可测）：仅当持久化凭据代次仍匹配
     * 发起授权时快照 [expectedGeneration] 才提交，防止滞留浏览器回调覆盖随后完成的个人令牌或登出。
     */
    internal fun completeOAuthTokenExchange(
        context: Context,
        expectedGeneration: Long?,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long,
        userIdHint: Long? = null,
    ): BangumiAuthError? {
        // 加密落盘失败或凭据代次漂移（Keystore 不可用或中途已换号/登出）均不得报告「授权成功」
        val savedEpoch = if (expectedGeneration != null) {
            BangumiAuthStore.saveTokensIfGenerationMatches(
                context,
                expectedGeneration,
                accessToken,
                refreshToken,
                expiresAtMillis,
            )
        } else {
            BangumiAuthStore.saveTokensReturningEpoch(
                context,
                accessToken,
                refreshToken,
                expiresAtMillis,
            )
        } ?: return BangumiAuthError.TOKEN_EXCHANGE_FAILED
        userIdHint?.takeIf { it > 0L }?.let { uid ->
            BangumiAuthStore.saveUserIdentityIfEpochMatches(context, uid, uid.toString(), savedEpoch)
        }
        Thread {
            runCatching { fetchAndSaveUserIdentity(context, accessToken, savedEpoch) }
        }.apply {
            isDaemon = true
            // core/auth 内仅有的两处裸线程之一（与 HikarinagiAuthService 对称），命名便于线程栈排障（BK-04）
            name = "bangumi-identity-load"
        }.start()
        return null
    }

    /**
     * 返回可用的访问令牌：快速路径直接读已存令牌；过期/缺失时刷新。须在 IO 线程调用。
     * 刷新的网络 IO 不持有任何共享监视器：主线程的两处取锁点
     * （[startAuthorization] / [handleAuthorizationResponse] 的 authorizationService 懒初始化）
     * 不被最长 ~35s 的刷新阻塞。并发去重见 [refreshGate]。
     * 返回 null 表示未配置凭据、needsReauth 已置位（上次刷新被服务端明确拒绝）或本次刷新失败；
     * 需要区分这三种情形的调用方改用 [getValidAccessTokenResult]。
     */
    fun getValidAccessToken(context: Context): String? =
        (getValidAccessTokenResult(context) as? BangumiAccessTokenResult.Success)?.accessToken

    /** [getValidAccessToken] 的类型化变体：额外区分「凭据未配置」档（重试无意义，应引导授权）。 */
    fun getValidAccessTokenResult(context: Context): BangumiAccessTokenResult {
        validStoredAccessToken(context)?.let { return BangumiAccessTokenResult.Success(it) }
        return refreshAccessToken(context)
    }

    /** 快速路径：仅当存在有效访问令牌（未过期或长期令牌）且未置位 needsReauth 时返回；防止 401/加密失败后继续重放注定失效的令牌。 */
    private fun validStoredAccessToken(context: Context): String? {
        val snapshot = BangumiAuthStore.readAccessSnapshot(context)
        if (snapshot.needsReauth) return null
        return snapshot.accessToken.takeIf {
            it.isNotBlank() && (snapshot.expiresAtMillis == 0L || System.currentTimeMillis() + REFRESH_SKEW_MS < snapshot.expiresAtMillis)
        }
    }

    private fun refreshAccessToken(context: Context): BangumiAccessTokenResult {
        // needsReauth 已置位（上次刷新被服务端 400/401 明确拒绝 / Keystore 落盘失败 / 个人令牌 401）：
        // 不再重放注定失败的刷新请求（写入路径据此直接按令牌失效分档报错）
        if (BangumiAuthStore.getStatus(context).needsReauth) {
            return BangumiAccessTokenResult.RefreshFailed
        }
        val clientId = clientId()
        val clientSecret = clientSecret()
        val (initialRefreshToken, _) = BangumiAuthStore.readRefreshSnapshot(context)
        // 凭据未配置：独立分档（同返回 null 的传输失败语义不同，判别结构供调用方
        // 引导授权而非提示网络重试）
        if (clientId.isBlank() || clientSecret.isBlank() || initialRefreshToken.isBlank()) {
            return BangumiAccessTokenResult.ConfigMissing
        }

        // 刷新去重：网络请求在协调器回调内执行，不处于任何共享监视器临界区；
        // 同一时刻至多一个刷新在途，并发调用方等待其结束后重走快速路径，不重复发起请求。
        val token = refreshGate.run(
            recheck = { validStoredAccessToken(context) },
            task = {
                // 进闸后再原子读取最新 refresh snapshot（防止等待期间已有新登录或轮换发生）
                if (BangumiAuthStore.getStatus(context).needsReauth) {
                    null
                } else {
                    val (latestRefresh, latestEpoch) = BangumiAuthStore.readRefreshSnapshot(context)
                    if (latestRefresh.isBlank()) {
                        null
                    } else {
                        performTokenRefresh(context, clientId, clientSecret, latestRefresh, latestEpoch)
                    }
                }
            },
        )
        return if (token != null) {
            BangumiAccessTokenResult.Success(token)
        } else {
            BangumiAccessTokenResult.RefreshFailed
        }
    }

    /**
     * JVM 单元测试桩（与 [SecureAuthStore.testCipherDelegate] / Hikarinagi 侧同款缝隙）：
     * 仅替换刷新请求的 HTTP 往返（入参 client/refresh token，返回 (HTTP 状态码, 响应体)），
     * 单飞协调、快速路径 recheck、400/401 拒绝置位与写回守卫仍在生产路径执行。生产代码恒为 null；
     * 测试结束必须复位为 null（@After），否则残留桩会污染跨测试的后续刷新。
     */
    @VisibleForTesting
    @Volatile
    internal var testRefreshHttpRoundTrip: ((clientId: String, refreshToken: String) -> Pair<Int, String>)? = null

    /** JVM 单元测试凭据覆盖（使单测不依赖本地或 CI 的 BuildConfig OAuth 配置）。生产代码恒为 null。 */
    @VisibleForTesting
    @Volatile
    internal var testClientCredentials: Pair<String, String>? = null

    /**
     * 执行一次刷新令牌网络请求（须在不持有任何共享监视器的线程上调用）：
     * 成功写回新令牌并返回访问令牌；刷新被明确拒绝时置位 needsReauth（仅当 refresh token
     * 未被并发覆盖）；传输失败/异常体返回 null。
     */
    private fun performTokenRefresh(
        context: Context,
        clientId: String,
        clientSecret: String,
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
            val form = buildRefreshFormBody(clientId, clientSecret, refreshToken)
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

    @VisibleForTesting
    internal fun buildRefreshFormBody(clientId: String, clientSecret: String, refreshToken: String): String =
        authFormBody(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "refresh_token" to refreshToken,
            "redirect_uri" to REDIRECT_URI,
        )

    /** 刷新响应落地（与网络往返解耦，JVM 可测）：仅明确 invalid_grant 置位 needsReauth，成功写回新令牌。 */
    private fun completeTokenRefresh(
        context: Context,
        expectedEpoch: Long,
        refreshToken: String,
        status: Int,
        body: String,
    ): String? {
        if (status !in 200..299) {
            if (SecureAuthStore.isOAuthInvalidGrantRejection(status, body)) {
                // 服务端明确返回 invalid_grant 拒收刷新令牌：在同一凭据代次与 refresh token 仍匹配时
                // 原子置位 needsReauth；空体/畸形体/invalid_client 等保留凭据以便后续重试
                BangumiAuthStore.markNeedsReauthIfMatching(context, expectedEpoch, refreshToken, BangumiAuthError.EXPIRED.name)
            }
            return null
        }

        val json = JSONObject(body)
        val access = json.optNonBlankString("access_token").orEmpty()
        if (access.isBlank()) return null
        val nextRefresh = json.optNonBlankString("refresh_token") ?: refreshToken
        val expiresIn = max(json.optLong("expires_in", 0L), 0L)
        val expiresAt = if (expiresIn == 0L) 0L else System.currentTimeMillis() + expiresIn * 1000L
        // 原子成功写回守卫：凭据代次与 refresh token 仍在同一 storeLock 下匹配时才提交，
        // 否则不覆盖新登录态、不把未落库的令牌返回给本次调用方（加密落盘失败同理）
        if (!BangumiAuthStore.saveTokensIfMatching(context, expectedEpoch, refreshToken, access, nextRefresh, expiresAt)) {
            return null
        }
        return access
    }

    /** 懒解析并缓存当前授权账号用户名或数字 ID（需在 IO 线程调用；失败时静默跳过）。 */
    fun ensureIdentityLoaded(context: Context) {
        val status = BangumiAuthStore.getStatus(context)
        // 数字 uid 已在 store 兜底占位 username（见 BangumiAuthStore.getStatus），此行对这类
        // 账号恒跳过：不再补解析真实用户名，避免 /v0/me 解析失败时每次进设置页重复发起请求；
        // 展示层显示数字 uid 属可接受降级（取舍由 AuthStatusPersistenceTest 钉住，BK-19 注释固化）
        if (!status.authorized || status.needsReauth || status.username.isNotBlank()) return
        val token = getValidAccessToken(context) ?: return
        val epoch = BangumiAuthStore.currentEpochIfAccessTokenMatches(context, token) ?: return
        fetchAndSaveUserIdentity(context, token, epoch)
    }

    private fun fetchAndSaveUserIdentity(context: Context, token: String, expectedEpoch: Long) {
        // 身份解析失败静默跳过（仅显示层兜底为数字 uid，不阻塞授权流程；既有取舍）
        when (val result = verifyPersonalToken(token)) {
            is BangumiTokenVerification.Success ->
                BangumiAuthStore.saveUserIdentityIfEpochMatches(
                    context,
                    result.identity.id,
                    result.identity.username,
                    expectedEpoch,
                )
            is BangumiTokenVerification.Failure -> Unit
        }
    }

    /**
     * 校验粘贴的个人令牌：GET /v0/me，成功返回解析出的账号身份，失败返回类型化错误码。
     * 「令牌被拒」（401/403）与「网络/服务异常」（传输失败、5xx、2xx 异常响应体）分档，
     * 调用方据此区分「重新生成令牌」与「稍后重试」。
     */
    fun verifyPersonalToken(token: String): BangumiTokenVerification {
        val result = httpJsonResult(
            "https://api.bgm.tv/v0/me",
            headers = mapOf("Authorization" to "Bearer $token"),
        )
        if (result.status !in 200..299) {
            return BangumiTokenVerification.Failure(bangumiMeStatusToAuthError(result.status))
        }
        // 2xx 但响应体非 JSON 或缺少身份字段：服务/网关异常，保守按 NETWORK（不得误判令牌无效）
        val identity = result.json?.let(::parseBangumiMeIdentity)
            ?: return BangumiTokenVerification.Failure(BangumiAuthError.NETWORK)
        return BangumiTokenVerification.Success(identity)
    }

    internal fun parseBangumiMeIdentity(json: JSONObject): BangumiTokenIdentity? {
        val id = json.optLong("id", 0L)
        if (id <= 0L) return null
        val resolved = json.optNonBlankString("username") ?: id.toString()
        return BangumiTokenIdentity(id = id, username = resolved)
    }

    private fun clientId(): String {
        val override = testClientCredentials
        if (override != null) check(BuildConfig.DEBUG) { "test stub must be null in release" }
        return (override?.first ?: BuildConfig.BANGUMI_CLIENT_ID).trim()
    }

    private fun clientSecret(): String {
        val override = testClientCredentials
        if (override != null) check(BuildConfig.DEBUG) { "test stub must be null in release" }
        return (override?.second ?: BuildConfig.BANGUMI_CLIENT_SECRET).trim()
    }
}
