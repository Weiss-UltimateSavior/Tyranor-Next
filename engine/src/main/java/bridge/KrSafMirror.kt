package bridge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Locale

/**
 * Builds the local shadow tree used by Kirikiroid2 for SAF-only game folders.
 *
 * Directories and empty placeholder files satisfy libc stat/access/opendir calls. Actual file
 * contents are supplied by [NativeBridge.open] from the indexed SAF Uri. Non-empty local files
 * are deliberately retained and take precedence, matching Tyranor's original overlay behavior.
 */
object KrSafMirror {
    private const val TAG = "KrSafMirror"
    private const val INDEX_VERSION = "krkr-saf-index-v1"

    data class Prepared(
        val mirrorRoot: File,
        val indexFile: File,
        val fileCount: Int,
    )

    @JvmStatic
    fun prepare(
        context: Context,
        sourceReference: String,
        logicalPath: String,
        displayName: String,
    ): Prepared {
        val tree = resolveDocumentDirectory(context.applicationContext, sourceReference, logicalPath)
            ?: error("无法读取 SD 卡游戏目录，请重新添加包含该游戏的 SD 卡目录授权")
        val mirrorRoot = mirrorRootFor(context, sourceReference, logicalPath, displayName)
        if (!mirrorRoot.isDirectory && !mirrorRoot.mkdirs()) error("无法创建 KRKR 镜像目录")

        val entries = ArrayList<Pair<String, String>>()
        mirrorDirectory(tree, mirrorRoot, entries)

        val indexDir = File(context.noBackupFilesDir, "krkr_saf_index")
        if (!indexDir.isDirectory && !indexDir.mkdirs()) error("无法创建 KRKR SAF 索引目录")
        val indexFile = File(indexDir, "${mirrorKey(sourceReference, logicalPath)}.idx")
        writeIndex(indexFile, entries)
        return Prepared(mirrorRoot, indexFile, entries.size)
    }

    /** [prepare] 使用的镜像 key；存档管理侧据此定位引擎真实读写的镜像目录，算法必须与 prepare 一致。 */
    @JvmStatic
    fun mirrorKey(sourceReference: String, logicalPath: String): String =
        sha256("$sourceReference\n$logicalPath").take(16)

    /** 预测 [prepare] 将使用的镜像根目录（不触发构建、不读取 SAF），供存档定位与删除清理复用。 */
    @JvmStatic
    fun mirrorRootFor(context: Context, sourceReference: String, logicalPath: String, displayName: String): File {
        val safeName = sanitize(displayName.ifBlank { "" }).ifBlank { "game" }
        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        return File(File(dataRoot, "games"), "${safeName.lowercase(Locale.ROOT)}-${mirrorKey(sourceReference, logicalPath)}")
    }

    @JvmStatic
    fun loadIndex(indexPath: String?): Map<String, Uri> {
        if (indexPath.isNullOrBlank()) return emptyMap()
        val file = File(indexPath)
        if (!file.isFile) return emptyMap()
        val result = LinkedHashMap<String, Uri>()
        file.bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            if (!iterator.hasNext() || iterator.next() != INDEX_VERSION) return emptyMap()
            while (iterator.hasNext()) {
                val line = iterator.next()
                val split = line.indexOf('\t')
                if (split <= 0 || split >= line.lastIndex) continue
                val path = decode(line.substring(0, split)).lowercase(Locale.ROOT)
                val uri = decode(line.substring(split + 1))
                if (path.isNotBlank() && uri.isNotBlank()) result[path] = Uri.parse(uri)
            }
        }
        return result
    }

    private fun mirrorDirectory(
        source: DocumentFile,
        destination: File,
        entries: MutableList<Pair<String, String>>,
    ) {
        for (child in source.listFiles()) {
            val name = child.name?.takeIf { it.isNotBlank() } ?: continue
            val local = File(destination, name.lowercase(Locale.ROOT))
            if (child.isDirectory) {
                if (!local.isDirectory && !local.mkdirs()) error("无法创建镜像目录：${local.name}")
                mirrorDirectory(child, local, entries)
            } else if (child.isFile) {
                if (!local.exists() && !local.createNewFile()) error("无法创建镜像文件：${local.name}")
                entries += local.absolutePath.lowercase(Locale.ROOT) to child.uri.toString()
            }
        }
    }

    /**
     * Resolve a scanned child document through the nearest persisted tree grant. A saved game Uri
     * is not guaranteed to be a tree Uri itself; providers commonly return a child document Uri
     * whose access is inherited from an ancestor selected through ACTION_OPEN_DOCUMENT_TREE.
     */
    private fun resolveDocumentDirectory(
        context: Context,
        sourceReference: String,
        logicalPath: String,
    ): DocumentFile? {
        val uri = Uri.parse(sourceReference)
        val contentUri = uri.takeIf { it.scheme.equals("content", ignoreCase = true) }
        val documentId = contentUri?.let {
            runCatching { DocumentsContract.getDocumentId(it) }.getOrNull()
                ?: runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()
        } ?: storagePathToDocumentId(logicalPath)
            ?: return null
        val candidates = LinkedHashMap<String, Uri>()
        contentUri?.let { sourceUri ->
            runCatching { DocumentsContract.getTreeDocumentId(sourceUri) }.getOrNull()?.let { treeId ->
                candidates[treeId] = sourceUri
            }
        }
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            if (contentUri != null && permission.uri.authority != contentUri.authority) continue
            if (contentUri == null && permission.uri.authority != "com.android.externalstorage.documents") continue
            val treeId = runCatching {
                DocumentsContract.getTreeDocumentId(permission.uri)
            }.getOrNull() ?: continue
            candidates[treeId] = permission.uri
        }
        val ordered = candidates.entries
            .filter { (treeId, _) -> isSameOrDescendant(documentId, treeId) }
            .sortedByDescending { it.key.length }
        for ((treeId, treeUri) in ordered) {
            val root = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory } ?: continue
            var current = root
            var failed = false
            val relative = if (documentId.length == treeId.length) "" else {
                documentId.substring(treeId.length + 1)
            }
            for (segment in relative.split('/').filter { it.isNotBlank() }) {
                val next = current.findFile(segment)
                    ?: current.listFiles().firstOrNull { it.name.equals(segment, ignoreCase = true) }
                if (next == null || !next.isDirectory) {
                    failed = true
                    break
                }
                current = next
            }
            if (!failed) {
                Log.i(TAG, "resolved document=$documentId from tree=$treeId name=${current.name}")
                return current
            }
        }
        Log.e(TAG, "cannot resolve document=$documentId grants=${candidates.keys}")
        return null
    }

    /** Convert the real path used by native KRKR into ExternalStorageProvider's document id. */
    @JvmStatic
    fun storagePathToDocumentId(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val normalized = path.replace('\\', '/').trimEnd('/')
        return when {
            normalized == "/storage/emulated/0" || normalized == "/sdcard" -> "primary:"
            normalized.startsWith("/storage/emulated/0/") ->
                "primary:${normalized.substring("/storage/emulated/0/".length)}"
            normalized.startsWith("/sdcard/") ->
                "primary:${normalized.substring("/sdcard/".length)}"
            normalized.startsWith("/storage/") -> {
                val rest = normalized.substring("/storage/".length)
                val slash = rest.indexOf('/')
                if (slash < 0) "$rest:" else {
                    val volume = rest.substring(0, slash)
                    val relative = rest.substring(slash + 1)
                    if (volume.isBlank()) null else "$volume:$relative"
                }
            }
            else -> null
        }
    }

    private fun isSameOrDescendant(documentId: String, treeId: String): Boolean =
        documentId.equals(treeId, ignoreCase = true) ||
            (documentId.length > treeId.length &&
                documentId.regionMatches(0, treeId, 0, treeId.length, ignoreCase = true) &&
                documentId[treeId.length] == '/')

    private fun writeIndex(file: File, entries: List<Pair<String, String>>) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val writer = OutputStreamWriter(stream, Charsets.UTF_8).buffered()
            writer.appendLine(INDEX_VERSION)
            for ((path, uri) in entries) {
                writer.append(encode(path)).append('\t').append(encode(uri)).append('\n')
            }
            writer.flush()
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)

    private fun sanitize(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
