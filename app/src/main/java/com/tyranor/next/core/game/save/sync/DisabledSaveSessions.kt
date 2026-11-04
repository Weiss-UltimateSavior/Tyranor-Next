package com.tyranor.next.core.game.save.sync

import org.json.JSONObject

/** Missing generations belong to the initial build; a persisted disable retires them immediately. */
internal fun saveSessionGenerationMatches(session: JSONObject, generation: Long): Boolean =
    session.optLong("enabledGeneration", 0L) == generation

/** Legacy disabled installs have already abandoned their old tickets, even without a saved epoch. */
internal fun saveSyncGenerationAfterToggle(previous: Long?, wasEnabled: Boolean, enabled: Boolean): Long =
    when {
        !enabled -> (previous ?: 0L) + 1L
        previous == null && !wasEnabled -> 1L
        else -> previous ?: 0L
    }

/** Keep every URI key so native host probing remains independent of session generation. */
internal fun generationFilteredSessionEvidence(
    relatedUris: Collection<String>,
    generation: Long,
    loadSession: (String) -> JSONObject,
): Map<String, JSONObject> = relatedUris.associateWith { uri ->
    loadSession(uri).takeIf { saveSessionGenerationMatches(it, generation) } ?: JSONObject()
}

/** An ephemeral native-start hint belongs to the same enable epoch as launch admission. */
internal data class NativeLaunchGrace(val generation: Long, val untilMillis: Long, val token: String)

internal fun nativeLaunchGraceActive(grace: NativeLaunchGrace?, generation: Long, nowMillis: Long): Boolean =
    // A caller with an older snapshot must still respect a newer admitted launch.
    grace != null && grace.generation >= generation && grace.untilMillis > nowMillis

internal fun nativeLaunchGraceOwnedBy(grace: NativeLaunchGrace?, generation: Long, token: String): Boolean =
    grace != null && grace.generation == generation && grace.token == token

/** A ticket identity captured when save sync is disabled. */
internal data class DisabledSaveSession(
    val key: String,
    val token: String,
    val activeKey: String,
)

/** Capture synchronously before scheduling cleanup so a later replacement has a different token. */
internal fun snapshotDisabledSaveSessions(store: SaveSyncStateStore): List<DisabledSaveSession> =
    store.keys().mapNotNull { key ->
        val current = store.load(key)
        val token = current.optString("token")
        if (!current.has("token") || token.isBlank()) {
            null
        } else {
            DisabledSaveSession(
                key = key,
                token = token,
                activeKey = current.optString("activeKey").ifBlank { current.optString("uri") },
            )
        }
    }

/** Retire only the exact tickets observed at disable time, regardless of the current switch state. */
internal suspend fun discardDisabledSaveSessions(
    store: SaveSyncStateStore,
    observed: List<DisabledSaveSession>,
) {
    for (session in observed) {
        SaveSyncLocks.active(session.activeKey) {
            SaveSyncLocks.session(session.key) {
                if (store.load(session.key).optString("token") == session.token) {
                    store.remove(session.key)
                }
            }
        }
    }
}
