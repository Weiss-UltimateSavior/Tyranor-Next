package com.tyranor.next.core.cover

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tyranor.next.core.auth.HikarinagiAccessTokenResult
import com.tyranor.next.core.auth.HikarinagiAuthError
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.net.httpJson
import com.tyranor.next.core.net.httpJsonResult
import com.tyranor.next.core.net.urlEncode
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class CoverScrapeResult(
    val games: List<ScanGame>,
    val updatedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
)

data class CoverSearchCandidate(
    val source: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val detail: String,
    val score: Int? = null,
    /** 预览地址：搜索结果卡片加载用（VNDB 固定为缩略图）。 */
    val coverUrl: String,
    /** 落库下载地址；为 null 时回退 [coverUrl]（VNDB 大图开关开启时这里是原图）。 */
    val downloadUrl: String? = null,
    /** VNDB 候选携带的元数据（绑定后可写入游戏库）；带 `vndb:v…` ref 的未萌 NextMoe 候选同样携带。 */
    val vndbId: String? = null,
    /** Bangumi 候选携带的条目 id（绑定后可写入游戏库）。 */
    val bangumiId: String? = null,
    /** Hikarinagi 候选携带的条目 id（绑定后可写入游戏库）。 */
    val hikarinagiId: String? = null,
    /** 未萌 NextMoe 候选携带的作品 id（catalog work id，绑定后可写入游戏库）。 */
    val nextmoeId: String? = null,
    val metadataTitle: String? = null,
)

sealed interface CoverSearchResult {
    data class Success(val candidates: List<CoverSearchCandidate>) : CoverSearchResult

    /** 失败只带错误码，文案由 UI 映射（`ui/cover/CoverErrorMessages.kt`）。 */
    data class Failure(val error: CoverErrorCode) : CoverSearchResult
}

object CoverScraperService {
    suspend fun scrapeLibraryCovers(
        context: Context,
        games: List<ScanGame>,
        onCoverUpdated: suspend (original: ScanGame, updated: ScanGame) -> Unit = { _, _ -> },
    ): CoverScrapeResult {
        val onlyMissing = AppSettingsStore.isCoverScraperOnlyMissing(context)
        val sources = AppSettingsStore.getCoverScraperSourceOrder(context)
            .filter { AppSettingsStore.isCoverScraperSourceEnabled(context, it) }

        var updatedCount = 0
        var skippedCount = 0
        var failedCount = 0

        val updatedGames = games.map { game ->
            coroutineContext.ensureActive()
            // 图标兜底的产物不算「已有封面」：先剥离再探测本地封面，之后在线来源仍可替换它
            val base = if (Xs4IconCover.isIconCoverUri(game.coverUri)) game.copy(coverUri = null) else game
            val local = runCatchingCancellable { EngineScanner.applyLocalCover(context, base) }.getOrDefault(base)
            val updated = if (keepsLocalCover(onlyMissing, local.coverUri)) {
                if (local.coverUri != game.coverUri) updatedCount++ else skippedCount++
                local
            } else {
                val scraped = sources.firstNotNullOfOrNull { source ->
                    coroutineContext.ensureActive()
                    runCatchingCancellable { scrapeWithSource(context, local.copy(coverUri = null), source) }.getOrNull()
                        ?.asCoverOnlyUpdateFrom(local)
                }
                if (scraped != null && !scraped.coverUri.isNullOrBlank()) {
                    updatedCount++
                    scraped
                } else {
                    // 本地与在线来源都没有封面，才由 XSYS4 图标兜底；仍无封面时保留原游戏（含原图标）
                    val resolved = applyXs4IconCover(context, local).takeIf { !it.coverUri.isNullOrBlank() } ?: game
                    if (resolved.coverUri != game.coverUri) {
                        updatedCount++
                    } else if (sources.isEmpty()) {
                        skippedCount++
                    } else {
                        failedCount++
                    }
                    resolved
                }
            }
            // 封面或 VNDB/Bangumi/Hikarinagi/NextMoe 元数据任一变化都要回调持久化（仅元数据变化时封面 URI 可能不变）
            if (updated.coverUri != game.coverUri || updated.coverSource != game.coverSource ||
                updated.vndbId != game.vndbId || updated.bangumiId != game.bangumiId ||
                updated.hikarinagiId != game.hikarinagiId || updated.nextmoeId != game.nextmoeId ||
                updated.metadataTitle != game.metadataTitle
            ) {
                onCoverUpdated(game, updated)
            }
            updated
        }
        return CoverScrapeResult(updatedGames, updatedCount, skippedCount, failedCount)
    }

    /** XSYS4 图标兜底（调用方须已确认本地与在线来源都无封面）；用户已有封面不覆盖，不写游戏目录。 */
    private fun applyXs4IconCover(context: Context, game: ScanGame): ScanGame {
        if (!Xs4IconCover.needsFallback(game.engine, game.coverUri)) return game
        val cover = runCatchingCancellable { Xs4IconCover.resolveCoverUri(context, game) }.getOrNull()
            ?: return game
        return game.copy(coverUri = cover, coverSource = AppSettingsStore.COVER_SOURCE_LOCAL)
    }

    /**
     * 一级候选：[vndbId] 非空时部分来源（未萌 NextMoe）会走精确反查而非关键词模糊匹配，
     * 由调用方决定是否传（交互式搜索仅在关键词未被改动、即等于游戏自身标题时传）。
     */
    fun searchCoverCandidates(
        context: Context,
        source: String,
        keyword: String,
        limit: Int = 8,
        vndbId: String? = null,
    ): CoverSearchResult {
        val query = keyword.trim()
        if (query.isBlank()) return CoverSearchResult.Success(emptyList())
        if (source !in AppSettingsStore.DEFAULT_COVER_SCRAPER_SOURCES) {
            return CoverSearchResult.Failure(CoverErrorCode.INVALID_SOURCE)
        }
        if (!AppSettingsStore.isCoverScraperSourceEnabled(context, source)) {
            return CoverSearchResult.Failure(CoverErrorCode.SOURCE_DISABLED)
        }
        return runCatchingCancellable {
            searchWithSource(context, source, query, limit.coerceIn(1, 10), vndbId?.takeIf { it.isNotBlank() })
        }
            .fold(
                onSuccess = { CoverSearchResult.Success(it) },
                onFailure = { failure -> CoverSearchResult.Failure(failure.toCoverErrorCode()) },
            )
    }

    /**
     * 二级候选：部分来源（未萌 NextMoe）一个作品有多张封面，按一级候选 [work] 拉取其可获取的封面（单页上限 100）。
     * 不支持二级的来源返回空列表（见 [supportsCoverDrillDown]）；来源有效性与启用态校验与一级一致。
     */
    fun searchWorkCovers(
        context: Context,
        source: String,
        work: CoverSearchCandidate,
        limit: Int = 100,
    ): CoverSearchResult {
        if (source !in AppSettingsStore.DEFAULT_COVER_SCRAPER_SOURCES) {
            return CoverSearchResult.Failure(CoverErrorCode.INVALID_SOURCE)
        }
        if (!AppSettingsStore.isCoverScraperSourceEnabled(context, source)) {
            return CoverSearchResult.Failure(CoverErrorCode.SOURCE_DISABLED)
        }
        if (!supportsCoverDrillDown(source)) return CoverSearchResult.Success(emptyList())
        return runCatchingCancellable { searchWorkCoversWithSource(context, source, work, limit) }
            .fold(
                onSuccess = { CoverSearchResult.Success(it) },
                onFailure = { failure -> CoverSearchResult.Failure(failure.toCoverErrorCode()) },
            )
    }

    private fun searchWorkCoversWithSource(
        context: Context,
        source: String,
        work: CoverSearchCandidate,
        limit: Int,
    ): List<CoverSearchCandidate> = when (source) {
        AppSettingsStore.COVER_SOURCE_NEXTMOE -> NextMoeCoverService.listCovers(work, limit, context)
        else -> emptyList()
    }

    /**
     * 条目绑定搜索（游玩状态）：接受 VNDB/Bangumi/Hikarinagi/NextMoe 可绑定来源，与封面搜索共用候选
     * 清洗/映射，但不校验封面来源启用开关（绑定能力独立于封面刮削开关）。失败抛出
     * [CoverSearchException]，由 [PlayStatusService.searchBindingCandidates] 收敛为错误码。
     */
    fun searchCandidatesForBinding(
        context: Context,
        source: String,
        keyword: String,
        limit: Int,
    ): List<CoverSearchCandidate> {
        require(
            source == AppSettingsStore.COVER_SOURCE_VNDB ||
                source == AppSettingsStore.COVER_SOURCE_BANGUMI ||
                source == AppSettingsStore.COVER_SOURCE_HIKARINAGI ||
                source == AppSettingsStore.COVER_SOURCE_NEXTMOE,
        ) {
            "unsupported binding source: $source"
        }
        return searchWithSource(context, source, keyword, limit, requireCover = false)
    }

    /** 该来源是否支持「作品 → 多封面」的两级选择。 */
    fun supportsCoverDrillDown(source: String): Boolean =
        source == AppSettingsStore.COVER_SOURCE_NEXTMOE

    fun bindCoverCandidate(context: Context, game: ScanGame, candidate: CoverSearchCandidate): ScanGame? {
        val prefix = "${candidate.source}_${stableKey(game.uri)}"
        val downloadUrl = candidate.downloadUrl?.takeIf { it.isNotBlank() } ?: candidate.coverUrl
        val cover = if (candidate.source == AppSettingsStore.COVER_SOURCE_STEAM) {
            val appId = candidate.id.toIntOrNull()
            CoverImageCache.download(context, downloadUrl, prefix, source = candidate.source)
                ?: appId?.let { SteamCoverSource.downloadSteamHeader(context, game, it) }
        } else {
            CoverImageCache.download(
                context = context,
                imageUrl = downloadUrl,
                prefix = prefix,
                source = candidate.source,
            )
        }
        return cover?.let {
            CoverImageCache.deleteCachedCover(context, game.coverUri, exceptUri = it)
            game.copy(
                coverUri = it,
                coverSource = candidate.source,
                // 携带绑定 id 的来源（VNDB、Bangumi、Hikarinagi、NextMoe）同时落库元数据；其余来源保留原值
                vndbId = candidate.vndbId ?: game.vndbId,
                bangumiId = candidate.bangumiId ?: game.bangumiId,
                hikarinagiId = candidate.hikarinagiId ?: game.hikarinagiId,
                nextmoeId = candidate.nextmoeId ?: game.nextmoeId,
                metadataTitle = candidate.metadataTitle ?: game.metadataTitle,
            )
        }
    }

    private fun scrapeWithSource(context: Context, game: ScanGame, source: String): ScanGame? =
        when (source) {
            AppSettingsStore.COVER_SOURCE_HIKARINAGI -> HikarinagiCoverSource.fetchBestCover(context, game)
            AppSettingsStore.COVER_SOURCE_BANGUMI -> BangumiCoverService.fetchBestCover(context, game)
            AppSettingsStore.COVER_SOURCE_STEAM -> SteamCoverSource.fetchBestCover(context, game)
            AppSettingsStore.COVER_SOURCE_VNDB -> VndbCoverService.fetchBestCover(context, game)
            AppSettingsStore.COVER_SOURCE_NEXTMOE -> NextMoeCoverService.fetchBestCover(context, game)
            else -> null
        }

    private fun searchWithSource(
        context: Context,
        source: String,
        keyword: String,
        limit: Int,
        vndbId: String? = null,
        requireCover: Boolean = true,
    ): List<CoverSearchCandidate> =
        when (source) {
            AppSettingsStore.COVER_SOURCE_HIKARINAGI -> HikarinagiCoverSource.searchCandidates(context, keyword, limit, requireCover)
            // 条目绑定搜索（requireCover=false）下 Bangumi 不丢弃无封面条目：绑定只消费
            // 条目 id 与标题（与 Hikarinagi 分支的 requireCover 透传一致）
            AppSettingsStore.COVER_SOURCE_BANGUMI -> BangumiCoverService.searchCandidates(keyword, limit, requireCover)
            AppSettingsStore.COVER_SOURCE_STEAM -> SteamCoverSource.searchCandidates(keyword, limit)
            AppSettingsStore.COVER_SOURCE_VNDB -> VndbCoverService.searchCandidates(context, keyword, limit).map {
                CoverSearchCandidate(
                    source = AppSettingsStore.COVER_SOURCE_VNDB,
                    id = it.id,
                    title = it.title.ifBlank { it.originalTitle },
                    subtitle = it.originalTitle,
                    detail = detailText("VNDB", it.id, it.released, it.developer),
                    // 预览固定缩略图，落库按大图开关取原图（downloadUrl）
                    coverUrl = it.thumbnailUrl.ifBlank { it.coverUrl },
                    downloadUrl = it.coverUrl.takeIf { url -> url.isNotBlank() },
                    vndbId = it.id.takeIf { id -> id.isNotBlank() },
                    metadataTitle = it.title.ifBlank { it.originalTitle }.trim().ifBlank { null },
                )
            }
            AppSettingsStore.COVER_SOURCE_NEXTMOE -> NextMoeCoverService.searchCandidates(keyword, vndbId, limit, context)
            else -> emptyList()
        }

    internal fun hikarinagiTokenFailureToCoverError(
        result: HikarinagiAccessTokenResult,
        needsReauth: Boolean,
    ): CoverErrorCode = when (result) {
        is HikarinagiAccessTokenResult.Success ->
            error("Success has no CoverErrorCode")
        HikarinagiAccessTokenResult.ConfigMissing -> CoverErrorCode.HIKARINAGI_AUTH_EXPIRED
        HikarinagiAccessTokenResult.RefreshFailed ->
            if (needsReauth) CoverErrorCode.HIKARINAGI_AUTH_EXPIRED else CoverErrorCode.HIKARINAGI_NETWORK
    }
}

private object HikarinagiCoverSource {
    private const val SEARCH_URL = "https://api.hikarinagi.org/v3/search"
    private const val IMAGE_BASE_URL = "https://imagesp.yurari.moe/"

    fun fetchBestCover(context: Context, game: ScanGame): ScanGame? {
        val token = HikarinagiAuthService.getValidAccessToken(context) ?: return null
        val query = cleanTitle(game.title)
        if (query.isBlank()) return null

        val candidate = searchRawCandidates(context, token, query, 5, requireCover = true)
            .maxByOrNull { it.score }
            ?: return null

        val cover = CoverImageCache.download(
            context,
            candidate.coverUrl,
            "hikarinagi_${stableKey(game.uri)}",
            source = AppSettingsStore.COVER_SOURCE_HIKARINAGI,
        )
            ?: return null
        return game.copy(
            coverUri = cover,
            coverSource = AppSettingsStore.COVER_SOURCE_HIKARINAGI,
            // 已有绑定优先：搜索首条命中可能与手工绑定不是同一条目，覆盖会让状态写入打到错误条目
            hikarinagiId = game.hikarinagiId?.takeIf { it.isNotBlank() } ?: candidate.id.takeIf { it.isNotBlank() },
            metadataTitle = game.metadataTitle?.takeIf { it.isNotBlank() }
                ?: candidate.title.trim().ifBlank { null },
        )
    }

    fun searchCandidates(
        context: Context,
        keyword: String,
        limit: Int,
        requireCover: Boolean = true,
    ): List<CoverSearchCandidate> {
        if (!HikarinagiAuthStore.getStatus(context).authorized) {
            throw CoverSearchException(CoverErrorCode.HIKARINAGI_AUTH_MISSING)
        }
        val token = when (val tokenResult = HikarinagiAuthService.getValidAccessTokenResult(context)) {
            is HikarinagiAccessTokenResult.Success -> tokenResult.accessToken
            else -> throw CoverSearchException(
                CoverScraperService.hikarinagiTokenFailureToCoverError(
                    tokenResult,
                    needsReauth = HikarinagiAuthStore.getStatus(context).needsReauth,
                ),
            )
        }
        val query = cleanTitle(keyword)
        if (query.isBlank()) return emptyList()
        return searchRawCandidates(context, token, query, limit, requireCover = requireCover).map {
            CoverSearchCandidate(
                source = AppSettingsStore.COVER_SOURCE_HIKARINAGI,
                id = it.id,
                title = it.title,
                subtitle = firstNonEmpty(it.developer, it.subtitle),
                // 票数由 UI 依据 score 渲染（见 CoverCandidateOverlay），core 不组装本地化文案
                detail = detailText("Hikarinagi", it.id, it.subtitle, it.developer),
                score = it.score,
                coverUrl = it.coverUrl,
                hikarinagiId = it.id.takeIf { id -> id.isNotBlank() },
                metadataTitle = it.title.trim().ifBlank { null },
            )
        }
    }

    private fun searchRawCandidates(
        context: Context,
        token: String,
        query: String,
        limit: Int,
        requireCover: Boolean,
    ): List<CoverCandidate> {
        val url = "$SEARCH_URL?q=${query.urlEncode()}&types=galgame&page=1&page_size=${limit.coerceIn(1, 10)}"
        val result = httpJsonResult(url, headers = mapOf("Authorization" to "Bearer $token"))
        if (result.status == 401) {
            // 令牌失效与权限缺失分位：401 = 令牌被服务端明确拒绝，属令牌失效语义，在发起请求时
            // 所持令牌尚未被并发刷新/重登替换的前提下置位 needsReauth；权限缺失（403）不在
            // 搜索路径置位，与游玩状态读路径的独立 permission_error 位互不影响
            HikarinagiAuthStore.markNeedsReauthIfAccessTokenMatches(context, token, HikarinagiAuthError.EXPIRED.name)
            throw CoverSearchException(CoverErrorCode.HIKARINAGI_AUTH_EXPIRED)
        }
        if (result.status == 429) {
            throw CoverSearchException(CoverErrorCode.HIKARINAGI_RETRY)
        }
        val json = result.json?.takeIf { result.status in 200..299 }
            ?: throw CoverSearchException(CoverErrorCode.HIKARINAGI_NETWORK)
        if (!json.optBoolean("success", false)) throw CoverSearchException(CoverErrorCode.HIKARINAGI_RETRY)
        val items = json.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).asSequence()
            .mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("type") == "galgame" }
            .mapNotNull { item ->
                val id = if (item.isNull("id")) "" else item.optString("id", "").trim()
                if (id.isBlank()) return@mapNotNull null
                val cover = item.optJSONObject("cover")
                val coverUrl = cover?.let { resolveHikarinagiImageUrl(it.optString("url", "")) }.orEmpty()
                if (requireCover && coverUrl.isBlank()) return@mapNotNull null
                val subtitle = if (item.isNull("subtitle")) "" else item.optString("subtitle", "").trim()
                val developer = if (item.isNull("developer")) "" else item.optString("developer", "").trim()
                CoverCandidate(
                    id = id,
                    title = firstNonEmpty(item.optString("title", ""), subtitle),
                    subtitle = subtitle,
                    developer = developer,
                    coverUrl = coverUrl,
                    score = cover?.optInt("votes", 0) ?: 0,
                )
            }
            .toList()
    }

    private fun resolveHikarinagiImageUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        val parsed = runCatching { Uri.parse(value) }.getOrNull() ?: return ""
        if (parsed.isAbsolute) return parsed.toString()
        return Uri.parse(IMAGE_BASE_URL).buildUpon().appendEncodedPath(value.trimStart('/')).build().toString()
    }
}

private object SteamCoverSource {
    private const val SEARCH_URL = "https://store.steampowered.com/api/storesearch/"
    private const val DETAILS_URL = "https://store.steampowered.com/api/appdetails"

    fun fetchBestCover(context: Context, game: ScanGame): ScanGame? {
        val query = cleanTitle(game.title)
        if (query.isBlank()) return null
        for (candidate in searchCandidates(query, 3)) {
            val appId = candidate.id.toIntOrNull() ?: continue
            val cover = CoverImageCache.download(context, candidate.coverUrl, "steam_${stableKey(game.uri)}", source = AppSettingsStore.COVER_SOURCE_STEAM)
                ?: downloadSteamHeader(context, game, appId)
            if (cover != null) {
                return game.copy(
                    coverUri = cover,
                    coverSource = AppSettingsStore.COVER_SOURCE_STEAM,
                )
            }
        }
        return null
    }

    fun searchCandidates(keyword: String, limit: Int): List<CoverSearchCandidate> {
        val query = cleanTitle(keyword)
        if (query.isBlank()) return emptyList()
        val search = httpJson("$SEARCH_URL?term=${query.urlEncode()}&l=schinese&cc=CN")
            ?: throw CoverSearchException(CoverErrorCode.STEAM_NETWORK)
        val items = search.optJSONArray("items") ?: return emptyList()
        return (0 until minOf(items.length(), limit.coerceIn(1, 10))).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val appId = item.optInt("id", 0)
            if (appId <= 0) return@mapNotNull null
            CoverSearchCandidate(
                source = AppSettingsStore.COVER_SOURCE_STEAM,
                id = appId.toString(),
                title = item.optString("name", ""),
                subtitle = "",
                detail = detailText("Steam", appId.toString()),
                coverUrl = "https://cdn.akamai.steamstatic.com/steam/apps/$appId/library_600x900.jpg",
            )
        }
    }

    fun downloadSteamHeader(context: Context, game: ScanGame, appId: Int): String? {
        val details = httpJson("$DETAILS_URL?appids=$appId&l=schinese&cc=CN") ?: return null
        val header = details.optJSONObject(appId.toString())
            ?.optJSONObject("data")
            ?.optString("header_image", "")
            .orEmpty()
        if (header.isBlank()) return null
        return CoverImageCache.download(context, header, "steam_${stableKey(game.uri)}", source = AppSettingsStore.COVER_SOURCE_STEAM)
    }
}

private data class CoverCandidate(
    val id: String,
    val title: String,
    val subtitle: String,
    val developer: String,
    val coverUrl: String,
    val score: Int,
)

/** 协程内 runCatching：透传取消信号，避免取消被当作来源/IO 失败吞掉（m9 同类修复）。 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Result.failure(t)
    }

private const val TAG = "CoverScraper"

/**
 * 来源异常 → 错误码：已类型化的失败直接用其错误码，未预期的异常收敛为
 * [CoverErrorCode.UNKNOWN] 并把原始异常打进日志（core 不外泄技术细节给 UI）。
 */
private fun Throwable.toCoverErrorCode(): CoverErrorCode {
    val known = this as? CoverSearchException
    if (known != null) return known.error
    Log.w(TAG, "cover source request failed", this)
    return CoverErrorCode.UNKNOWN
}

/**
 * 批量刮削对单个游戏的分支判定（纯函数，JVM 可测）：仅「只补缺失」且本地已有封面时保留本地封面，
 * 其余情况都先走在线来源；图标兜底只能排在在线来源之后。
 */
internal fun keepsLocalCover(onlyMissing: Boolean, localCoverUri: String?): Boolean =
    onlyMissing && !localCoverUri.isNullOrBlank()

/** 批量刮削单游戏结果（纯函数，JVM 可测）：已有绑定优先，仅在为空时以刮削命中填充。 */
internal fun ScanGame.asCoverOnlyUpdateFrom(base: ScanGame): ScanGame =
    base.copy(
        coverUri = coverUri,
        coverSource = coverSource,
        // 已有绑定优先：刮削命中可能与手工绑定不是同一条目，覆盖会让状态写入打到错误条目
        vndbId = base.vndbId?.takeIf { it.isNotBlank() } ?: vndbId?.takeIf { it.isNotBlank() },
        bangumiId = base.bangumiId?.takeIf { it.isNotBlank() } ?: bangumiId?.takeIf { it.isNotBlank() },
        hikarinagiId = base.hikarinagiId?.takeIf { it.isNotBlank() } ?: hikarinagiId?.takeIf { it.isNotBlank() },
        nextmoeId = base.nextmoeId?.takeIf { it.isNotBlank() } ?: nextmoeId?.takeIf { it.isNotBlank() },
        metadataTitle = base.metadataTitle?.takeIf { it.isNotBlank() }
            ?: metadataTitle?.takeIf { it.isNotBlank() },
    )
