package com.tyranor.next.core.game.save.sync

import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/** A damaged cache entry is reconstructible and ignored until a lifecycle record rewrites it. */
internal fun loadSaveHostRecords(
    store: SaveSyncStateStore,
    knownKeys: Set<String>? = null,
): Map<String, JSONObject> = buildMap {
    for (key in store.keys()) {
        if (knownKeys != null && key !in knownKeys) continue
        val state = try {
            store.load(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SaveSyncException) {
            if (failure.code != SaveSyncError.LOCAL_IO) throw failure
            continue
        }
        if (state.length() == 0 || state.optInt("pid", -1) <= 0) continue
        put(key, state)
    }
}

internal data class SaveHostProcess(val pid: Int, val name: String)
internal sealed interface SaveHostIdentity {
    data object Dead : SaveHostIdentity
    data object Unknown : SaveHostIdentity
    data class Live(val name: String) : SaveHostIdentity
}

/** ActivityManager and /proc can independently be incomplete on OEM Android builds. */
internal interface SaveHostProbe {
    /** null means unavailable (including an empty ActivityManager process list). */
    fun visibleHosts(): List<SaveHostProcess>?
    fun records(): Map<String, JSONObject>
    fun identity(pid: Int): SaveHostIdentity
}

/**
 * Used by launch, manual selection and exit upload.
 * [hasSession] is true only for native work that still needs host-process evidence.
 */
internal fun hasRunningSaveHost(
    probe: SaveHostProbe,
    gameUri: String,
    hasSession: Boolean,
    inStartGrace: Boolean,
    elapsedMillis: Long,
    relatedUris: Set<String> = setOf(gameUri),
): Boolean {
    if (inStartGrace) return true
    val visible = probe.visibleHosts()
    if (visible == null && hasSession) return true
    val records = probe.records()
    fun relation(pid: Int, key: String): HostRecordRelation {
        val record = records[key] ?: return HostRecordRelation.UNKNOWN
        val array = record.optJSONArray("uris") ?: return HostRecordRelation.UNKNOWN
        val uris = (0 until array.length()).map { index ->
            (array.opt(index) as? String)?.takeIf(String::isNotBlank) ?: return HostRecordRelation.UNKNOWN
        }
        val idleSince = record.optLong("idleSince")
        val idleFor = if (idleSince > 0) elapsedMillis - idleSince else -1L
        val activities = record.optInt("activities", -1)
        val effectiveActivities = if (record.optBoolean("nativeOwnerPending") && activities >= 0) {
            maxOf(1, activities) // Retain owner URI correlation until native destruction ACK.
        } else activities
        return relatedUris.map { related ->
            classifyHostRecord(pid, record.optInt("pid", -1), effectiveActivities, uris, related,
                idleForMillis = idleFor)
        }.firstOrNull { it != HostRecordRelation.UNRELATED } ?: HostRecordRelation.UNRELATED
    }
    val visibleProcesses = visible.orEmpty()
    if (visibleProcesses.any { process ->
            process.name.isBlank() ||
                relation(process.pid, SaveContentHash.text(process.name)) != HostRecordRelation.UNRELATED
        }) {
        return true
    }
    var observed = visibleProcesses.isNotEmpty()
    // Never discard a malformed record: without a usable PID or identity, it cannot prove that
    // the host which owns the save tree has stopped.
    for ((key, record) in records) {
        val pid = record.optInt("pid", -1)
        if (pid <= 0) return true
        when (val identity = probe.identity(pid)) {
            SaveHostIdentity.Unknown -> return true
            SaveHostIdentity.Dead -> observed = true
            is SaveHostIdentity.Live -> {
                // A reused PID is evidence that the registered process ended, not that it runs.
                observed = true
                if (SaveContentHash.text(identity.name) == key &&
                    relation(pid, key) != HostRecordRelation.UNRELATED) return true
            }
        }
    }
    return hasSession && !observed
}
