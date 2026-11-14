package com.tyranor.next.core.cover

import android.content.Context
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.net.httpJson
import com.tyranor.next.core.net.optNonBlankString
import com.tyranor.next.core.settings.AppSettingsStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bangumi 封面/条目来源（api.bgm.tv v0）。调用面收敛于 [CoverScraperService]（批量刮削与
 * 条目绑定搜索均经其 searchWithSource 路由，条目绑定搜索不强制封面、不受封面来源启用
 * 开关约束——开关校验在调用方），无外部直接调用方，故收敛为 internal。
 */
internal object BangumiCoverService {
    private const val SEARCH_URL = "https://api.bgm.tv/v0/search/subjects"

    fun fetchBestCover(context: Context, game: ScanGame): ScanGame? {
        val query = cleanTitle(game.title)
        if (query.isBlank()) return null
        val candidate = searchCandidates(query, 1).firstOrNull() ?: return null
        val cover = CoverImageCache.download(context, candidate.coverUrl, "bangumi_${stableKey(game.uri)}", source = AppSettingsStore.COVER_SOURCE_BANGUMI)
            ?: return null
        return game.copy(
            coverUri = cover,
            coverSource = AppSettingsStore.COVER_SOURCE_BANGUMI,
            // 已有绑定优先：搜索首条命中可能与手工绑定不是同一条目，覆盖会让状态写入打到错误条目
            bangumiId = game.bangumiId?.takeIf { it.isNotBlank() } ?: candidate.bangumiId?.takeIf { it.isNotBlank() },
            metadataTitle = game.metadataTitle?.takeIf { it.isNotBlank() }
                ?: candidate.metadataTitle?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 搜索 Bangumi 条目。[requireCover] = false 时不丢弃无封面图的条目（条目绑定搜索用）：
     * 绑定只消费条目 id 与标题，封面缺失不应缩小候选集；默认 true 保持封面搜索既有行为
     * （无封面条目对封面刮削无价值）。默认值保证既有调用方零影响。
     */
    fun searchCandidates(keyword: String, limit: Int, requireCover: Boolean = true): List<CoverSearchCandidate> {
        val query = cleanTitle(keyword)
        if (query.isBlank()) return emptyList()
        val body = JSONObject()
            .put("keyword", query)
            .put("sort", "rank")
            .put(
                "filter",
                JSONObject()
                    .put("type", JSONArray().put(4))
                    .put("nsfw", true),
            )
            .toString()
        val url = "$SEARCH_URL?limit=${limit.coerceIn(1, 10)}&offset=0"
        val json = httpJson(url, method = "POST", body = body)
            ?: throw CoverSearchException(CoverErrorCode.BANGUMI_NETWORK)
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).asSequence()
            .mapNotNull { data.optJSONObject(it) }
            .filter { it.optInt("type") == 4 }
            .mapNotNull { item ->
                val images = item.optJSONObject("images")
                val coverUrl = images?.let {
                    firstNonEmpty(it.optNonBlankString("large"), it.optNonBlankString("common"))
                }.orEmpty()
                if (requireCover && coverUrl.isBlank()) return@mapNotNull null
                val id = item.optNonBlankString("id").orEmpty()
                val name = item.optNonBlankString("name").orEmpty()
                val nameCn = item.optNonBlankString("name_cn").orEmpty()
                val title = firstNonEmpty(nameCn, name)
                CoverSearchCandidate(
                    source = AppSettingsStore.COVER_SOURCE_BANGUMI,
                    id = id,
                    title = title,
                    subtitle = name,
                    detail = detailText("Bangumi", id, item.optNonBlankString("date")),
                    coverUrl = coverUrl,
                    // 条目 id 随候选携带，绑定后写入游戏库供游玩状态同步使用
                    bangumiId = id.takeIf { it.isNotBlank() },
                    metadataTitle = title.trim().ifBlank { null },
                )
            }
            .toList()
    }
}
