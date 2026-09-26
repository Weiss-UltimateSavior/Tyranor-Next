package com.tyranor.next.core.unpack

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
 * 在用户选取的目录下递归扫描 XP3 封包（深度与数量有上限）。
 *
 * 优先走真实路径（[GamePathUtils.safUriToPath]）；映射失败时退回 DocumentFile 遍历，
 * 结果携带 `docUri` 供后续中转。按名排序，目录树中的相对路径作为展示名。
 */
object ArchiveScanner {
    private const val MAX_ARCHIVES = 500
    private const val MAX_DEPTH = 6

    fun scan(context: Context, treeUri: Uri): List<ScannedArchive> {
        val mapped = GamePathUtils.safUriToPath(treeUri.toString())
            ?.let { File(it) }?.takeIf { it.isDirectory }
        return if (mapped != null) scanRealDir(mapped) else scanDocumentTree(context, treeUri)
    }

    private fun scanRealDir(root: File): List<ScannedArchive> {
        val out = mutableListOf<ScannedArchive>()
        val stack = ArrayDeque<Pair<File, String>>()
        stack.add(root to "")
        while (stack.isNotEmpty() && out.size < MAX_ARCHIVES) {
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

    private fun scanDocumentTree(context: Context, treeUri: Uri): List<ScannedArchive> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<ScannedArchive>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.add(root to "")
        while (stack.isNotEmpty() && out.size < MAX_ARCHIVES) {
            val (dir, rel) = stack.removeLast()
            if (rel.count { it == '/' } >= MAX_DEPTH) continue
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                if (child.isDirectory) {
                    stack.add(child to childRel)
                } else if (child.isFile) {
                    if (!isArchiveFileName(name)) continue
                    out.add(
                        ScannedArchive(
                            id = "doc:${child.uri}",
                            displayName = childRel,
                            fileName = name,
                            size = child.length(),
                            realFile = null,
                            docUri = child.uri,
                        ),
                    )
                }
            }
        }
        return out.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }
}
