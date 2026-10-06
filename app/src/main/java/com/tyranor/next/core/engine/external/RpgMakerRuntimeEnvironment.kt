package com.tyranor.next.core.engine.external

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * RTP 导入被拒的原因（类型化错误码，不带文案）。
 *
 * core 层禁止把本地化文案当返回值（AGENT.md 错误处理协议）：此处只给类型与数值，
 * 可展示文案由 UI 层组装（见 `ui/settings/RpgMakerRtpMessages.kt`）。
 */
sealed interface RtpImportRejection {

    /** 条目数超过上限。 */
    data class EntryCountExceeded(val count: Int, val limit: Int) : RtpImportRejection

    /** 单条目解压后超过上限。 */
    data class EntrySizeExceeded(val size: Long, val limit: Long) : RtpImportRejection

    /** 累计解压量超过上限。 */
    data class TotalSizeExceeded(val size: Long, val limit: Long) : RtpImportRejection

    /** 无法创建目录（参数为目录名或条目名）。 */
    data class DirectoryCreateFailed(val name: String) : RtpImportRejection
}

/**
 * RPGM 外置模块的共享目录管理（RTP / 自定义字体）。
 *
 * 目录契约由 RPGM 插件固定（插件 `MainActivity` 将
 * `/sdcard/JoiPlay/RTP/<RPGXP|RPGVX|RPGVXACE|mkxp-z>/app` 挂载为 RTP 根）：
 * 因此 RTP 与自定义字体必须落在共享存储，App 私有目录外置插件不可读。
 *
 * 所有方法允许失败（返回 false/null），调用方按非致命处理。
 */
object RpgMakerRuntimeEnvironment {
    private const val RTP_PARENT = "JoiPlay/RTP"
    private const val FONT_PARENT = "JoiPlay/fonts"
    private const val IMPORT_TEMP_DIR = ".import_tmp"

    // 用户挑选的 RTP 包解压上限。数值与 NativePluginInstaller.unzipSafely **不同**
    // （那边是 32 条目 / 512MiB 总量，面向内置插件包）：RTP 是整包素材，条目数与总量
    // 都大得多，因此这里给到 10 万条目 / 4GiB、单条目同为 256MiB
    // （早期版本用 2 万 / 1GiB，对含音频的整包素材偏紧，属功能收窄）。
    // 三者都只是为了拦住 zip 炸弹（高压缩比）这一类构造，正常素材包远不会触及。
    private const val MAX_ZIP_ENTRIES = 100_000
    private const val MAX_ENTRY_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L
    private const val ZIP_BUFFER_SIZE = 64 * 1024

    /** 上限拒绝的原因（仅在失败时填写，供调用方给出可诊断的提示）。 */
    @Volatile
    private var lastRejectReason: RtpImportRejection? = null

    /** 最近一次 [importRtpZip] 失败的原因；成功或未运行时为 null。 */
    fun lastRejectReason(): RtpImportRejection? = lastRejectReason
    private val FONT_EXTENSIONS = listOf(".ttf", ".ttc", ".otf", ".otc")

    fun rtpDirName(gameType: String): String = when (gameType.trim().lowercase(Locale.ROOT)) {
        "rpgmxp" -> "RPGXP"
        "rpgmvx" -> "RPGVX"
        "mkxp-z" -> "mkxp-z"
        else -> "RPGVXACE"
    }

    fun rtpDir(gameType: String): File =
        File(Environment.getExternalStorageDirectory(), "$RTP_PARENT/${rtpDirName(gameType)}")

    fun rtpAppDir(gameType: String): File = File(rtpDir(gameType), "app")

    /** RTP 是否已导入（app 目录存在且非空）。 */
    fun isRtpImported(gameType: String): Boolean {
        val dir = rtpAppDir(gameType)
        return dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
    }

    /** 清除该子类型的 RTP（仅删 `app` 层级，保留同目录其余用户内容）。 */
    fun clearRtp(gameType: String): Boolean {
        val appDir = rtpAppDir(gameType)
        if (!appDir.exists()) return true
        return appDir.deleteRecursively()
    }

    /** 从 SAF zip 导入 RTP：解压到临时目录 → 拍平单层根目录 → 替换 `app`。 */
    fun importRtpZip(context: Context, gameType: String, uri: Uri): Boolean {
        // 先清空上次的拒绝原因：本函数的若干失败早退路径不写原因，
        // 不清空会把上一次的原因挂到本次失败提示上（如「磁盘满」报成「条目数超限」）
        lastRejectReason = null
        val target = rtpAppDir(gameType)
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temp = File(parent, IMPORT_TEMP_DIR)
        temp.deleteRecursively()
        if (!temp.mkdirs()) return false
        return try {
            if (!extractZip(context, uri, temp)) return false
            flattenSingleRoot(temp)
            if (target.exists() && !target.deleteRecursively()) return false
            if (!temp.renameTo(target)) {
                if (!temp.copyRecursively(target, overwrite = true)) return false
            }
            target.listFiles()?.isNotEmpty() == true
        } catch (_: Throwable) {
            false
        } finally {
            temp.deleteRecursively()
        }
    }

    /** 从 SAF 目录树导入 RTP：递归复制 → 拍平单层根目录 → 替换 `app`。 */
    fun importRtpTree(context: Context, gameType: String, treeUri: Uri): Boolean {
        val source = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val target = rtpAppDir(gameType)
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temp = File(parent, IMPORT_TEMP_DIR)
        temp.deleteRecursively()
        if (!temp.mkdirs()) return false
        return try {
            if (!copyDocumentTree(context, source, temp)) return false
            flattenSingleRoot(temp)
            if (target.exists() && !target.deleteRecursively()) return false
            if (!temp.renameTo(target)) {
                if (!temp.copyRecursively(target, overwrite = true)) return false
            }
            target.listFiles()?.isNotEmpty() == true
        } catch (_: Throwable) {
            false
        } finally {
            temp.deleteRecursively()
        }
    }

    /** 导入自定义字体到共享目录，返回插件可读的绝对路径；扩展名非法或 IO 失败返回 null。 */
    fun importCustomFont(context: Context, uri: Uri): String? = try {
        val displayName = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
        val name = (displayName ?: "font.ttf").substringAfterLast('/').substringAfterLast('\\')
        if (FONT_EXTENSIONS.none { name.lowercase(Locale.ROOT).endsWith(it) }) return null
        val dir = File(Environment.getExternalStorageDirectory(), FONT_PARENT)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    } catch (_: Throwable) {
        null
    }

    fun isCustomFontPresent(path: String): Boolean =
        path.isNotBlank() && File(path).exists()

    fun customFontFileName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    private fun extractZip(context: Context, uri: Uri, dest: File): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        return input.use { extractZipStream(it.buffered(), dest) }
    }

    /**
     * ZIP 解压主体。与 [extractZip] 拆开是为了可单测：本函数只依赖输入流，
     * 不需要 Context/Uri；上限参数默认取生产常量，测试注入小值即可低成本覆盖边界。
     */
    private fun reject(reason: RtpImportRejection): Boolean {
        lastRejectReason = reason
        return false
    }

    internal fun extractZipStream(
        input: InputStream,
        dest: File,
        maxEntries: Int = MAX_ZIP_ENTRIES,
        maxEntryBytes: Long = MAX_ENTRY_UNCOMPRESSED_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES,
    ): Boolean {
        // 本入口也清空上一次的原因：下列若干失败路径（父目录创建、写入超限）不写原因，
        // 不清理会把上一次的拒绝原因挂到本次失败上
        lastRejectReason = null
        val tempRoot = dest.canonicalPath
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // 先计数再判断跳过：__MACOSX/ 与 .DS_Store 也是条目，
                // 若跳过后才计数，构造大量此类条目即可绕过上限、持续消耗解析与 I/O
                entryCount += 1
                if (entryCount > maxEntries) {
                    return reject(RtpImportRejection.EntryCountExceeded(entryCount, maxEntries))
                }
                // 声明大小检查对所有条目生效（含被过滤的）。流式 zip 的 entry.size 恒为 -1，
                // 故这里只是廉价的前置拒绝，真正的兜底是下面的有界读取。
                if (entry.size > maxEntryBytes) {
                    return reject(RtpImportRejection.EntrySizeExceeded(entry.size, maxEntryBytes))
                }

                val name = entry.name.replace('\\', '/')
                val outFile = File(dest, name)
                val insideRoot = outFile.canonicalPath.startsWith(tempRoot + File.separator)
                val filtered = name.startsWith("__MACOSX/") || name.contains(".DS_Store")
                if (filtered || !insideRoot) {
                    // 跳过也必须**有界**读取：ZipInputStream 是顺序流，跳过条目必须读掉其数据，
                    // 而 closeEntry() 会把整条无界读完（DEFLATED 条目被完整解压）。
                    // 实测：255KB 的 zip（条目名 __MACOSX/big.bin、解压后 256MB）光
                    // closeEntry() 就耗时 586ms —— 被过滤条目因此是绕过字节上限的通道。
                    when (val drained = drainEntryBounded(zip, maxEntryBytes, maxTotalBytes, totalBytes)) {
                        is DrainResult.Drained -> totalBytes = drained.total
                        is DrainResult.EntryOver ->
                            return reject(RtpImportRejection.EntrySizeExceeded(drained.bytes, maxEntryBytes))
                        is DrainResult.TotalOver ->
                            return reject(RtpImportRejection.TotalSizeExceeded(drained.bytes, maxTotalBytes))
                    }
                    zip.closeEntry()
                    continue
                }

                if (entry.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        return reject(RtpImportRejection.DirectoryCreateFailed(outFile.name))
                    }
                } else {
                    outFile.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) return false
                    }
                    // 上限与 NativePluginInstaller.unzipSafely 一致：用户挑选的 zip 也可能
                    // 是损坏或精心构造的（高压缩比），无界解压会写满共享存储。
                    // copyTo 不提供计数，故手工逐块读写并同时校验单条目与总量。
                    // 超限/IO 失败时删除半成品：被中止的条目会留下**截断文件**，
                    // 而导入失败后整个临时目录会被丢弃，残留文件只会误导排查；
                    // 若调用方复用了目标目录，截断文件还会被当成有效内容。
                    var written = false
                    try {
                        outFile.outputStream().use { output ->
                            val buffer = ByteArray(ZIP_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                if (entryBytes > maxEntryBytes || totalBytes > maxTotalBytes) {
                                    return false
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        written = true
                    } finally {
                        if (!written) {
                            runCatching { outFile.delete() }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val ok = dest.listFiles()?.isNotEmpty() == true
        if (ok) lastRejectReason = null
        return ok
    }

    /** 有界排空的结果：成功给出新的累计值，超限给出**区分类型**的拒绝原因。 */
    private sealed interface DrainResult {
        data class Drained(val total: Long) : DrainResult

        /** 单条目超限（[bytes] 为该条目实际解压量）。 */
        data class EntryOver(val bytes: Long) : DrainResult

        /** 累计超限（[bytes] 为触发时的实际累计值）。 */
        data class TotalOver(val bytes: Long) : DrainResult
    }

    /**
     * 有界排空当前条目的剩余数据并计入累计字节。
     *
     * 与解压写入路径共用同一对上限，确保被过滤/越界的条目不能成为绕过字节上限的通道。
     * 两类超限分别上报：合并成一个错误码会产出「0 B 超过 1 MiB」这类自相矛盾的提示
     * （单条目超限时，进入该条目之前的累计值可能远小于上限）。
     */
    private fun drainEntryBounded(
        zip: ZipInputStream,
        maxEntryBytes: Long,
        maxTotalBytes: Long,
        runningTotal: Long,
    ): DrainResult {
        val buffer = ByteArray(ZIP_BUFFER_SIZE)
        var entryBytes = 0L
        var total = runningTotal
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            entryBytes += read
            total += read
            if (entryBytes > maxEntryBytes) return DrainResult.EntryOver(entryBytes)
            if (total > maxTotalBytes) return DrainResult.TotalOver(total)
        }
        return DrainResult.Drained(total)
    }

    private fun copyDocumentTree(context: Context, source: DocumentFile, dest: File): Boolean {
        for (child in source.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                val dir = File(dest, name)
                if (!dir.exists() && !dir.mkdirs()) return false
                if (!copyDocumentTree(context, child, dir)) return false
            } else {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    File(dest, name).outputStream().use { output -> input.copyTo(output) }
                } ?: return false
            }
        }
        return true
    }

    /**
     * 若目录下仅有一个子目录且无文件，把该子目录内容上移一层：
     * 兼容官方 RTP 包 `RPGVXAce_RTP/Graphics/...` 这类多包一层根目录的结构。
     */
    private fun flattenSingleRoot(dir: File) {
        val children = dir.listFiles() ?: return
        if (children.size != 1 || !children[0].isDirectory) return
        val inner = children[0]
        val innerChildren = inner.listFiles() ?: return
        for (child in innerChildren) {
            if (!child.renameTo(File(dir, child.name))) {
                return
            }
        }
        inner.delete()
    }
}
