package com.tyranor.next.core.game.save.sync

import org.json.JSONObject

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

/** Used by launch, manual selection and exit upload, not only by a policy-only test. */
internal fun hasRunningSaveHost(
    probe: SaveHostProbe,
    gameUri: String,
    hasSession: Boolean,
    inStartGrace: Boolean,
    elapsedMillis: Long,
): Boolean {
    if (inStartGrace) return true
    val visible = probe.visibleHosts() ?: return true
    val records = probe.records()
    fun relation(pid: Int, key: String): HostRecordRelation {
        val record = records[key] ?: return HostRecordRelation.UNKNOWN
        val array = record.optJSONArray("uris") ?: return HostRecordRelation.UNKNOWN
        val uris = (0 until array.length()).map { index ->
            (array.opt(index) as? String)?.takeIf(String::isNotBlank) ?: return HostRecordRelation.UNKNOWN
        }
        val idleSince = record.optLong("idleSince")
        return classifyHostRecord(pid, record.optInt("pid", -1), record.optInt("activities", -1), uris, gameUri,
            idleForMillis = if (idleSince > 0) elapsedMillis - idleSince else -1L)
    }
    if (visible.any { it.name.isBlank() || relation(it.pid, SaveContentHash.text(it.name)) != HostRecordRelation.UNRELATED }) {
        return true
    }
    var observed = visible.isNotEmpty()
    for ((key, record) in records) {
        val pid = record.optInt("pid", -1)
        if (pid <= 0) return true
        when (val identity = probe.identity(pid)) {
            SaveHostIdentity.Unknown -> return true
            SaveHostIdentity.Dead -> observed = true
            is SaveHostIdentity.Live -> {
                // A reused PID is evidence that the registered process ended, not that it runs.
                observed = true
                if (SaveContentHash.text(identity.name) == key && relation(pid, key) != HostRecordRelation.UNRELATED) return true
            }
        }
    }
    return hasSession && !observed
}
