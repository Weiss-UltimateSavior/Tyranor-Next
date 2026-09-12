package com.tyranor.next.core.engine.external

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

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
        val tempRoot = dest.canonicalPath
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("__MACOSX/") || name.contains(".DS_Store")) {
                        zip.closeEntry()
                        continue
                    }
                    val outFile = File(dest, name)
                    if (!outFile.canonicalPath.startsWith(tempRoot + File.separator)) {
                        zip.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        if (!outFile.exists() && !outFile.mkdirs()) return false
                    } else {
                        outFile.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) return false
                        }
                        outFile.outputStream().use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                }
            }
        } ?: return false
        return dest.listFiles()?.isNotEmpty() == true
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
