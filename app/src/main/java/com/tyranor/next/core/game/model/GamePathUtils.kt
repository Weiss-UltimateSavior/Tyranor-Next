package com.tyranor.next.core.game.model

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import bridge.PrimaryStorageRoot
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.abs

/**
 * 游戏路径工具：SAF URI → 真实文件路径映射、可移动存储判定、应用内安全文件名。
 * 从 EngineScanner 拆出，供扫描 / 启动 / 存档 / 在线补丁共用。
 */
object GamePathUtils {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    /**
     * 将 SAF tree/document URI 映射为真实文件路径（用于引擎 native 启动）。
     * 外部存储 provider 的 documentId 形如 "primary:path" → 主存储挂载根/path；其他卷 →
     * /storage/<volume>/path。Downloads 的 raw 文档和根目录也能直接映射。
     * 主存储根按进程视角解析（[PrimaryStorageRoot]）：单 user 环境为 /storage/emulated/0，
     * 三星安全文件夹等隔离 user 下为 /storage/emulated/<userId>，`primary:` 卷标识不随
     * user 变化，不得硬编码 emulated/0（issue #152）。
     */
    fun safUriToPath(uriText: String?, primaryRoot: String = PrimaryStorageRoot.get()): String? {
        if (uriText.isNullOrBlank() || uriText.startsWith('/')) return uriText
        return try {
            val uri = Uri.parse(uriText)
            if (uri.scheme.equals("file", ignoreCase = true)) return uri.path
            if (!uri.scheme.equals("content", ignoreCase = true)) return null
            documentPath(uri.authority, documentId(uri) ?: return null, primaryRoot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 含 provider 查询的路径解析。文件或目录是否可读由调用方验证。 */
    internal fun resolveLocalPath(context: Context, uriText: String): String? {
        safUriToPath(uriText)?.takeIf { it.startsWith('/') }?.let { return it }
        val uri = Uri.parse(uriText)
        if (uri.scheme != "content") return null
        if (uri.authority == DOWNLOADS_AUTHORITY) {
            val id = documentId(uri) ?: return null
            // OEM provider 若对旧数字下载 ID 提供 _data，可继续使用该路径。
            if (id.toLongOrNull() != null) return queryDataPath(context, uri)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return documentPath(uri.authority, id, PrimaryStorageRoot.get()) { mediaId ->
                queryDataPath(context, ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, mediaId))
            }
        }
        return queryDataPath(context, uri)
    }

    /** 启动与存档共用实际目录判断，可移动存储上的 Kirikiri 可通过 SAF 镜像访问。 */
    internal fun resolveLocalDirectory(context: Context, uriText: String, allowSafOnly: Boolean): String? {
        return try {
            val uri = Uri.parse(uriText)
            val externalStorage = uri.authority == EXTERNAL_STORAGE_AUTHORITY
            if (uri.scheme == "content" && !externalStorage && uri.authority != DOWNLOADS_AUTHORITY &&
                (!DocumentsContract.isTreeUri(uri) || DocumentFile.fromTreeUri(context, uri)?.exists() != true)
            ) return null
            val path = resolveLocalPath(context, uriText) ?: return null
            if (File(path).isDirectory) return path
            if (!allowSafOnly || !externalStorage || !isRemovableStoragePath(path) ||
                !DocumentsContract.isTreeUri(uri)
            ) return null
            path.takeIf { DocumentFile.fromTreeUri(context, uri)?.isDirectory == true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }
    }

    internal fun isMediaStoreDownload(uriText: String): Boolean {
        val uri = Uri.parse(uriText)
        return uri.scheme == "content" && uri.authority == DOWNLOADS_AUTHORITY &&
            documentId(uri)?.let(::downloadMediaId) != null
    }

    private fun documentId(uri: Uri): String? = try {
        // 混合 URI 取实际子文档的 ID，纯 tree URI 才取根 ID。
        if (uri.pathSegments.size == 2 && uri.pathSegments.first() == "tree") {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    internal fun documentPath(
        authority: String?,
        id: String,
        primaryRoot: String,
        mediaPath: (Long) -> String? = { null },
    ): String? = when (authority) {
        EXTERNAL_STORAGE_AUTHORITY -> documentIdToPath(id, primaryRoot)
        DOWNLOADS_AUTHORITY -> {
            val downloads = "$primaryRoot/Download"
            when {
                id == "downloads" -> downloads
                id.startsWith("raw:") -> id.removePrefix("raw:").takeIf { path ->
                    (path == downloads || path.startsWith("$downloads/")) && isAbsoluteDocumentPath(path)
                }
                else -> downloadMediaId(id)?.let(mediaPath)?.takeIf(::isAbsoluteDocumentPath)
            }
        }
        else -> null
    }

    private fun downloadMediaId(id: String): Long? {
        if (!id.startsWith("msd:") && !id.startsWith("msf:")) return null
        val number = id.substring(4)
        if (number.isEmpty() || number.any { it !in '0'..'9' }) return null
        return number.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun isAbsoluteDocumentPath(path: String): Boolean =
        path.startsWith('/') && '\\' !in path && '\u0000' !in path &&
            path.split('/').none { it == "." || it == ".." }

    private fun queryDataPath(context: Context, uri: Uri): String? = try {
        val column = MediaStore.MediaColumns.DATA
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.takeIf(::isAbsoluteDocumentPath) else null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        null
    }

    /**
     * SAF documentId（`卷:相对路径`）→ 真实路径的纯决策函数（无 Android 依赖，供 JVM 单测）。
     * `primary:` 映射到 [primaryRoot]（进程视角主存储根）；其他卷映射到 `/storage/<volume>`。
     * documentId 来自 provider、不可信：卷名不得为空、含分隔符或 `.`/`..` 段；相对部分拒绝
     * 反斜杠，逐段规范化（清空段与 `.` 段、拒绝 `..` 段）后校验结果必须仍位于卷根内，越界返回 `null`。
     */
    internal fun documentIdToPath(documentId: String, primaryRoot: String): String? {
        val colon = documentId.indexOf(':')
        val volume = if (colon >= 0) documentId.substring(0, colon) else documentId
        val relative = if (colon >= 0) documentId.substring(colon + 1) else ""
        if (volume.isEmpty() || volume.contains('\\')) return null
        if (volume.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        val root = if (volume.equals("primary", ignoreCase = true)) primaryRoot else "/storage/$volume"
        if (relative.isEmpty()) return root
        if (relative.contains('\\')) return null
        val segments = ArrayList<String>(root.split('/').filter { it.isNotEmpty() })
        for (segment in relative.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> return null
                else -> segments.add(segment)
            }
        }
        val normalized = "/${segments.joinToString("/")}"
        // 规范化后的目标必须仍位于卷根内
        return normalized.takeIf { it == root || it.startsWith("$root/") }
    }

    fun isRemovableStoragePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        // emulated 卷是内置主存储的 per-user 挂载（emulated/<userId>），不是可移动存储；
        // 安全文件夹（如 emulated/150）误判会错误触发 SAF 镜像等可移动存储分支。
        return normalized.matches(Regex("""^/storage/(?!emulated/\d+(?:/|$))[^/]+(/.*)?$"""))
    }

    /** 目录名 → 安全文件名（用于应用内镜像/独立存档目录），非法字符替换为下划线。 */
    fun safeSaveName(rootPath: String): String {
        val name = runCatching { File(rootPath).name.takeIf { it.isNotBlank() } }.getOrNull()
            ?: abs(rootPath.hashCode()).toString()
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "default" }
    }
}
