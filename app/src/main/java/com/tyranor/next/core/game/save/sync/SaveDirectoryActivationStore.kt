package com.tyranor.next.core.game.save.sync

import org.json.JSONObject
import java.io.File

/** Written under the active-directory lock before import; shared by every URI using that directory. */
internal class SaveDirectoryActivationStore(root: File) {
    private val states = SaveSyncStateStore(File(root, "directory_selections"))

    data class Pending(val activeKey: String, val uri: String, val token: String)

    fun revision(activeKey: String): String = load(activeKey).optString("revision")

    fun requireAvailable(activeKey: String, ownerUri: String? = null) {
        val owner = load(activeKey).optString("ownerUri")
        if (owner.isNotBlank() && owner != ownerUri) throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
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

    fun complete(activeKey: String, uri: String, token: String, committed: Boolean = true) {
        val state = load(activeKey)
        if (state.optString("ownerUri").isBlank()) return
        if (state.optString("ownerUri") != uri || state.optString("ownerToken") != token) {
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
        state.remove("ownerUri")
        state.remove("ownerToken")
        if (!committed) state.put("revision", state.optString("previousRevision"))
        state.remove("previousRevision")
        // Keep the revision: an older pinned transfer must stay stale after cleanup succeeds.
        states.save(SaveContentHash.text(activeKey), state)
    }

    fun owned(uri: String): List<Pending> = states.keys().mapNotNull { key ->
        val state = states.load(key)
        if (state.optString("ownerUri") != uri) null else Pending(
            state.getString("activeKey"), uri, state.getString("ownerToken"))
    }

    private fun load(activeKey: String): JSONObject = states.load(SaveContentHash.text(activeKey))
}
