package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.game.save.SavePathSafety
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal data class ExternalSaveLaunch(
    val uri: String,
    val token: String,
    val activeKey: String?,
    val owner: String,
    val dispatchSequence: Long = 0,
)

/**
 * Durable external-host launch evidence independent of upload mode and configuration generation.
 *
 * A prepared marker exists before dispatch. A foreground pass may retire old process-instance
 * owners immediately and current-owner dispatches only through the sequence captured at foreground.
 * Markers left at sequence zero by a failed dispatch write are retired when no live in-memory token
 * still owns them. Corrupt markers are isolated to their own key instead of blocking every game.
 */
internal class ExternalSaveLaunchStore(
    private val root: File,
    private val owner: String,
) {
    init {
        require(owner.isNotBlank()) { "owner must not be blank" }
    }

    fun prepare(uri: String, token: String, activeKey: String?) = withDiskLock { disk ->
        requireIdentity(uri, token)
        val entries = replaceToken(disk, uri, ExternalSaveLaunch(uri, token, activeKey, owner))
        persist(disk, uri, entries)
    }

    fun dispatched(uri: String, token: String, activeKey: String?, sequence: Long) = withDiskLock { disk ->
        requireIdentity(uri, token)
        require(sequence >= 0L) { "dispatch sequence must not be negative" }
        val entries = replaceToken(disk, uri, ExternalSaveLaunch(uri, token, activeKey, owner, sequence))
        persist(disk, uri, entries)
    }

    fun failed(uri: String, token: String) = withDiskLock { disk ->
        requireIdentity(uri, token)
        val existing = load(disk, uri)
        val remaining = existing.filterNot { it.token == token }
        if (remaining.size != existing.size) persist(disk, uri, remaining)
    }

    fun foreground(
        sequenceCutoff: Long,
        returned: Boolean,
        liveTokens: Set<String> = emptySet(),
    ) = withDiskLock { disk ->
        for (marker in markerKeys(disk)) {
            if (!marker.regular) continue
            val key = marker.key
            val entries = try {
                decode(key, disk.states.load(key))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SaveSyncException) {
                // A damaged marker must not stop other markers from being retired.
                continue
            }
            val remaining = entries.filterNot { entry ->
                entry.owner != owner ||
                    (entry.owner == owner && entry.dispatchSequence == 0L && entry.token !in liveTokens) ||
                    (returned && entry.owner == owner &&
                        entry.dispatchSequence > 0L && entry.dispatchSequence <= sequenceCutoff)
            }
            if (remaining != entries) persistKey(disk, key, remaining)
        }
    }

    fun hasActive(relatedUris: Set<String>, activeKey: String): Boolean = withDiskLock { disk ->
        val entries = ArrayList<ExternalSaveLaunch>()
        for (marker in markerKeys(disk)) {
            val key = marker.key
            val related = activeKey.isBlank() || relatedUris.any { SaveContentHash.text(it) == key }
            if (!marker.regular) {
                if (related) throw localIo()
                continue
            }
            val decoded = try {
                decode(key, disk.states.load(key))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SaveSyncException) {
                // Fail closed only for the affected key; one damaged marker must not block
                // unrelated games.
                if (related) throw localIo()
                continue
            }
            entries += decoded
        }
        if (entries.isEmpty()) return@withDiskLock false
        if (activeKey.isBlank()) return@withDiskLock true
        entries.any { entry ->
            entry.uri in relatedUris ||
                entry.activeKey.isNullOrBlank() ||
                entry.activeKey == activeKey
        }
    }

    /** Explicit local cleanup for a game that no longer exists. Never decodes the marker. */
    fun remove(uri: String) = withDiskLock { disk ->
        require(uri.isNotBlank()) { "uri must not be blank" }
        disk.states.remove(SaveContentHash.text(uri))
    }

    private fun load(disk: DiskContext, uri: String): List<ExternalSaveLaunch> {
        val key = SaveContentHash.text(uri)
        val state = readMarker(disk, key) ?: return emptyList()
        val entries = decode(key, state)
        if (entries.any { it.uri != uri }) throw localIo()
        return entries
    }

    private fun replaceToken(
        disk: DiskContext,
        uri: String,
        replacement: ExternalSaveLaunch,
    ): List<ExternalSaveLaunch> {
        val key = SaveContentHash.text(uri)
        val existing = readMarker(disk, key)?.let { decode(key, it) } ?: emptyList()
        if (existing.any { it.uri != uri }) throw localIo()
        return existing.filterNot { it.token == replacement.token } + replacement
    }

    private fun persist(disk: DiskContext, uri: String, entries: List<ExternalSaveLaunch>) {
        persistKey(disk, SaveContentHash.text(uri), entries)
    }

    private fun persistKey(disk: DiskContext, key: String, entries: List<ExternalSaveLaunch>) {
        if (entries.isEmpty()) {
            disk.states.remove(key)
            return
        }
        val uri = entries.first().uri
        if (SaveContentHash.text(uri) != key || entries.any { it.uri != uri }) throw localIo()
        val encoded = JSONArray()
        for (entry in entries) {
            encoded.put(
                JSONObject()
                    .put(KEY_TOKEN, entry.token)
                    .put(KEY_ACTIVE_KEY, entry.activeKey ?: JSONObject.NULL)
                    .put(KEY_OWNER, entry.owner)
                    .put(KEY_DISPATCH_SEQUENCE, entry.dispatchSequence),
            )
        }
        disk.states.save(key, JSONObject().put(KEY_URI, uri).put(KEY_ENTRIES, encoded))
    }

    private fun decode(key: String, state: JSONObject): List<ExternalSaveLaunch> {
        val uri = state.requiredString(KEY_URI)
        if (uri.isBlank() || SaveContentHash.text(uri) != key) throw localIo()
        val encoded = state.opt(KEY_ENTRIES) as? JSONArray ?: throw localIo()
        if (encoded.length() == 0) throw localIo()
        val tokens = HashSet<String>()
        return buildList {
            for (index in 0 until encoded.length()) {
                val entry = encoded.opt(index) as? JSONObject ?: throw localIo()
                val token = entry.requiredString(KEY_TOKEN)
                val entryOwner = entry.requiredString(KEY_OWNER)
                val activeKey = entry.requiredNullableString(KEY_ACTIVE_KEY)
                val dispatchSequence = entry.requiredLong(KEY_DISPATCH_SEQUENCE)
                if (token.isBlank() || entryOwner.isBlank() || dispatchSequence < 0L || !tokens.add(token)) {
                    throw localIo()
                }
                add(ExternalSaveLaunch(uri, token, activeKey, entryOwner, dispatchSequence))
            }
        }
    }

    private data class Marker(val key: String, val regular: Boolean)

    private fun markerKeys(disk: DiskContext): List<Marker> {
        if (!disk.directory.exists()) return emptyList()
        val files = disk.directory.listFiles() ?: throw localIo()
        return files.filter { it.name.endsWith(".json") }.mapNotNull { file ->
            val key = file.name.removeSuffix(".json")
            if (!key.matches(KEY_PATTERN)) return@mapNotNull null
            val regular = try {
                requireRegularMarker(disk.directory, file)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NoSuchFileException) {
                return@mapNotNull null
            } catch (_: Exception) {
                // Never follow a symlink or read a non-regular marker; isolate it instead.
                false
            }
            Marker(key, regular)
        }
    }

    private fun readMarker(disk: DiskContext, key: String): JSONObject? {
        val marker = markerFile(disk.directory, key)
        return try {
            requireRegularMarker(disk.directory, marker)
            disk.states.load(key)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun requireRegularMarker(directory: File, marker: File) {
        val attributes = Files.readAttributes(
            marker.toPath(),
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (!attributes.isRegularFile) throw localIo()
        SavePathSafety.requireSafeFile(directory, marker)
    }

    private fun markerFile(directory: File, key: String): File = File(directory, "$key.json")

    private fun JSONObject.requiredString(name: String): String =
        opt(name) as? String ?: throw localIo()

    private fun JSONObject.requiredNullableString(name: String): String? {
        if (!has(name)) throw localIo()
        return when (val value = opt(name)) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> throw localIo()
        }
    }

    private fun JSONObject.requiredLong(name: String): Long {
        val value = opt(name) as? Number ?: throw localIo()
        return value.toString().toLongOrNull() ?: throw localIo()
    }

    private fun requireIdentity(uri: String, token: String) {
        require(uri.isNotBlank()) { "uri must not be blank" }
        require(token.isNotBlank()) { "token must not be blank" }
    }

    private fun localIo(): SaveSyncException = SaveSyncException(SaveSyncError.LOCAL_IO)

    private fun <T> withDiskLock(block: (DiskContext) -> T): T = synchronized(DISK_LOCK) {
        try {
            val directory = SavePathSafety.requireSafeRoot(
                File(File(root, "save_sync"), "external_launches"),
                trustedRoot = root,
            )
            block(DiskContext(directory, SaveSyncStateStore(directory)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SaveSyncException) {
            throw failure
        } catch (failure: Exception) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    private data class DiskContext(val directory: File, val states: SaveSyncStateStore)

    companion object {
        private val DISK_LOCK = Any()
        private val KEY_PATTERN = Regex("[a-f0-9]{64}")
        private const val KEY_URI = "uri"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_TOKEN = "token"
        private const val KEY_ACTIVE_KEY = "activeKey"
        private const val KEY_OWNER = "owner"
        private const val KEY_DISPATCH_SEQUENCE = "dispatchSequence"
    }
}
