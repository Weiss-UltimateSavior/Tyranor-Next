package com.tyranor.next.core.game.scan

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 游戏目录特征指纹（迁移方案阶段 5 任务 5）：对顶层条目的名称 + 文件大小/mtime
 * 做 SHA-256。Artemis 的关键特征（root.pfs / patch.pfs.* / system.ini / boot.ini /
 * OBB 等）都在顶层，游戏更新（补丁增删、文件替换）会改变指纹，据此使识别记忆失效。
 * 返回 null 表示目录不可读（调用方按无指纹处理）。
 */
object GameDirFingerprint {

    fun compute(path: String): String? {
        if (path.isBlank() || path.startsWith("content://")) return null
        val root = File(path)
        if (!root.isDirectory) return null
        val entries = runCatching {
            root.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }
        }.getOrNull() ?: return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            for (entry in entries) {
                digest.update(entry.name.toByteArray(Charsets.UTF_8))
                digest.update(0)
                if (entry.isDirectory) {
                    digest.update("dir".toByteArray(Charsets.UTF_8))
                } else {
                    digest.update("f:${entry.length()}:${entry.lastModified()}".toByteArray(Charsets.UTF_8))
                }
                digest.update(0)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
