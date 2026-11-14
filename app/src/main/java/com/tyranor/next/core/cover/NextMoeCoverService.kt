package com.tyranor.next.core.cover

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.tyranor.next.BuildConfig
import com.tyranor.next.core.auth.NextMoeAuthService
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.RequestThrottle
import com.tyranor.next.core.net.httpJsonResult
import com.tyranor.next.core.net.optNonBlankString
import com.tyranor.next.core.net.urlEncode
import com.tyranor.next.core.settings.AppSettingsStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 未萌 NextMoe（鲲 Galgame 论坛）封面与条目来源：支持匿名调用，也支持在已授权且令牌有效时
 * 附带用户访问令牌（`catalog:read`，额度由匿名 30/min 提升至 100/min）；若令牌被拒 401
 * 自动标记失效并回退匿名请求，确保封面搜索与刮削不因授权失效而阻断。
 *
 * 接口（实测）：
 * - 作品检索 `GET /v2/catalog/works?q=<kw>&nsfw=true&limit=N&include=refs`
 * - 外部 / 目录 ID 反查 `GET /v2/catalog/works?refs=vndb:v<id>&nsfw=true` / `?ids=<id>&nsfw=true`
 * - 作品封面 `GET /v2/catalog/works/{id}/covers?nsfw=true&limit=N`
 */
internal object NextMoeCoverService {
    private const val WORKS_URL = "https://api.nextmoe.dev/v2/catalog/works"

    /** 匿名限流保守间隔 1.3s（用户令牌限流 100/min，统一沿用 1.3s 对目录接口足够温和）。 */
    private val throttle = RequestThrottle(1300L)

    private const val SEXUAL_SAFE = "safe"

    /** problem+json 的限流错误码（429 之外的限流表达）。 */
    private val RATE_LIMIT_CODES = setOf("RATE_LIMITED", "QUOTA_EXCEEDED")

    @VisibleForTesting
    @Volatile
    internal var testHttpRoundTrip: ((url: String, headers: Map<String, String>) -> HttpJsonResult)? = null

    fun fetchBestCover(context: Context, game: ScanGame): ScanGame? {
        val candidates = buildList {
            game.nextmoeId?.trim()?.takeIf { it.isNotBlank() }?.let { workId ->
                addAll(requestWorks(ids = workId, query = null, refs = null, limit = 1, context = context))
            }
            if (isEmpty()) {
                addAll(searchCandidates(game.title, game.vndbId, 3, context))
            }
        }
        // 逐条尝试直到有一张能下载成功：首条候选缺封面或下载失败时不至于直接放弃
        for (candidate in candidates) {
            val url = candidate.downloadUrl ?: candidate.coverUrl
            if (url.isBlank()) continue
            val cover = CoverImageCache.download(
                context,
                url,
                "nextmoe_${stableKey(game.uri)}",
                source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            ) ?: continue
            return game.copy(
                coverUri = cover,
                coverSource = AppSettingsStore.COVER_SOURCE_NEXTMOE,
                // 已有绑定优先：空串不得覆盖库中已有元数据
                vndbId = game.vndbId?.takeIf { it.isNotBlank() } ?: candidate.vndbId?.takeIf { it.isNotBlank() },
                nextmoeId = game.nextmoeId?.takeIf { it.isNotBlank() } ?: candidate.nextmoeId?.takeIf { it.isNotBlank() },
                metadataTitle = game.metadataTitle?.takeIf { it.isNotBlank() }
                    ?: candidate.metadataTitle?.takeIf { it.isNotBlank() },
            )
        }
        return null
    }

    /** 一级候选：作品（每作品取官方选定的竖版封面做预览）。有 VNDB id 时优先按 ref 精确反查。 */
    fun searchCandidates(
        keyword: String,
        vndbId: String?,
        limit: Int,
        context: Context? = null,
    ): List<CoverSearchCandidate> {
        val query = cleanTitle(keyword)
        if (vndbId.isNullOrBlank() && query.isBlank()) return emptyList()
        vndbId?.takeIf { it.isNotBlank() }?.let { ref ->
            val byRef = requestWorks(ids = null, query = null, refs = "vndb:$ref", limit = limit, context = context)
            if (byRef.isNotEmpty()) return byRef
        }
        if (query.isBlank()) return emptyList()
        // 关键词模糊匹配命中哪部作品并未被验证，故不回填 VNDB 元数据（避免把异作的 vndbId 落库）；
        // 候选自身的 nextmoeId 与标题仍保留，供用户手工绑定或缺失时补全。
        return requestWorks(ids = null, query = query, refs = null, limit = limit, context = context)
            .map { it.copy(vndbId = null) }
    }

    /**
     * 二级候选：某作品**可获取的**封面（NextMoe 一个作品常有多张：main / pkgfront / pkgback / dig…）。
     * 元数据继承自一级作品候选（[nextmoeId] 必须取作品 id 而非封面 id）。只拉单页（上限 100）。
     */
    fun listCovers(
        parent: CoverSearchCandidate,
        limit: Int,
        context: Context? = null,
    ): List<CoverSearchCandidate> {
        val workId = parent.nextmoeId?.takeIf { it.isNotBlank() } ?: parent.id
        if (workId.isBlank()) return emptyList()
        // 作品 id 是路径段，用 RFC 3986 路径段编码（空格必须是 %20 而不是 +，同时兼容纯 JVM 单测）
        val encodedWorkId = workId.urlEncode().replace("+", "%20")
        val url = "$WORKS_URL/$encodedWorkId/covers?nsfw=true&limit=${limit.coerceIn(1, 100)}"
        val json = requestJson(url, emptyOnNotFound = true, context = context)
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).asSequence()
            .mapNotNull { items.optJSONObject(it) }
            .mapNotNull { parseCover(it) }
            // 社区钉选的竖版置顶；其余保持接口原序（不依赖 cover_kind 判别）
            .sortedByDescending { it.pinned }
            .map { cover -> mapCoverToCandidate(parent, cover) }
            .toList()
    }

    internal fun mapCoverToCandidate(parent: CoverSearchCandidate, cover: NextMoeCover): CoverSearchCandidate {
        val workId = parent.nextmoeId?.takeIf { it.isNotBlank() } ?: parent.id
        return CoverSearchCandidate(
            source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            id = cover.id,
            title = parent.title,
            subtitle = cover.kind,
            detail = detailText(cover.kind, cover.sizeText(), cover.sexual.takeIf { it != SEXUAL_SAFE }),
            score = cover.votes,
            coverUrl = cover.url,
            downloadUrl = cover.url.takeIf { it.isNotBlank() },
            vndbId = parent.vndbId,
            nextmoeId = workId.takeIf { it.isNotBlank() },
            metadataTitle = parent.metadataTitle,
        )
    }

    private fun requestWorks(
        ids: String?,
        query: String?,
        refs: String?,
        limit: Int,
        context: Context?,
    ): List<CoverSearchCandidate> {
        val params = buildList {
            ids?.takeIf { it.isNotBlank() }?.let { add("ids=${it.urlEncode()}") }
            query?.takeIf { it.isNotBlank() }?.let { add("q=${it.urlEncode()}") }
            refs?.takeIf { it.isNotBlank() }?.let { add("refs=${it.urlEncode()}") }
            add("nsfw=true")
            add("limit=${limit.coerceIn(1, 100)}")
            add("include=refs")
        }
        val json = requestJson("$WORKS_URL?${params.joinToString("&")}", context = context)
        val items = json.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val work = items.optJSONObject(i)?.let(::parseWork) ?: continue
                // id 为空的作品进不了二级（取不到封面），直接丢弃
                if (work.id.isBlank()) continue
                add(work)
            }
        }
    }

    internal fun parseWork(o: JSONObject): CoverSearchCandidate {
        val id = o.optNonBlankString("id").orEmpty()
        val original = firstNonEmpty(o.optNonBlankString("display_name"), o.optNonBlankString("latin"))
        val localTitle = localizedTitle(o)
        val title = firstNonEmpty(localTitle, original)
        val cover = o.optJSONObject("cover")
        val coverUrl = cover?.optNonBlankString("url").orEmpty()
        // 只有作品确实带 VNDB 作品 ref 时才回写 VNDB 元数据，避免「VNDB id + 第三方标题」混搭
        val vndbId = findVndbRef(o.optJSONArray("refs"))
        val resolvedNextMoeId = id.takeIf { it.isNotBlank() }
        return CoverSearchCandidate(
            source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            id = id,
            title = title,
            subtitle = original.takeIf { it.isNotBlank() && it != title }.orEmpty(),
            detail = detailText(
                id,
                cover?.let { sizeText(it.optInt("width", 0), it.optInt("height", 0)) },
                o.optNonBlankString("release_date"),
            ),
            coverUrl = coverUrl,
            downloadUrl = coverUrl.takeIf { it.isNotBlank() },
            vndbId = vndbId,
            nextmoeId = resolvedNextMoeId,
            metadataTitle = title.takeIf { it.isNotBlank() && (resolvedNextMoeId != null || vndbId != null) },
        )
    }

    internal fun parseCover(o: JSONObject): NextMoeCover? {
        val url = o.optNonBlankString("url") ?: return null
        return NextMoeCover(
            id = o.optNonBlankString("id").orEmpty(),
            kind = o.optNonBlankString("cover_kind").orEmpty(),
            url = url,
            width = o.optInt("width", 0),
            height = o.optInt("height", 0),
            sexual = o.optNonBlankString("sexual").orEmpty(),
            pinned = o.optBoolean("portrait_pinned", false),
            votes = o.optInt("vote_count", 0),
        )
    }

    /** 取 `localized` 里的中文标题（机器翻译不采信），无则返回空串。 */
    private fun localizedTitle(o: JSONObject): String {
        val localized = o.optJSONObject("localized") ?: return ""
        return listOf("zh-Hans", "zh-Hant").asSequence()
            .mapNotNull { localized.optJSONObject(it) }
            .filter { !it.optBoolean("is_machine", false) }
            .mapNotNull { it.optNonBlankString("value") }
            .firstOrNull()
            .orEmpty()
    }

    /** 作品 refs 里的 VNDB 作品 id（形如 `v19073`）；`r…` 是发售版引用，忽略。 */
    private fun findVndbRef(refs: JSONArray?): String? {
        if (refs == null) return null
        for (i in 0 until refs.length()) {
            val ref = refs.optJSONObject(i) ?: continue
            if (ref.optNonBlankString("source") != "vndb") continue
            val id = ref.optNonBlankString("external_id").orEmpty()
            if (id.startsWith("v")) return id
        }
        return null
    }

    private fun requestJson(
        url: String,
        emptyOnNotFound: Boolean = false,
        context: Context? = null,
    ): JSONObject {
        val stub = testHttpRoundTrip
        if (stub != null) {
            check(BuildConfig.DEBUG) { "test stub must be null in release" }
        }
        val authContextToken = context?.let { ctx ->
            val status = NextMoeAuthStore.getStatus(ctx)
            if (status.authorized && !status.needsReauth) {
                NextMoeAuthService.getValidAccessToken(ctx)?.let { ctx to it }
            } else {
                null
            }
        }
        if (stub == null) throttle.await()
        val headers = authContextToken?.let { (_, token) -> mapOf("Authorization" to "Bearer $token") } ?: emptyMap()
        var result = stub?.invoke(url, headers) ?: httpJsonResult(url, headers = headers)
        // 带了失效令牌打 /v2/catalog 会返回 401 INVALID_CREDENTIAL 而不会自动降级匿名：
        // 仅在目录面静默回退匿名请求（不直接置 needsReauth，避免轻量时钟偏差锁死 refresh_token 自动续期）。
        if (authContextToken != null && result.status == 401) {
            if (stub == null) throttle.await()
            result = stub?.invoke(url, emptyMap()) ?: httpJsonResult(url)
        }
        if (result.status == 404 && emptyOnNotFound) return JSONObject()
        val json = result.json
        if (result.status == 429 || json?.optNonBlankString("code").orEmpty() in RATE_LIMIT_CODES) {
            throw CoverSearchException(CoverErrorCode.NEXTMOE_RATE_LIMITED)
        }
        if (result.status !in 200..299 || json == null) throw CoverSearchException(CoverErrorCode.NEXTMOE_NETWORK)
        return json
    }
}

internal data class NextMoeCover(
    val id: String,
    val kind: String,
    val url: String,
    val width: Int,
    val height: Int,
    val sexual: String,
    val pinned: Boolean,
    val votes: Int,
) {
    fun sizeText(): String? = sizeText(width, height)
}

private fun sizeText(width: Int, height: Int): String? =
    if (width > 0 && height > 0) "${width}×${height}" else null
