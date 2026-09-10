package com.tyranor.next.core.patch

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.annotation.StringRes
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.i18n.AppLocaleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class KrkrPatchEntry(
    val timestamp: Long,
    val brand: String,
    val path: String,
    val name: String,
    val patches: List<String>,
)

data class KrkrPatchInstallResult(
    val installed: List<String>,
    val target: String,
)

object KrkrOnlinePatchService {
    private const val INDEX_URL = "https://zeas2.github.io/Kirikiroid2_patch/patch/alldata.js"
    private const val PATCH_BASE_URL = "https://zeas2.github.io/Kirikiroid2_patch/patch/"
    /** 单个补丁下载体积上限（200MB）：索引异常或 URL 指向超大资源时防止写满存储。 */
    private const val MAX_PATCH_BYTES = 200L * 1024 * 1024
    private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024
    private val indexRegex = Regex("""\[(\d+), "(.+?)", "(.+?)", "(.+?)", \[(.+)]],?""")

    // 安装互斥锁：UI 层的 installing 守卫会随 Activity 重建丢失（旋转屏幕时旧协程的阻塞 IO
    // 仍会跑完并继续写盘），进程级锁保证 downloadAndInstall 任意时刻只有一个实例在下载/写入，
    // 避免并发任务交错写同一临时文件、以及先完成者 finally 删除他人正在写的文件。参照
    // GameSaveManager.importLock 的既有模式。
    private val installLock = Mutex()

    suspend fun fetchPatchIndex(context: Context): List<KrkrPatchEntry> = withContext(Dispatchers.IO) {
        val text = httpGetText(context, INDEX_URL)
        text.lineSequence()
            .mapNotNull { line -> parseLine(line.trim()) }
            .sortedWith(compareBy<KrkrPatchEntry> { it.name }.thenBy { it.brand })
            .toList()
    }

    fun search(entries: List<KrkrPatchEntry>, keyword: String): List<KrkrPatchEntry> {
        val query = keyword.trim()
        if (query.isBlank()) return entries
        return entries.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.brand.contains(query, ignoreCase = true) ||
                it.path.contains(query, ignoreCase = true)
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        game: ScanGame,
        urls: List<String>,
        progress: (String) -> Unit = {},
    ): KrkrPatchInstallResult = withContext(Dispatchers.IO) {
        require(game.engine == EngineType.KIRIKIRI) { text(context, R.string.patch_error_only_kirikiri) }
        require(urls.isNotEmpty()) { text(context, R.string.patch_error_select_patch) }

        installLock.withLock {
            val downloadDir = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }
            val installed = mutableListOf<String>()
            val targetDescription = resolveTargetDescription(game)

            urls.forEach { url ->
                val fileName = fileNameFromUrl(url)
                progress(text(context, R.string.patch_progress_downloading, fileName))
                // 唯一临时文件：并发只会被 installLock 串行化，但保险起见仍用 createTempFile
                // 保证不与历史残留文件（崩溃遗留的 .tmp）冲突，finally 只清理自己的临时文件。
                val tempFile = File.createTempFile("$fileName.", ".tmp", downloadDir)
                try {
                    downloadToFile(context, url, tempFile)
                    progress(text(context, R.string.patch_progress_writing, fileName))
                    copyIntoGameDir(context, game, tempFile, fileName)
                    installed += fileName
                } finally {
                    tempFile.delete()
                }
            }

            KrkrPatchInstallResult(installed = installed, target = targetDescription)
        }
    }

    private fun parseLine(line: String): KrkrPatchEntry? {
        val match = indexRegex.matchEntire(line) ?: return null
        val patches = match.groupValues[5]
            .split(", ")
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .map { PATCH_BASE_URL + it }

        return KrkrPatchEntry(
            timestamp = match.groupValues[1].toLongOrNull()?.times(1000L) ?: 0L,
            brand = match.groupValues[2],
            path = match.groupValues[3],
            name = match.groupValues[4],
            patches = patches,
        )
    }

    private fun httpGetText(context: Context, url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = "GET"
        }
        return connection.use {
            if (responseCode !in 200..299) error(text(context, R.string.patch_index_fetch_failed_http, responseCode))
            inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
        }
    }

    private fun downloadToFile(context: Context, url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        connection.use {
            if (responseCode !in 200..299) error(text(context, R.string.patch_download_failed_http, responseCode))
            inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PATCH_BYTES) {
                            throw IOException(text(context, R.string.patch_download_too_large))
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun copyIntoGameDir(context: Context, game: ScanGame, source: File, fileName: String) {
        resolveWritableGameFileDir(game)?.let { dir ->
            source.copyTo(File(dir, fileName), overwrite = true)
            return
        }

        val uri = runCatching { Uri.parse(game.uri) }.getOrNull()
        if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
            val docDir = DocumentFile.fromTreeUri(context, uri)
            if (docDir != null && docDir.isDirectory) {
                docDir.findFile(fileName)?.delete()
                val outFile = docDir.createFile("application/octet-stream", fileName)
                if (outFile != null) {
                    context.contentResolver.openOutputStream(outFile.uri, "w")?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error(text(context, R.string.patch_error_open_write_stream))
                    return
                }
            }
        }

        error(text(context, R.string.patch_error_write_game_dir))
    }

    private fun resolveWritableGameFileDir(game: ScanGame): File? {
        val candidates = listOfNotNull(
            game.uri.takeIf { it.startsWith("/") },
            GamePathUtils.safUriToPath(game.uri),
        ).distinct()

        return candidates
            .map { File(it) }
            .firstOrNull { it.exists() && it.isDirectory && it.canWrite() }
    }

    private fun resolveTargetDescription(game: ScanGame): String {
        return resolveWritableGameFileDir(game)?.absolutePath ?: game.uri
    }

    private fun fileNameFromUrl(url: String): String {
        val rawName = URL(url).path.substringAfterLast('/').ifBlank { "patch.xp3" }
        return URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private inline fun <T : HttpURLConnection, R> T.use(block: T.() -> R): R {
        return try {
            block()
        } finally {
            disconnect()
        }
    }

    private fun text(context: Context, @StringRes id: Int, vararg args: Any): String =
        AppLocaleController.wrap(context).getString(id, *args)
}
