package com.tyranor.next.core.game.model

import java.io.File

/** Shared save-location contract; the legacy private root is only a migration source. */
object EthornellPaths {
    fun gameUserDataDir(gameRoot: File): File = com.core.ethornell.EthornellPaths.gameUserDataDir(gameRoot)

    fun validateGameUserDataDir(gameRoot: File, suppliedPath: String): File =
        com.core.ethornell.EthornellPaths.validateGameUserDataDir(gameRoot, suppliedPath)

    fun userDataDir(filesDir: File, gameIdentity: String): File {
        return com.core.ethornell.EthornellPaths.userDataDir(filesDir, gameIdentity)
    }

    fun validateUserDataDir(filesDir: File, gameIdentity: String, suppliedPath: String): File =
        com.core.ethornell.EthornellPaths.validateUserDataDir(filesDir, gameIdentity, suppliedPath)

    fun validatedFont(filesDir: File, path: String): String? {
        if (path.isBlank()) return ""
        val root = File(filesDir, "fonts").canonicalFile
        if (root != File(filesDir.canonicalFile, "fonts")) return null
        val font = File(path).canonicalFile
        return font.takeIf {
            it.parentFile == root && it.isFile && it.canRead() &&
                it.extension.lowercase(java.util.Locale.ROOT) in setOf("ttf", "ttc", "otf", "otc")
        }?.absolutePath
    }
}
