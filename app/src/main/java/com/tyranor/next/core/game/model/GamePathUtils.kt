package com.tyranor.next.core.game.model

import android.net.Uri
import android.provider.DocumentsContract
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
     * documentId 形如 "primary:path" → /storage/emulated/0/path；其他卷 → /storage/<volume>/path。
     * 适用于 Android 内置存储；非 primary 卷映射到 /storage/<volume>。
     */
    fun safUriToPath(uriText: String?): String? {
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
            documentId?.let { id ->
                val colon = id.indexOf(':')
                val volume = if (colon >= 0) id.substring(0, colon) else id
                val relative = if (colon >= 0) id.substring(colon + 1) else ""
                if (volume.equals("primary", ignoreCase = true)) {
                    return if (relative.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$relative"
                }
                if (volume.isNotEmpty()) {
                    return if (relative.isEmpty()) "/storage/$volume" else "/storage/$volume/$relative"
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun isRemovableStoragePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.matches(Regex("""^/storage/(?!emulated/0(?:/|$))[^/]+(/.*)?$"""))
    }

    /** 目录名 → 安全文件名（用于应用内镜像/独立存档目录），非法字符替换为下划线。 */
    fun safeSaveName(rootPath: String): String {
        val name = runCatching { File(rootPath).name.takeIf { it.isNotBlank() } }.getOrNull()
            ?: abs(rootPath.hashCode()).toString()
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "default" }
    }
}
