package com.tyranor.next.core.cover

import android.content.Context
import android.net.Uri
import com.tyranor.next.core.settings.AppSettingsStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 搜索失败：携带错误码而非文案。`detail` 只进异常 message 供日志排查，绝不直接展示；
 * 文案映射见 `ui/cover/CoverErrorMessages.kt`。
 */
internal class CoverSearchException(
    val error: CoverErrorCode,
    detail: String? = null,
) : RuntimeException(detail ?: error.name)

/** HTTP 响应：状态码 + 已解析的 JSON 体（非 JSON 体时为 null）。 */
internal data class HttpJsonResult(val status: Int, val json: JSONObject?)

/** JSON 响应体上限：与 CoverImageCache 的图片上限同理，避免被超大响应打爆内存。 */
private const val MAX_JSON_CHARS = 4 * 1024 * 1024

/**
 * 带状态码的 JSON 请求：供需要区分 HTTP 状态的来源使用（如 NextMoe 的 429 限流）。
 * 传输异常统一返回 `status = 0`；已在读体前拿到的状态码不会因读体失败而丢失。
 */
internal fun httpJsonResult(
    url: String,
    method: String = "GET",
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): HttpJsonResult {
    var conn: HttpURLConnection? = null
    return try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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
        conn = connection
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val status = connection.responseCode
        val text = runCatching {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readTextCapped(MAX_JSON_CHARS) }
        }.getOrNull()
        HttpJsonResult(status, text?.let { runCatching { JSONObject(it) }.getOrNull() })
    } catch (_: Exception) {
        HttpJsonResult(0, null)
    } finally {
        conn?.disconnect()
    }
}

private fun java.io.Reader.readTextCapped(maxChars: Int): String {
    val buffer = CharArray(16 * 1024)
    val out = StringBuilder()
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (out.length + read > maxChars) throw IllegalStateException("json response too large")
        out.append(buffer, 0, read)
    }
    return out.toString()
}

/**
 * 请求最小间隔节流：VNDB / 未萌 NextMoe 都是「单机串行 + 服务端限流」的来源。
 * 必须在 IO 线程调用（内部阻塞）。
 */
internal class RequestThrottle(private val minIntervalMs: Long) {
    private var lastRequestTime = 0L

    @Synchronized
    fun await() {
        val wait = minIntervalMs - (System.currentTimeMillis() - lastRequestTime)
        if (wait > 0) Thread.sleep(wait)
        lastRequestTime = System.currentTimeMillis()
    }
}

/** 仅成功（2xx）时返回 JSON，行为与各来源原有 httpJson 保持一致。 */
internal fun httpJson(
    url: String,
    method: String = "GET",
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): JSONObject? = httpJsonResult(url, method, body, headers)
    .let { if (it.status in 200..299) it.json else null }

internal fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

/**
 * 可取消的下载句柄：取消时标记并断开连接，避免协程取消后仍阻塞在 `input.read` 上。
 * 下载线程每次读取前检查 [isCancelled]；[attach] 在连接建立后挂接，供取消方立即断开。
 */
internal class CoverDownloadCancel {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var connection: HttpURLConnection? = null

    val isCancelled: Boolean get() = cancelled.get()

    fun attach(target: HttpURLConnection) {
        connection = target
        if (cancelled.get()) target.disconnect()
    }

    fun cancel() {
        cancelled.set(true)
        connection?.disconnect()
    }
}

internal object CoverImageCache {
    private const val MAX_COVER_BYTES = 20L * 1024L * 1024L

    fun download(
        context: Context,
        imageUrl: String,
        prefix: String,
        source: String? = null,
        referer: String? = source?.let(::coverRefererForSource),
        cookie: String? = source?.let(::coverCookieForSource),
        persistent: Boolean = true,
        cancel: CoverDownloadCancel? = null,
        onProgress: (Float) -> Unit = {},
    ): String? {
        if (imageUrl.isBlank()) return null
        if (cancel?.isCancelled == true) return null
        val dir = coverCacheDir(context, persistent)
        if (!dir.exists() && !dir.mkdirs()) return null
        val safePrefix = prefix.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val target = File(dir, "${safePrefix}_${stableKey(imageUrl)}.jpg")
        if (target.isFile && target.length() > 0) return Uri.fromFile(target).toString()

        var conn: HttpURLConnection? = null
        var tmp: File? = null
        return try {
            conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                referer?.let { setRequestProperty("Referer", it) }
                cookie?.let { setRequestProperty("Cookie", it) }
            }
            // 挂接后取消方可以直接 disconnect，立即打断阻塞中的 read。
            cancel?.attach(conn)
            if (cancel?.isCancelled == true) return null
            if (conn.responseCode !in 200..299) return null
            if (conn.contentLengthLong > MAX_COVER_BYTES) return null
            val length = conn.contentLengthLong
            onProgress(0.05f)
            val tempFile = File.createTempFile(target.nameWithoutExtension, ".tmp", dir)
            tmp = tempFile
            var total = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (cancel?.isCancelled == true) return null
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_COVER_BYTES) error("cover too large")
                        output.write(buffer, 0, read)
                        if (length > 0L) {
                            // 只在整数百分比变化时回调，下载线程不必每个读块都通知 UI。
                            val percent = ((total * 100L) / length).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(0.05f + percent / 100f * 0.85f)
                            }
                        }
                    }
                }
            }
            if (total <= 0L) error("empty cover")
            onProgress(0.95f)
            moveTempCover(tempFile, target)
            Uri.fromFile(target).toString()
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
            tmp?.delete()
        }
    }

    fun deleteCachedCover(context: Context, coverUri: String?, exceptUri: String? = null) {
        if (coverUri.isNullOrBlank() || coverUri == exceptUri) return
        val dir = coverCacheDir(context)
        val file = runCatching { File(Uri.parse(coverUri).path ?: return) }.getOrNull() ?: return
        val cachePath = runCatching { dir.canonicalPath }.getOrNull() ?: return
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return
        val exceptPath = runCatching { File(Uri.parse(exceptUri.orEmpty()).path.orEmpty()).canonicalPath }.getOrNull()
        val inCacheDir = filePath == cachePath || filePath.startsWith("$cachePath${File.separator}")
        if (filePath != exceptPath && inCacheDir) {
            file.delete()
        }
    }

    private fun moveTempCover(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    private fun coverCacheDir(context: Context, persistent: Boolean = true): File =
        if (persistent) {
            persistentCoverDir(context)
        } else {
            File(context.applicationContext.cacheDir, "covers_preview")
        }
}

/**
 * 持久封面目录（`filesDir/covers_remote`）：在线封面、自定义封面与 XSYS4 图标缓存共用。
 * [CoverImageCache.deleteCachedCover] 只在此目录内删除，凡写入此处的封面都由它统一回收。
 */
internal fun persistentCoverDir(context: Context): File =
    File(context.applicationContext.filesDir, "covers_remote")

internal fun cleanTitle(s: String): String {
    val localizedEditionWords = listOf(
        "\u6C49\u5316",
        "\u4E2D\u6587\u7248",
        "\u65E5\u6587\u7248",
        "\u4F53\u9A8C\u7248",
    ).joinToString("|")
    val cleaned = s.replace("""\[[^\]]*\]|【[^】]*】""".toRegex(), " ")
        .replace("[\\[\\]【】]".toRegex(), " ")
        .replace("[（）()].*".toRegex(), " ")
        // 英文版本词必须整词匹配，避免“Dispatch”命中“patch”被误截断（中文词无词边界）
        .replace("(?i)\\b(complete|trial|patch)\\b|$localizedEditionWords".toRegex(), " ")
        .replace('_', ' ')
        // 清理后可能留下连续空格（如 "Game Complete Edition"），统一折叠
        .replace("\\s+".toRegex(), " ")
        .trim()
    return cleaned.ifEmpty { s.trim() }
}

internal fun stableKey(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(StandardCharsets.UTF_8))
    return bytes.take(8).joinToString("") { "%02x".format(it) }
}

internal fun firstNonEmpty(a: String?, b: String?): String {
    return when {
        !a.isNullOrBlank() && a != "null" -> a
        !b.isNullOrBlank() && b != "null" -> b
        else -> ""
    }
}

internal fun detailText(vararg values: String?): String =
    values.filter { !it.isNullOrBlank() && it != "null" }.joinToString(" · ")

internal fun coverRefererForSource(source: String): String? = when (source) {
    AppSettingsStore.COVER_SOURCE_BANGUMI -> "https://bgm.tv/"
    AppSettingsStore.COVER_SOURCE_STEAM -> "https://store.steampowered.com/"
    AppSettingsStore.COVER_SOURCE_VNDB -> "https://vndb.org/"
    else -> null
}

internal fun coverCookieForSource(source: String): String? = when (source) {
    AppSettingsStore.COVER_SOURCE_VNDB -> "vndb_img=1; vndb_samesite=1"
    else -> null
}
