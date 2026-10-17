package com.tyranor.next.core.cover

import android.content.Context
import android.net.Uri
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 未萌 NextMoe（鲲 Galgame 论坛）封面来源：**匿名**接入，不依赖任何登录/授权态。
 *
 * 接口（实测）：
 * - 作品检索 `GET /v2/catalog/works?q=<kw>&nsfw=true&limit=N&include=refs`
 * - VNDB 反查 `GET /v2/catalog/works?refs=vndb:v<id>&nsfw=true`
 * - 作品封面 `GET /v2/catalog/works/{id}/covers?nsfw=true&limit=N`
 *
 * 限流实测 `ratelimit-policy: 50;w=60, 10000;w=86400`，故请求间强制最小间隔。
 * `cover_kind` 是开放词表（OpenAPI 明示不得作判别式），这里只作展示文本。
 */
internal object NextMoeCoverService {
    private const val WORKS_URL = "https://api.nextmoe.dev/v2/catalog/works"

    /** 服务端限流 50 次/分钟 → 取 1.3s 间隔留少量余量（与 VNDB 共用同一实现，各自独立实例）。 */
    private val throttle = RequestThrottle(1300L)

    private const val SEXUAL_SAFE = "safe"

    /** problem+json 的限流错误码（429 之外的限流表达）。 */
    private val RATE_LIMIT_CODES = setOf("RATE_LIMITED", "QUOTA_EXCEEDED")

    fun fetchBestCover(context: Context, game: ScanGame): ScanGame? {
        // 逐条尝试直到有一张能下载成功：首条候选缺封面或下载失败时不至于直接放弃
        for (candidate in searchCandidates(game.title, game.vndbId, 3)) {
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
                // 空串不得覆盖库中已有元数据
                vndbId = candidate.vndbId?.takeIf { it.isNotBlank() } ?: game.vndbId,
                metadataTitle = candidate.metadataTitle?.takeIf { it.isNotBlank() } ?: game.metadataTitle,
            )
        }
        return null
    }

    /** 一级候选：作品（每作品取官方选定的竖版封面做预览）。有 VNDB id 时优先按 ref 精确反查。 */
    fun searchCandidates(keyword: String, vndbId: String?, limit: Int): List<CoverSearchCandidate> {
        val query = cleanTitle(keyword)
        if (vndbId.isNullOrBlank() && query.isBlank()) return emptyList()
        vndbId?.takeIf { it.isNotBlank() }?.let { ref ->
            val byRef = requestWorks(query = null, refs = "vndb:$ref", limit = limit)
            if (byRef.isNotEmpty()) return byRef
        }
        if (query.isBlank()) return emptyList()
        // 关键词模糊匹配命中哪部作品并未被验证，故不写回 VNDB 元数据（避免把异作的 id/标题落库）
        return requestWorks(query = query, refs = null, limit = limit)
            .map { it.copy(vndbId = null, metadataTitle = null) }
    }

    /**
     * 二级候选：某作品**可获取的**封面（NextMoe 一个作品常有多张：main / pkgfront / pkgback / dig…）。
     * 元数据继承自一级作品候选，绑定后与一级选择行为一致。只拉单页（上限 100，超出不翻页）。
     */
    fun listCovers(parent: CoverSearchCandidate, limit: Int): List<CoverSearchCandidate> {
        if (parent.id.isBlank()) return emptyList()
        // 作品 id 是路径段，用 Uri.encode 而非表单编码（空格必须是 %20 而不是 +）
        val url = "$WORKS_URL/${Uri.encode(parent.id, "")}/covers?nsfw=true&limit=${limit.coerceIn(1, 100)}"
        val json = requestJson(url, emptyOnNotFound = true)
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).asSequence()
            .mapNotNull { items.optJSONObject(it) }
            .mapNotNull { parseCover(it) }
            // 社区钉选的竖版置顶；其余保持接口原序（不依赖 cover_kind 判别）
            .sortedByDescending { it.pinned }
            .map { cover ->
                CoverSearchCandidate(
                    source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
                    id = cover.id,
                    title = parent.title,
                    subtitle = cover.kind,
                    detail = detailText(cover.kind, cover.sizeText(), cover.sexual.takeIf { it != SEXUAL_SAFE }),
                    score = cover.votes,
                    coverUrl = cover.url,
                    downloadUrl = cover.url.takeIf { it.isNotBlank() },
                    vndbId = parent.vndbId,
                    metadataTitle = parent.metadataTitle,
                )
            }
            .toList()
    }

    private fun requestWorks(query: String?, refs: String?, limit: Int): List<CoverSearchCandidate> {
        val params = buildList {
            query?.takeIf { it.isNotBlank() }?.let { add("q=${it.urlEncode()}") }
            refs?.takeIf { it.isNotBlank() }?.let { add("refs=${it.urlEncode()}") }
            add("nsfw=true")
            add("limit=${limit.coerceIn(1, 100)}")
            add("include=refs")
        }
        val json = requestJson("$WORKS_URL?${params.joinToString("&")}")
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

    private fun parseWork(o: JSONObject): CoverSearchCandidate {
        val id = o.optStr("id").orEmpty()
        val original = firstNonEmpty(o.optStr("display_name"), o.optStr("latin"))
        val localTitle = localizedTitle(o)
        val title = firstNonEmpty(localTitle, original)
        val cover = o.optJSONObject("cover")
        val coverUrl = cover?.optStr("url").orEmpty()
        // 只有作品确实带 VNDB 作品 ref 时才回写 VNDB 元数据，避免「VNDB id + 第三方标题」混搭
        val vndbId = findVndbRef(o.optJSONArray("refs"))
        return CoverSearchCandidate(
            source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            id = id,
            title = title,
            subtitle = original.takeIf { it.isNotBlank() && it != title }.orEmpty(),
            detail = detailText(
                id,
                cover?.let { sizeText(it.optInt("width", 0), it.optInt("height", 0)) },
                o.optStr("release_date"),
            ),
            coverUrl = coverUrl,
            downloadUrl = coverUrl.takeIf { it.isNotBlank() },
            vndbId = vndbId,
            metadataTitle = title.takeIf { it.isNotBlank() && vndbId != null },
        )
    }

    private fun parseCover(o: JSONObject): NextMoeCover? {
        val url = o.optStr("url") ?: return null
        return NextMoeCover(
            id = o.optStr("id").orEmpty(),
            kind = o.optStr("cover_kind").orEmpty(),
            url = url,
            width = o.optInt("width", 0),
            height = o.optInt("height", 0),
            sexual = o.optStr("sexual").orEmpty(),
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
            .mapNotNull { it.optStr("value") }
            .firstOrNull()
            .orEmpty()
    }

    /** 作品 refs 里的 VNDB 作品 id（形如 `v19073`）；`r…` 是发售版引用，忽略。 */
    private fun findVndbRef(refs: JSONArray?): String? {
        if (refs == null) return null
        for (i in 0 until refs.length()) {
            val ref = refs.optJSONObject(i) ?: continue
            if (ref.optStr("source") != "vndb") continue
            val id = ref.optStr("external_id").orEmpty()
            if (id.startsWith("v")) return id
        }
        return null
    }

    private fun requestJson(url: String, emptyOnNotFound: Boolean = false): JSONObject {
        throttle.await()
        val result = httpJsonResult(url)
        if (result.status == 404 && emptyOnNotFound) return JSONObject()
        val json = result.json
        if (result.status == 429 || json?.optString("code").orEmpty() in RATE_LIMIT_CODES) {
            throw CoverSearchException(CoverErrorCode.NEXTMOE_RATE_LIMITED)
        }
        if (result.status !in 200..299 || json == null) throw CoverSearchException(CoverErrorCode.NEXTMOE_NETWORK)
        return json
    }
}

/** `optString` 会把 JSON null 变成字符串 "null"，这里统一收敛为 null。 */
private fun JSONObject.optStr(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() && it != "null" }

private data class NextMoeCover(
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
