package com.tyranor.next.core.game.save.sync

import java.io.File

/** Only this feature's temporary files are reclaimed; in-flight transfers remain registered. */
internal object SaveSyncTemporaryFiles {
    private val active = HashSet<String>()

    @Synchronized
    fun create(prefix: String, suffix: String, directory: File?): File {
        if (directory != null) clean(directory, prefix, suffix)
        return File.createTempFile(prefix, suffix, directory).also { active.add(it.absolutePath) }
    }

    @Synchronized
    fun clean(directory: File, prefix: String, suffix: String) {
        if (!directory.exists()) return
        val files = directory.listFiles() ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
        for (file in files) {
            if (file.isFile && file.name.startsWith(prefix) && file.name.endsWith(suffix) &&
                file.absolutePath !in active && !file.delete()
            ) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        }
    }

    @Synchronized
    fun release(file: File) {
        active.remove(file.absolutePath)
        // Preserve cancellation/the original failure; a failed delete blocks the next create.
        file.delete()
    }
}
