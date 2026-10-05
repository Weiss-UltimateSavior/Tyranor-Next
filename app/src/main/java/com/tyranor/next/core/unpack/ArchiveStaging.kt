package com.tyranor.next.core.unpack

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.tyranor.next.core.game.model.GamePathUtils
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * SAF 双轨桥：封包/解包只认真实 [File]，SAF `content://` 在此中转。
 *
 * - 能映射真实路径（[GamePathUtils.safUriToPath]）的直接用原文，零拷贝；
 * - 映射失败（外置存储卷、特殊 provider）的拷入 `cacheDir/archive_staging/` 中转，
 *   解包产物再经 [publishDir] 写回用户所选目录树。
 * 调用方负责对目录树 URI 取 persistable 读写权限。
 *
 * 目录树遍历一律走 [DocumentsContract] 单查询（游标异常如实穿透）：
 * androidx `DocumentFile.listFiles()` 内部 catch 后只打日志并返回部分结果，
 * binder 瞬断会让遍历/回写静默缺子树——封包产物缺文件还报成功，绝不可用。
 */
object ArchiveStaging {
    private const val STAGING_DIR = "archive_staging"
    private const val IO_CHUNK = 64 * 1024

    /** 目录树子项单次查询取全列（id/名字/类型），避免逐条目 3 次 binder 调用。 */
    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    /**
     * 暂存卷最低保留水位：拷贝过程中低于该值即中止。注意 XP3 索引是 64 位
     * offset/size，不存在 4 GiB 格式上限——约束只能来自磁盘可用空间。
     */
    private const val STAGING_FREE_HEADROOM = 256L * 1024 * 1024

    fun stagingDir(context: Context): File = File(context.cacheDir, STAGING_DIR)

    /** 该文件是否位于本模块中转区内（用于安全清理替换掉的旧中转）。 */
    fun isStagingFile(context: Context, file: File): Boolean {
        val root = runCatching { stagingDir(context).canonicalPath }.getOrNull() ?: return false
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return path == root || path.startsWith(root + File.separator)
    }

    /** 目录是否有子项（单查询；provider 异常如实上抛，绝不静默当空）。 */
    fun hasChildren(context: Context, parentDocUri: Uri): Boolean =
        listChildren(context, parentDocUri).isNotEmpty()

    /**
     * 在父目录下创建名为 [baseName] 的子目录，返回其 document URI；同名（文件或
     * 文件夹）已存在时返回 null——拒绝静默去重，由调用方提示用户处理同名产物。
     * 存在性比对做大小写折叠：sdcardfs/FUSE 大小写不敏感，精确匹配会被已有目录绕过。
     */
    fun createChildDirectoryExclusive(context: Context, parentDocUri: Uri, baseName: String): Uri? {
        val name = sanitizeName(baseName)
        if (name.lowercase(Locale.ROOT) in listChildren(context, parentDocUri).keys.map { it.lowercase(Locale.ROOT) }) {
            return null
        }
        val created = DocumentsContract.createDocument(
            context.contentResolver, parentDocUri, DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: return null
        // provider 可能对已存在目录返回成功：创建后核对名称（大小写不敏感），
        // 不一致即删除并返回 null，交由调用方走同名拒绝。
        val createdName = displayName(context, created)
        if (createdName?.equals(name, ignoreCase = true) != true) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
            return null
        }
        return created
    }

    /**
     * 回滚删除本功能自建的输出目录：provider 拒绝（第三方 provider 未必支持递归
     * 删除）时如实返回 false，调用方必须按类型化错误上报残留，绝不静默吞掉。
     */
    fun rollbackCreatedDirectory(context: Context, docUri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, docUri)
    }.getOrDefault(false)

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
                    copyBounded(
                        input,
                        output,
                        what = "staging input",
                        isCancelled = isCancelled,
                        onBytes = { copied -> onProgress?.invoke(copied, total) },
                        freeSpaceDir = stagingDir(context),
                    )
                }
            } ?: throw IOException("Cannot open document: $uri")
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }
        return target
    }

    /** 目录入参：可映射直用，否则经 [DocumentsContract] 单查询递归拷入中转区。 */
    fun stageInputDir(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean = { false },
    ): File {
        GamePathUtils.safUriToPath(uri.toString())?.let { path ->
            val direct = File(path)
            // 与 ViewModel 的权限模型对齐：R+ 需要「所有文件访问」才能完整
            // read_dir 目录树；canRead/isDirectory 在未授权时仍可能为真，
            // 不满足时必须回退 SAF 暂存而不是把不可读的真实路径交给 native。
            val fullAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
            if (direct.isDirectory && direct.canRead() && fullAccess) return direct
        }
        val rootDocId = DocumentsContract.getTreeDocumentId(uri)
        val target = uniqueFile(stagingDir(context), "input_dir")
        target.mkdirs()
        try {
            val stack = ArrayDeque<Pair<String, File>>() // docId to 本地落盘目录
            stack.add(rootDocId to target)
            while (stack.isNotEmpty()) {
                if (isCancelled()) throw ArchiveCancelledException(uri.toString())
                val (docId, outDir) = stack.removeLast()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
                // 每目录一次游标查询；查询失败/被 provider 拒绝必须如实抛错，
                // 静默缺子树 = 封包产物缺文件还报成功。
                val cursor = context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
                    ?: throw IOException("Cannot list directory: $docId")
                cursor.use {
                    while (it.moveToNext()) {
                        if (isCancelled()) throw ArchiveCancelledException(uri.toString())
                        val childId = it.getString(0) ?: continue
                        val name = it.getString(1) ?: continue
                        val isDir = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        // provider 返回的名字不可信：清洗后才能拼进本地路径（防暂存区路径注入）。
                        val childName = sanitizeName(name)
                        if (isDir) {
                            stack.add(childId to File(outDir, childName).apply { mkdirs() })
                        } else {
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                            // 清洗后撞名的文档走 uniqueFile 改名，绝不静默覆盖中转树里已拷贝的文件。
                            val dest = uniqueFile(outDir, childName)
                            context.contentResolver.openInputStream(childUri)?.use { input ->
                                dest.outputStream().use { output ->
                                    copyBounded(
                                        input,
                                        output,
                                        what = "staging directory",
                                        isCancelled = isCancelled,
                                        freeSpaceDir = stagingDir(context),
                                    )
                                }
                            } ?: throw IOException("Cannot read document: $childUri")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching { target.deleteRecursively() }
            throw error
        }
        return target
    }

    /** 把 [srcDir] 树写进已打开的 SAF 目录文档（幂等覆盖：同名文件删后重建）。
     *  [onProgress] 按字节上报回写进度（SAF 写入慢，GB 级可达数分钟，进度条不能停格）。 */
    fun publishDir(
        context: Context,
        srcDir: File,
        targetDocUri: Uri,
        isCancelled: () -> Boolean = { false },
        onProgress: ((copied: Long, total: Long, fileName: String) -> Unit)? = null,
    ) {
        var published = 0L
        val publishTotal = srcDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        val stack = ArrayDeque<Pair<File, Uri>>()
        stack.add(srcDir to targetDocUri)
        while (stack.isNotEmpty()) {
            if (isCancelled()) throw ArchiveCancelledException(targetDocUri.toString())
            val (dir, target) = stack.removeLast()
            val children = dir.listFiles() ?: throw IOException("Cannot list: ${dir.path}")
            // 每个目标目录只查一次子项建名字映射（游标异常穿透）：逐条 findFile 是
            // 全量 listFiles + 逐个 getName 的 O(n²) 次跨进程调用，大目录回写不可接受。
            val existing = listChildren(context, target)
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) {
                    val sub = existing[child.name]?.takeIf { it.isDir }?.uri
                        ?: DocumentsContract.createDocument(
                            context.contentResolver, target, DocumentsContract.Document.MIME_TYPE_DIR, child.name,
                        )
                        ?: throw IOException("Cannot create directory: ${child.name}")
                    stack.add(child to sub)
                } else if (child.isFile) {
                    existing[child.name]?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) } }
                    val dest = DocumentsContract.createDocument(
                        context.contentResolver, target, "application/octet-stream", child.name,
                    ) ?: throw IOException("Cannot create file: ${child.name}")
                    try {
                        context.contentResolver.openOutputStream(dest)?.use { output ->
                            child.inputStream().use { input ->
                                val copied = copyBounded(
                                    input,
                                    output,
                                    what = "publishing results",
                                    isCancelled = isCancelled,
                                    onBytes = { chunk ->
                                        onProgress?.invoke(published + chunk, publishTotal, child.name)
                                    },
                                )
                                published += copied
                            }
                        } ?: throw IOException("Cannot write file: ${child.name}")
                    } catch (error: Throwable) {
                        // 目标是用户文件：尽力删半成品（原文已被替换，无法还原，如实抛错）。
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, dest) }
                        throw error
                    }
                }
            }
        }
    }

    fun clearStaging(context: Context) {
        runCatching { stagingDir(context).deleteRecursively() }
    }

    /** 文档显示名（OpenableColumns 查询；provider 不报或失败回 null，由调用方兜底）。 */
    fun queryDisplayName(context: Context, uri: Uri): String? = displayName(context, uri)

    /** 目录树子项单查询（name → 文档）；查询失败抛 [IOException]，绝不静默缺项。 */
    private fun listChildren(context: Context, parentDocUri: Uri): Map<String, ChildDoc> {
        val docId = DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentDocUri, docId)
        val out = HashMap<String, ChildDoc>()
        val cursor = context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
            ?: throw IOException("Cannot list children: $parentDocUri")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                out[name] = ChildDoc(
                    uri = DocumentsContract.buildDocumentUriUsingTree(parentDocUri, id),
                    isDir = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        return out
    }

    private data class ChildDoc(val uri: Uri, val isDir: Boolean)

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

    /**
     * 返回本次拷贝字节数；[freeSpaceDir] 非空时按卷剩余水位中止（XP3 无 4 GiB
     * 格式上限，约束只能来自磁盘可用空间），失败由调用方清理。
     */
    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        what: String,
        isCancelled: () -> Boolean = { false },
        onBytes: ((copied: Long) -> Unit)? = null,
        freeSpaceDir: File? = null,
    ): Long {
        val chunk = ByteArray(IO_CHUNK)
        var total = 0L
        while (true) {
            if (isCancelled()) throw ArchiveCancelledException(what)
            if (freeSpaceDir != null && total % (4L * IO_CHUNK) == 0L &&
                freeSpaceDir.usableSpace < STAGING_FREE_HEADROOM
            ) {
                throw IOException("Insufficient cache space for staging: $what")
            }
            val n = input.read(chunk)
            if (n < 0) break
            output.write(chunk, 0, n)
            total += n
            onBytes?.invoke(total)
        }
        output.flush()
        return total
    }
}
