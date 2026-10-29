package com.tyranor.next.core.game.save.sync

import java.io.File

/** Only this feature's temporary files are reclaimed; in-flight transfers remain registered. */
internal object SaveSyncTemporaryFiles {
    private val active = HashSet<String>()

    fun create(
        prefix: String,
        suffix: String,
        directory: File?,
        fileSystem: SaveSyncTemporaryFileSystem = SaveSyncTemporaryFileSystem.DEFAULT,
    ): File = createRegistered(prefix, suffix, directory, fileSystem, cleanFirst = true)

    @Synchronized
    private fun createRegistered(
        prefix: String,
        suffix: String,
        directory: File?,
        fileSystem: SaveSyncTemporaryFileSystem,
        cleanFirst: Boolean,
    ): File {
        val temp = runSaveSyncStorageOperation(directory, 0, fileSystem.usableSpace) {
            if (directory != null) {
                if (cleanFirst) clean(directory, prefix, suffix)
                requireSaveSyncTransientSpace(0, directory, fileSystem.usableSpace)
            }
            fileSystem.createTempFile(prefix, suffix, directory)
        }
        return temp.also { active.add(it.absolutePath) }
    }

    fun copy(
        source: File,
        prefix: String,
        suffix: String,
        directory: File,
        fileSystem: SaveSyncTemporaryFileSystem = SaveSyncTemporaryFileSystem.DEFAULT,
    ): File {
        val sourceBytes = runSaveSyncStorageOperation(directory, 0, fileSystem.usableSpace) {
            source.length()
        }
        runSaveSyncStorageOperation(directory, sourceBytes, fileSystem.usableSpace) {
            clean(directory, prefix, suffix)
        }
        requireSaveSyncTransientSpace(sourceBytes, directory, fileSystem.usableSpace)
        var target: File? = null
        try {
            val created = createRegistered(prefix, suffix, directory, fileSystem, cleanFirst = false)
            target = created
            requireSaveSyncTransientSpace(sourceBytes, directory, fileSystem.usableSpace)
            runSaveSyncStorageOperation(directory, sourceBytes, fileSystem.usableSpace) {
                fileSystem.copyFile(source, created)
            }
            return created
        } catch (failure: Throwable) {
            target?.let(::release)
            throw failure
        }
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
