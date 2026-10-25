package com.tyranor.next.core.game.save.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Private, per-game immutable ZIP history. Only a successful new version can evict an old one. */
class SaveSnapshotStore(private val root: File) {
    /** Recover interrupted writes without ever visiting a game's active save directory. */
    fun maintain(gameKey: String) {
        val directory = directory(gameKey)
        SaveSyncTemporaryFiles.clean(directory, "snapshot_", ".tmp")
        prune(gameKey)
        removeOrphanMarkers(directory)
    }

    fun list(gameKey: String): List<SaveVersion> {
        val directory = directory(gameKey)
        if (!directory.exists()) return emptyList()
        val children = directory.listFiles() ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
        return children.mapNotNull { file ->
            val parsed = SaveVersionNames.parse(file.name) ?: return@mapNotNull null
            if (!file.isFile) return@mapNotNull null
            val source = if (File(directory, file.name + ".cloud").isFile) {
                SaveVersionSource.CLOUD
            } else SaveVersionSource.LOCAL
            SaveVersion(parsed.name, parsed.contentHash, parsed.createdAt, file.length(), source, parsed.restored, parsed.previousCreatedAt)
        }.sortedWith(compareByDescending<SaveVersion> { it.createdAt }.thenByDescending { it.id })
    }

    fun file(gameKey: String, id: String): File {
        if (SaveVersionNames.parse(id) == null) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        return File(directory(gameKey), id)
    }

    /** Reads one cached ZIP on demand; false is limited to an archive-integrity failure. */
    fun isValid(gameKey: String, version: SaveVersion, check: () -> Unit = {}): Boolean {
        val nameHash = SaveVersionNames.parse(version.id)?.contentHash
            ?: throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        val archive = file(gameKey, version.id)
        if (!archive.isFile) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        return try {
            SaveContentHash.archive(archive, check) == nameHash
        } catch (failure: SaveSyncException) {
            if (failure.code == SaveSyncError.CORRUPT_ARCHIVE) false else throw failure
        }
    }

    fun add(
        gameKey: String,
        archive: File,
        createdAt: Long,
        source: SaveVersionSource,
        expectedHash: String? = null,
        restored: Boolean = false,
        refreshExisting: Boolean = false,
        previousCreatedAt: Long? = null,
        check: () -> Unit = {},
    ): SaveVersion {
        val hash = SaveContentHash.archive(archive, check)
        if (expectedHash != null && hash != expectedHash) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        var existing = list(gameKey)
        // If an earlier write committed a sixth ZIP but could not prune it, do not keep
        // publishing further files while the same cleanup error persists.
        if (existing.size > LOCAL_LIMIT) {
            prune(gameKey)
            existing = list(gameKey)
        }
        val directory = directory(gameKey)
        SaveSyncTemporaryFiles.clean(directory, "snapshot_", ".tmp")
        removeOrphanMarkers(directory)
        existing.firstOrNull { it.contentHash == hash }?.let {
            val current = if (isValid(gameKey, it, check)) {
                it
            } else {
                replaceVerifiedArchive(gameKey, it, archive, hash, check)
            }
            if (refreshExisting || (restored && !current.restored)) {
                return refreshVersion(gameKey, current, if (refreshExisting) createdAt else current.createdAt,
                    previousCreatedAt ?: if (refreshExisting) current.createdAt else null)
            }
            return current
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        val id = if (restored) SaveVersionNames.createRestored(createdAt, hash, previousCreatedAt)
            else SaveVersionNames.create(createdAt, hash)
        val destination = file(gameKey, id)
        val temp = SaveSyncTemporaryFiles.create("snapshot_", ".tmp", directory)
        try {
            stageArchive(archive, temp, check)
            // Never replace an existing immutable version, even after an interrupted operation.
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath())
            }
            try {
                if (source == SaveVersionSource.CLOUD) File(directory, id + ".cloud").writeText("")
            } finally {
                prune(gameKey, keepId = id)
            }
            return SaveVersion(id, hash, createdAt, destination.length(), source, restored, previousCreatedAt)
        } finally {
            SaveSyncTemporaryFiles.release(temp)
        }
    }

    private fun replaceVerifiedArchive(
        gameKey: String,
        existing: SaveVersion,
        archive: File,
        expectedHash: String,
        check: () -> Unit,
    ): SaveVersion {
        val directory = directory(gameKey)
        val destination = file(gameKey, existing.id)
        val temp = SaveSyncTemporaryFiles.create("snapshot_", ".tmp", directory)
        try {
            stageArchive(archive, temp, check)
            if (SaveContentHash.archive(temp, check) != expectedHash) {
                throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
            }
            try {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return existing.copy(size = destination.length())
        } finally {
            SaveSyncTemporaryFiles.release(temp)
        }
    }

    private fun stageArchive(source: File, destination: File, check: () -> Unit) {
        source.inputStream().use { input ->
            java.io.FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
    }

    /** Refreshes one profile by renaming it; save bytes are neither deleted nor duplicated. */
    fun refreshVersion(
        gameKey: String,
        version: SaveVersion,
        createdAt: Long,
        previousCreatedAt: Long? = version.previousCreatedAt,
    ): SaveVersion {
        removeOrphanMarkers(directory(gameKey))
        val source = file(gameKey, version.id)
        if (!source.isFile) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        val id = SaveVersionNames.createRestored(createdAt, version.contentHash, previousCreatedAt)
        if (id == version.id) return version
        val target = file(gameKey, id)
        val oldCloudMarker = File(source.parentFile, source.name + ".cloud")
        val newCloudMarker = File(target.parentFile, target.name + ".cloud")
        // Write source metadata first; a failed rename leaves the old valid ZIP untouched.
        if (version.source == SaveVersionSource.CLOUD) newCloudMarker.writeText("")
        try {
            try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath()) }
        } catch (failure: Exception) {
            newCloudMarker.delete()
            throw failure
        }
        oldCloudMarker.delete()
        removeOrphanMarkers(directory(gameKey))
        return version.copy(id = id, createdAt = createdAt, restored = true, previousCreatedAt = previousCreatedAt)
    }

    private fun prune(gameKey: String, keepId: String? = null) {
        // A pull must remain selectable even if the remote device's clock is behind.
        // Evict the oldest prior profile only after the incoming archive has committed.
        list(gameKey).filter { it.id != keepId }.drop(LOCAL_LIMIT - if (keepId == null) 0 else 1).forEach { old ->
            val archive = file(gameKey, old.id)
            if (!archive.delete()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
            File(archive.parentFile, archive.name + ".cloud").delete()
        }
    }

    private fun removeOrphanMarkers(directory: File) {
        if (!directory.exists()) return
        val files = directory.listFiles() ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".cloud")) continue
            val archive = file.name.removeSuffix(".cloud")
            if (SaveVersionNames.parse(archive) != null && !File(directory, archive).isFile && !file.delete()) {
                throw SaveSyncException(SaveSyncError.LOCAL_IO)
            }
        }
    }

    private fun directory(gameKey: String): File {
        require(gameKey.matches(Regex("[a-f0-9]{64}")))
        return File(root, gameKey)
    }

    companion object { const val LOCAL_LIMIT = 5 }
}
