package com.tyranor.next.core.game.model

import android.net.Uri
import android.provider.DocumentsContract
import bridge.PrimaryStorageRoot
import java.io.File
import kotlin.math.abs

/**
 * 游戏路径工具：SAF URI → 真实文件路径映射、可移动存储判定、应用内安全文件名。
 * 从 EngineScanner 拆出，供扫描 / 启动 / 存档 / 在线补丁共用。
 */
object GamePathUtils {

    /**
     * 将 SAF tree/document URI 映射为真实文件路径（用于引擎 native 启动）。
     * 移植自 RinneMobile ScriptEngineLaunchers.uriToFilePath：
     * documentId 形如 "primary:path" → 主存储挂载根/path；其他卷 → /storage/<volume>/path。
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

            var documentId: String? = null
            val encodedPath = uri.encodedPath
            val documentMarker = encodedPath?.indexOf("/document/")
            if (documentMarker != null && documentMarker >= 0) {
                // 兼容 tree/document 混合 URI：取 /document/ 之后的编码段解码得子文档 id
                documentId = runCatching {
                    Uri.decode(encodedPath.substring(documentMarker + "/document/".length))
                }.getOrNull()
            }
            if (documentId.isNullOrEmpty()) {
                documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            }
            if (documentId.isNullOrEmpty()) {
                documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            }
            documentId?.let { id -> return documentIdToPath(id, primaryRoot) }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * SAF documentId（`卷:相对路径`）→ 真实路径的纯决策函数（无 Android 依赖，供 JVM 单测）。
     * `primary:` 映射到 [primaryRoot]（进程视角主存储根）；其他卷映射到 `/storage/<volume>`。
     * documentId 来自 provider、不可信：拒绝反斜杠与任何 `..` 段，逐段规范化（清空段与 `.` 段）
     * 后校验结果必须仍位于卷根内，越界返回 `null`。
     */
    internal fun documentIdToPath(documentId: String, primaryRoot: String): String? {
        val colon = documentId.indexOf(':')
        val volume = if (colon >= 0) documentId.substring(0, colon) else documentId
        val relative = if (colon >= 0) documentId.substring(colon + 1) else ""
        if (volume.isEmpty() || relative.contains('\\')) return null
        val root = if (volume.equals("primary", ignoreCase = true)) primaryRoot else "/storage/$volume"
        if (relative.isEmpty()) return root
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
