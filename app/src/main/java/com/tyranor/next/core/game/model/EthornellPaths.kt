package com.tyranor.next.core.game.model

import java.io.File

/** Private writable root only; resource directories must never be mirrored as user data. */
object EthornellPaths {
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
