package bridge

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Immutable index snapshot with per-launch failures and local ownership. */
internal class KrMirrorReadSession<T>(
    documents: Map<String, T>,
    val localFiles: KrMirrorLocalFiles? = null,
    initialOverrides: Set<String> = emptySet(),
    /** The index file was unreadable or parsed to zero rows: mirror opens fail closed. */
    val degraded: Boolean = false,
) {
    private val documents = documents.toMap()
    private val failedReads = ConcurrentHashMap.newKeySet<String>()
    private val localOverrides = ConcurrentHashMap.newKeySet<String>().apply { addAll(initialOverrides) }
    private val removedKeys = ConcurrentHashMap.newKeySet<String>()
    private val ownershipMarkers = ConcurrentHashMap<String, File>()

    /**
     * Known resources remain routed after unlink; falling back could recreate a placeholder.
     * Top-level `savedata/` rows (only present in pre-upgrade indexes) are excluded: the
     * standard save directory stays a plain local directory — routing it would run
     * copy-up/resource locks against local save files and create resource ownership
     * markers, violating the save-redirect boundary.
     */
    fun shouldRoute(key: String): Boolean {
        if (key !in documents && key !in removedKeys && key !in localOverrides) return false
        val relative = localFiles?.relativeForIndexKey(key) ?: return true
        return !relative.equals("savedata", ignoreCase = true) &&
            !relative.startsWith("savedata/", ignoreCase = true)
    }

    fun sourceEntry(key: String): T? = if (key in removedKeys) null else documents[key]

    fun entry(key: String): T? = if (key in localOverrides || key in removedKeys) null else documents[key]

    /** Cache path derivation, not existence: another process may publish a zero-byte override. */
    fun isLocal(key: String, file: File): Boolean {
        if (key in localOverrides) return true
        val state = localFiles ?: return false
        val marker = ownershipMarkers.computeIfAbsent(key) { state.ownershipMarker(file) }
        return marker.isFile
    }

    fun recordRead(key: String, succeeded: Boolean) {
        if (succeeded) failedReads.remove(key) else failedReads.add(key)
    }

    fun readFailed(key: String): Boolean = key in failedReads

    /** Execute the actual mutation and ownership updates inside copy-up's lock domain. */
    fun runMutation(
        fromKey: String?,
        from: File?,
        toKey: String? = null,
        to: File? = null,
        operation: () -> Long,
    ): Long {
        val state = localFiles ?: return operation()
        return state.withFileLocks(listOfNotNull(from, to)) {
            val result = operation()
            if (result.toInt() == 0) synchronizeMutation(fromKey, from, toKey, to)
            result
        }
    }

    /** Synchronize ownership after a successful local unlink/remove/rename. */
    fun synchronizeMutation(fromKey: String?, from: File?, toKey: String? = null, to: File? = null) {
        val state = localFiles ?: return
        // rename(path, path) succeeds but does not replace a source placeholder.
        if (from != null && to != null && (from == to || fromKey == toKey)) return
        // Each step is fault-isolated: a failed marker write must not prevent the
        // tombstone for the removed/renamed source, or the old file resurrects for
        // the rest of this launch.
        if (to != null && to.isFile) {
            // The libc rename already succeeded, so in-session reads must target the
            // local file even if the cross-launch marker write fails.
            promote(requireNotNull(toKey))
            try {
                state.markOverridden(to)
            } catch (error: Exception) {
                // In-session ownership remains, but a failed durable marker can lose
                // cross-launch ownership, especially for an intentionally empty file.
            }
        }
        if (from != null && fromKey != null && !from.exists()) {
            // The source is gone: tombstone first so it cannot resurrect in-session.
            remove(fromKey)
            try {
                state.removeOverride(from)
            } catch (error: Exception) {
                // Marker delete failure is retried by the next prepare's cleanup.
            }
        }
    }

    /** Successful unlink/rename must not resurrect the old source during this launch. */
    fun remove(key: String) {
        removedKeys.add(key)
        localOverrides.remove(key)
        ownershipMarkers.remove(key)
        failedReads.remove(key)
    }

    fun promote(key: String) {
        removedKeys.remove(key)
        localOverrides.add(key)
        failedReads.remove(key)
    }
}
