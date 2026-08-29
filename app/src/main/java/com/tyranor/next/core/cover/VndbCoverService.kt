package com.tyranor.next.core.cover

import android.content.Context
import android.net.Uri
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class VndbCandidate(
    val id: String,
    val title: String,
    val originalTitle: String,
    val developer: String,
    val released: String,
    val coverUrl: String,
)

object VndbCoverService {
    private const val ENDPOINT = "https://api.vndb.org/kana/vn"
    private const val FIELDS =
        "title,alttitle,titles.lang,titles.title,titles.latin,titles.official,titles.main,released,image.url,image.thumbnail,developers.name,developers.original"
    private const val MIN_REQUEST_INTERVAL_MS = 1100L

    private var lastRequestTime = 0L

    fun fetchBestCover(context: Context, game: ScanGame): ScanGame? {
        if (!game.coverUri.isNullOrBlank()) return game
        val candidate = searchCandidates(context, game.title, 1).firstOrNull() ?: return null
        val cover = CoverImageCache.download(
            context,
            candidate.coverUrl,
            "vndb_${stableKey(game.uri)}",
            source = AppSettingsStore.COVER_SOURCE_VNDB,
        ) ?: return null
        return game.copy(
            coverUri = cover,
            coverSource = AppSettingsStore.COVER_SOURCE_VNDB,
            vndbId = candidate.id,
            metadataTitle = candidate.displayTitle(),
        )
    }

    fun bindCandidate(context: Context, game: ScanGame, candidate: VndbCandidate): ScanGame? {
        val cover = CoverImageCache.download(
            context,
            candidate.coverUrl,
            "vndb_${stableKey(game.uri)}",
            source = AppSettingsStore.COVER_SOURCE_VNDB,
        ) ?: return null
        CoverImageCache.deleteCachedCover(context, game.coverUri, exceptUri = cover)
        return game.copy(
            coverUri = cover,
            coverSource = AppSettingsStore.COVER_SOURCE_VNDB,
            vndbId = candidate.id,
            metadataTitle = candidate.displayTitle(),
        )
    }

    /** 将用户从相册选择的图片保存为该游戏的自定义封面，返回更新后的游戏（失败返回 null）。 */
    fun saveCustomCover(context: Context, game: ScanGame, pickedUri: Uri): ScanGame? {
        val dir = File(context.filesDir, "covers_remote")
        if (!dir.exists() && !dir.mkdirs()) return null
        val ext = when (context.contentResolver.getType(pickedUri)?.lowercase()?.substringAfterLast('/')) {
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            else -> "jpg"
        }
        val target = File(dir, "custom_${stableKey(game.uri)}.$ext")
        return try {
            context.contentResolver.openInputStream(pickedUri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return null
            val targetUri = Uri.fromFile(target).toString()
            CoverImageCache.deleteCachedCover(context, game.coverUri, exceptUri = targetUri)
            game.copy(coverUri = targetUri, coverSource = AppSettingsStore.COVER_SOURCE_CUSTOM)
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    fun searchCandidates(context: Context, keyword: String, limit: Int): List<VndbCandidate> {
        val query = cleanTitle(keyword)
        if (query.isBlank()) return emptyList()

        val body = JSONObject()
            .put("filters", JSONArray().put("search").put("=").put(query))
            .put("fields", FIELDS)
            .put("sort", "searchrank")
            .put("results", limit.coerceIn(1, 10))

        throttle()

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "TyranorNext/1.0")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw CoverSearchException(text(context, R.string.cover_error_vndb_network))
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
            buildList {
                for (i in 0 until results.length()) {
                    results.optJSONObject(i)?.let { add(parseCandidate(it)) }
                }
            }
        } catch (e: CoverSearchException) {
            throw e
        } catch (_: Exception) {
            throw CoverSearchException(text(context, R.string.cover_error_vndb_network))
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseCandidate(o: JSONObject): VndbCandidate {
        var chineseTitle = ""
        var originalTitle = o.optString("alttitle", "")
        o.optJSONArray("titles")?.let { titles ->
            for (i in 0 until titles.length()) {
                val t = titles.optJSONObject(i) ?: continue
                val lang = t.optString("lang", "")
                val title = t.optString("title", "")
                if ((lang == "zh-Hans" || lang == "zh-Hant" || lang == "zh") && chineseTitle.isEmpty()) {
                    chineseTitle = title
                }
                if (t.optBoolean("main", false) && originalTitle.isEmpty()) originalTitle = title
            }
        }
        val image = o.optJSONObject("image")
        val devs = o.optJSONArray("developers")
        val developers = buildList {
            if (devs != null) {
                for (i in 0 until devs.length()) {
                    if (size >= 3) break
                    val d = devs.optJSONObject(i) ?: continue
                    val name = firstNonEmpty(d.optString("original", ""), d.optString("name", ""))
                    if (name.isNotBlank()) add(name)
                }
            }
        }.joinToString(" / ")
        return VndbCandidate(
            id = o.optString("id", ""),
            title = firstNonEmpty(chineseTitle, o.optString("title", "")),
            originalTitle = firstNonEmpty(originalTitle, o.optString("title", "")),
            developer = developers,
            released = o.optString("released", ""),
            coverUrl = firstNonEmpty(image?.optString("thumbnail", ""), image?.optString("url", "")),
        )
    }

    @Synchronized
    private fun throttle() {
        val now = System.currentTimeMillis()
        val wait = MIN_REQUEST_INTERVAL_MS - (now - lastRequestTime)
        if (wait > 0) Thread.sleep(wait)
        lastRequestTime = System.currentTimeMillis()
    }

    private fun VndbCandidate.displayTitle(): String =
        firstNonEmpty(title, originalTitle).trim()

    private fun text(context: Context, id: Int): String =
        AppLocaleController.wrap(context).getString(id)
}
