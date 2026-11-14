package com.tyranor.next.core.status

import android.content.Context
import com.tyranor.next.core.auth.BangumiAccessTokenResult
import com.tyranor.next.core.auth.BangumiAuthError
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.auth.HikarinagiAccessTokenResult
import com.tyranor.next.core.auth.HikarinagiAuthError
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAccessTokenResult
import com.tyranor.next.core.auth.NextMoeAuthError
import com.tyranor.next.core.auth.NextMoeAuthService
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.auth.VndbAuthError
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.cover.CoverErrorCode
import com.tyranor.next.core.cover.CoverSearchException
import com.tyranor.next.core.cover.CoverSearchResult
import com.tyranor.next.core.cover.CoverScraperService
import com.tyranor.next.core.cover.VndbCoverService
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.net.RequestThrottle
import com.tyranor.next.core.net.httpJsonResult
import com.tyranor.next.core.net.optNonBlankString
import com.tyranor.next.core.net.urlEncode
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Bangumi 收藏类型（SubjectCollectionType，游戏渲染为：想玩/玩过/在玩/搁置/抛弃）。 */
object BangumiCollectTypes {
    const val WISH = 1
    const val DONE = 2
    const val DOING = 3
    const val ON_HOLD = 4
    const val DROPPED = 5

    val ALL = listOf(WISH, DONE, DOING, ON_HOLD, DROPPED)
}

/** VNDB ulist 预置标签（id < 10 的互斥四态；愿望单/黑名单不参与游玩状态）。 */
object VndbLabels {
    const val PLAYING = 1
    const val FINISHED = 2
    const val STALLED = 3
    const val DROPPED = 4

    val ALL = listOf(PLAYING, FINISHED, STALLED, DROPPED)
}

/** Hikarinagi Galgame 状态枚举（UpsertGalgameRateDto.status：想玩/通关/在玩/搁置/弃坑）。 */
object HikarinagiStatuses {
    const val PLAN = "PLAN"
    const val COMPLETED = "COMPLETED"
    const val GOING = "GOING"
    const val ON_HOLD = "ON_HOLD"
    const val DROPPED = "DROPPED"

    val ALL = listOf(PLAN, COMPLETED, GOING, ON_HOLD, DROPPED)
}

/** 未萌 NextMoe 游玩状态封闭词表（`PUT /v2/me/work-states/{work_id}`：想玩/在玩/通关/搁置/弃坑）。 */
object NextMoeWorkStates {
    const val WISH = "wish"
    const val DOING = "doing"
    const val DONE = "done"
    const val ON_HOLD = "on_hold"
    const val DROPPED = "dropped"

    val ALL = listOf(WISH, DOING, DONE, ON_HOLD, DROPPED)
}

/** 未萌 NextMoe 可选完成度词表（`wish` 不允许携带）。 */
internal object NextMoeCompletions {
    val ALL = setOf("one_route", "main", "all")
}

/** 纯映射（JVM 可测）：VNDB 写入某标签时需要显式 unset 的其余互斥标签。 */
internal fun vndbLabelsToUnset(label: Int): List<Int> =
    VndbLabels.ALL.filter { it != label }

/** 纯映射（JVM 可测）：Bangumi 状态类型合法性。 */
internal fun isBangumiCollectType(type: Int): Boolean = type in BangumiCollectTypes.ALL

/**
 * 纯映射（JVM 可测）：Bangumi 非 2xx HTTP 状态码 → 类型化错误分档。
 * 401 = 令牌失效、403 = 服务端明确拒绝（权限不足），其余（含传输失败 status=0）按网络/服务异常。
 * 403 专列分支与同文件 VNDB / Hikarinagi 的既有分档对齐；Bangumi 服务端对「令牌有效但缺
 * 收藏写权限」的确切状态码未经实机 curl 证实，此处以「可判定的 HTTP 状态码」为准绳：
 * 403 恒为服务端判定失败而非传输问题，单独分档保守成立，不可判定的其余状态保守回退网络档。
 */
internal fun bangumiStatusToError(status: Int): PlayStatusError = when (status) {
    401 -> PlayStatusError.BANGUMI_TOKEN_EXPIRED
    403 -> PlayStatusError.BANGUMI_TOKEN_PERMISSION
    else -> PlayStatusError.BANGUMI_NETWORK
}

/**
 * 纯映射（JVM 可测）：Bangumi 写路径非 2xx → 授权态回写决策（BK-05A）。
 * 仅 401（服务端明确拒绝 = 令牌失效/被吊销）落 needsReauth（[BangumiAuthError.EXPIRED].name），
 * 与读路径 401 分档对齐——被吊销令牌由此获得失效信号，不再永远显示「已授权」；403 维持
 * 只抛错不落档（Bangumi 无独立权限位，落档会与刷新短路交互，对照 Hikarinagi 读路径 403
 * 写独立 permission 位的设计），其余失败不触碰授权态。返回 null = 不回写授权态；
 * `markNeedsReauth` 同值短路，重复 401 不重复落盘与 bump。
 */
internal fun bangumiWriteNeedsReauthReason(status: Int): String? =
    if (status == 401) BangumiAuthError.EXPIRED.name else null

/**
 * 纯映射（JVM 可测）：VNDB /authinfo 非 2xx → 类型化错误分档。
 * 401 = token 失效；403 = 服务端明确拒绝——/authinfo 403 的确切语义（listread 缺失还是
 * 其他拒绝）无法静态判定、未经实机证实，按保守档处理：与读/写路径（readVndbStatus /
 * writeVndbStatus）的 403 分档对齐为权限档，不与 401 失效混同，避免把「改权限即可修复」
 * 误报成「token 已失效」；其余状态（含传输失败 status=0）保守回退网络档。
 */
internal fun vndbAuthInfoStatusToError(status: Int): PlayStatusError = when (status) {
    401 -> PlayStatusError.VNDB_TOKEN_INVALID
    403 -> PlayStatusError.VNDB_TOKEN_PERMISSION
    else -> PlayStatusError.VNDB_NETWORK
}

/**
 * 纯映射（JVM 可测）：VNDB ulist 2xx 响应体 → 状态。2xx 但响应体非 JSON（网关错误页 /
 * 截断响应）按网络档抛出，不得误报「未在列表」（2xx 非 JSON 是服务/网关异常，不是
 * 「该 VN 不在 ulist 中」）；「未在列表」仅指 2xx JSON 正常包络下无命中行或无 labels 数组。
 */
internal fun parseVndbUlistStatus(json: JSONObject?, vnId: String): PlayStatusService.VndbStatus {
    val payload = json
        ?: throw PlayStatusException(PlayStatusError.VNDB_NETWORK, "ulist read non-json body")
    val entry = payload.optJSONArray("results")?.optJSONObject(0)
        ?.takeIf { it.optNonBlankString("id") == vnId }
        ?: return PlayStatusService.VndbStatus(labelIds = emptySet())
    val labels = entry.optJSONArray("labels") ?: return PlayStatusService.VndbStatus(labelIds = emptySet())
    return PlayStatusService.VndbStatus(
        labelIds = (0 until labels.length()).asSequence()
            .mapNotNull { labels.optJSONObject(it)?.optInt("id") }
            .toSet(),
    )
}

/**
 * 纯映射（JVM 可测）：刷新令牌失败（`BangumiAccessTokenResult.RefreshFailed`）的错误分档。
 * 仅当刷新过程**确认令牌失效**（刷新响应 400/401 时各 AuthService 已 `markNeedsReauth`）
 * 才报 TOKEN_EXPIRED；传输失败/超时等未确认失效的场景报 NETWORK——避免离线时把刷新
 * 失败误报成「授权已失效」、诱导用户重走授权（与 token 校验路径分档同族对齐）。
 * configMissing = OAuth 应用凭据未配置（`BangumiAccessTokenResult.ConfigMissing`）：
 * 刷新不可能成功，但既非令牌失效也非网络故障，按「需重新授权」档提示
 * （刷新配置缺失不再误报网络档；消费侧见 [PlayStatusService.bangumiRequest]）。
 */
internal fun bangumiTokenRefreshFailureToError(
    needsReauth: Boolean,
    configMissing: Boolean = false,
): PlayStatusError =
    if (needsReauth || configMissing) PlayStatusError.BANGUMI_TOKEN_EXPIRED else PlayStatusError.BANGUMI_NETWORK

/**
 * 同 [bangumiTokenRefreshFailureToError] 的 Hikarinagi 侧分档（消费
 * `HikarinagiAccessTokenResult.RefreshFailed`/`ConfigMissing`，语义与 Bangumi 侧一致）。
 */
internal fun hikarinagiTokenRefreshFailureToError(
    needsReauth: Boolean,
    configMissing: Boolean = false,
): PlayStatusError =
    if (needsReauth || configMissing) PlayStatusError.HIKARINAGI_TOKEN_EXPIRED else PlayStatusError.HIKARINAGI_NETWORK

/** 纯映射（JVM 可测）：Hikarinagi 状态合法性。 */
internal fun isHikarinagiStatus(status: String?): Boolean = status in HikarinagiStatuses.ALL

/**
 * 纯映射（JVM 可测）：Hikarinagi 非 2xx 且非 401/403 HTTP 状态码 → 类型化错误分档（BK-39）。
 * 实测写接口对不存在条目返回 HTTP 404（`err=GALGAME_NOT_FOUND`），归为条目不存在提示重绑，
 * 避免与网络/服务故障混同；其余非 2xx 回退网络档。
 */
internal fun hikarinagiNonSuccessToError(status: Int): PlayStatusError = when (status) {
    404 -> PlayStatusError.HIKARINAGI_SUBJECT_NOT_FOUND
    else -> PlayStatusError.HIKARINAGI_NETWORK
}

internal fun nextMoeTokenRefreshFailureToError(
    needsReauth: Boolean,
    configMissing: Boolean = false,
): PlayStatusError =
    if (needsReauth || configMissing) PlayStatusError.NEXTMOE_TOKEN_EXPIRED else PlayStatusError.NEXTMOE_NETWORK

internal fun isNextMoeWorkState(state: String?): Boolean = state in NextMoeWorkStates.ALL

internal fun nextMoeNonSuccessToError(status: Int, problemCode: String?): PlayStatusError = when {
    status == 429 || problemCode == "RATE_LIMITED" || problemCode == "QUOTA_EXCEEDED" ->
        PlayStatusError.NEXTMOE_RATE_LIMITED
    status == 404 || status == 410 || problemCode == "ENTITY_MERGED" || problemCode == "ENTITY_DELETED" ->
        PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND
    else -> PlayStatusError.NEXTMOE_NETWORK
}

internal fun isNextMoeNotFoundUnmarked(status: Int, json: JSONObject?, problemCode: String?): Boolean =
    status == 404 && json != null && problemCode != "ENTITY_MERGED" && problemCode != "ENTITY_DELETED"

internal fun parseNextMoeWorkState(json: JSONObject?): PlayStatusService.NextMoeStatus {
    val payload = json
        ?: throw PlayStatusException(PlayStatusError.NEXTMOE_NETWORK, "work state read non-json body")
    val state = payload.optNonBlankString("state")
    val completion = payload.optNonBlankString("completion")
    return PlayStatusService.NextMoeStatus(exists = state != null, state = state, completion = completion)
}

internal fun buildNextMoePutWorkStateBody(state: String, preservedCompletion: String? = null): String {
    val body = JSONObject().put("state", state)
    if (state != NextMoeWorkStates.WISH && preservedCompletion in NextMoeCompletions.ALL) {
        body.put("completion", preservedCompletion)
    }
    return body.toString()
}

/**
 * 纯映射（JVM 可测）：Hikarinagi `GET /v3/user/me/rates/galgames/{id}` 2xx 响应体 → 状态。
 * 实测未评分条目返回 HTTP 200 且 `{"success":true,"data":null}`（而非 404）；
 * `success=true` 且 `data` 缺失或为 null 归一为未标记（`exists=false, status=null`），
 * 仅当 `success=false` 或非 null `data` 非对象/响应非 JSON 时按网络档抛出。
 */
internal fun parseHikarinagiRateStatus(json: JSONObject?): PlayStatusService.HikarinagiStatus {
    val payload = json
        ?: throw PlayStatusException(PlayStatusError.HIKARINAGI_NETWORK, "rate read non-json body")
    if (!payload.optBoolean("success", false)) {
        throw PlayStatusException(PlayStatusError.HIKARINAGI_NETWORK, "rate read success=false")
    }
    if (!payload.has("data") || payload.isNull("data")) {
        return PlayStatusService.HikarinagiStatus(exists = false, status = null)
    }
    val data = payload.optJSONObject("data")
        ?: throw PlayStatusException(PlayStatusError.HIKARINAGI_NETWORK, "rate read invalid data")
    val status = data.optNonBlankString("status")
    return PlayStatusService.HikarinagiStatus(exists = true, status = status)
}

/**
 * 纯映射（JVM 可测）：绑定单来源的行内变换（BK-44 删除同 id 早退后统一走
 * [PlayStatusService.bindCoverTransform] 管道的纯函数面）。
 * 写入绑定 id，并在携带有效新标题时刷新 metadataTitle（与解绑保守保留路线 BK-42B 自洽：
 * 重绑正是把被保留的旧标题恢复为绑定名的入口）；无有效变化时返回与 [current] 相等的值，
 * 由 facade 六列比较拦截落库、按幂等成功重读复放；空绑定 id / 未知来源返回 null。
 */
internal fun bindSubjectTransform(
    source: String,
    subjectId: String,
    title: String?,
    current: ScanGame,
): ScanGame? {
    val id = subjectId.trim().takeIf { it.isNotBlank() } ?: return null
    val effectiveTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: current.metadataTitle
    return when (source) {
        AppSettingsStore.COVER_SOURCE_VNDB -> current.copy(vndbId = id, metadataTitle = effectiveTitle)
        AppSettingsStore.COVER_SOURCE_BANGUMI -> current.copy(bangumiId = id, metadataTitle = effectiveTitle)
        AppSettingsStore.COVER_SOURCE_HIKARINAGI -> current.copy(hikarinagiId = id, metadataTitle = effectiveTitle)
        AppSettingsStore.COVER_SOURCE_NEXTMOE -> current.copy(nextmoeId = id, metadataTitle = effectiveTitle)
        else -> null
    }
}

/**
 * 纯映射（JVM 可测）：解绑单来源的行内变换（BK-42B 保守保留路线）。
 * 只清对应来源的绑定 id，metadataTitle 一律保留（理由见 [PlayStatusService.unbindSubject]）；
 * 本已解绑时返回值与 [current] 相等（facade 拦截落库、按幂等成功）；未知来源返回 null。
 */
internal fun unbindSubjectTransform(source: String, current: ScanGame): ScanGame? = when (source) {
    AppSettingsStore.COVER_SOURCE_VNDB -> current.copy(vndbId = null)
    AppSettingsStore.COVER_SOURCE_BANGUMI -> current.copy(bangumiId = null)
    AppSettingsStore.COVER_SOURCE_HIKARINAGI -> current.copy(hikarinagiId = null)
    AppSettingsStore.COVER_SOURCE_NEXTMOE -> current.copy(nextmoeId = null)
    else -> null
}

/**
 * 游玩状态读写（Bangumi 收藏、VNDB ulist、Hikarinagi 评分与未萌 NextMoe 作品状态）。请求全部为阻塞 IO，
 * 须在 Dispatchers.IO 调用；失败抛 [PlayStatusException]，文案由 UI 映射。
 *
 * 每次写入都是用户显式操作：写入会出现在站点的公开主页（VNDB 预置标签无私有选项），
 * 不做任何自动状态推送。
 */
object PlayStatusService {
    // 失败排障埋点 tag（adb logcat -s PlayStatus，记录 HTTP 状态码与端点，不含令牌）
    // 引用 PlayStatusErrors.PLAY_STATUS_LOG_TAG 单一事实源（BK-76），不再私有复制

    private const val HIKARINAGI_RATE_GALGAME_BASE = "https://api.hikarinagi.org/v3/user/me/rates/galgames/"
    private const val NEXTMOE_WORK_STATE_BASE = "https://api.nextmoe.dev/v2/me/work-states/"
    private const val BANGUMI_COLLECTION_BASE = "https://api.bgm.tv/v0/users/-/collections/"
    private const val VNDB_ENDPOINT = "https://api.vndb.org/kana"

    /** Hikarinagi Open API 每应用每分钟 60 次限流：状态读写串行温和节流。 */
    private val hikarinagiThrottle = RequestThrottle(1100L)

    /** NextMoe 用户令牌限流 100 次/分钟（600ms/次）：取 700ms 间隔留少量余量。 */
    private val nextmoeThrottle = RequestThrottle(700L)

    /** Bangumi 无公开硬限流，但状态读写比搜索低频得多：独立串行节流保持温和。 */
    private val bangumiThrottle = RequestThrottle(1100L)

    data class HikarinagiStatus(
        /** false 表示远端尚未记录该条目（GET 404）。 */
        val exists: Boolean,
        val status: String?,
    )

    data class NextMoeStatus(
        /** false 表示远端尚未记录该条目（GET 404 NOT_FOUND）。 */
        val exists: Boolean,
        val state: String?,
        val completion: String? = null,
    )

    data class BangumiStatus(
        /** Bangumi 为只写模型（不做 GET 回读），写入成功乐观更新后恒为 true。 */
        val exists: Boolean,
        val type: Int?,
    )

    data class VndbStatus(
        /** 该 VN 命中的 ulist 标签 id 集合；空集合表示不在列表中。 */
        val labelIds: Set<Int>,
    )

    data class VndbAuthInfo(val userId: String, val username: String, val permissions: List<String>)

    /** 校验粘贴的 token：GET /authinfo，成功返回解析出的用户身份与权限列表。 */
    fun verifyVndbToken(token: String): VndbAuthInfo {
        val info = authInfo(token)
        return VndbAuthInfo(userId = info.first, username = info.second, permissions = info.third)
    }

    /**
     * 用已存 token 查询 /authinfo 并缓存权限列表（绑定 VNDB 条目后调用，用于
     * 「只读可预览、写需 listwrite」的权限提示）。未配置 token 或查询失败返回 null。
     */
    fun checkVndbPermissions(context: Context): List<String>? {
        val token = VndbAuthStore.getToken(context)
        if (token.isBlank()) return null
        return try {
            val (userId, _, permissions) = authInfo(token)
            if (userId.isNotBlank()) VndbAuthStore.savePermissionsIfTokenMatches(context, token, permissions)
            permissions
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** GET /authinfo 共用实现：返回 (userId, username, permissions)。 */
    private fun authInfo(token: String): Triple<String, String, List<String>> {
        VndbCoverService.throttle.await()
        val result = httpJsonResult("$VNDB_ENDPOINT/authinfo", headers = vndbHeaders(token))
        if (result.status !in 200..299) {
            // 校验/读/写三路径 401、403 分档统一（401 = 失效、403 = 权限档，见
            // [vndbAuthInfoStatusToError] 的保守档注释），不再把 403 混同 401 失效
            throw PlayStatusException(vndbAuthInfoStatusToError(result.status), "authinfo status=${result.status}")
        }
        val id = result.json?.optNonBlankString("id").orEmpty()
        if (id.isBlank()) {
            throw PlayStatusException(PlayStatusError.VNDB_NETWORK, "authinfo missing id")
        }
        val permissions = result.json?.optJSONArray("permissions")?.let { array ->
            (0 until array.length()).asSequence()
                .mapNotNull { idx ->
                    if (array.isNull(idx)) null
                    else array.optString(idx, "").trim().takeIf { p -> p.isNotBlank() && p != "null" }
                }
                .toList()
        } ?: emptyList()
        return Triple(id, result.json?.optNonBlankString("username").orEmpty(), permissions)
    }

    // ---------- 读取 ----------

    fun readHikarinagiStatus(context: Context, galgameId: String): HikarinagiStatus {
        val id = galgameId.trim()
        require(id.isNotEmpty()) { "invalid hikarinagi galgame id" }
        val (json, token) = hikarinagiRequestWithToken(
            context,
            "$HIKARINAGI_RATE_GALGAME_BASE${id.urlEncode()}",
            method = "GET",
            treatNotFoundAsNull = true,
            recordAuthError = true,
        )
        return finishHikarinagiStatusRead(context, token, json)
    }

    /**
     * 解析 Hikarinagi 状态读回响应，并仅在已存 access token 仍匹配本次请求令牌时
     * 清理错误标记（含 404 null 分支），防止并发重新登录或登出后的跨号误清。
     */
    internal fun finishHikarinagiStatusRead(
        context: Context,
        token: String,
        json: JSONObject?,
    ): HikarinagiStatus {
        val parsed = if (json == null) HikarinagiStatus(exists = false, status = null) else parseHikarinagiRateStatus(json)
        HikarinagiAuthStore.clearLastErrorIfAccessTokenMatches(context, token)
        HikarinagiAuthStore.clearPermissionErrorIfAccessTokenMatches(context, token)
        return parsed
    }

    fun readNextMoeStatus(context: Context, workId: String): NextMoeStatus {
        val id = workId.trim()
        require(id.isNotEmpty()) { "invalid nextmoe work id" }
        val (json, token) = nextmoeRequestWithToken(
            context,
            "$NEXTMOE_WORK_STATE_BASE${id.urlEncode()}",
            method = "GET",
            treatNotFoundAsNull = true,
            recordAuthError = true,
        )
        return finishNextMoeStatusRead(context, token, json)
    }

    internal fun finishNextMoeStatusRead(
        context: Context,
        token: String,
        json: JSONObject?,
    ): NextMoeStatus {
        val parsed = if (json == null) NextMoeStatus(exists = false, state = null, completion = null) else parseNextMoeWorkState(json)
        NextMoeAuthStore.clearLastErrorIfAccessTokenMatches(context, token)
        NextMoeAuthStore.clearPermissionErrorIfAccessTokenMatches(context, token)
        return parsed
    }

    // Bangumi 状态为只写模型：写入后 UI 乐观更新；不做 GET 回读（`me`/`-` 通配均不可用）。

    fun readVndbStatus(context: Context, vnId: String): VndbStatus {
        val id = vnId.trim()
        require(id.isNotEmpty()) { "invalid vndb vn id" }
        val auth = vndbAuth(context) ?: throw PlayStatusException(PlayStatusError.VNDB_AUTH_MISSING)
        val body = JSONObject()
            .put("user", auth.userId)
            .put("filters", JSONArray().put("id").put("=").put(id))
            .put("fields", "id,labels.id")
            .put("results", 1)
        VndbCoverService.throttle.await()
        val result = httpJsonResult(
            "$VNDB_ENDPOINT/ulist",
            method = "POST",
            body = body.toString(),
            headers = vndbHeaders(auth.token),
        )
        android.util.Log.i(PLAY_STATUS_LOG_TAG, "vndb POST /ulist ($id) -> http ${result.status}")
        if (result.status == 401 || result.status == 403) {
            android.util.Log.w(PLAY_STATUS_LOG_TAG, "vndb ulist read rejected: http ${result.status}")
            // 记录失败原因但保留 token（403 = 权限不足、401 = 失效，均可重新粘贴修复，
            // 不自动清除授权——实测反馈：权限错误不应表现为「解绑」）
            VndbAuthStore.markLastErrorIfTokenMatches(
                context,
                auth.token,
                if (result.status == 403) VndbAuthError.PERMISSION.name else VndbAuthError.TOKEN_INVALID.name,
            )
            throw PlayStatusException(
                if (result.status == 403) PlayStatusError.VNDB_TOKEN_PERMISSION else PlayStatusError.VNDB_TOKEN_INVALID,
            )
        }
        if (result.status !in 200..299) {
            android.util.Log.w(PLAY_STATUS_LOG_TAG, "vndb ulist read failed: http ${result.status}")
            throw PlayStatusException(PlayStatusError.VNDB_NETWORK, "ulist read status=${result.status}")
        }
        // 2xx 非 JSON 体按网络档抛出，不误报「未在列表」；解析收敛到纯函数（JVM 可测）
        return parseVndbUlistStatus(result.json, id).also {
            // 读取成功：清除历史失败标记，设置页恢复「已授权」显示
            VndbAuthStore.markLastErrorIfTokenMatches(context, auth.token, "")
        }
    }

    // ---------- 写入 ----------

    /**
     * Hikarinagi：PUT /v3/user/me/rates/galgames/{id}，body 全可选、只传 status。写失败不回写授权态。
     * 成功侧（2xx）不同步清 lastError（BK-23）：写后开抽屉/回读会经读路径「成功即清」补清
     * （[readHikarinagiStatus]），写路径保持与「写失败不回写授权态」同侧的单侧约束，不触碰授权态。
     */
    fun writeHikarinagiStatus(
        context: Context,
        galgameId: String,
        status: String,
        expectedSessionEpoch: Long? = null,
    ) {
        require(isHikarinagiStatus(status)) { "invalid hikarinagi status: $status" }
        val id = galgameId.trim()
        require(id.isNotEmpty()) { "invalid hikarinagi galgame id" }
        val json = hikarinagiRequest(
            context,
            "$HIKARINAGI_RATE_GALGAME_BASE${id.urlEncode()}",
            method = "PUT",
            body = JSONObject().put("status", status).toString(),
            treatNotFoundAsNull = false,
            recordAuthError = false,
            expectedSessionEpoch = expectedSessionEpoch,
        )
        if (json == null || !json.optBoolean("success", false)) {
            throw PlayStatusException(PlayStatusError.HIKARINAGI_NETWORK, "rate write invalid response")
        }
    }

    /**
     * 未萌 NextMoe：PUT /v2/me/work-states/{work_id}。天然幂等；写失败不回写授权态。
     * 仅在目标状态不为 `wish` 且已有合法 completion 时透传 [preservedCompletion]，避免误清网页端完成度。
     */
    fun writeNextMoeStatus(
        context: Context,
        workId: String,
        state: String,
        preservedCompletion: String? = null,
        expectedSessionEpoch: Long? = null,
    ) {
        require(isNextMoeWorkState(state)) { "invalid nextmoe work state: $state" }
        val id = workId.trim()
        require(id.isNotEmpty()) { "invalid nextmoe work id" }
        nextmoeRequestWithToken(
            context,
            "$NEXTMOE_WORK_STATE_BASE${id.urlEncode()}",
            method = "PUT",
            body = buildNextMoePutWorkStateBody(state, preservedCompletion),
            treatNotFoundAsNull = false,
            recordAuthError = false,
            requireJsonBody = false,
            expectedSessionEpoch = expectedSessionEpoch,
        )
    }

    /**
     * Bangumi：POST 语义为「不存在则创建、存在则修改」，body 全可选、只传 type。
     * 失败侧（BK-05A）：仅 401（服务端明确拒绝）落 needsReauth（统一在 [bangumiRequest] 处理，
     * 与读路径 401 分档对齐），其余失败不回写授权态。成功侧（2xx）不同步清 lastError（BK-23）：
     * Bangumi 为只写模型、无读路径，历史失效标记经下一次令牌刷新成功写回（saveTokens 成功侧
     * 清档）或用户重新授权清除；写路径对授权态保持单侧约束。
     */
    fun writeBangumiStatus(
        context: Context,
        subjectId: String,
        type: Int,
        expectedSessionEpoch: Long? = null,
    ) {
        require(isBangumiCollectType(type)) { "invalid bangumi collect type: $type" }
        // 空绑定 id 前置防御（与 Hikarinagi 写路径同口径）：UI 已按绑定态放行，此处兜底
        val id = subjectId.trim()
        require(id.isNotEmpty()) { "invalid bangumi subject id" }
        bangumiRequest(
            context,
            "$BANGUMI_COLLECTION_BASE${id.urlEncode()}",
            method = "POST",
            body = JSONObject().put("type", type).toString(),
            expectedSessionEpoch = expectedSessionEpoch,
        )
    }

    /**
     * VNDB：PATCH 不在列表时自动加入。用 labels_set/labels_unset 显式保持四态互斥
     * （服务端不强制互斥；不用整包 labels 覆盖以保留自定义标签）。写失败不修改授权状态。
     * 成功侧（2xx）不同步清 lastError（BK-23）：写后开抽屉/回读会经读路径「成功即清」补清
     * （[readVndbStatus]），写路径保持与「写失败不修改授权状态」同侧的单侧约束，不触碰授权态。
     */
    fun writeVndbStatus(
        context: Context,
        vnId: String,
        label: Int,
        expectedSessionEpoch: Long? = null,
    ) {
        require(label in VndbLabels.ALL) { "invalid vndb label: $label" }
        // 空绑定 id 前置防御（与 Hikarinagi 写路径同口径）：UI 已按绑定态放行，此处兜底
        val id = vnId.trim()
        require(id.isNotEmpty()) { "invalid vndb vn id" }
        if (expectedSessionEpoch != null && VndbAuthStore.sessionEpoch.value != expectedSessionEpoch) {
            throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        }
        val auth = vndbAuth(context) ?: throw PlayStatusException(PlayStatusError.VNDB_AUTH_MISSING)
        val body = JSONObject()
            .put("labels_set", JSONArray().put(label))
            .put(
                "labels_unset",
                JSONArray().apply { vndbLabelsToUnset(label).forEach { put(it) } },
            )
        VndbCoverService.throttle.await()
        // 发送前在同一 storeLock 内复核会话代次与读取 token，防止节流等待期间换号/登出导致串写新账号
        val activeToken = if (expectedSessionEpoch != null) {
            VndbAuthStore.readTokenIfSessionMatches(context, expectedSessionEpoch)
                ?: throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        } else {
            auth.token
        }
        val result = httpJsonResult(
            "$VNDB_ENDPOINT/ulist/${id.urlEncode()}",
            method = "PATCH",
            body = body.toString(),
            headers = vndbHeaders(activeToken),
        )
        android.util.Log.i(PLAY_STATUS_LOG_TAG, "vndb PATCH /ulist/$id -> http ${result.status}")
        if (result.status == 401 || result.status == 403) {
            android.util.Log.w(PLAY_STATUS_LOG_TAG, "vndb ulist write rejected: http ${result.status} (vn=$vnId label=$label)")
            // 写入失败不改变授权态，仅抛出错误由 UI 反馈
            throw PlayStatusException(
                if (result.status == 403) PlayStatusError.VNDB_TOKEN_PERMISSION else PlayStatusError.VNDB_TOKEN_INVALID,
            )
        }
        if (result.status !in 200..299) {
            android.util.Log.w(PLAY_STATUS_LOG_TAG, "vndb ulist write failed: http ${result.status} (vn=$vnId label=$label)")
            throw PlayStatusException(PlayStatusError.VNDB_NETWORK, "ulist patch status=${result.status}")
        }
    }

    // ---------- 绑定 ----------

    /**
     * 封面/绑定元数据单行更新包装（幂等口径）：[GameLibraryFacade.updateGameCoverMetadata] 对
     * 「游戏不在库」与「变换后无变化」都返回 null——无变化通常是 UI 快照过期（行内绑定
     * 已是目标态）而非失败，不应被上报为失败。返回 null 时重读当前行并复放同一变换：
     * 结果仍等于当前行则视为幂等成功返回当前行；仅游戏确实不在库时才把 null 交给
     * 调用方按失败处理。
     */
    private fun bindCoverTransform(
        context: Context,
        uri: String,
        transform: (ScanGame) -> ScanGame,
    ): ScanGame? {
        val updated = GameLibraryFacade.updateGameCoverMetadata(context, uri, transform)
        if (updated != null) return updated
        val current = GameLibraryFacade.loadGames(context).firstOrNull { it.uri == uri } ?: return null
        return if (transform(current) == current) current else null
    }

    /**
     * 条目绑定：只写绑定 id 与元数据标题，不动封面。经 [bindCoverTransform] 单行落库
     * （封面列保持原值）。同 id 重复绑定不再早退（BK-44）：携带有效新标题时刷新
     * metadataTitle；无有效变化时 transform 结果等于 current，facade 元数据列比较拦截落库、
     * 按重读结果幂等成功（BK-45 语义），均不报失败。返回 null 仅表示游戏不在库
     * （空绑定 id / 未知来源同样保持 null 语义）。
     */
    fun bindSubject(
        context: Context,
        game: ScanGame,
        source: String,
        subjectId: String,
        title: String?,
    ): ScanGame? {
        val id = subjectId.trim().takeIf { it.isNotBlank() } ?: return null
        if (source != AppSettingsStore.COVER_SOURCE_VNDB &&
            source != AppSettingsStore.COVER_SOURCE_BANGUMI &&
            source != AppSettingsStore.COVER_SOURCE_HIKARINAGI &&
            source != AppSettingsStore.COVER_SOURCE_NEXTMOE
        ) {
            return null
        }
        return bindCoverTransform(context, game.uri) { current ->
            // 来源与 id 已预检，transform 恒非空；?: current 为全量自洽兜底（不可达）
            bindSubjectTransform(source, id, title, current) ?: current
        }
    }

    /**
     * 解绑：清除对应来源的绑定 id；metadataTitle 一律保留（BK-42B 保守保留路线）。
     * 四来源共享的展示标题来源（绑定名或刮削名）未持久化，旧「解绑最后来源时无条件清空」
     * 会误清刮削写入的标题——误清代价（须重新刮削/重绑才能恢复）高于残留绑定名标题的代价
     * （仅展示性文本、无正确性影响），故翻转旧清档行为；残留标题由下次绑定/刮削命中自然覆盖。
     * 本已解绑时 transform 结果等于 current，按重读结果幂等成功（BK-45 语义，不报失败）；
     * 返回 null 仅表示游戏不在库（未知来源保持 null 语义）。
     */
    fun unbindSubject(context: Context, game: ScanGame, source: String): ScanGame? =
        when (source) {
            AppSettingsStore.COVER_SOURCE_VNDB,
            AppSettingsStore.COVER_SOURCE_BANGUMI,
            AppSettingsStore.COVER_SOURCE_HIKARINAGI,
            AppSettingsStore.COVER_SOURCE_NEXTMOE,
            ->
                bindCoverTransform(context, game.uri) { current ->
                    // 来源已预检，transform 恒非空；?: current 为全量自洽兜底（不可达）
                    unbindSubjectTransform(source, current) ?: current
                }
            else -> null
        }

    /**
     * 条目绑定搜索：与封面搜索同一套候选清洗/映射，但不经封面来源启用开关（绑定能力
     * 独立于封面来源开关），且只接受可绑定的来源（VNDB / Bangumi / Hikarinagi / NextMoe）。
     */
    fun searchBindingCandidates(
        context: Context,
        source: String,
        keyword: String,
        limit: Int = 8,
    ): CoverSearchResult {
        if (source != AppSettingsStore.COVER_SOURCE_VNDB &&
            source != AppSettingsStore.COVER_SOURCE_BANGUMI &&
            source != AppSettingsStore.COVER_SOURCE_HIKARINAGI &&
            source != AppSettingsStore.COVER_SOURCE_NEXTMOE
        ) {
            return CoverSearchResult.Failure(CoverErrorCode.INVALID_SOURCE)
        }
        val query = keyword.trim()
        if (query.isBlank()) return CoverSearchResult.Success(emptyList())
        return try {
            CoverSearchResult.Success(
                CoverScraperService.searchCandidatesForBinding(context, source, query, limit.coerceIn(1, 10)),
            )
        } catch (e: CoverSearchException) {
            CoverSearchResult.Failure(e.error)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CoverSearchResult.Failure(CoverErrorCode.UNKNOWN)
        }
    }

    // ---------- 内部 ----------

    private data class VndbAuth(val token: String, val userId: String)

    private fun vndbAuth(context: Context): VndbAuth? {
        val token = VndbAuthStore.getToken(context)
        val userId = VndbAuthStore.getUserId(context)
        if (token.isBlank() || userId.isBlank()) return null
        return VndbAuth(token, userId)
    }

    private fun vndbHeaders(token: String) = mapOf("Authorization" to "Token $token")

    private fun bangumiHeaders(token: String) = mapOf("Authorization" to "Bearer $token")

    /**
     * 带鉴权的 Bangumi 收藏写请求（唯一调用方为写路径，读取期参数随分档整改移除）：
     * 2xx 返回解析后的 JSON（204 等空体返回 null）；非 2xx 经 [bangumiStatusToError]
     * 分档抛出（401 失效 / 403 权限 / 其余网络）。写失败仅对「服务端明确拒绝（401）」
     * 落 needsReauth（BK-05A：与读路径 401 分档对齐，吊销令牌获得失效信号，重新授权/
     * 重贴令牌后自愈），其余失败不回写授权态（与 VNDB / Hikarinagi 写路径一致的单侧约束）。
     */
    private fun bangumiRequest(
        context: Context,
        url: String,
        method: String,
        body: String? = null,
        expectedSessionEpoch: Long? = null,
    ): JSONObject? {
        if (expectedSessionEpoch != null && BangumiAuthStore.sessionEpoch.value != expectedSessionEpoch) {
            throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        }
        if (!BangumiAuthStore.getStatus(context).authorized) {
            throw PlayStatusException(PlayStatusError.BANGUMI_AUTH_MISSING)
        }
        val token = when (val tokenResult = BangumiAuthService.getValidAccessTokenResult(context)) {
            is BangumiAccessTokenResult.Success -> tokenResult.accessToken
            // OAuth 应用凭据未配置：重试刷新无意义，按「需重新授权」档提示而非误报网络档
            // （不落 last_error，仅本次请求文案分档）
            is BangumiAccessTokenResult.ConfigMissing -> {
                if (expectedSessionEpoch != null && BangumiAuthStore.sessionEpoch.value != expectedSessionEpoch) {
                    throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
                }
                throw PlayStatusException(
                    bangumiTokenRefreshFailureToError(needsReauth = false, configMissing = true),
                )
            }
            // 刷新失败分档：needsReauth 已置位（刷新被 400/401 明确拒绝）才是令牌失效；
            // 传输失败按网络档提示，不再诱导用户重新授权
            is BangumiAccessTokenResult.RefreshFailed -> {
                if (expectedSessionEpoch != null && BangumiAuthStore.sessionEpoch.value != expectedSessionEpoch) {
                    throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
                }
                throw PlayStatusException(
                    bangumiTokenRefreshFailureToError(
                        needsReauth = BangumiAuthStore.getStatus(context).needsReauth,
                    ),
                )
            }
        }
        bangumiThrottle.await()
        // 发送前在同一 storeLock 内复核会话代次与读取 token，防止刷新或节流等待期间换号/登出导致串写新账号
        val activeToken = if (expectedSessionEpoch != null) {
            BangumiAuthStore.readAccessTokenIfSessionMatches(context, expectedSessionEpoch)
                ?: throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        } else {
            token
        }
        val result = httpJsonResult(url, method = method, body = body, headers = bangumiHeaders(activeToken))
        android.util.Log.i(PLAY_STATUS_LOG_TAG, "bangumi $method ${url.substringAfter("/v0")} -> http ${result.status}")
        if (result.status !in 200..299) {
            android.util.Log.w(PLAY_STATUS_LOG_TAG, "bangumi $method rejected: http ${result.status}")
            // BK-05A：写路径 401 落 needsReauth（EXPIRED）——服务端明确拒绝即吊销/失效信号，
            // 与读路径 401 分档对齐，被吊销令牌不再永远显示「已授权」；置位后后续写经刷新短路
            // （refreshAccessToken 的 needsReauth 短路）直接按令牌失效分档报错，自愈途径 =
            // 重新授权 / 重贴令牌（成功写回即清 last_error）。403 维持只抛错不落档（Bangumi 无
            // 独立权限位，bangumiStatusToError 分档语义不动）；其余失败不回写授权态
            bangumiWriteNeedsReauthReason(result.status)?.let {
                BangumiAuthStore.markNeedsReauthIfAccessTokenMatches(context, activeToken, it)
            }
            throw PlayStatusException(bangumiStatusToError(result.status), "status=${result.status}")
        }
        return result.json
    }

    /**
     * 带鉴权的 Hikarinagi 请求：401/403 区分失效与缺权限（写入路径不修改授权态）——
     * 读取路径 401 置位令牌失效（needsReauth）、403 置位独立权限缺失位（二者不共用一个
     * 状态位，否则 403 会与刷新短路交互、锁死令牌过期的自动刷新自愈路径）；
     * 读取路径可将 404 归一为 null；2xx 非 JSON 响应不得被误判为成功或未标记。
     */
    private fun hikarinagiRequest(
        context: Context,
        url: String,
        method: String,
        body: String? = null,
        treatNotFoundAsNull: Boolean = false,
        recordAuthError: Boolean = true,
        expectedSessionEpoch: Long? = null,
    ): JSONObject? = hikarinagiRequestWithToken(
        context = context,
        url = url,
        method = method,
        body = body,
        treatNotFoundAsNull = treatNotFoundAsNull,
        recordAuthError = recordAuthError,
        expectedSessionEpoch = expectedSessionEpoch,
    ).first

    private fun hikarinagiRequestWithToken(
        context: Context,
        url: String,
        method: String,
        body: String? = null,
        treatNotFoundAsNull: Boolean = false,
        recordAuthError: Boolean = true,
        expectedSessionEpoch: Long? = null,
    ): Pair<JSONObject?, String> {
        if (expectedSessionEpoch != null && HikarinagiAuthStore.sessionEpoch.value != expectedSessionEpoch) {
            throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        }
        if (!HikarinagiAuthStore.getStatus(context).authorized) {
            throw PlayStatusException(PlayStatusError.HIKARINAGI_AUTH_MISSING)
        }
        val token = when (val tokenResult = HikarinagiAuthService.getValidAccessTokenResult(context)) {
            is HikarinagiAccessTokenResult.Success -> tokenResult.accessToken
            // OAuth 应用凭据未配置：重试刷新无意义，按「需重新授权」档提示而非误报网络档
            // （不落 last_error，仅本次请求文案分档）
            is HikarinagiAccessTokenResult.ConfigMissing -> {
                if (expectedSessionEpoch != null && HikarinagiAuthStore.sessionEpoch.value != expectedSessionEpoch) {
                    throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
                }
                throw PlayStatusException(
                    hikarinagiTokenRefreshFailureToError(needsReauth = false, configMissing = true),
                )
            }
            // 刷新失败分档：needsReauth 已置位（刷新被 400/401 明确拒绝）才是令牌失效；
            // 传输失败按网络档提示，不再诱导用户重新授权
            is HikarinagiAccessTokenResult.RefreshFailed -> {
                if (expectedSessionEpoch != null && HikarinagiAuthStore.sessionEpoch.value != expectedSessionEpoch) {
                    throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
                }
                throw PlayStatusException(
                    hikarinagiTokenRefreshFailureToError(
                        needsReauth = HikarinagiAuthStore.getStatus(context).needsReauth,
                    ),
                )
            }
        }
        hikarinagiThrottle.await()
        // 发送前在同一 storeLock 内复核会话代次与读取 token，防止刷新或节流等待期间换号/登出导致串写新账号
        val activeToken = if (expectedSessionEpoch != null) {
            HikarinagiAuthStore.readAccessTokenIfSessionMatches(context, expectedSessionEpoch)
                ?: throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        } else {
            token
        }
        val result = httpJsonResult(
            url,
            method = method,
            body = body,
            headers = mapOf("Authorization" to "Bearer $activeToken"),
        )
        android.util.Log.i(PLAY_STATUS_LOG_TAG, "hikarinagi $method ${url.substringAfter("/v3")} -> http ${result.status}")
        if (result.status == 401) {
            android.util.Log.w(PLAY_STATUS_LOG_TAG, "hikarinagi token rejected: http 401")
            if (recordAuthError) {
                HikarinagiAuthStore.markNeedsReauthIfAccessTokenMatches(context, activeToken, HikarinagiAuthError.EXPIRED.name)
            }
            throw PlayStatusException(PlayStatusError.HIKARINAGI_TOKEN_EXPIRED)
        }
        if (result.status == 403) {
            android.util.Log.w(PLAY_STATUS_LOG_TAG, "hikarinagi forbidden: http 403")
            if (recordAuthError) {
                // 权限缺失与令牌失效分位：403 = 令牌有效但权限不足，写入独立
                // permission_error 位，不写 needsReauth/last_error —— 否则会与刷新短路
                // （HikarinagiAuthService.refreshAccessToken）交互，把 access token 过期后的
                // 自动刷新自愈路径永久锁死。令牌失效（401）仍走 markNeedsReauth(EXPIRED)。
                HikarinagiAuthStore.markPermissionDeniedIfAccessTokenMatches(context, activeToken, HikarinagiAuthError.PERMISSION.name)
            }
            throw PlayStatusException(PlayStatusError.HIKARINAGI_TOKEN_PERMISSION)
        }
        if (result.status == 404 && treatNotFoundAsNull) return null to activeToken
        if (result.status !in 200..299) {
            throw PlayStatusException(hikarinagiNonSuccessToError(result.status), "status=${result.status}")
        }
        if (result.json == null) {
            throw PlayStatusException(PlayStatusError.HIKARINAGI_NETWORK, "non-json body")
        }
        return result.json to activeToken
    }

    private fun nextmoeRequestWithToken(
        context: Context,
        url: String,
        method: String,
        body: String? = null,
        treatNotFoundAsNull: Boolean = false,
        recordAuthError: Boolean = true,
        requireJsonBody: Boolean = true,
        expectedSessionEpoch: Long? = null,
    ): Pair<JSONObject?, String> {
        if (expectedSessionEpoch != null && NextMoeAuthStore.sessionEpoch.value != expectedSessionEpoch) {
            throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        }
        if (!NextMoeAuthStore.getStatus(context).authorized) {
            throw PlayStatusException(PlayStatusError.NEXTMOE_AUTH_MISSING)
        }
        val token = when (val tokenResult = NextMoeAuthService.getValidAccessTokenResult(context)) {
            is NextMoeAccessTokenResult.Success -> tokenResult.accessToken
            is NextMoeAccessTokenResult.ConfigMissing -> {
                if (expectedSessionEpoch != null && NextMoeAuthStore.sessionEpoch.value != expectedSessionEpoch) {
                    throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
                }
                throw PlayStatusException(
                    nextMoeTokenRefreshFailureToError(needsReauth = false, configMissing = true),
                )
            }
            is NextMoeAccessTokenResult.RefreshFailed -> {
                if (expectedSessionEpoch != null && NextMoeAuthStore.sessionEpoch.value != expectedSessionEpoch) {
                    throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
                }
                throw PlayStatusException(
                    nextMoeTokenRefreshFailureToError(
                        needsReauth = NextMoeAuthStore.getStatus(context).needsReauth,
                    ),
                )
            }
        }
        nextmoeThrottle.await()
        val activeToken = if (expectedSessionEpoch != null) {
            NextMoeAuthStore.readAccessTokenIfSessionMatches(context, expectedSessionEpoch)
                ?: throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        } else {
            token
        }
        val result = httpJsonResult(
            url,
            method = method,
            body = body,
            headers = mapOf("Authorization" to "Bearer $activeToken"),
        )
        android.util.Log.i(PLAY_STATUS_LOG_TAG, "nextmoe $method ${url.substringAfter("/v2")} -> http ${result.status}")
        if (result.status == 401) {
            android.util.Log.w(PLAY_STATUS_LOG_TAG, "nextmoe token rejected: http 401")
            if (recordAuthError) {
                NextMoeAuthStore.markNeedsReauthIfAccessTokenMatches(context, activeToken, NextMoeAuthError.EXPIRED.name)
            }
            throw PlayStatusException(PlayStatusError.NEXTMOE_TOKEN_EXPIRED)
        }
        if (result.status == 403) {
            android.util.Log.w(PLAY_STATUS_LOG_TAG, "nextmoe forbidden: http 403")
            if (recordAuthError) {
                NextMoeAuthStore.markPermissionDeniedIfAccessTokenMatches(context, activeToken, NextMoeAuthError.PERMISSION.name)
            }
            throw PlayStatusException(PlayStatusError.NEXTMOE_TOKEN_PERMISSION)
        }
        val problemCode = result.json?.optNonBlankString("code")
        // GET /v2/me/work-states/{work_id} returns 404 JSON NOT_FOUND when the user has never set a state.
        // Non-JSON 404 (CDN/proxy error) or 404 ENTITY_MERGED / ENTITY_DELETED must NOT be misread as "unmarked".
        if (treatNotFoundAsNull && isNextMoeNotFoundUnmarked(result.status, result.json, problemCode)) {
            return null to activeToken
        }
        if (result.status !in 200..299) {
            throw PlayStatusException(nextMoeNonSuccessToError(result.status, problemCode), "status=${result.status} code=$problemCode")
        }
        if (requireJsonBody && result.json == null) {
            throw PlayStatusException(PlayStatusError.NEXTMOE_NETWORK, "non-json body")
        }
        return result.json to activeToken
    }
}
