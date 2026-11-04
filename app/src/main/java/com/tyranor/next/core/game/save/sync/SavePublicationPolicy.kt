package com.tyranor.next.core.game.save.sync

import org.json.JSONObject

/** Publication and cleanup stay relative to one game's observed cloud history. */
internal object SavePublicationPolicy {
    fun block(checkpoint: JSONObject, name: String, etag: String?) {
        checkpoint.put("blockedPublication", name).put("blockedEtag", etag.orEmpty())
    }

    fun isBlocked(checkpoint: JSONObject, target: RemoteSaveVersion?): Boolean {
        val name = checkpoint.optString("blockedPublication")
        if (name.isBlank()) return false
        if (target != null && target.name == name && target.etag.orEmpty() == checkpoint.optString("blockedEtag")) {
            return true
        }
        checkpoint.remove("blockedPublication")
        checkpoint.remove("blockedEtag")
        return false
    }

    fun needsUpload(version: SaveVersion?, uploadedHash: String, promoting: Boolean, remote: List<RemoteSaveVersion>): Boolean =
        version != null && (promoting || version.contentHash != uploadedHash || remote.none { it.contentHash == version.contentHash })

    fun superseded(remote: List<RemoteSaveVersion>, publication: SaveVersion, fromHash: String): List<RemoteSaveVersion> =
        remote.filter { candidate ->
            (candidate.legacy || candidate.name != publication.id) && candidate.createdAt <= publication.createdAt &&
                candidate.contentHash in setOf(publication.contentHash, fromHash)
        }
}
