package com.tyranor.next.core.unpack

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.core.game.model.GamePathUtils
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * SAF 双轨桥：封包/解包只认真实 [File]，SAF `content://` 在此中转。
 *
 * - 能映射真实路径（[GamePathUtils.safUriToPath]）的直接用原文，零拷贝；
 * - 映射失败（外置存储卷、特殊 provider）的拷入 `cacheDir/archive_staging/` 中转，
 *   解包产物再经 [publishDirToTree] 写回用户所选目录树。
 * 调用方负责对目录树 URI 取 persistable 读写权限。
 */
object ArchiveStaging {
    private const val STAGING_DIR = "archive_staging"
    private const val IO_CHUNK = 64 * 1024

    /** 封包格式上限同样约束中转拷贝，避免异常 provider 无限流写满磁盘。 */
    private const val MAX_STAGING_BYTES = 0xFFFFFFFFL

    data class PublishStats(val files: Int, val bytes: Long)

    fun stagingDir(context: Context): File = File(context.cacheDir, STAGING_DIR)

    /** 该文件是否位于本模块中转区内（用于安全清理替换掉的旧中转）。 */
    fun isStagingFile(context: Context, file: File): Boolean {
        val root = runCatching { stagingDir(context).canonicalPath }.getOrNull() ?: return false
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return path == root || path.startsWith(root + File.separator)
    }

    /** 单文件入参：可映射直用，否则拷入中转区（带字节级进度）。返回可直接读的真实文件。 */
    fun stageInputFile(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean = { false },
        onProgress: ((copied: Long, total: Long) -> Unit)? = null,
    ): File {
        GamePathUtils.safUriToPath(uri.toString())?.let { path ->
            val direct = File(path)
            // canRead：未授权时 stat 元数据可见但读会被拒（EACCES），
            // 此时必须走 SAF 暂存而不是把不可读的真实路径交给 native。
            if (direct.isFile && direct.canRead()) return direct
        }
        val name = sanitizeName(displayName(context, uri).orEmpty())
        val total = documentSize(context, uri)
        onProgress?.invoke(0L, total)
        val target = uniqueFile(stagingDir(context).resolve("input"), name)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    copyBounded(input, output, what = "staging input", isCancelled = isCancelled) { copied ->
                        onProgress?.invoke(copied, total)
                    }
                }
            } ?: throw IOException("Cannot open document: $uri")
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }
        return target
    }

    /** 目录入参：可映射直用，否则经 DocumentFile 递归拷入中转区。 */
    fun stageInputDir(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean = { false },
    ): File {
        GamePathUtils.safUriToPath(uri.toString())?.let { path ->
            val direct = File(path)
            if (direct.isDirectory) return direct
        }
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("Cannot open directory tree: $uri")
        val target = uniqueFile(stagingDir(context), "input_dir")
        target.mkdirs()
        try {
            var bytes = 0L
            val stack = ArrayDeque<Pair<DocumentFile, File>>()
            stack.add(root to target)
            while (stack.isNotEmpty()) {
                if (isCancelled()) throw ArchiveCancelledException(uri.toString())
                val (docDir, outDir) = stack.removeLast()
                for (child in docDir.listFiles()) {
                    // provider 返回的名字不可信：清洗后才能拼进本地路径（防暂存区路径注入）。
                    val childName = child.name?.let(::sanitizeName) ?: continue
                    if (child.isDirectory) {
                        val sub = File(outDir, childName).apply { mkdirs() }
                        stack.add(child to sub)
                    } else if (child.isFile) {
                        // 清洗后撞名的文档走 uniqueFile 改名，绝不静默覆盖中转树里已拷贝的文件。
                        val dest = uniqueFile(outDir, childName)
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            dest.outputStream().use { output ->
                                bytes += copyBounded(input, output, bytes, "staging directory", isCancelled)
                            }
                        } ?: throw IOException("Cannot read document: ${child.uri}")
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching { target.deleteRecursively() }
            throw error
        }
        return target
    }

    /** 把 [srcDir] 树写回用户所选 SAF 目录树（幂等覆盖：同名文件删后重建）。 */
    fun publishDirToTree(
        context: Context,
        srcDir: File,
        treeUri: Uri,
        isCancelled: () -> Boolean = { false },
    ): PublishStats {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Cannot open directory tree: $treeUri")
        return publishDir(context, srcDir, root, isCancelled)
    }

    /**
     * 在目录树下创建名为 [baseName] 的子目录；同名（文件或文件夹）已存在时返回 null
     * ——拒绝静默去重，由调用方提示用户处理同名产物。
     * 存在性比对做大小写折叠：sdcardfs/FUSE 大小写不敏感，精确匹配会被已有目录绕过。
     */
    fun createChildDirectoryExclusive(context: Context, treeUri: Uri, baseName: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val taken = root.listFiles().mapNotNull { it.name?.lowercase(Locale.ROOT) }.toHashSet()
        if (baseName.lowercase(Locale.ROOT) in taken) return null
        return root.createDirectory(baseName)
    }

    /** 把 [srcDir] 树写进已打开的 SAF 目录（幂等覆盖：同名文件删后重建）。 */
    fun publishDir(
        context: Context,
        srcDir: File,
        docDir: DocumentFile,
        isCancelled: () -> Boolean = { false },
    ): PublishStats {
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque<Pair<File, DocumentFile>>()
        stack.add(srcDir to docDir)
        while (stack.isNotEmpty()) {
            if (isCancelled()) throw ArchiveCancelledException(docDir.uri.toString())
            val (dir, target) = stack.removeLast()
            val children = dir.listFiles() ?: throw IOException("Cannot list: ${dir.path}")
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) {
                    val sub = target.findFile(child.name)?.takeIf { it.isDirectory }
                        ?: target.createDirectory(child.name)
                        ?: throw IOException("Cannot create directory: ${child.name}")
                    stack.add(child to sub)
                } else if (child.isFile) {
                    target.findFile(child.name)?.delete()
                    val dest = target.createFile("application/octet-stream", child.name)
                        ?: throw IOException("Cannot create file: ${child.name}")
                    try {
                        context.contentResolver.openOutputStream(dest.uri)?.use { output ->
                            child.inputStream().use { input ->
                                bytes += copyBounded(input, output, bytes, "publishing results", isCancelled)
                            }
                        } ?: throw IOException("Cannot write file: ${child.name}")
                    } catch (error: Throwable) {
                        // 目标是用户文件：尽力删半成品（原文已被替换，无法还原，如实抛错）。
                        runCatching { dest.delete() }
                        throw error
                    }
                    files++
                }
            }
        }
        return PublishStats(files, bytes)
    }

    fun clearStaging(context: Context) {
        runCatching { stagingDir(context).deleteRecursively() }
    }

    /** provider 显示名不可信：剥掉路径分隔符与 `..`，防暂存区路径注入；清洗后为空走兜底名。 */
    private fun sanitizeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').replace("..", "_")
            .takeIf { it.isNotBlank() } ?: "archive.bin"

    private fun displayName(context: Context, uri: Uri): String? {
        // OpenableColumns 对单文档/树子文档均适用；失败回 null 走兜底名。
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    /** 文档字节数（provider 不报或查询失败回 0，进度条退化为不定长）。 */
    private fun documentSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            val stem = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            candidate = File(dir, if (ext.isEmpty() || ext == name) "$stem ($index)" else "$stem ($index).$ext")
            index++
        }
        return candidate
    }

    /** 返回本次拷贝字节数；累计超 [MAX_STAGING_BYTES] 或已超 [already] 抛错并由调用方清理。 */
    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        already: Long = 0L,
        what: String,
        isCancelled: () -> Boolean = { false },
        onBytes: ((copied: Long) -> Unit)? = null,
    ): Long {
        val chunk = ByteArray(IO_CHUNK)
        var total = 0L
        while (true) {
            if (isCancelled()) throw ArchiveCancelledException(what)
            val n = input.read(chunk)
            if (n < 0) break
            if (already + total + n > MAX_STAGING_BYTES) {
                throw IOException("Staging too large (>4GB): $what")
            }
            output.write(chunk, 0, n)
            total += n
            onBytes?.invoke(total)
        }
        output.flush()
        return total
    }
}
