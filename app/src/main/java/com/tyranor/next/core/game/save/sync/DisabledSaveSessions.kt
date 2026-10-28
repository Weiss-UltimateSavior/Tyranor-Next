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
