package com.tyranor.next.core.status

import android.content.Context
import android.util.Log
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
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.auth.VndbAuthError
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.auth.isBangumiIdentityResolved
import com.tyranor.next.core.cover.CoverErrorCode
import com.tyranor.next.core.cover.CoverSearchException
import com.tyranor.next.core.cover.CoverSearchResult
import com.tyranor.next.core.cover.CoverScraperService
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.net.RequestThrottle
import com.tyranor.next.core.net.httpJsonResult
import com.tyranor.next.core.net.optNonBlankString
import com.tyranor.next.core.net.urlEncodePathSegment
import com.tyranor.next.core.net.vndbThrottle
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
 * 纯映射（JVM 可测）：Bangumi 写路径非 2xx → 错误分档（LOGIC-3，与 Hikarinagi / NextMoe
 * 同族对齐）。404 且响应体为 JSON（官方错误体）= 条目不存在/已删除，分档
 * [PlayStatusError.BANGUMI_SUBJECT_NOT_FOUND] 提示重绑，不再误报网络档引发无效重试；
 * 非 JSON 404（CDN/反代 HTML 页）与其余非 2xx 维持网络档。读路径的 404 JSON 体已在
 * [readBangumiStatus] 前置归一「未收藏」，不会走到本映射。
 */
internal fun bangumiWriteStatusToError(status: Int, hasJsonBody: Boolean): PlayStatusError =
    if (status == 404 && hasJsonBody) PlayStatusError.BANGUMI_SUBJECT_NOT_FOUND else bangumiStatusToError(status)

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
 * 纯映射（JVM 可测）：Bangumi 状态读回路径的用户名解析。
 * `GET /v0/users/{username}/collections/{subject_id}` 的 username 参数不接受 `me`/`-` 通配
 * （官方 spec：「设置了用户名之后无法使用 UID」），优先使用已持久化用户名；缺失时以数字
 * uid 兜底（与 [BangumiAuthStore] 的 BK-19 身份兜底同口径——OAuth/个人令牌落库时 username
 * 已含兜底形态，此处对旧版本/异常清空再兜一道）。二者皆无 = 授权身份未解析，返回 null
 * 交由调用方按未授权档抛出，不发送空用户名请求。
 *
 * M2 整改边界：数字 uid 兜底形态仅在「身份已解析且确认无用户名」或「读回前置回查成功」时
 * 可作为路径参数使用；身份未解析（uid 占位且 /v0/me 回查失败）时调用方不得以 uid 发起读回，
 * 须按 [PlayStatusError.BANGUMI_IDENTITY_UNRESOLVED] 挂起（见 [readBangumiStatus]），
 * 否则对设有用户名的账号会恒 404 并被误归一「未收藏」。
 */
internal fun resolveBangumiReadUsername(username: String, userId: Long): String? {
    val resolved = username.trim()
    if (resolved.isNotBlank()) return resolved
    return userId.takeIf { it > 0L }?.toString()
}

/**
 * 纯映射（JVM 可测）：Bangumi 单条收藏 GET 2xx 响应体 → 状态。
 * `UserSubjectCollection.type`（SubjectCollectionType，游戏条目取值 1..5）缺失、为 null 或
 * 超出词表时归一为 `type = null`（展示层映射「状态未知」）；2xx 但响应体非 JSON（网关错误页 /
 * 截断响应）按网络档抛出——不得误报「未收藏」（与 VNDB ulist 2xx 非 JSON 的保守口径一致）。
 */
internal fun parseBangumiCollectionStatus(json: JSONObject?): PlayStatusService.BangumiStatus {
    val payload = json
        ?: throw PlayStatusException(PlayStatusError.BANGUMI_NETWORK, "collection read non-json body")
    val type = payload.optInt("type", 0).takeIf { isBangumiCollectType(it) }
    return PlayStatusService.BangumiStatus(exists = true, type = type)
}

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
            .mapNotNull { index ->
                val obj = labels.optJSONObject(index) ?: return@mapNotNull null
                if (obj.isNull("id")) null else obj.optInt("id", -1).takeIf { id -> id > 0 }
            }
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
internal fun hikarinagiNonSuccessToError(status: Int, hasJsonBody: Boolean = true): PlayStatusError = when (status) {
    // 非 JSON 404（CDN/反代 HTML 页）归为网络故障，不得误报条目不存在
    404 -> if (hasJsonBody) PlayStatusError.HIKARINAGI_SUBJECT_NOT_FOUND else PlayStatusError.HIKARINAGI_NETWORK
    else -> PlayStatusError.HIKARINAGI_NETWORK
}

/**
 * 同 [bangumiTokenRefreshFailureToError] 的未萌 NextMoe 侧分档（消费
 * `NextMoeAccessTokenResult.RefreshFailed`/`ConfigMissing`，语义与 Bangumi/Hikarinagi 侧一致）。
 */
internal fun nextMoeTokenRefreshFailureToError(
    needsReauth: Boolean,
    configMissing: Boolean = false,
): PlayStatusError =
    if (needsReauth || configMissing) PlayStatusError.NEXTMOE_TOKEN_EXPIRED else PlayStatusError.NEXTMOE_NETWORK

/** 纯映射（JVM 可测）：未萌 NextMoe 作品状态合法性。 */
internal fun isNextMoeWorkState(state: String?): Boolean = state in NextMoeWorkStates.ALL

/**
 * 纯映射（JVM 可测）：未萌 NextMoe 非 2xx HTTP 状态码 → 类型化错误分档（BK-39 同族）。
 * 429 与 `RATE_LIMITED`/`QUOTA_EXCEEDED` 问题码独立限流档；404 且带 JSON 体 / 410 /
 * `ENTITY_MERGED`/`ENTITY_DELETED` 问题码 = 条目不存在、已合并或已删除，分档提示重绑，
 * 不与网络/服务故障混同；非 JSON 404（CDN/反代 HTML 页）与其余非 2xx 回退网络档。
 */
internal fun nextMoeNonSuccessToError(
    status: Int,
    problemCode: String?,
    hasJsonBody: Boolean = true,
): PlayStatusError = when {
    status == 429 || problemCode == "RATE_LIMITED" || problemCode == "QUOTA_EXCEEDED" ->
        PlayStatusError.NEXTMOE_RATE_LIMITED
    // 非 JSON 404（CDN/反代 HTML 页）归为网络故障，不得误报条目不存在
    status == 404 ->
        if (hasJsonBody) PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND else PlayStatusError.NEXTMOE_NETWORK
    status == 410 || problemCode == "ENTITY_MERGED" || problemCode == "ENTITY_DELETED" ->
        PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND
    else -> PlayStatusError.NEXTMOE_NETWORK
}

internal fun isNextMoeNotFoundUnmarked(status: Int, json: JSONObject?, problemCode: String?): Boolean =
    status == 404 && json != null && problemCode == "NOT_FOUND"

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
    val id = subjectId.trim().takeIf { it.isNotBlank() && it != "." && it != ".." } ?: return null
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
    // 读回基址：GET /v0/users/{username}/collections/{subject_id} 的 username 参数不接受
    // `me`/`-` 通配，且官方 spec 明确「设置了用户名之后无法使用 UID」（与
    // [resolveBangumiReadUsername] KDoc 引文一致）——数字 uid 仅是「未设用户名账号」的
    // 唯一合法形态，身份未解析时不得以 uid 发起读回（M2 整改：读回前置身份门，见
    // [readBangumiStatus]），与写路径 `-` 分开
    private const val BANGUMI_USER_COLLECTION_READ_BASE = "https://api.bgm.tv/v0/users/"
    private const val VNDB_ENDPOINT = "https://api.vndb.org/kana"

    private val BINDABLE_SOURCES = setOf(
        AppSettingsStore.COVER_SOURCE_VNDB,
        AppSettingsStore.COVER_SOURCE_BANGUMI,
        AppSettingsStore.COVER_SOURCE_HIKARINAGI,
        AppSettingsStore.COVER_SOURCE_NEXTMOE,
    )

    private sealed interface OAuthTokenAttempt {
        data class Success(val token: String) : OAuthTokenAttempt
        data object ConfigMissing : OAuthTokenAttempt
        data object RefreshFailed : OAuthTokenAttempt
    }

    /** Hikarinagi Open API 每应用每分钟 60 次限流：状态读写串行温和节流。 */
    private val hikarinagiThrottle = RequestThrottle(1100L)

    /** NextMoe 用户令牌限流 100 次/分钟（600ms/次）：取 700ms 间隔留少量余量。 */
    private val nextMoeThrottle = RequestThrottle(700L)

    /** Bangumi 无公开硬限流，但状态读写比搜索低频得多：独立串行节流保持温和。 */
    private val bangumiThrottle = RequestThrottle(1100L)

    data class HikarinagiStatus(
        /** false 表示远端尚未记录该条目（GET 返回 200 且 data 为 null）。 */
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
        /**
         * false 表示远端尚未收藏该条目（GET 单条收藏 404）；官方 spec 对「用户不存在 /
         * 条目未收藏 / 条目为私有收藏」统一返回 404，无法细分，应用内一并归一为未收藏。
         */
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
        val snapshot = VndbAuthStore.readAuthSnapshot(context)
        val token = snapshot.token
        if (token.isBlank() ||
            snapshot.lastError == SecureAuthStore.SECURE_STORAGE_ERROR ||
            snapshot.lastError == VndbAuthError.TOKEN_INVALID.name
        ) {
            return null
        }
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
        vndbThrottle.await()
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
            "$HIKARINAGI_RATE_GALGAME_BASE${id.urlEncodePathSegment()}",
            method = "GET",
            recordAuthError = true,
        )
        return finishHikarinagiStatusRead(context, token, json)
    }

    /**
     * 解析 Hikarinagi 状态读回响应，并仅在已存 access token 仍匹配本次请求令牌时
     * 清理错误标记，防止并发重新登录或登出后的跨号误清。
     */
    internal fun finishHikarinagiStatusRead(
        context: Context,
        token: String,
        json: JSONObject,
    ): HikarinagiStatus {
        val parsed = parseHikarinagiRateStatus(json)
        HikarinagiAuthStore.clearLastErrorIfAccessTokenMatches(context, token)
        HikarinagiAuthStore.clearPermissionErrorIfAccessTokenMatches(context, token)
        return parsed
    }

    fun readNextMoeStatus(context: Context, workId: String): NextMoeStatus {
        val id = workId.trim()
        require(id.isNotEmpty()) { "invalid nextmoe work id" }
        val (json, token) = nextMoeRequestWithToken(
            context,
            "$NEXTMOE_WORK_STATE_BASE${id.urlEncodePathSegment()}",
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

    /**
     * Bangumi 单条收藏读回：`GET /v0/users/{username}/collections/{subject_id}`（Authorization
     * 头携带已持久化令牌）。username 取授权时持久化的账号名（见 [resolveBangumiReadUsername]）；
     * 404（JSON 错误体）= 未收藏（独立状态而非错误，官方 spec 对未收藏/私有收藏/用户不存在
     * 统一 404）；非 JSON 404（CDN/反代 HTML 页）与传输失败按网络档抛出，不误报未收藏。
     *
     * M2 整改（读回前置身份门）：身份未解析（username 为数字 uid 占位且 /v0/me 回查失败）
     * 时按 [PlayStatusError.BANGUMI_IDENTITY_UNRESOLVED] 挂起本次读回——不得以 uid 占位发起
     * 读回（对设有用户名的账号恒 404），也不得把该 404 归一「未收藏」+ 双清（假自愈）；
     * 数字 uid 仅保留给「回查确认未设用户名」的账号（此时 uid 是唯一合法路径形态）。
     *
     * 自愈语义（与 VNDB/Hikarinagi/NextMoe 读路径先例对齐，BK-23「写成功不清」的对偶）：
     * - 读 2xx（含 404 未收藏）→ [finishBangumiStatusRead] 双清 lastError 与 permission_error
     *   （仅当已存 access token 仍匹配本次请求令牌，防跨号误清）；
     * - 读 401 → 置位 needsReauth（EXPIRED，与写路径 BK-05A 同款同值短路）；此后读/写取令牌
     *   均经刷新短路（`BangumiAuthService.refreshAccessToken` 的 needsReauth 短路）按令牌失效
     *   分档报错——「读成功清档」在 needsReauth 已置位期间不可达，常规自愈 = 重新授权 / 重贴
     *   令牌（saveTokens 成功侧清档）；清档实际覆盖的是「标记置位后令牌仍可用」的竞态窗口
     *   （如写 401 置位后在途读回 2xx 证明令牌存活）与权限位（读 403 置位后权限恢复或重新
     *   授权，读 2xx 即双清）。
     * - 读 403 → 置位独立 permission_error 位（R9-A 分位语义：不写 needsReauth，不锁刷新
     *   短路；对照 [readHikarinagiStatus] / [readNextMoeStatus]）。抽屉摘要门
     *   （`resolvePlayStatusGates` 的 authorized && !needsReauth）不排权限位：权限缺失时
     *   Bangumi 段仍参与摘要并以错误文案即时展示（不入 TTL 缓存），与 Hikarinagi/NextMoe 一致。
     */
    fun readBangumiStatus(context: Context, subjectId: String): BangumiStatus {
        val id = subjectId.trim()
        require(id.isNotEmpty()) { "invalid bangumi subject id" }
        val userId = BangumiAuthStore.getUserId(context)
        val storedUsername = BangumiAuthStore.getUsername(context)
        // M2 整改（读回前置身份门）：身份是否解析以库内 username 形态判定（真实用户名 = 已
        // 解析；数字 uid 占位/空白 = 未解析，通常因授权完成时 /v0/me 身份回查一次性失败）。
        // 未解析且有可解析身份（username 或 uid）时先补一次身份回查（尽力而为，失败可重试）；
        // 回查成功 → 落库真实用户名（或确认「未设用户名账号」的 uid 唯一合法形态），按正常
        // 语义读回。身份完全无从解析（无 username 且无 uid）时不发网络请求，交由下方
        // 未授权档前置拒绝（既有口径不变）。已解析身份不再回查，needsReauth 等授权态分档
        // 不受本门影响（按既有令牌分档报错）。
        var identityResolved = isBangumiIdentityResolved(storedUsername, userId)
        if (!identityResolved && (storedUsername.isNotBlank() || userId > 0L)) {
            identityResolved = BangumiAuthService.ensureIdentityLoadedForResult(context)
        }
        val username = resolveBangumiReadUsername(
            BangumiAuthStore.getUsername(context),
            userId,
        ) ?: throw PlayStatusException(PlayStatusError.BANGUMI_AUTH_MISSING)
        if (!identityResolved) {
            // 身份仍未解析：不得以 uid 占位发起读回——对设有用户名的账号会按本仓自引的官方
            // spec 恒 404，并被下方归一逻辑误报「未收藏」+ 双清（假自愈）。按明确的「身份
            // 未解析」档挂起本次读回（不触碰授权态、下次读回可重试），消除假自愈。
            throw PlayStatusException(
                PlayStatusError.BANGUMI_IDENTITY_UNRESOLVED,
                "bangumi identity unresolved (uid placeholder)",
            )
        }
        val (json, token) = bangumiRequestWithToken(
            context,
            "$BANGUMI_USER_COLLECTION_READ_BASE${username.urlEncodePathSegment()}/collections/${id.urlEncodePathSegment()}",
            method = "GET",
            treatJsonNotFoundAsNull = true,
            requireJsonBody = true,
            recordPermissionError = true,
        )
        return finishBangumiStatusRead(context, token, json)
    }

    /**
     * 解析 Bangumi 状态读回响应，并仅在已存 access token 仍匹配本次请求令牌时清理错误标记，
     * 防止并发重新登录或登出后的跨号误清（与 [finishHikarinagiStatusRead] / [finishNextMoeStatusRead]
     * 同构）。`json == null`（GET 404 JSON 错误体归一）→ 未收藏，同样算读成功并双清。
     */
    internal fun finishBangumiStatusRead(
        context: Context,
        token: String,
        json: JSONObject?,
    ): BangumiStatus {
        val parsed = if (json == null) BangumiStatus(exists = false, type = null) else parseBangumiCollectionStatus(json)
        BangumiAuthStore.clearLastErrorIfAccessTokenMatches(context, token)
        BangumiAuthStore.clearPermissionErrorIfAccessTokenMatches(context, token)
        return parsed
    }

    fun readVndbStatus(context: Context, vnId: String): VndbStatus {
        val id = vnId.trim()
        require(id.isNotEmpty()) { "invalid vndb vn id" }
        val auth = vndbAuth(context) ?: throw PlayStatusException(PlayStatusError.VNDB_AUTH_MISSING)
        vndbPreRequestBlockedError(auth.lastError)?.let { throw PlayStatusException(it) }
        val body = JSONObject()
            .put("user", auth.userId)
            .put("filters", JSONArray().put("id").put("=").put(id))
            .put("fields", "id,labels.id")
            .put("results", 1)
        vndbThrottle.await()
        val result = httpJsonResult(
            "$VNDB_ENDPOINT/ulist",
            method = "POST",
            body = body.toString(),
            headers = vndbHeaders(auth.token),
        )
        logInfo("vndb POST /ulist ($id) -> http ${result.status}")
        if (result.status == 401 || result.status == 403) {
            logWarn("vndb ulist read rejected: http ${result.status}")
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
            logWarn("vndb ulist read failed: http ${result.status}")
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
            "$HIKARINAGI_RATE_GALGAME_BASE${id.urlEncodePathSegment()}",
            method = "PUT",
            body = JSONObject().put("status", status).toString(),
            recordAuthError = false,
            expectedSessionEpoch = expectedSessionEpoch,
        )
        if (!json.optBoolean("success", false)) {
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
        nextMoeRequestWithToken(
            context,
            "$NEXTMOE_WORK_STATE_BASE${id.urlEncodePathSegment()}",
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
     * 与读路径 401 分档对齐），403 只抛错不落权限位，其余失败不回写授权态。成功侧（2xx）
     * 不同步清 lastError（BK-23）：写后开弹窗/抽屉会经读回路径「成功即清」补清
     * （[readBangumiStatus]），写路径对授权态保持单侧约束。
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
            "$BANGUMI_COLLECTION_BASE${id.urlEncodePathSegment()}",
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
        vndbPreRequestBlockedError(auth.lastError)?.let { throw PlayStatusException(it) }
        val body = JSONObject()
            .put("labels_set", JSONArray().put(label))
            .put(
                "labels_unset",
                JSONArray().apply { vndbLabelsToUnset(label).forEach { put(it) } },
            )
        vndbThrottle.await()
        // 发送前在同一 storeLock 内复核会话代次与读取 token，防止节流等待期间换号/登出导致串写新账号
        val activeToken = if (expectedSessionEpoch != null) {
            VndbAuthStore.readTokenIfSessionMatches(context, expectedSessionEpoch)
                ?: throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        } else {
            auth.token
        }
        val result = httpJsonResult(
            "$VNDB_ENDPOINT/ulist/${id.urlEncodePathSegment()}",
            method = "PATCH",
            body = body.toString(),
            headers = vndbHeaders(activeToken),
        )
        logInfo("vndb PATCH /ulist/$id -> http ${result.status}")
        if (result.status == 401 || result.status == 403) {
            logWarn("vndb ulist write rejected: http ${result.status} (vn=$vnId label=$label)")
            // 写入失败不改变授权态，仅抛出错误由 UI 反馈
            throw PlayStatusException(
                if (result.status == 403) PlayStatusError.VNDB_TOKEN_PERMISSION else PlayStatusError.VNDB_TOKEN_INVALID,
            )
        }
        if (result.status !in 200..299) {
            logWarn("vndb ulist write failed: http ${result.status} (vn=$vnId label=$label)")
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
        if (updated != null) {
            // 通知游戏库变更：即使用户旋转屏幕取消了 UI 协程，MainLibraryViewModel 也会从 Room 同步最新绑定字段
            GameLibraryFacade.notifyGamesChanged()
            return updated
        }
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
        val id = subjectId.trim().takeIf { it.isNotBlank() && it != "." && it != ".." } ?: return null
        if (source !in BINDABLE_SOURCES) return null
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
    fun unbindSubject(context: Context, game: ScanGame, source: String): ScanGame? {
        if (source !in BINDABLE_SOURCES) return null
        return bindCoverTransform(context, game.uri) { current ->
            // 来源已预检，transform 恒非空；?: current 为全量自洽兜底（不可达）
            unbindSubjectTransform(source, current) ?: current
        }
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
        if (source !in BINDABLE_SOURCES) {
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

    private data class VndbAuth(val token: String, val userId: String, val lastError: String)

    private fun vndbAuth(context: Context): VndbAuth? {
        val snapshot = VndbAuthStore.readAuthSnapshot(context)
        if (snapshot.token.isBlank() || snapshot.userId.isBlank()) return null
        return VndbAuth(snapshot.token, snapshot.userId, snapshot.lastError)
    }

    private fun vndbPreRequestBlockedError(lastError: String): PlayStatusError? = when (lastError) {
        "",
        // PERMISSION 是唯一允许直接重试的路径：用户可在 VNDB 网页端补勾 listread/listwrite 后免换 token 自愈
        VndbAuthError.PERMISSION.name -> null
        else -> PlayStatusError.VNDB_TOKEN_INVALID
    }

    private fun vndbHeaders(token: String) = mapOf("Authorization" to "Token $token")

    private fun bangumiHeaders(token: String) = mapOf("Authorization" to "Bearer $token")

    /**
     * Bangumi / Hikarinagi / NextMoe 三端 OAuth 请求的共用前置取令牌模板：
     * 校验会话代次 → 校验授权状态 → 获取可用访问令牌（ConfigMissing / RefreshFailed 映射错误码）
     * → 节流排队 → 发送前同一 storeLock 内二次核对会话代次并读取最终 token。
     */
    private inline fun resolveOAuthRequestToken(
        expectedSessionEpoch: Long?,
        currentSessionEpoch: () -> Long,
        isAuthorized: () -> Boolean,
        authMissingError: PlayStatusError,
        acquireToken: () -> OAuthTokenAttempt,
        isNeedsReauth: () -> Boolean,
        mapRefreshFailure: (needsReauth: Boolean, configMissing: Boolean) -> PlayStatusError,
        throttle: RequestThrottle,
        readTokenIfSessionMatches: (Long) -> String?,
    ): String {
        if (expectedSessionEpoch != null && currentSessionEpoch() != expectedSessionEpoch) {
            throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        }
        if (!isAuthorized()) {
            throw PlayStatusException(authMissingError)
        }
        val token = when (val attempt = acquireToken()) {
            is OAuthTokenAttempt.Success -> attempt.token
            OAuthTokenAttempt.ConfigMissing -> {
                if (expectedSessionEpoch != null && currentSessionEpoch() != expectedSessionEpoch) {
                    throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
                }
                if (expectedSessionEpoch == null && !isAuthorized()) {
                    throw PlayStatusException(authMissingError)
                }
                throw PlayStatusException(mapRefreshFailure(false, true))
            }
            OAuthTokenAttempt.RefreshFailed -> {
                if (expectedSessionEpoch != null && currentSessionEpoch() != expectedSessionEpoch) {
                    throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
                }
                if (expectedSessionEpoch == null && !isAuthorized()) {
                    throw PlayStatusException(authMissingError)
                }
                throw PlayStatusException(mapRefreshFailure(isNeedsReauth(), false))
            }
        }
        throttle.await()
        return if (expectedSessionEpoch != null) {
            readTokenIfSessionMatches(expectedSessionEpoch)
                ?: throw PlayStatusException(PlayStatusError.AUTH_SESSION_CHANGED)
        } else {
            token
        }
    }

    /**
     * 带鉴权的 Bangumi 收藏写请求：2xx 返回解析后的 JSON（204 等空体返回 null）；非 2xx 经
     * [bangumiStatusToError] 分档抛出（401 失效 / 403 权限 / 其余网络）。写失败仅对「服务端
     * 明确拒绝（401）」落 needsReauth（BK-05A：与读路径 401 分档对齐，吊销令牌获得失效信号，
     * 重新授权/重贴令牌后自愈），403 维持只抛错不落档（写路径不动权限位，与读路径分档区分，
     * 见 [bangumiRequestWithToken]），其余失败不回写授权态（与 VNDB / Hikarinagi 写路径一致的
     * 单侧约束）。成功侧（2xx）不同步清 lastError（BK-23）：写后开弹窗/抽屉的读回
     * （[readBangumiStatus]）成功即双清补清——读回路径就位前注释所称「无读路径」的缺口已闭合。
     */
    private fun bangumiRequest(
        context: Context,
        url: String,
        method: String,
        body: String? = null,
        expectedSessionEpoch: Long? = null,
    ): JSONObject? = bangumiRequestWithToken(
        context = context,
        url = url,
        method = method,
        body = body,
        expectedSessionEpoch = expectedSessionEpoch,
    ).first

    /**
     * 带鉴权的 Bangumi 请求共用实现（写路径 [bangumiRequest] 与读路径 [readBangumiStatus] 共用）：
     * 令牌解析（会话代次 → 授权校验 → 取可用访问令牌 → 节流排队 → 发送前复核）与错误分档统一。
     * - 401：读/写路径均置位 needsReauth（BK-05A）；
     * - 403：仅 [recordPermissionError]（读路径）置位独立 permission_error 位（R9-A 分位语义，
     *   不写 needsReauth 以免锁死刷新自愈）；写路径 403 维持只抛错不落档；
     * - [treatJsonNotFoundAsNull]（读路径）：404 且 JSON 错误体归一为「未收藏」返回 null——
     *   非 JSON 404 不在此列，继续走网络档，避免把 CDN/反代 HTML 错误页误报成未收藏；
     * - [requireJsonBody]（读路径）：2xx 空体/非 JSON 按网络档抛出（与 GET 单条收藏必带
     *   JSON 体的官方 spec 对齐；写路径 204 空体返回 null 的既有语义不受影响）。
     */
    private fun bangumiRequestWithToken(
        context: Context,
        url: String,
        method: String,
        body: String? = null,
        expectedSessionEpoch: Long? = null,
        treatJsonNotFoundAsNull: Boolean = false,
        requireJsonBody: Boolean = false,
        recordPermissionError: Boolean = false,
    ): Pair<JSONObject?, String> {
        val activeToken = resolveOAuthRequestToken(
            expectedSessionEpoch = expectedSessionEpoch,
            currentSessionEpoch = { BangumiAuthStore.sessionEpoch.value },
            isAuthorized = { BangumiAuthStore.getStatus(context).authorized },
            authMissingError = PlayStatusError.BANGUMI_AUTH_MISSING,
            acquireToken = {
                when (val tokenResult = BangumiAuthService.getValidAccessTokenResult(context)) {
                    is BangumiAccessTokenResult.Success -> OAuthTokenAttempt.Success(tokenResult.accessToken)
                    is BangumiAccessTokenResult.ConfigMissing -> OAuthTokenAttempt.ConfigMissing
                    is BangumiAccessTokenResult.RefreshFailed -> OAuthTokenAttempt.RefreshFailed
                }
            },
            isNeedsReauth = { BangumiAuthStore.isNeedsReauth(context) },
            mapRefreshFailure = { needsReauth, configMissing ->
                bangumiTokenRefreshFailureToError(needsReauth = needsReauth, configMissing = configMissing)
            },
            throttle = bangumiThrottle,
            readTokenIfSessionMatches = { epoch -> BangumiAuthStore.readAccessTokenIfSessionMatches(context, epoch) },
        )
        val result = httpJsonResult(url, method = method, body = body, headers = bangumiHeaders(activeToken))
        logInfo("bangumi $method ${url.substringAfter("/v0")} -> http ${result.status}")
        if (treatJsonNotFoundAsNull && result.status == 404 && result.json != null) {
            return null to activeToken
        }
        if (result.status !in 200..299) {
            logWarn("bangumi $method rejected: http ${result.status}")
            // BK-05A：401 落 needsReauth（EXPIRED）——服务端明确拒绝即吊销/失效信号，读/写路径
            // 同款分档；置位后后续读/写经刷新短路（refreshAccessToken 的 needsReauth 短路）直接
            // 按令牌失效分档报错，自愈途径 = 重新授权 / 重贴令牌（成功写回即清 last_error）
            bangumiWriteNeedsReauthReason(result.status)?.let {
                BangumiAuthStore.markNeedsReauthIfAccessTokenMatches(context, activeToken, it)
            }
            // 读路径 403（recordPermissionError）：令牌有效但权限缺失，写独立 permission_error 位、
            // 不写 needsReauth（与 Hikarinagi/NextMoe 读路径分位同构）；写路径不触碰权限位
            if (result.status == 403 && recordPermissionError) {
                BangumiAuthStore.markPermissionDeniedIfAccessTokenMatches(
                    context,
                    activeToken,
                    BangumiAuthError.PERMISSION.name,
                )
            }
            throw PlayStatusException(
            // LOGIC-3：写路径 404 且带 JSON 错误体 = 条目不存在/已删除，分档 SUBJECT_NOT_FOUND
            // 提示重绑（与 Hikarinagi/NextMoe 同族对齐）；读路径 404 JSON 体已前置归一未收藏，
            // 非 JSON 404 与其余非 2xx 维持网络档
            bangumiWriteStatusToError(result.status, hasJsonBody = result.json != null),
            "status=${result.status}",
        )
        }
        if (requireJsonBody && result.json == null) {
            throw PlayStatusException(PlayStatusError.BANGUMI_NETWORK, "non-json body")
        }
        return result.json to activeToken
    }

    /**
     * 带鉴权的 Hikarinagi 请求：401/403 区分失效与缺权限（写入路径不修改授权态）——
     * 读取路径 401 置位令牌失效（needsReauth）、403 置位独立权限缺失位（二者不共用一个
     * 状态位，否则 403 会与刷新短路交互、锁死令牌过期的自动刷新自愈路径）；
     * 2xx 非 JSON 响应不得被误判为成功或未标记。
     */
    private fun hikarinagiRequest(
        context: Context,
        url: String,
        method: String,
        body: String? = null,
        recordAuthError: Boolean = true,
        expectedSessionEpoch: Long? = null,
    ): JSONObject = hikarinagiRequestWithToken(
        context = context,
        url = url,
        method = method,
        body = body,
        recordAuthError = recordAuthError,
        expectedSessionEpoch = expectedSessionEpoch,
    ).first

    private fun hikarinagiRequestWithToken(
        context: Context,
        url: String,
        method: String,
        body: String? = null,
        recordAuthError: Boolean = true,
        expectedSessionEpoch: Long? = null,
    ): Pair<JSONObject, String> {
        val activeToken = resolveOAuthRequestToken(
            expectedSessionEpoch = expectedSessionEpoch,
            currentSessionEpoch = { HikarinagiAuthStore.sessionEpoch.value },
            isAuthorized = { HikarinagiAuthStore.getStatus(context).authorized },
            authMissingError = PlayStatusError.HIKARINAGI_AUTH_MISSING,
            acquireToken = {
                when (val tokenResult = HikarinagiAuthService.getValidAccessTokenResult(context)) {
                    is HikarinagiAccessTokenResult.Success -> OAuthTokenAttempt.Success(tokenResult.accessToken)
                    is HikarinagiAccessTokenResult.ConfigMissing -> OAuthTokenAttempt.ConfigMissing
                    is HikarinagiAccessTokenResult.RefreshFailed -> OAuthTokenAttempt.RefreshFailed
                }
            },
            isNeedsReauth = { HikarinagiAuthStore.isNeedsReauth(context) },
            mapRefreshFailure = { needsReauth, configMissing ->
                hikarinagiTokenRefreshFailureToError(needsReauth = needsReauth, configMissing = configMissing)
            },
            throttle = hikarinagiThrottle,
            readTokenIfSessionMatches = { epoch -> HikarinagiAuthStore.readAccessTokenIfSessionMatches(context, epoch) },
        )
        val result = httpJsonResult(
            url,
            method = method,
            body = body,
            headers = mapOf("Authorization" to "Bearer $activeToken"),
        )
        logInfo("hikarinagi $method ${url.substringAfter("/v3")} -> http ${result.status}")
        if (result.status == 401) {
            logWarn("hikarinagi token rejected: http 401")
            if (recordAuthError) {
                HikarinagiAuthStore.markNeedsReauthIfAccessTokenMatches(context, activeToken, HikarinagiAuthError.EXPIRED.name)
            }
            throw PlayStatusException(PlayStatusError.HIKARINAGI_TOKEN_EXPIRED)
        }
        if (result.status == 403) {
            logWarn("hikarinagi forbidden: http 403")
            if (recordAuthError) {
                // 权限缺失与令牌失效分位：403 = 令牌有效但权限不足，写入独立
                // permission_error 位，不写 needsReauth/last_error —— 否则会与刷新短路
                // （HikarinagiAuthService.refreshAccessToken）交互，把 access token 过期后的
                // 自动刷新自愈路径永久锁死。令牌失效（401）仍走 markNeedsReauth(EXPIRED)。
                HikarinagiAuthStore.markPermissionDeniedIfAccessTokenMatches(context, activeToken, HikarinagiAuthError.PERMISSION.name)
            }
            throw PlayStatusException(PlayStatusError.HIKARINAGI_TOKEN_PERMISSION)
        }
        if (result.status !in 200..299) {
            throw PlayStatusException(
                hikarinagiNonSuccessToError(result.status, hasJsonBody = result.json != null),
                "status=${result.status}",
            )
        }
        val json = result.json
            ?: throw PlayStatusException(PlayStatusError.HIKARINAGI_NETWORK, "non-json body")
        return json to activeToken
    }

    private fun nextMoeRequestWithToken(
        context: Context,
        url: String,
        method: String,
        body: String? = null,
        treatNotFoundAsNull: Boolean = false,
        recordAuthError: Boolean = true,
        requireJsonBody: Boolean = true,
        expectedSessionEpoch: Long? = null,
    ): Pair<JSONObject?, String> {
        val activeToken = resolveOAuthRequestToken(
            expectedSessionEpoch = expectedSessionEpoch,
            currentSessionEpoch = { NextMoeAuthStore.sessionEpoch.value },
            isAuthorized = { NextMoeAuthStore.getStatus(context).authorized },
            authMissingError = PlayStatusError.NEXTMOE_AUTH_MISSING,
            acquireToken = {
                when (val tokenResult = NextMoeAuthService.getValidAccessTokenResult(context)) {
                    is NextMoeAccessTokenResult.Success -> OAuthTokenAttempt.Success(tokenResult.accessToken)
                    is NextMoeAccessTokenResult.ConfigMissing -> OAuthTokenAttempt.ConfigMissing
                    is NextMoeAccessTokenResult.RefreshFailed -> OAuthTokenAttempt.RefreshFailed
                }
            },
            isNeedsReauth = { NextMoeAuthStore.isNeedsReauth(context) },
            mapRefreshFailure = { needsReauth, configMissing ->
                nextMoeTokenRefreshFailureToError(needsReauth = needsReauth, configMissing = configMissing)
            },
            throttle = nextMoeThrottle,
            readTokenIfSessionMatches = { epoch -> NextMoeAuthStore.readAccessTokenIfSessionMatches(context, epoch) },
        )
        val result = httpJsonResult(
            url,
            method = method,
            body = body,
            headers = mapOf("Authorization" to "Bearer $activeToken"),
        )
        logInfo("nextmoe $method ${url.substringAfter("/v2")} -> http ${result.status}")
        if (result.status == 401) {
            logWarn("nextmoe token rejected: http 401")
            if (recordAuthError) {
                NextMoeAuthStore.markNeedsReauthIfAccessTokenMatches(context, activeToken, NextMoeAuthError.EXPIRED.name)
            }
            throw PlayStatusException(PlayStatusError.NEXTMOE_TOKEN_EXPIRED)
        }
        if (result.status == 403) {
            logWarn("nextmoe forbidden: http 403")
            if (recordAuthError) {
                NextMoeAuthStore.markPermissionDeniedIfAccessTokenMatches(context, activeToken, NextMoeAuthError.PERMISSION.name)
            }
            throw PlayStatusException(PlayStatusError.NEXTMOE_TOKEN_PERMISSION)
        }
        val problemCode = result.json?.optNonBlankString("code")
        if (problemCode == "RATE_LIMITED" || problemCode == "QUOTA_EXCEEDED") {
            throw PlayStatusException(
                PlayStatusError.NEXTMOE_RATE_LIMITED,
                "status=${result.status} code=$problemCode",
            )
        }
        // GET /v2/me/work-states/{work_id} 对从未设置过状态的用户返回 404 JSON NOT_FOUND
        // （归一「未标记」）；非 JSON 404（CDN/反代错误页）与 404 ENTITY_MERGED / ENTITY_DELETED
        // 不得误判为「未标记」，按条目不存在/网络档分档抛出
        if (treatNotFoundAsNull && isNextMoeNotFoundUnmarked(result.status, result.json, problemCode)) {
            return null to activeToken
        }
        if (result.status !in 200..299) {
            throw PlayStatusException(
                nextMoeNonSuccessToError(result.status, problemCode, hasJsonBody = result.json != null),
                "status=${result.status} code=$problemCode",
            )
        }
        if (requireJsonBody && result.json == null) {
            throw PlayStatusException(PlayStatusError.NEXTMOE_NETWORK, "non-json body")
        }
        return result.json to activeToken
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(PLAY_STATUS_LOG_TAG, message) }
    }

    private fun logWarn(message: String) {
        runCatching { Log.w(PLAY_STATUS_LOG_TAG, message) }
    }
}
