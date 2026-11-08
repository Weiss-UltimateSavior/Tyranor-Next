package com.tyranor.next.core.game.save

import com.tyranor.next.core.game.save.sync.SaveSyncException
import com.tyranor.runtime.systemnnn.SystemNnnHost
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** SystemNNN shares sav with PC and the standalone player, so sav itself must stay in place. */
internal class SystemNnnSaveStore(
    private val game: File,
    val directory: File,
    private val moveSave: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    },
) {
    private val migration = File(directory, SystemNnnHost.SAVE_MIGRATION_DIRECTORY)
    // The released standalone player rejects unknown entries in this stage before opening saves.
    private val restore = File(migration, SystemNnnHost.SAVE_RESTORE_DIRECTORY)
    private val before = File(restore, "before")
    private val pending = File(restore, "pending")

    fun <T> access(allowLegacyMigration: Boolean = false, action: () -> T): T {
        try {
            SavePathSafety.requireSafeRoot(directory, game)
            return SystemNnnHost.lockSaveDirectory(game).use {
                recover(allowLegacyMigration)
                action()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (busy: SystemNnnHost.SaveBusyException) {
            throw GameSaveException(SaveErrorCode.ENGINE_RUNNING, cause = busy)
        } catch (failure: GameSaveException) {
            throw failure
        } catch (failure: SaveSyncException) {
            throw failure
        } catch (failure: IOException) {
            throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = failure)
        } catch (failure: SecurityException) {
            throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = failure)
        }
    }

    fun recoverForLaunch() {
        if (!Files.notExists(migration.toPath(), NOFOLLOW_LINKS)) access(allowLegacyMigration = true) {}
    }

    fun files(root: File = directory): Map<String, File> {
        val safeRoot = SavePathSafety.requireSafeRoot(root)
        if (Files.notExists(safeRoot.toPath(), NOFOLLOW_LINKS)) return emptyMap()
        val result = linkedMapOf<String, File>()
        fun visit(folder: File) {
            val children = folder.listFiles() ?: throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            for (child in children) {
                val relative = safeRoot.toPath().relativize(child.toPath())
                when {
                    SystemNnnHost.isManagedSaveDirectory(relative) ->
                        visit(SavePathSafety.requireSafeRoot(child, safeRoot))
                    SystemNnnHost.isManagedSaveFile(relative) ->
                        result[relative.toString().replace(File.separatorChar, '/')] = SavePathSafety.requireSafeFile(safeRoot, child)
                }
            }
        }
        visit(safeRoot)
        return result
    }

    /** Caller holds the engine's lock. A complete backup precedes any changes to live files. */
    fun replace(source: File?, onCommitted: () -> Unit = {}): Int {
        val incoming = source?.let(::files).orEmpty()
        if (source != null && incoming.values.none { it.length() > 0 }) {
            throw GameSaveException(SaveErrorCode.NO_FILES_IN_ZIP)
        }
        val existing = files()
        if (existing.isEmpty() && incoming.isEmpty()) return 0
        Files.createDirectory(migration.toPath())
        try {
            Files.createDirectory(restore.toPath())
            Files.createDirectory(before.toPath())
            for ((name, file) in existing) copySynced(file, File(before, name))
            FileOutputStream(pending).use { it.fd.sync() }
            apply(incoming)
            Files.delete(pending.toPath())
        } catch (failure: Exception) {
            try {
                if (Files.notExists(restore.toPath(), NOFOLLOW_LINKS)) Files.delete(migration.toPath())
                else recover()
            } catch (recovery: Exception) {
                failure.addSuppressed(recovery)
            }
            throw failure
        }
        onCommitted()
        clearRestore()
        return if (source == null) existing.size else incoming.size
    }

    private fun recover(allowLegacyMigration: Boolean = false) {
        if (Files.notExists(migration.toPath(), NOFOLLOW_LINKS)) return
        SavePathSafety.requirePlainDirectoryEntry(migration)
        if (Files.notExists(restore.toPath(), NOFOLLOW_LINKS)) {
            if (migration.listFiles()?.isEmpty() == true) {
                Files.delete(migration.toPath())
                return
            }
            // An existing legacy migration must be completed by the player.
            if (!allowLegacyMigration) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            return
        }
        if (migration.listFiles()?.singleOrNull() != restore) {
            throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
        }
        SavePathSafety.requirePlainDirectoryEntry(restore)
        if (!Files.notExists(pending.toPath(), NOFOLLOW_LINKS)) {
            SavePathSafety.requireSafeFile(restore, pending)
            if (!Files.isDirectory(before.toPath(), NOFOLLOW_LINKS)) {
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            }
            apply(files(before))
            Files.delete(pending.toPath())
        }
        clearRestore()
    }

    private fun clearRestore() {
        SavePathSafety.deleteTreeNoFollow(restore)
        Files.delete(migration.toPath())
    }

    private fun apply(snapshot: Map<String, File>) {
        val live = files()
        for ((name, file) in snapshot) {
            val target = File(directory, name)
            val parent = SavePathSafety.requireSafeRoot(target.parentFile!!, directory)
            Files.createDirectories(parent.toPath())
            val temporary = File.createTempFile("write-", ".tmp", restore)
            try {
                copySynced(file, temporary)
                moveSave(temporary, target)
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
        }
        for ((name, file) in live) if (name !in snapshot) Files.delete(file.toPath())
    }

    private fun copySynced(source: File, destination: File) {
        Files.createDirectories(destination.parentFile!!.toPath())
        FileOutputStream(destination).use { output ->
            source.inputStream().use { it.copyTo(output) }
            output.fd.sync()
        }
        Files.setLastModifiedTime(destination.toPath(), Files.getLastModifiedTime(source.toPath()))
    }
}
