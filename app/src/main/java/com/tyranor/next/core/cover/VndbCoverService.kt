package com.tyranor.next.core.cover

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.net.MAX_JSON_CHARS
import com.tyranor.next.core.net.optNonBlankString
import com.tyranor.next.core.net.readTextCapped
import com.tyranor.next.core.net.vndbThrottle
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class VndbCandidate(
    val id: String,
    val title: String,
    val originalTitle: String,
    val developer: String,
    val released: String,
    /** 按「VNDB 封面大图」开关解析后的下载地址：开=原图 image.url，关=缩略图 image.thumbnail。 */
    val coverUrl: String,
    /** 始终为缩略图（image.thumbnail），供搜索预览省流量使用。 */
    val thumbnailUrl: String,
)

object VndbCoverService {
    private const val TAG = "VndbCoverService"
    private const val ENDPOINT = "https://api.vndb.org/kana/vn"
    private const val FIELDS =
        "title,alttitle,titles.lang,titles.title,titles.latin,titles.official,titles.main,released,image.url,image.thumbnail,developers.name,developers.original"
    private val customCoverFileLock = Any()

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
            // 已有绑定优先：搜索首条命中可能与手工绑定不是同一条目，覆盖会让状态写入打到错误条目
            vndbId = game.vndbId?.takeIf { it.isNotBlank() } ?: candidate.id.takeIf { it.isNotBlank() },
            metadataTitle = game.metadataTitle?.takeIf { it.isNotBlank() }
                ?: candidate.displayTitle().takeIf { it.isNotBlank() },
        )
    }

    internal fun parseSearchResults(text: String, preferLarge: Boolean): List<VndbCandidate> {
        val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val candidate = parseCandidate(obj, preferLarge)
                if (candidate.id.isNotBlank()) add(candidate)
            }
        }
    }

    /**
     * 将用户从相册选择的图片保存为该游戏的自定义封面，返回更新后的游戏（失败返回 null）。
     * 先写入临时文件并计算内容短哈希（`custom_<gameKey>_<hash>.<ext>`），校验非空后再原子替换，
     * 同名不同图也获得新 URI 以刷新缓存与列表；失败/取消仅清理本次新建文件，绝不破坏已有封面。
     */
    internal fun customCoverExtension(mimeType: String?): String =
        when (mimeType?.trim()?.lowercase()?.substringAfterLast('/')) {
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            else -> "jpg"
        }

    fun saveCustomCover(context: Context, game: ScanGame, pickedUri: Uri): ScanGame? {
        val ext = customCoverExtension(context.contentResolver.getType(pickedUri))
        return saveCustomCoverFromStream(
            context = context,
            game = game,
            ext = ext,
            openStream = { context.contentResolver.openInputStream(pickedUri) },
        )
    }

    internal fun saveCustomCoverFromStream(
        context: Context,
        game: ScanGame,
        ext: String,
        openStream: () -> InputStream?,
        toUriString: (File) -> String = { Uri.fromFile(it).toString() },
    ): ScanGame? {
        val dir = persistentCoverDir(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        val gameKey = stableKey(game.uri)
        val wasInLibrary = GameLibraryFacade.loadGames(context).any { it.uri == game.uri }
        var tmpFile: File? = null
        var createdTarget: File? = null
        var targetUriForCleanup: String? = null
        return try {
            val tempFile = File.createTempFile("custom_${gameKey}_", ".$ext.tmp", dir)
            tmpFile = tempFile
            val digest = MessageDigest.getInstance("SHA-1")
            var total = 0L
            openStream()?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > CoverImageCache.MAX_COVER_BYTES) return null
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return null
            if (total <= 0L) return null
            val contentHash = digest.digest().take(6).joinToString("") { "%02x".format(it) }
            val target = File(dir, "custom_${gameKey}_$contentHash.$ext")
            val targetUri = toUriString(target)
            targetUriForCleanup = targetUri
            synchronized(customCoverFileLock) {
                val existedBefore = target.isFile && target.length() > 0L
                if (!existedBefore) {
                    CoverImageCache.moveTempCover(tempFile, target)
                    createdTarget = target
                }
            }
            fun cleanupCreatedTargetIfUnreferenced() {
                val file = createdTarget ?: return
                synchronized(customCoverFileLock) {
                    if (GameLibraryFacade.loadGames(context).none { it.coverUri == targetUri }) {
                        file.delete()
                    }
                }
            }
            persistCustomCover(context, game, targetUri, wasInLibrary) ?: run {
                cleanupCreatedTargetIfUnreferenced()
                null
            }
        } catch (e: CancellationException) {
            createdTarget?.let { file ->
                val uri = targetUriForCleanup
                synchronized(customCoverFileLock) {
                    if (uri == null || GameLibraryFacade.loadGames(context).none { it.coverUri == uri }) {
                        file.delete()
                    }
                }
            }
            throw e
        } catch (_: Exception) {
            createdTarget?.let { file ->
                val uri = targetUriForCleanup
                synchronized(customCoverFileLock) {
                    if (uri == null || GameLibraryFacade.loadGames(context).none { it.coverUri == uri }) {
                        file.delete()
                    }
                }
            }
            null
        } finally {
            tmpFile?.delete()
        }
    }

    private fun persistCustomCover(
        context: Context,
        game: ScanGame,
        targetUri: String,
        wasInLibrary: Boolean,
    ): ScanGame? {
        var replacedCoverUri: String? = game.coverUri
        val persisted = GameLibraryFacade.updateGameCoverMetadata(context, game.uri) { current ->
            replacedCoverUri = current.coverUri
            current.copy(coverUri = targetUri, coverSource = AppSettingsStore.COVER_SOURCE_CUSTOM)
        }
        if (persisted != null) {
            // 通知游戏库变更：即使用户旋转屏幕取消了 UI 协程，MainLibraryViewModel 也会从 Room 同步最新封面字段
            GameLibraryFacade.notifyGamesChanged()
            CoverImageCache.deleteCachedCover(context, replacedCoverUri, exceptUri = persisted.coverUri)
            return persisted
        }
        val current = GameLibraryFacade.loadGames(context).firstOrNull { it.uri == game.uri }
        if (current == null) {
            // 拷贝期间游戏已被并发删除：不再返回伪成功副本，交由调用方回收新建封面
            if (wasInLibrary) return null
            // 独立调用（未入库/测试桩）：退回纯内存结果（既有返回语义），照常回收被替换的旧图
            CoverImageCache.deleteCachedCover(context, game.coverUri, exceptUri = targetUri)
            return game.copy(coverUri = targetUri, coverSource = AppSettingsStore.COVER_SOURCE_CUSTOM)
        }
        // 幂等：并发写已把行更新为同一目标态（重复保存同一文件）；照常回收被替换的旧图
        CoverImageCache.deleteCachedCover(context, replacedCoverUri, exceptUri = current.coverUri)
        return current
    }

    fun searchCandidates(context: Context, keyword: String, limit: Int): List<VndbCandidate> {
        val query = cleanTitle(keyword)
        if (query.isBlank()) return emptyList()

        val body = JSONObject()
            .put("filters", JSONArray().put("search").put("=").put(query))
            .put("fields", FIELDS)
            .put("sort", "searchrank")
            .put("results", limit.coerceIn(1, 10))

        vndbThrottle.await()

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
            if (conn.responseCode !in 200..299) throw CoverSearchException(CoverErrorCode.VNDB_NETWORK)
            // 与 httpJson 的 readTextCapped 同上限（BK-22）：超大响应触顶抛 IllegalStateException
            // → 下方既有泛 catch → CoverSearchException(VNDB_NETWORK)，无新失败形态
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readTextCapped(MAX_JSON_CHARS) }
            parseSearchResults(text, AppSettingsStore.isVndbLargeCover(context))
        } catch (e: CoverSearchException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 与 HttpJson 同口径：脱敏 Log.w 留排障线索，再收敛为类型化错误码
            Log.w(TAG, "vndb search request failed", e)
            throw CoverSearchException(CoverErrorCode.VNDB_NETWORK)
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseCandidate(o: JSONObject, preferLarge: Boolean): VndbCandidate {
        var chineseTitle = ""
        var originalTitle = o.optNonBlankString("alttitle").orEmpty()
        o.optJSONArray("titles")?.let { titles ->
            for (i in 0 until titles.length()) {
                val t = titles.optJSONObject(i) ?: continue
                val lang = t.optNonBlankString("lang").orEmpty()
                val title = t.optNonBlankString("title").orEmpty()
                if ((lang == "zh-Hans" || lang == "zh-Hant" || lang == "zh") && chineseTitle.isEmpty()) {
                    chineseTitle = title
                }
                if (t.optBoolean("main", false) && originalTitle.isEmpty()) originalTitle = title
            }
        }
        val image = o.optJSONObject("image")
        // image.url 为原图（实测 v17 1080×1529），image.thumbnail 仅 256×362；
        // 大图开关关闭时沿用缩略图，避免额外流量。
        val largeUrl = image?.optNonBlankString("url").orEmpty()
        val thumbnailUrl = firstNonEmpty(image?.optNonBlankString("thumbnail"), largeUrl)
        val devs = o.optJSONArray("developers")
        val developers = buildList {
            if (devs != null) {
                for (i in 0 until devs.length()) {
                    if (size >= 3) break
                    val d = devs.optJSONObject(i) ?: continue
                    val name = firstNonEmpty(d.optNonBlankString("original"), d.optNonBlankString("name"))
                    if (name.isNotBlank()) add(name)
                }
            }
        }.joinToString(" / ")
        val fallbackTitle = o.optNonBlankString("title")
        return VndbCandidate(
            id = o.optNonBlankString("id").orEmpty(),
            title = firstNonEmpty(chineseTitle, fallbackTitle),
            originalTitle = firstNonEmpty(originalTitle, fallbackTitle),
            developer = developers,
            released = o.optNonBlankString("released").orEmpty(),
            coverUrl = if (preferLarge) firstNonEmpty(largeUrl, thumbnailUrl) else thumbnailUrl,
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun VndbCandidate.displayTitle(): String =
        firstNonEmpty(title, originalTitle).trim()
}
