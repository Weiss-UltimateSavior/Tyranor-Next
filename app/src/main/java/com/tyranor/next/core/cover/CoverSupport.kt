package com.tyranor.next.core.cover

import android.content.Context
import android.net.Uri
import com.tyranor.next.core.settings.AppSettingsStore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class CoverSearchException(message: String) : RuntimeException(message)

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
    ): String? {
        if (imageUrl.isBlank()) return null
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
            if (conn.responseCode !in 200..299) return null
            if (conn.contentLengthLong > MAX_COVER_BYTES) return null
            val tempFile = File.createTempFile(target.nameWithoutExtension, ".tmp", dir)
            tmp = tempFile
            var total = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_COVER_BYTES) error("cover too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total <= 0L) error("empty cover")
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
            File(context.applicationContext.filesDir, "covers_remote")
        } else {
            File(context.applicationContext.cacheDir, "covers_preview")
        }
}

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
        .replace("(?i)complete|$localizedEditionWords|trial|patch".toRegex(), " ")
        .replace('_', ' ')
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
