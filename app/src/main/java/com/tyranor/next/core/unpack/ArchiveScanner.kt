package com.tyranor.next.core.unpack

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.tyranor.next.core.game.model.GamePathUtils
import java.io.File
import java.util.Locale

/**
 * 扫描结果：目录下发现的一个封包。
 *
 * [realFile] 与 [docUri] 二选一：[realFile] 为真实路径直用；SAF 专有 provider
 * 映射失败时保留 [docUri]，按需经 [ArchiveStaging.stageInputFile] 中转。
 */
data class ScannedArchive(
    val id: String,
    /** 列表展示名（相对扫描根的路径，便于区分同名）。 */
    val displayName: String,
    /** 归档文件自身的名字（含扩展名），用于派生输出文件夹名。 */
    val fileName: String,
    val size: Long,
    val realFile: File?,
    val docUri: Uri?,
)

/**
 * 在用户选取的目录下递归扫描 XP3 封包（深度与数量有上限，[isCancelled] 随时可中断）。
 *
 * 优先走真实路径（[GamePathUtils.safUriToPath]）；File 直读不可用时退回 SAF 遍历，
 * 结果携带 `docUri` 供后续中转。按名排序，目录树中的相对路径作为展示名。
 */
object ArchiveScanner {
    private const val MAX_ARCHIVES = 500
    private const val MAX_DEPTH = 6

    /** SAF 目录单次查询取全列（id/名字/类型/大小），避免 DocumentFile 逐条目 3 次 binder 调用。 */
    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun scan(context: Context, treeUri: Uri, isCancelled: () -> Boolean = { false }): List<ScannedArchive> {
        val mapped = GamePathUtils.safUriToPath(treeUri.toString())
            ?.let { File(it) }?.takeIf { it.isDirectory }
        // File 直读依赖「所有文件访问」授权（新装应用可能未授予，listFiles 静默返回空）：
        // 真实路径扫不到时回退 SAF DocumentsContract 遍历——所选目录树必有持久授权，必定可列。
        val viaFiles = mapped?.let { scanRealDir(it, isCancelled) }.orEmpty()
        return if (viaFiles.isEmpty()) scanDocumentTree(context, treeUri, isCancelled) else viaFiles
    }

    private fun scanRealDir(root: File, isCancelled: () -> Boolean): List<ScannedArchive> {
        val out = mutableListOf<ScannedArchive>()
        val stack = ArrayDeque<Pair<File, String>>()
        stack.add(root to "")
        while (stack.isNotEmpty() && out.size < MAX_ARCHIVES) {
            if (isCancelled()) throw ArchiveCancelledException(root.path)
            val (dir, rel) = stack.removeLast()
            if (rel.count { it == '/' } >= MAX_DEPTH) continue
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                if (child.isDirectory) {
                    stack.add(child to childRel)
                } else if (child.isFile) {
                    if (!isArchiveFileName(child.name)) continue
                    out.add(
                        ScannedArchive(
                            id = "file:${child.absolutePath}",
                            displayName = childRel.replace('\\', '/'),
                            fileName = child.name,
                            size = child.length(),
                            realFile = child,
                            docUri = null,
                        ),
                    )
                }
            }
        }
        return out.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    /**
     * SAF 遍历：每目录一次 [DocumentsContract] 游标取全列（DocumentFile 每条目要
     * 名字/类型/大小三次 binder 调用，大目录不可接受），构建 document URI 作 docUri。
     */
    private fun scanDocumentTree(context: Context, treeUri: Uri, isCancelled: () -> Boolean): List<ScannedArchive> {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<ScannedArchive>()
        val stack = ArrayDeque<Pair<String, String>>() // docId to 相对路径
        stack.add(rootDocId to "")
        while (stack.isNotEmpty() && out.size < MAX_ARCHIVES) {
            if (isCancelled()) throw ArchiveCancelledException(treeUri.toString())
            val (docId, rel) = stack.removeLast()
            if (rel.count { it == '/' } >= MAX_DEPTH) continue
            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            }.getOrNull() ?: continue
            // 只兜 provider 异常；取消异常在 use 内抛出，必须穿透而不是被 runCatching 吞掉。
            val cursor = runCatching {
                context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
            }.getOrNull() ?: continue
            cursor.use {
                while (it.moveToNext() && out.size < MAX_ARCHIVES) {
                    if (isCancelled()) throw ArchiveCancelledException(treeUri.toString())
                    val childId = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val isDir = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = it.getLong(3)
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (isDir) {
                        stack.add(childId to childRel)
                    } else if (isArchiveFileName(name)) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        out.add(
                            ScannedArchive(
                                id = "doc:$docUri",
                                displayName = childRel,
                                fileName = name,
                                size = size,
                                realFile = null,
                                docUri = docUri,
                            ),
                        )
                    }
                }
            }
        }
        return out.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }
}
