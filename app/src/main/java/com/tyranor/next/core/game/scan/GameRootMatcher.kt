package com.tyranor.next.core.game.scan

import android.net.Uri
import android.provider.DocumentsContract
import com.tyranor.next.core.game.model.GamePathUtils

/**
 * 扫描根归属判定：先按真实路径前缀匹配（SAF URI 经 [GamePathUtils.safUriToPath] 映射），
 * 再按 SAF documentId 前缀匹配兜底。删除扫描目录与存储迁移导入共用。
 */
internal object GameRootMatcher {

    fun isGameUnderRoot(rootUriText: String, gameUriText: String): Boolean {
        val rootPath = normalizePath(GamePathUtils.safUriToPath(rootUriText))
        val gamePath = normalizePath(safUriToPath(gameUriText) ?: uriFilePath(gameUriText))
        if (rootPath != null && gamePath != null && isSameOrChildPath(rootPath, gamePath)) return true

        val rootDocId = documentId(rootUriText) ?: return false
        val gameDocId = documentId(gameUriText) ?: return false
        return gameDocId == rootDocId || gameDocId.startsWith("${rootDocId.trimEnd('/')}/")
    }

    private fun safUriToPath(uriText: String): String? = GamePathUtils.safUriToPath(uriText)

    private fun documentId(uriText: String): String? = runCatching {
        DocumentsContract.getDocumentId(Uri.parse(uriText))
    }.getOrNull() ?: runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(uriText))
    }.getOrNull()

    private fun uriFilePath(uriText: String): String? = runCatching {
        val uri = Uri.parse(uriText)
        if (uri.scheme.equals("file", ignoreCase = true)) uri.path else null
    }.getOrNull() ?: uriText.takeIf { it.startsWith("/") }

    private fun normalizePath(path: String?): String? =
        path?.replace('\\', '/')?.trimEnd('/')?.let { if (it.isEmpty()) "/" else it }

    private fun isSameOrChildPath(rootPath: String, gamePath: String): Boolean =
        rootPath == "/" || gamePath == rootPath || gamePath.startsWith("$rootPath/")
}
