package com.tyranor.next.core.game.save.sync

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Written under the active-directory lock before import; shared by every URI using that directory. */
internal class SaveDirectoryActivationStore(root: File) {
    private val states = SaveSyncStateStore(File(root, "directory_selections"))

    data class Pending(
        val activeKey: String,
        val uri: String,
        val token: String,
        val committed: Boolean? = null,
        val previousRevision: String? = null,
    )

    fun revision(activeKey: String): String {
        val own = load(activeKey).optString("revision")
        val overlapping = overlappingStates(activeKey).mapNotNull { state ->
            state.optString("revision").takeIf { it.isNotBlank() }?.let { state.getString("activeKey") + "\n" + it }
        }
        return if (overlapping.isEmpty()) own else SaveContentHash.text(
            (overlapping + (activeKey + "\n" + own)).sorted().joinToString("\n"))
    }

    fun requireAvailable(activeKey: String, ownerUri: String? = null) {
        for (state in listOf(load(activeKey)) + overlappingStates(activeKey)) {
            val owner = state.optString("ownerUri")
            if (owner.isNotBlank() && owner != ownerUri) throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
    }

    private fun overlappingStates(activeKey: String): List<JSONObject> = states.keys().mapNotNull { key ->
        if (key == SaveContentHash.text(activeKey)) return@mapNotNull null
        val inspected = try { states.inspect(key) }
        catch (failure: SaveSyncException) {
            if (failure.code != SaveSyncError.LOCAL_IO) throw failure
            return@mapNotNull null
        }
        val other = inspected.opt("activeKey") as? String ?: return@mapNotNull null
        if (other.isBlank() || SaveContentHash.text(other) != key || !saveDirectoryScopesOverlap(activeKey, other)) {
            return@mapNotNull null
        }
        states.load(key) { validate(it, key) }
    }

    fun begin(activeKey: String, uri: String, token: String) {
        requireAvailable(activeKey, uri)
        val state = load(activeKey)
        if (state.optString("ownerToken").let { it.isNotBlank() && it != token }) {
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
        if (state.optString("ownerToken").isBlank()) state.put("previousRevision", state.optString("revision"))
        states.save(SaveContentHash.text(activeKey), state.put("activeKey", activeKey)
            .put("revision", token).put("ownerUri", uri).put("ownerToken", token))
    }

    /** A retry may resume its own epoch, but can never rewind a later alias's selection. */
    fun resume(activeKey: String, uri: String, token: String) {
        val state = load(activeKey)
        val revision = state.optString("revision")
        if (revision.isNotBlank() && revision != token) throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        begin(activeKey, uri, token)
    }

    /** Persist the outcome while retaining ownership until journal deletion has succeeded. */
    fun prepareCompletion(activeKey: String, uri: String, token: String, committed: Boolean) {
        val state = load(activeKey)
        requireOwner(state, uri, token)
        if (state.has("completionCommitted") && state.getBoolean("completionCommitted") != committed) {
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
        states.save(SaveContentHash.text(activeKey), state.put("completionCommitted", committed))
    }

    fun complete(activeKey: String, uri: String, token: String, committed: Boolean = true) {
        val state = load(activeKey)
        if (state.optString("ownerUri").isBlank()) return
        requireOwner(state, uri, token)
        if (state.has("completionCommitted") && state.getBoolean("completionCommitted") != committed) {
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
        state.remove("ownerUri")
        state.remove("ownerToken")
        if (!committed) state.put("revision", state.optString("previousRevision"))
        state.remove("previousRevision")
        state.remove("completionCommitted")
        // Keep the revision: an older pinned transfer must stay stale after cleanup succeeds.
        states.save(SaveContentHash.text(activeKey), state)
    }

    fun owned(uri: String): List<Pending> = states.keys().mapNotNull { key ->
        val state = try {
            states.inspect(key).also { if (it.opt("ownerUri") == uri) validate(it, key) }
        } catch (failure: SaveSyncException) {
            // A scan cannot infer ownership from an unreadable record. Known directory keys
            // are checked strictly by load/repairForRemoval; old malformed entries stay put.
            if (failure.code != SaveSyncError.LOCAL_IO) throw failure
            return@mapNotNull null
        } catch (_: JSONException) {
            return@mapNotNull null
        }
        if (state.opt("ownerUri") != uri) null else Pending(
            state.getString("activeKey"), uri, state.getString("ownerToken"),
            state.opt("completionCommitted") as? Boolean,
            (state.opt("previousRevision") as? String)?.takeIf { it.isNotBlank() })
    }

    /** Caller must hold the directory/idle guards; a new revision keeps old transfers stale. */
    fun repairForRemoval(activeKey: String, uri: String): Boolean {
        val key = SaveContentHash.text(activeKey)
        try {
            states.load(key) { state ->
                try {
                    validate(state, key)
                } catch (failure: JSONException) {
                    val owner = state.opt("ownerUri") as? String
                    if (!owner.isNullOrBlank() && owner != uri) {
                        throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
                    }
                    throw failure
                }
            }
            return false
        } catch (failure: SaveSyncException) {
            if (failure.cause !is JSONException) throw failure
            // Replace atomically after quarantine succeeds, so a failed write retains the fence.
            states.save(key, JSONObject().put("activeKey", activeKey).put("revision", UUID.randomUUID().toString()))
            return true
        }
    }

    private fun requireOwner(state: JSONObject, uri: String, token: String) {
        if (state.optString("ownerUri") != uri || state.optString("ownerToken") != token) {
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
    }

    private fun load(activeKey: String): JSONObject {
        val key = SaveContentHash.text(activeKey)
        return states.load(key) { validate(it, key) }
    }

    private fun validate(state: JSONObject, key: String) {
        val activeKey = state.opt("activeKey") as? String
        if (activeKey.isNullOrBlank() || SaveContentHash.text(activeKey) != key || state.opt("revision") !is String) {
            throw JSONException("Invalid selection directory or revision")
        }
        if (state.has("ownerUri") || state.has("ownerToken")) {
            val owner = state.opt("ownerUri") as? String
            val token = state.opt("ownerToken") as? String
            if (owner.isNullOrBlank() || token.isNullOrBlank() || token != state.opt("revision")) {
                throw JSONException("Invalid selection ownership")
            }
        } else if (state.has("previousRevision") || state.has("completionCommitted")) {
            throw JSONException("Selection completion has no owner")
        }
        if (state.has("previousRevision") && state.opt("previousRevision") !is String ||
            state.has("completionCommitted") && state.opt("completionCommitted") !is Boolean) {
            throw JSONException("Invalid selection completion")
        }
    }
}
