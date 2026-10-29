package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.engine.EngineType
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
    if (guardMutation) SaveSyncLocks.mutation { current() } else current()
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

internal suspend fun markSaveSessionForeground(
    store: SaveSyncStateStore,
    key: String,
    observed: JSONObject,
    activeKey: String,
    engine: EngineType,
    priorBackground: Boolean,
    ownerPid: Int,
) = withCurrentSaveSession(store, key, observed, activeKey) { current ->
    if (shouldMarkExternalYurisReturned(engine, priorBackground, current.optBoolean("leftApp"),
            current.optInt("ownerPid", -1), ownerPid)) {
        store.save(key, current.put("returned", true))
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
