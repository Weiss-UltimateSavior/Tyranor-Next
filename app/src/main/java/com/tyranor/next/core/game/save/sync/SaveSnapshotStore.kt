package com.tyranor.next.core.game.save.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import org.json.JSONArray
import org.json.JSONObject

/** Private, per-game immutable ZIP history. Only a successful new version can evict an old one. */
class SaveSnapshotStore(private val root: File) {
    /** Recover interrupted writes without ever visiting a game's active save directory. */
    fun maintain(gameKey: String, protectedHashes: Set<String> = emptySet(), check: () -> Unit = {}) {
        val directory = directory(gameKey)
        SaveSyncTemporaryFiles.clean(directory, "snapshot_", ".tmp")
        prune(gameKey, protectedHashes = protectedHashes, check = check)
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
            SaveVersion(parsed.name, parsed.contentHash, parsed.createdAt, file.length(), source, parsed.restored,
                parsed.previousCreatedAt, readProvenance(File(directory, file.name + ".runtime.json")))
        }.sortedWith(compareByDescending<SaveVersion> { it.createdAt }.thenByDescending { it.id })
    }

    fun file(gameKey: String, id: String): File {
        if (SaveVersionNames.parse(id) == null) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        return File(directory(gameKey), id)
    }

    /** Records provenance only after the caller has matched these bytes to a stable active save. */
    fun recordRuntimeProvenance(gameKey: String, version: SaveVersion, provenance: RuntimeSaveProvenance): SaveVersion {
        if (version.contentHash != SaveVersionNames.parse(version.id)?.contentHash) {
            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        }
        val archive = file(gameKey, version.id)
        if (!archive.isFile) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        val merged = version.runtimeProvenances + provenance
        writeProvenance(File(archive.parentFile, archive.name + ".runtime.json"), merged)
        return version.copy(runtimeProvenances = merged)
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
        protectedHashes: Set<String> = emptySet(),
        runtimeProvenance: RuntimeSaveProvenance? = null,
        check: () -> Unit = {},
    ): SaveVersion {
        val hash = SaveContentHash.archive(archive, check)
        if (expectedHash != null && hash != expectedHash) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        var existing = list(gameKey)
        // If an earlier write committed a sixth ZIP but could not prune it, do not keep
        // publishing further files while the same cleanup error persists.
        if (existing.size > LOCAL_LIMIT) {
            prune(gameKey, protectedHashes = protectedHashes + hash, check = check)
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
            val withProvenance = if (runtimeProvenance == null) current else {
                recordRuntimeProvenance(gameKey, current, runtimeProvenance)
            }
            if (refreshExisting || (restored && !withProvenance.restored)) {
                return refreshVersion(gameKey, withProvenance, if (refreshExisting) createdAt else withProvenance.createdAt,
                    previousCreatedAt ?: if (refreshExisting) withProvenance.createdAt else null)
            }
            return withProvenance
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
                if (runtimeProvenance != null) writeProvenance(File(directory, id + ".runtime.json"), setOf(runtimeProvenance))
                if (source == SaveVersionSource.CLOUD) File(directory, id + ".cloud").writeText("")
            } finally {
                prune(gameKey, keepId = id, protectedHashes = protectedHashes, check = check)
            }
            return SaveVersion(id, hash, createdAt, destination.length(), source, restored, previousCreatedAt,
                runtimeProvenance?.let(::setOf).orEmpty())
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
        val parent = destination.parentFile ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
        requireSnapshotSpace(source.length(), parent.usableSpace)
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
        val oldRuntimeMarker = File(source.parentFile, source.name + ".runtime.json")
        val newRuntimeMarker = File(target.parentFile, target.name + ".runtime.json")
        // Write source metadata first; a failed rename leaves the old valid ZIP untouched.
        if (version.source == SaveVersionSource.CLOUD) newCloudMarker.writeText("")
        if (oldRuntimeMarker.isFile) Files.copy(oldRuntimeMarker.toPath(), newRuntimeMarker.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath()) }
        } catch (failure: Exception) {
            newCloudMarker.delete()
            newRuntimeMarker.delete()
            throw failure
        }
        oldCloudMarker.delete()
        oldRuntimeMarker.delete()
        removeOrphanMarkers(directory(gameKey))
        return version.copy(id = id, createdAt = createdAt, restored = true, previousCreatedAt = previousCreatedAt)
    }

    private fun prune(
        gameKey: String,
        keepId: String? = null,
        protectedHashes: Set<String> = emptySet(),
        check: () -> Unit = {},
    ) {
        // A pull must remain selectable even if the remote device's clock is behind.
        // Evict the oldest prior profile only after the incoming archive has committed.
        val candidates = list(gameKey)
        if (candidates.size <= LOCAL_LIMIT) return
        // Validate only when eviction is necessary. A cheap list/startup must not hash up to
        // five large ZIPs, but corrupt bytes can never justify deleting a usable version.
        val versions = candidates.filter { version ->
            check()
            if (isValid(gameKey, version, check)) true else {
                val archive = file(gameKey, version.id)
                var target = File(archive.parentFile, archive.name + ".corrupt")
                var suffix = 1
                while (target.exists()) target = File(archive.parentFile, archive.name + ".corrupt-${suffix++}")
                Files.move(archive.toPath(), target.toPath())
                File(archive.parentFile, archive.name + ".cloud").delete()
                false
            }
        }
        val protected = versions.filter { it.id == keepId || it.contentHash in protectedHashes }.toSet()
        if (protected.size > LOCAL_LIMIT) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        versions.filter { it !in protected }.drop(LOCAL_LIMIT - protected.size).forEach { old ->
            check()
            val archive = file(gameKey, old.id)
            if (!archive.delete()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
            File(archive.parentFile, archive.name + ".cloud").delete()
            File(archive.parentFile, archive.name + ".runtime.json").delete()
        }
    }

    private fun removeOrphanMarkers(directory: File) {
        if (!directory.exists()) return
        val files = directory.listFiles() ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
        for (file in files) {
            if (!file.isFile || (!file.name.endsWith(".cloud") && !file.name.endsWith(".runtime.json"))) continue
            val archive = if (file.name.endsWith(".cloud")) file.name.removeSuffix(".cloud")
                else file.name.removeSuffix(".runtime.json")
            if (SaveVersionNames.parse(archive) != null && !File(directory, archive).isFile && !file.delete()) {
                throw SaveSyncException(SaveSyncError.LOCAL_IO)
            }
        }
    }

    private fun readProvenance(file: File): Set<RuntimeSaveProvenance> {
        if (!file.isFile) return emptySet()
        return try {
            val array = JSONObject(file.readText()).optJSONArray("items") ?: return emptySet()
            buildSet {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val packageName = item.optString("package")
                    val fingerprint = item.optString("fingerprint")
                    val directory = item.optString("directory")
                    if (packageName.isNotBlank() && fingerprint.matches(Regex("[a-f0-9]{64}")) && directory.isNotBlank()) {
                        add(RuntimeSaveProvenance(packageName, fingerprint, directory))
                    }
                }
            }
        } catch (failure: Exception) {
            if (failure is SecurityException) throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
            emptySet() // Corrupt/missing provenance is unknown and activation fails closed.
        }
    }

    private fun writeProvenance(file: File, provenance: Set<RuntimeSaveProvenance>) {
        val parent = file.parentFile ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
        if (!parent.isDirectory && !parent.mkdirs()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        val array = JSONArray()
        provenance.sortedWith(compareBy({ it.packageName }, { it.savesFingerprint }, { it.canonicalDirectory })).forEach {
            array.put(JSONObject().put("package", it.packageName).put("fingerprint", it.savesFingerprint)
                .put("directory", it.canonicalDirectory))
        }
        val temp = SaveSyncTemporaryFiles.create("snapshot_meta_", ".tmp", parent)
        try {
            java.io.FileOutputStream(temp).use { output ->
                output.write(JSONObject().put("items", array).toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (failure: Exception) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        } finally {
            SaveSyncTemporaryFiles.release(temp)
        }
    }

    private fun directory(gameKey: String): File {
        require(gameKey.matches(Regex("[a-f0-9]{64}")))
        return File(root, gameKey)
    }

    companion object { const val LOCAL_LIMIT = 5 }
}

/** Admission, not eviction: never sacrifice one of the five retained versions for disk space. */
internal fun requireSnapshotSpace(archiveBytes: Long, usableBytes: Long) {
    val reserve = 16L * 1024 * 1024
    if (archiveBytes < 0 || usableBytes < reserve || archiveBytes > usableBytes - reserve) {
        throw SaveSyncException(SaveSyncError.STORAGE_FULL)
    }
}
