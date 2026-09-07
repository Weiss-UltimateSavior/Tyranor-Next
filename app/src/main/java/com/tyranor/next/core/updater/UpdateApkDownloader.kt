package com.tyranor.next.core.updater

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface UpdateDownloadResult {
    /** 下载并校验成功，apkFile 为可直接安装的最终文件。 */
    data class Success(val apkFile: File) : UpdateDownloadResult

    /** 下载或校验失败，message 为本地化错误文案。 */
    data class Failed(val message: String) : UpdateDownloadResult

    // 注意：用户取消不在此表达——取消以 CancellationException 重抛（结构化并发约定），
    // 临时文件由 finally 清理，UI 层自行感知取消并复位弹窗状态。
}

object UpdateApkDownloader {
    private const val TAG = "UpdateApkDownloader"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val BUFFER_SIZE = 8 * 1024

    /**
     * 下载 [asset] 指向的 APK 到应用缓存目录的 updates 子目录。
     *
     * 进度通过 [onProgress] 上报（约 100ms 节流）；协程取消时以 CancellationException 重抛并清理临时文件。
     * 除取消外的全部错误在内部消化为 [UpdateDownloadResult.Failed]，message 已本地化，原始异常仅写日志。
     */
    suspend fun download(
        context: Context,
        asset: UpdateAsset,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): UpdateDownloadResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val localized = AppLocaleController.wrap(appContext)
        try {
            val downloadDir = getUpdatesDir(appContext).apply { mkdirs() }
            cleanupStaleParts(downloadDir)

            // 磁盘空间预检：可用空间需大于 APK 体积（预留 16MB 余量）；StatFs 异常时跳过预检
            if (asset.size > 0) {
                val availableBytes = runCatching { StatFs(downloadDir.absolutePath).availableBytes }.getOrNull()
                if (availableBytes != null && availableBytes < asset.size + 16L * 1024 * 1024) {
                    throw UpdateDownloadBusinessException(R.string.update_download_insufficient_space)
                }
            }

            val finalFile = File(downloadDir, sanitizeFileName(asset.name))
            // 固定 "update_" 前缀：createTempFile 要求前缀至少 3 字符，资产名过短时会抛异常
            val tempFile = File.createTempFile("update_", ".apk.part", downloadDir)
            try {
                downloadTo(tempFile, asset, onProgress)
                verify(tempFile, asset, localized)
                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    throw UpdateDownloadBusinessException(R.string.update_download_move_failed)
                }
                keepOnlyLatestApk(downloadDir, finalFile)
                UpdateDownloadResult.Success(finalFile)
            } finally {
                // 失败/取消时清理临时文件；成功后 tempFile 已改名，delete() 无副作用
                tempFile.delete()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (business: UpdateDownloadBusinessException) {
            val message = business.arg?.let { arg -> localized.getString(business.messageId, arg) }
                ?: localized.getString(business.messageId)
            UpdateDownloadResult.Failed(message)
        } catch (error: Throwable) {
            // 非预期异常（IO/DNS 等）：原始信息仅写日志，弹窗统一回落通用本地化文案
            Log.w(TAG, "APK download failed: ${asset.name}", error)
            UpdateDownloadResult.Failed(localized.getString(R.string.update_download_failed_generic))
        }
    }

    /** 更新 APK 的存放目录：优先外部缓存，外置存储不可用时回退内部存储（file_paths.xml 两条均已声明）。 */
    fun getUpdatesDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "updates")
    }

    /** 启动时清理上次异常退出遗留的 .part 临时文件。 */
    fun cleanupStaleParts(downloadDir: File) {
        downloadDir.listFiles { file -> file.name.endsWith(".apk.part") }?.forEach { it.delete() }
    }

    /** 跨版本更新后仅保留最新下载的 APK，避免 updates/ 累积多个上百 MB 的安装包。 */
    private fun keepOnlyLatestApk(downloadDir: File, latest: File) {
        downloadDir.listFiles { file -> file.path != latest.path && file.name.endsWith(".apk") }?.forEach { it.delete() }
    }

    private suspend fun downloadTo(
        target: File,
        asset: UpdateAsset,
        onProgress: (Long, Long) -> Unit,
    ) {
        val connection = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TyranorNext")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw UpdateDownloadBusinessException(R.string.update_download_http_failed, code)
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
            var downloaded = 0L
            var lastReportAt = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        // 用户取消（协程取消）时立即中断下载
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        // 约 100ms 节流上报一次进度，避免刷新过快
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt >= 100 || (total > 0 && downloaded >= total)) {
                            lastReportAt = now
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            onProgress(downloaded, if (total > 0) total else downloaded)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 业务性失败（空间不足/HTTP 非 2xx/校验不过/移动失败），messageId 为本地化资源，arg 可选（如 HTTP 状态码）。
     * 全部转换为本地化文案进弹窗，绝不携带英文原文。
     */
    private class UpdateDownloadBusinessException(
        val messageId: Int,
        val arg: Int? = null,
    ) : RuntimeException()

    /** 优先按 GitHub digest（sha256:xxx）校验；无 digest 时退化为体积校验。 */
    private fun verify(file: File, asset: UpdateAsset, localized: Context) {
        val digest = asset.digest
        if (!digest.isNullOrBlank()) {
            val algorithm = digest.substringBefore(':', "sha256").lowercase()
            val expected = digest.substringAfter(':', "").lowercase()
            if (expected.isNotBlank() && algorithm in setOf("sha256", "sha512", "sha1", "md5")) {
                val actual = file.inputStream().use { input ->
                    val md = MessageDigest.getInstance(algorithm)
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        md.update(buffer, 0, read)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw business()
                }
                return
            }
        }
        if (asset.size > 0 && file.length() != asset.size) {
            throw business()
        }
    }

    private fun business(): UpdateDownloadBusinessException =
        UpdateDownloadBusinessException(R.string.update_download_checksum_failed)

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "update.apk" else cleaned
    }
}
