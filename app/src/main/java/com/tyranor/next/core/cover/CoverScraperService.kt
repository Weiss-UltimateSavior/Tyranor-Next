package com.tyranor.next.core.cover

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.tyranor.next.R
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
    val coverUrl: String,
)

sealed interface CoverSearchResult {
    data class Success(val candidates: List<CoverSearchCandidate>) : CoverSearchResult
    data class Failure(val message: String) : CoverSearchResult
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
            val local = runCatching { EngineScanner.applyLocalCover(context, game) }.getOrDefault(game)
            val updated = if (onlyMissing && !local.coverUri.isNullOrBlank()) {
                if (local.coverUri != game.coverUri) updatedCount++ else skippedCount++
                local
            } else {
                val searchBase = if (onlyMissing) local else local.copy(coverUri = null)
                val scraped = sources.firstNotNullOfOrNull { source ->
                    coroutineContext.ensureActive()
                    runCatching { scrapeWithSource(context, searchBase, source) }.getOrNull()
                        ?.asCoverOnlyUpdateFrom(local)
                }
                if (scraped != null && !scraped.coverUri.isNullOrBlank()) {
                    updatedCount++
                    scraped
                } else {
                    if (local.coverUri != game.coverUri) {
                        updatedCount++
                    } else if (sources.isEmpty()) {
                        skippedCount++
                    } else {
                        failedCount++
                    }
                    local
                }
            }
            if (updated.coverUri != game.coverUri || updated.coverSource != game.coverSource) {
                onCoverUpdated(game, updated)
            }
            updated
        }
        return CoverScrapeResult(updatedGames, updatedCount, skippedCount, failedCount)
    }

    fun searchCoverCandidates(
        context: Context,
        source: String,
        keyword: String,
        limit: Int = 8,
    ): CoverSearchResult {
        val query = keyword.trim()
        if (query.isBlank()) return CoverSearchResult.Success(emptyList())
        if (source !in AppSettingsStore.DEFAULT_COVER_SCRAPER_SOURCES) {
            return CoverSearchResult.Failure(text(context, R.string.cover_error_invalid_source))
        }
        if (!AppSettingsStore.isCoverScraperSourceEnabled(context, source)) {
            return CoverSearchResult.Failure(text(context, R.string.cover_error_source_disabled))
        }
        return runCatching { searchWithSource(context, source, query, limit.coerceIn(1, 10)) }
            .fold(
                onSuccess = { CoverSearchResult.Success(it) },
                onFailure = { CoverSearchResult.Failure(it.message ?: text(context, R.string.cover_error_search_network)) },
            )
    }

    fun bindCoverCandidate(context: Context, game: ScanGame, candidate: CoverSearchCandidate): ScanGame? {
        val prefix = "${candidate.source}_${stableKey(game.uri)}"
        val cover = if (candidate.source == AppSettingsStore.COVER_SOURCE_STEAM) {
            val appId = candidate.id.toIntOrNull()
            CoverImageCache.download(context, candidate.coverUrl, prefix, source = candidate.source)
                ?: appId?.let { SteamCoverSource.downloadSteamHeader(context, game, it) }
        } else {
            CoverImageCache.download(
                context = context,
                imageUrl = candidate.coverUrl,
                prefix = prefix,
                source = candidate.source,
            )
        }
        return cover?.let {
            CoverImageCache.deleteCachedCover(context, game.coverUri, exceptUri = it)
            game.copy(
                coverUri = it,
                coverSource = candidate.source,
            )
        }
    }

    private fun scrapeWithSource(context: Context, game: ScanGame, source: String): ScanGame? =
        when (source) {
            AppSettingsStore.COVER_SOURCE_HIKARINAGI -> HikarinagiCoverSource.fetchBestCover(context, game)
            AppSettingsStore.COVER_SOURCE_BANGUMI -> BangumiCoverSource.fetchBestCover(context, game)
            AppSettingsStore.COVER_SOURCE_STEAM -> SteamCoverSource.fetchBestCover(context, game)
            AppSettingsStore.COVER_SOURCE_VNDB -> VndbCoverService.fetchBestCover(context, game)
            else -> null
        }

    private fun searchWithSource(
        context: Context,
        source: String,
        keyword: String,
        limit: Int,
    ): List<CoverSearchCandidate> =
        when (source) {
            AppSettingsStore.COVER_SOURCE_HIKARINAGI -> HikarinagiCoverSource.searchCandidates(context, keyword, limit)
            AppSettingsStore.COVER_SOURCE_BANGUMI -> BangumiCoverSource.searchCandidates(context, keyword, limit)
            AppSettingsStore.COVER_SOURCE_STEAM -> SteamCoverSource.searchCandidates(context, keyword, limit)
            AppSettingsStore.COVER_SOURCE_VNDB -> VndbCoverService.searchCandidates(context, keyword, limit).map {
                CoverSearchCandidate(
                    source = AppSettingsStore.COVER_SOURCE_VNDB,
                    id = it.id,
                    title = it.title.ifBlank { it.originalTitle },
                    subtitle = it.originalTitle,
                    detail = detailText("VNDB", it.id, it.released, it.developer),
                    coverUrl = it.coverUrl,
                )
            }
            else -> emptyList()
        }

}

private object HikarinagiCoverSource {
    private const val SEARCH_URL = "https://api.hikarinagi.org/v3/search"
    private const val IMAGE_BASE_URL = "https://imagesp.yurari.moe/"

    fun fetchBestCover(context: Context, game: ScanGame): ScanGame? {
        val token = HikarinagiAuthService.getValidAccessToken(context) ?: return null
        val query = cleanTitle(game.title)
        if (query.isBlank()) return null

        val candidate = searchRawCandidates(context, token, query, 5)
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
        )
    }

    fun searchCandidates(context: Context, keyword: String, limit: Int): List<CoverSearchCandidate> {
        val token = HikarinagiAuthService.getValidAccessToken(context)
            ?: throw CoverSearchException(text(context, R.string.auth_hikarinagi_expired))
        val query = cleanTitle(keyword)
        if (query.isBlank()) return emptyList()
        return searchRawCandidates(context, token, query, limit).map {
            CoverSearchCandidate(
                source = AppSettingsStore.COVER_SOURCE_HIKARINAGI,
                id = it.id,
                title = it.title,
                subtitle = "",
                detail = detailText("Hikarinagi", it.id, text(context, R.string.cover_hikarinagi_votes, it.score)),
                score = it.score,
                coverUrl = it.coverUrl,
            )
        }
    }

    private fun searchRawCandidates(context: Context, token: String, query: String, limit: Int): List<CoverCandidate> {
        val url = "$SEARCH_URL?q=${query.urlEncode()}&types=galgame&page=1&page_size=${limit.coerceIn(1, 10)}"
        val json = httpJson(url, headers = mapOf("Authorization" to "Bearer $token"))
            ?: throw CoverSearchException(text(context, R.string.cover_error_hikarinagi_network))
        if (!json.optBoolean("success", false)) throw CoverSearchException(text(context, R.string.cover_error_hikarinagi_retry))
        val items = json.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).asSequence()
            .mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("type") == "galgame" }
            .mapNotNull { item ->
                val cover = item.optJSONObject("cover") ?: return@mapNotNull null
                val coverUrl = resolveHikarinagiImageUrl(cover.optString("url", ""))
                if (coverUrl.isBlank()) return@mapNotNull null
                CoverCandidate(
                    id = item.optString("id", ""),
                    title = firstNonEmpty(item.optString("title", ""), item.optString("subtitle", "")),
                    coverUrl = coverUrl,
                    score = cover.optInt("votes", 0),
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

private object BangumiCoverSource {
    private const val SEARCH_URL = "https://api.bgm.tv/v0/search/subjects"

    fun fetchBestCover(context: Context, game: ScanGame): ScanGame? {
        val query = cleanTitle(game.title)
        if (query.isBlank()) return null
        val candidate = searchCandidates(context, query, 1).firstOrNull() ?: return null
        val cover = CoverImageCache.download(context, candidate.coverUrl, "bangumi_${stableKey(game.uri)}", source = AppSettingsStore.COVER_SOURCE_BANGUMI)
            ?: return null
        return game.copy(
            coverUri = cover,
            coverSource = AppSettingsStore.COVER_SOURCE_BANGUMI,
        )
    }

    fun searchCandidates(context: Context, keyword: String, limit: Int): List<CoverSearchCandidate> {
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
            ?: throw CoverSearchException(text(context, R.string.cover_error_bangumi_network))
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).asSequence()
            .mapNotNull { data.optJSONObject(it) }
            .filter { it.optInt("type") == 4 }
            .mapNotNull { item ->
                val images = item.optJSONObject("images") ?: return@mapNotNull null
                val coverUrl = firstNonEmpty(images.optString("large", ""), images.optString("common", ""))
                if (coverUrl.isBlank()) return@mapNotNull null
                val name = item.optString("name", "")
                val nameCn = item.optString("name_cn", "")
                CoverSearchCandidate(
                    source = AppSettingsStore.COVER_SOURCE_BANGUMI,
                    id = item.optString("id", ""),
                    title = firstNonEmpty(nameCn, name),
                    subtitle = name,
                    detail = detailText("Bangumi", item.optString("id", ""), item.optString("date", "")),
                    coverUrl = coverUrl,
                )
            }
            .toList()
    }
}

private object SteamCoverSource {
    private const val SEARCH_URL = "https://store.steampowered.com/api/storesearch/"
    private const val DETAILS_URL = "https://store.steampowered.com/api/appdetails"

    fun fetchBestCover(context: Context, game: ScanGame): ScanGame? {
        val query = cleanTitle(game.title)
        if (query.isBlank()) return null
        for (candidate in searchCandidates(context, query, 3)) {
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

    fun searchCandidates(context: Context, keyword: String, limit: Int): List<CoverSearchCandidate> {
        val query = cleanTitle(keyword)
        if (query.isBlank()) return emptyList()
        val search = httpJson("$SEARCH_URL?term=${query.urlEncode()}&l=schinese&cc=CN")
            ?: throw CoverSearchException(text(context, R.string.cover_error_steam_network))
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
    val coverUrl: String,
    val score: Int,
)

private fun ScanGame.asCoverOnlyUpdateFrom(base: ScanGame): ScanGame =
    base.copy(
        coverUri = coverUri,
        coverSource = coverSource,
    )

private fun httpJson(
    url: String,
    method: String = "GET",
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): JSONObject? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TyranorNext/1.0")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        if (conn.responseCode !in 200..299) return null
        val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        JSONObject(text)
    } catch (_: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun text(context: Context, @StringRes id: Int, vararg args: Any): String =
    AppLocaleController.wrap(context).getString(id, *args)
