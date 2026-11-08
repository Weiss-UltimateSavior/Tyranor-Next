package com.tyranor.next.core.game.model

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * CMVS private paths.  Kept in one object so the launcher, save manager and
 * cleanup code cannot drift: the engine's `user=` line and the save UI must
 * resolve to the exact same directory.
 */
object CmvsPaths {
    fun userDataDir(filesDir: File, gameIdentity: String): File =
        File(File(filesDir, "cmvs/users"), hash(gameIdentity))

    fun launchConfig(filesDir: File): File = File(filesDir, "launch.cfg")

    fun fontDir(filesDir: File): File = File(filesDir, "fonts")

    fun validatedFont(filesDir: File, path: String): String? {
        if (path.isBlank()) return null
        val root = fontDir(filesDir).canonicalFile
        val font = File(path).canonicalFile
        return font.takeIf {
            it.parentFile == root && it.isFile && it.canRead() &&
                it.extension.lowercase(Locale.ROOT) in setOf("ttf", "ttc", "otf", "otc")
        }?.absolutePath
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
