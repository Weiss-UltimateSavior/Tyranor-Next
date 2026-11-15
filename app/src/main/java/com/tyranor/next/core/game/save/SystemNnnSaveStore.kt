package com.tyranor.next.core.game.save

import com.tyranor.next.core.game.save.sync.SaveSyncException
import com.tyranor.runtime.systemnnn.SystemNnnHost
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** SystemNNN shares sav with PC and the standalone player, so sav itself must stay in place. */
internal class SystemNnnSaveStore(
    private val game: File,
    val directory: File,
    private val validateSnapshot: (File, Map<String, File>, Set<String>, () -> Unit) -> Boolean,
    private val moveSave: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    },
) {
    private val migration = migrationDirectory(directory)
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
        if (hasRecovery(directory)) access(allowLegacyMigration = true) {}
    }

    fun files(root: File = directory): Map<String, File> = saveFiles(root, restoreRetired = false)

    private fun saveFiles(root: File, restoreRetired: Boolean): Map<String, File> {
        val safeRoot = SavePathSafety.requireSafeRoot(root)
        val rootPath = safeRoot.toPath()
        if (Files.notExists(rootPath, NOFOLLOW_LINKS)) return emptyMap()
        val result = linkedMapOf<String, File>()
        fun visit(folder: File) {
            val names = hashSetOf<String>()
            for (child in directoryEntries(folder.toPath())) {
                val relative = rootPath.relativize(child)
                val directory = SystemNnnHost.isManagedSaveDirectory(relative)
                val file = SystemNnnHost.isManagedSaveFile(relative) ||
                    restoreRetired && SystemNnnHost.isRetiredRollbackFile(relative)
                if (!directory && !file) continue
                if (!names.add(foldSaveName(child.fileName.toString()))) {
                    throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                }
                when {
                    directory -> visit(SavePathSafety.requireSafeRoot(child.toFile(), safeRoot))
                    file ->
                        result[relative.toString().replace(File.separatorChar, '/')] = SavePathSafety.requireSafeFile(safeRoot, child.toFile())
                }
            }
        }
        visit(safeRoot)
        return result
    }

    /**
     * Caller holds the engine's lock. A complete backup precedes any changes to live files.
     * [onCommitted] runs after the rollback marker is removed. Cleanup still runs if it throws.
     */
    fun replace(source: File?, checkCancelled: () -> Unit = {}, onCommitted: () -> Unit = {}): Int {
        val incoming = source?.let(::files).orEmpty()
        val existing = files()
        if (source != null) {
            val restorable = try {
                validateSnapshot(source, incoming, existing.keys, checkCancelled)
            } catch (unavailable: IOException) {
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = unavailable)
            }
            if (!restorable) throw GameSaveException(SaveErrorCode.NO_FILES_IN_ZIP)
            checkCancelled()
        }
        if (existing.isEmpty() && incoming.isEmpty()) {
            return if (source == null) SystemNnnHost.clearRetiredProgressBackups(directory) else 0
        }
        Files.createDirectory(migration.toPath())
        try {
            Files.createDirectory(restore.toPath())
            Files.createDirectory(before.toPath())
            for ((name, file) in existing) copySynced(file, File(before, name))
            FileOutputStream(pending).use {
                it.write(3)
                it.fd.sync()
            }
            // Older backups omit PC slots, global archives, modern chart flags, or the legacy
            // chart family. Modern chart nodes were already backed up and retain replacement semantics.
            apply(
                incoming,
                preservePcSlots = source != null && incoming.keys.none(SystemNnnHost::isPcStorySave),
                preservePcGlobal = source != null && incoming.keys.none(SystemNnnHost::isPcGlobalArchive),
                preserveModernChartProgress = source != null && incoming.keys.none(SystemNnnHost::isModernChartProgress),
                preserveLegacyChart = source != null && incoming.keys.none(SystemNnnHost::isLegacyChartSave),
            )
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
        Closeable { clearRestore() }.use { onCommitted() }
        return if (source == null) existing.size + SystemNnnHost.clearRetiredProgressBackups(directory) else incoming.size
    }

    private fun recover(allowLegacyMigration: Boolean = false) {
        if (!hasRecovery(directory)) return
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
            // Empty markers predate PC slots; versions before 3 did not collect modern chart
            // flags or legacy chart files. Their backups already included modern chart nodes.
            val version = Files.newInputStream(pending.toPath(), NOFOLLOW_LINKS).use {
                val version = it.read()
                if (version == -1 || version in 1..3 && it.read() == -1) version
                else throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            }
            apply(
                saveFiles(before, restoreRetired = version == -1),
                preservePcSlots = version == -1,
                preservePcGlobal = version < 2,
                preserveModernChartProgress = version < 3,
                preserveLegacyChart = version < 3,
            )
            Files.delete(pending.toPath())
        }
        clearRestore()
    }

    private fun clearRestore() {
        SavePathSafety.deleteTreeNoFollow(restore)
        Files.delete(migration.toPath())
    }

    companion object {
        fun hasRecovery(directory: File): Boolean =
            !Files.notExists(migrationDirectory(directory).toPath(), NOFOLLOW_LINKS)

        private fun migrationDirectory(directory: File): File = File(directory, SystemNnnHost.SAVE_MIGRATION_DIRECTORY)
    }

    private fun apply(
        snapshot: Map<String, File>,
        preservePcSlots: Boolean,
        preservePcGlobal: Boolean,
        preserveModernChartProgress: Boolean,
        preserveLegacyChart: Boolean,
    ) {
        val live = files()
        val names = snapshot.keys.mapTo(hashSetOf(), ::foldSaveName)
        val targets = snapshot.keys.associateWith(::targetFile)
        for ((name, file) in snapshot) {
            val target = targets.getValue(name)
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
        for ((name, file) in live) {
            if (foldSaveName(name) !in names &&
                !(preservePcSlots && SystemNnnHost.isPcStorySave(name)) &&
                !(preservePcGlobal && SystemNnnHost.isPcGlobalArchive(name)) &&
                !(preserveModernChartProgress && SystemNnnHost.isModernChartProgress(name)) &&
                !(preserveLegacyChart && SystemNnnHost.isLegacyChartSave(name))
            ) {
                Files.delete(file.toPath())
            }
        }
    }

    /** Keep the existing PC spelling on both case-sensitive and case-insensitive volumes. */
    private fun targetFile(name: String): File {
        if (!SystemNnnHost.isPcStorySave(name) && !SystemNnnHost.isPcGlobalSave(name) &&
            !SystemNnnHost.isChartNodeSave(name)
        ) return File(directory, name)
        var target = SavePathSafety.requireSafeRoot(directory, game)
        val parts = name.split('/')
        for ((index, part) in parts.withIndex()) {
            val directoryPath = target.toPath()
            val children = if (Files.notExists(directoryPath, NOFOLLOW_LINKS)) emptyList()
            else directoryEntries(directoryPath)
            val folded = foldSaveName(part)
            val aliases = children.filter {
                val filename = it.fileName
                foldSaveName(filename.toString()) == folded && SystemNnnHost.hasExactAsciiSaveSpelling(filename)
            }
            if (aliases.size > 1) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            val existing = aliases.singleOrNull()
            target = when {
                existing == null -> File(target, part).also {
                    // The volume may resolve a spelling outside the managed ASCII names.
                    if (!Files.notExists(it.toPath(), NOFOLLOW_LINKS)) {
                        throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                    }
                }
                index < parts.lastIndex -> SavePathSafety.requireSafeRoot(existing.toFile(), directory)
                else -> SavePathSafety.requireSafeFile(directory, existing.toFile())
            }
        }
        val relative = SavePathSafety.requireSafeRoot(directory, game).toPath().relativize(target.toPath())
        if (!SystemNnnHost.isManagedSaveFile(relative)) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
        return target
    }

    private fun directoryEntries(directory: Path): List<Path> = try {
        Files.newDirectoryStream(directory).use { it.toList() }
    } catch (failure: DirectoryIteratorException) {
        throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = failure.cause)
    } catch (failure: IOException) {
        throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = failure)
    }

    private fun foldSaveName(name: String): String = buildString(name.length) {
        for (character in name) append(if (character in 'A'..'Z') character + ('a' - 'A') else character)
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
