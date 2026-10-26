package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One recoverable activation per game; its ZIP is staging, not another history version. */
internal class SaveActivationJournal(private val directory: File) {
    private val records = SaveSyncStateStore(File(directory, "records"))

    data class Pending(
        val token: String,
        val selected: SaveVersion,
        val beforeHash: String?,
        val committed: Boolean = false,
    ) {
        fun canFinalize(currentHash: String?): Boolean =
            committed || (currentHash == selected.contentHash && currentHash != beforeHash)
    }

    fun load(key: String): Pending? {
        val state = records.load(key)
        if (!state.has("token")) return null
        try {
            val parsed = SaveVersionNames.parse(state.getString("id"))
                ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
            return Pending(
                state.getString("token"),
                SaveVersion(parsed.name, parsed.contentHash, parsed.createdAt, archive(key).length(),
                    SaveVersionSource.valueOf(state.getString("source")), parsed.restored, parsed.previousCreatedAt),
                state.optString("before").takeIf { it.isNotBlank() },
                state.optBoolean("committed"),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    /** Consumes an already-private staging ZIP after validating it; never pass a history file. */
    fun prepare(key: String, selected: SaveVersion, staging: File, beforeHash: String?, check: () -> Unit = {}): Pending {
        if (load(key) != null) throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        if (SaveContentHash.archive(staging, check) != selected.contentHash) {
            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        }
        val target = archive(key)
        val parent = target.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        check()
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val pending = Pending(UUID.randomUUID().toString(), selected, beforeHash)
        records.save(key, encode(pending))
        return pending
    }

    fun markCommitted(key: String): Pending {
        val pending = load(key) ?: throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        return pending.copy(committed = true).also { records.save(key, encode(it)) }
    }

    fun archive(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "archives/$key.zip")
    }

    /** Clear an aborted attempt only after its directory recovery and source retention succeed. */
    fun discardUncommitted(key: String, recover: (File, SaveVersion) -> Unit) {
        val pending = load(key) ?: return
        if (pending.committed) throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        recover(archive(key), pending.selected)
        clear(key)
    }

    fun clear(key: String) {
        records.remove(key)
        val source = archive(key)
        if (source.exists() && !source.delete()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
    }

    private fun encode(pending: Pending) = JSONObject()
        .put("token", pending.token).put("id", pending.selected.id)
        .put("source", pending.selected.source.name).put("before", pending.beforeHash.orEmpty())
        .put("committed", pending.committed)
}
