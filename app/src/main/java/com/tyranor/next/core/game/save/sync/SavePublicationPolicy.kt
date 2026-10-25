package com.tyranor.next.core.game.save.sync

/** Publication and cleanup stay relative to one game's observed cloud history. */
internal object SavePublicationPolicy {
    fun needsUpload(version: SaveVersion?, uploadedHash: String, promoting: Boolean, remote: List<RemoteSaveVersion>): Boolean =
        version != null && (promoting || version.contentHash != uploadedHash || remote.none { it.contentHash == version.contentHash })

    fun superseded(remote: List<RemoteSaveVersion>, publication: SaveVersion, fromHash: String): List<RemoteSaveVersion> =
        remote.filter { candidate ->
            (candidate.legacy || candidate.name != publication.id) && candidate.createdAt <= publication.createdAt &&
                candidate.contentHash in setOf(publication.contentHash, fromHash)
        }
}
