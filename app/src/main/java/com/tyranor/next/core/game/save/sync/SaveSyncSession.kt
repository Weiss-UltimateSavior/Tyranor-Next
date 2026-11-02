package com.tyranor.next.core.game.save.sync

import org.json.JSONObject

/** A delayed callback may mutate only the session it observed before waiting for the lock. */
internal suspend fun <T> withCurrentSaveSession(
    store: SaveSyncStateStore,
    key: String,
    observed: JSONObject,
    activeKey: String,
    resolveActiveKey: (() -> String)? = null,
    guardMutation: Boolean = false,
    action: suspend (JSONObject) -> T,
): T? = SaveSyncLocks.active(activeKey) {
    suspend fun current(): T? = SaveSyncLocks.session(key) {
        val token = observed.optString("token")
        val current = store.load(key)
        if (token.isBlank() || current.optString("token") != token) return@session null
        resolveActiveKey?.let { requireSessionDirectory(current, it()) }
        if (current.optString("activeKey") != activeKey) return@session null
        action(current)
    }
    if (guardMutation) SaveSyncLocks.mutation(activeKey) { current() } else current()
}

/** Missing bindings from an older build also require an explicit selection to re-establish ownership. */
internal fun requireSessionDirectory(session: JSONObject, activeKey: String) {
    requireSameSaveDirectory(session.optString("activeKey"), activeKey)
}

internal fun requireSameSaveDirectory(expected: String, current: String) {
    if (expected.isBlank() || expected != current) {
        throw SaveSyncException(SaveSyncError.SAVE_LOCATION_CHANGED)
    }
}

/** Historical returned/leftApp/ownerPid hints cannot prove an independent external host exited. */
internal fun externalSessionNeedsExitConfirmation(session: JSONObject): Boolean {
    val token = session.optString("token")
    if (token.isBlank()) return false
    if (session.optString("externalExitConfirmedToken") == token) return false
    // Explicit local restore bookkeeping never launched Winlator.
    return !(session.has("startedAt") && session.optLong("startedAt", -1L) == 0L && session.optBoolean("returned"))
}

internal fun requireExternalConfirmationSession(session: JSONObject, token: String, activeKey: String) {
    if (session.optString("token") != token || session.optString("activeKey") != activeKey) {
        throw SaveSyncException(SaveSyncError.LOCAL_STATE_CHANGED)
    }
}

internal suspend fun markSaveSessionBackground(
    store: SaveSyncStateStore,
    key: String,
    observed: JSONObject,
    activeKey: String,
) = withCurrentSaveSession(store, key, observed, activeKey) { current ->
    store.save(key, current.put("leftApp", true))
}
