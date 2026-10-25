package com.tyranor.next.core.game.save.sync

import android.content.Context
import android.net.Uri
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.game.save.RpgSaveSyncState
import com.tyranor.next.core.game.storage.GameLibraryFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** All cloud transfers stage ZIPs. Only [activateVersion], called by the user, imports one. */
class SaveSyncService(context: Context) {
    private val context = context.applicationContext
    private val manager = GameSaveManager(this.context)
    private val root = File(this.context.noBackupFilesDir, "save_sync")
    private val snapshots = SaveSnapshotStore(File(root, "versions"))
    private val states = SaveSyncStateStore(File(root, "checkpoints"))

    suspend fun listVersions(game: ScanGame): List<SaveVersion> = withContext(Dispatchers.IO) {
        if (!WebDavSettingsStore.isEnabled(context)) return@withContext emptyList()
        SaveSyncLocks.versions(game.uri) {
            requireEnabled()
            snapshots.maintain(localKey(game))
            snapshots.list(localKey(game))
        }
    }

    /** A portable identity is independent of storage volume, absolute path and renamed title. */
    fun identity(game: ScanGame): String = states.load(localKey(game)).optString("identity")
        .ifBlank { defaultIdentity(game) }

    suspend fun setIdentity(game: ScanGame, identity: String) = withContext(Dispatchers.IO) {
        SaveSyncLocks.versions(game.uri) {
            requireEnabled()
            val value = identity.trim()
            if (value.length > 256 || value.any { it.code < 32 }) throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
            val state = states.load(localKey(game))
            state.put("identity", value)
            states.save(localKey(game), state)
        }
    }

    suspend fun activateVersion(game: ScanGame, id: String): Boolean = withContext(Dispatchers.IO) {
        SaveSyncLocks.versions(game.uri) {
            SaveSyncLocks.active(game.uri) {
                requireEnabled()
                requireIdle(game)
                snapshots.maintain(localKey(game))
                val selected = snapshots.list(localKey(game)).firstOrNull { it.id == id }
                    ?: throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                val recoveryNeeded = RpgSaveFormat.isRpgWebEngine(game.engine) &&
                    RpgSaveSyncState.forContext(context).needsManualSelectionRecovery(game.uri)
                if (!recoveryNeeded && fingerprint(game) == selected.contentHash) return@active false
                val temp = temporaryZip()
                try {
                    snapshots.file(localKey(game), id).copyTo(temp, overwrite = true)
                    val job = currentCoroutineContext()
                    if (SaveContentHash.archive(temp) { job.ensureActive() } != selected.contentHash) {
                        throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                    }
                    // Copy the selection before adding the current save: a sixth version may evict it.
                    captureLocked(game)
                    job.ensureActive()
                    requireEnabled()
                    // Once the active save changes, leaving this page must not cancel its
                    // matching version metadata or the pending automatic update.
                    withContext(NonCancellable) {
                        manager.importFromZip(game, Uri.fromFile(temp), manualSelection = true)
                        val restoredHash = fingerprint(game) ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
                        val promoted = if (restoredHash == selected.contentHash) {
                            snapshots.add(localKey(game), temp, nextTimestamp(game), selected.source,
                                selected.contentHash, restored = true, refreshExisting = true,
                                previousCreatedAt = selected.createdAt)
                        } else {
                            captureLocked(game, restored = true, refreshExisting = true, finalizeRestore = true,
                                previousCreatedAt = selected.createdAt)
                                ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
                        }
                        val state = states.load(localKey(game))
                        state.put("promotionFromHash", selected.contentHash)
                        state.put("promotionFromTime", selected.createdAt)
                        state.put("promotionAt", promoted.createdAt)
                        states.save(localKey(game), state)
                        SaveSyncRuntime.recordRestoredVersion(context, game, promoted.contentHash)
                    }
                    true
                } finally {
                    SaveSyncTemporaryFiles.release(temp)
                }
            }
        }
    }

    suspend fun syncAll(direction: SaveSyncDirection): SaveSyncReport = withContext(Dispatchers.IO) {
        if (!WebDavSettingsStore.isEnabled(context)) return@withContext SaveSyncReport()
        // Validate once before walking the library. No archive transfer is needed for unchanged games.
        val config = WebDavSettingsStore.load(context)
        if (!config.configured) throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
        var report = SaveSyncReport()
        val keys = LinkedHashMap<String, MutableList<ScanGame>>()
        for (game in GameLibraryFacade.loadGames(context)) {
            currentCoroutineContext().ensureActive()
            requireEnabled()
            try {
                if (manager.resolveSaveLocation(game).available) keys.getOrPut(remoteKey(game)) { ArrayList() }.add(game)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report += SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, errorCode(failure))))
            }
        }
        WebDavClient(config, context.cacheDir) { WebDavSettingsStore.isEnabled(context) }.use { client ->
            for ((_, games) in keys) for (game in games) {
                currentCoroutineContext().ensureActive()
                requireEnabled()
                report += if (games.size > 1) {
                    SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, SaveSyncError.IDENTITY_CONFLICT)))
                } else syncOne(game, direction, config, client)
            }
        }
        report
    }

    suspend fun syncGame(game: ScanGame, direction: SaveSyncDirection): SaveSyncReport = withContext(Dispatchers.IO) {
        if (!WebDavSettingsStore.isEnabled(context)) return@withContext SaveSyncReport()
        val config = WebDavSettingsStore.load(context)
        if (!config.configured) throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
        val collisions = GameLibraryFacade.loadGames(context).count {
            try {
                manager.resolveSaveLocation(it).available && remoteKey(it) == remoteKey(game)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unavailable library entry must not block a different game's upload.
                it.uri == game.uri
            }
        }
        if (collisions > 1) throw SaveSyncException(SaveSyncError.IDENTITY_CONFLICT)
        WebDavClient(config, context.cacheDir) { WebDavSettingsStore.isEnabled(context) }
            .use { syncOne(game, direction, config, it) }
    }

    private suspend fun syncOne(
        game: ScanGame,
        direction: SaveSyncDirection,
        config: WebDavConfig,
        client: WebDavClient,
    ): SaveSyncReport = SaveSyncLocks.versions(game.uri) {
        var downloaded = 0
        var uploaded = 0
        var busy = 0
        try {
            requireEnabled()
            snapshots.maintain(localKey(game))
            val remoteGame = RemoteSaveGame(game.engine.name, identity(game))
            val remoteKey = remoteGame.key
            val checkpointKey = checkpointKey(game, config, remoteKey)
            val checkpoint = states.load(checkpointKey)
            var remote = client.list(remoteGame)
            requireEnabled()

            if (direction.pulls) {
                val newest = remote.maxWithOrNull(REMOTE_ORDER)
                val seen = SaveVersionNames.parse(checkpoint.optString("seen"))
                if (newest != null && (seen == null || REMOTE_ORDER.compare(newest, seen) > 0)) {
                    val cached = snapshots.list(localKey(game)).firstOrNull { it.contentHash == newest.contentHash }
                    if (cached == null) {
                        val temp = temporaryZip()
                        try {
                            client.download(remoteGame, newest, temp)
                            requireEnabled()
                            val job = currentCoroutineContext()
                            snapshots.add(localKey(game), temp, newest.createdAt, SaveVersionSource.CLOUD,
                                newest.contentHash, restored = newest.restored, previousCreatedAt = newest.previousCreatedAt) {
                                job.ensureActive()
                            }
                            downloaded++
                        } finally {
                            SaveSyncTemporaryFiles.release(temp)
                        }
                    } else if (newest.restored && (!cached.restored || newest.createdAt > cached.createdAt ||
                            (cached.previousCreatedAt == null && newest.previousCreatedAt != null))) {
                        // Another device promoted bytes that are already cached: only refresh metadata.
                        snapshots.refreshVersion(localKey(game), cached, newest.createdAt, newest.previousCreatedAt)
                    }
                    // Persist even when this content was already cached or has since been evicted.
                    checkpoint.put("seen", newest.name)
                    states.save(checkpointKey, checkpoint)
                }
            }
            if (direction.uploads) {
                // A failed prior DELETE must not let each later save append another cloud ZIP.
                // Finish this game's already-pending overflow cleanup before publishing more.
                if (checkpoint.optBoolean("needsPrune") && config.remoteLimit > 0 && remote.size > config.remoteLimit) {
                    val excess = remote.sortedWith(REMOTE_ORDER.reversed()).drop(config.remoteLimit)
                    for (old in excess) {
                        requireEnabled()
                        client.delete(remoteGame, old)
                    }
                    remote = remote - excess.toSet()
                    checkpoint.remove("needsPrune")
                    states.save(checkpointKey, checkpoint)
                }
                val version = SaveSyncLocks.active(game.uri) {
                    requireEnabled()
                    if (SaveSyncRuntime.isGameRunning(context, game)) {
                        busy++
                        null
                    } else {
                        captureLocked(game)
                    }
                }
                val localState = states.load(localKey(game))
                val promotionAt = localState.optLong("promotionAt")
                val promoting = promotionAt > checkpoint.optLong("promotedAt")
                if (version != null && (promoting || version.contentHash != checkpoint.optString("uploaded"))) {
                    if (promoting || remote.none { it.contentHash == version.contentHash }) {
                        val archive = snapshots.file(localKey(game), version.id)
                        val job = currentCoroutineContext()
                        if (SaveContentHash.archive(archive) { job.ensureActive() } != version.contentHash) {
                            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                        }
                        val latestRemote = remote.maxOfOrNull { it.createdAt } ?: 0L
                        if (latestRemote == Long.MAX_VALUE) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                        // Advance beyond the observed cloud head even when device clocks differ.
                        val pending = SaveVersionNames.parse(checkpoint.optString("promotionPublication"))
                            ?.takeIf { promoting && it.contentHash == version.contentHash &&
                                checkpoint.optLong("publishingPromotionAt") == promotionAt }
                        val publishedAt = pending?.createdAt ?: maxOf(System.currentTimeMillis(), version.createdAt, latestRemote + 1)
                        val previousCreatedAt = if (promoting) {
                            localState.optLong("promotionFromTime").takeIf { it > 0 } ?: version.previousCreatedAt
                        } else version.previousCreatedAt
                        val publication = version.copy(
                            id = pending?.name ?: SaveVersionNames.createRemote(publishedAt, version.contentHash,
                                restored = promoting || version.restored, previousCreatedAt = previousCreatedAt),
                            createdAt = publishedAt, restored = promoting || version.restored,
                            previousCreatedAt = pending?.previousCreatedAt ?: previousCreatedAt,
                        )
                        // Record cleanup intent before publishing, so a failed retention DELETE retries
                        // without uploading the same ZIP on every subsequent app opening.
                        checkpoint.put("needsPrune", true)
                        if (promoting) {
                            checkpoint.put("promotionPublication", publication.id)
                            checkpoint.put("publishingPromotionAt", promotionAt)
                        }
                        states.save(checkpointKey, checkpoint)
                        requireEnabled()
                        val existingTarget = remote.firstOrNull { !it.legacy && it.name == publication.id }
                        val identical = remote.filter { it.contentHash == version.contentHash }.maxWithOrNull(REMOTE_ORDER)
                        val expectedSize = when {
                            promoting && existingTarget != null -> existingTarget.size
                            promoting && identical != null -> client.refresh(remoteGame, identical, publication, archive)
                            else -> {
                                if (client.upload(remoteGame, publication, archive)) uploaded++
                                archive.length()
                            }
                        }
                        // Reload after publishing: another device may have added a version meanwhile.
                        remote = client.list(remoteGame)
                        requireEnabled()
                        if (remote.none { !it.legacy && it.name == publication.id && it.size == expectedSize }) {
                            // Never prune older cloud versions before the new archive is visible.
                            throw SaveSyncException(SaveSyncError.SERVER)
                        }
                        if (promoting) {
                            // Only the explicitly selected old save and identical copies are superseded.
                            // A newer concurrent publication is never deleted by this promotion.
                            val old = remote.filter { candidate ->
                                (candidate.legacy || candidate.name != publication.id) && candidate.createdAt <= publishedAt &&
                                    candidate.contentHash in setOf(version.contentHash, localState.optString("promotionFromHash"))
                            }
                            for (candidate in old) {
                                requireEnabled()
                                client.delete(remoteGame, candidate)
                            }
                            remote = remote - old.toSet()
                            snapshots.refreshVersion(localKey(game), version, publishedAt, publication.previousCreatedAt)
                            checkpoint.put("promotedAt", promotionAt)
                            checkpoint.remove("promotionPublication")
                            checkpoint.remove("publishingPromotionAt")
                            if (uploaded == 0) uploaded++ // A DAV MOVE is one completed logical upload.
                        }
                    }
                    checkpoint.put("uploaded", version.contentHash)
                    // Our own upload must not be downloaded again on the next app opening.
                    remote.filter { it.contentHash == version.contentHash }.maxWithOrNull(REMOTE_ORDER)?.let {
                        val seen = SaveVersionNames.parse(checkpoint.optString("seen"))
                        if (seen == null || REMOTE_ORDER.compare(it, seen) > 0) checkpoint.put("seen", it.name)
                    }
                    states.save(checkpointKey, checkpoint)
                }
                // Retention is scoped to this changed game, never applied to unrelated games.
                if (checkpoint.optBoolean("needsPrune")) {
                    if (config.remoteLimit > 0) {
                        remote.sortedWith(REMOTE_ORDER.reversed()).drop(config.remoteLimit).forEach {
                            requireEnabled()
                            client.delete(remoteGame, it)
                        }
                    }
                    checkpoint.remove("needsPrune")
                    states.save(checkpointKey, checkpoint)
                }
            }
            SaveSyncReport(uploaded, downloaded, if (uploaded + downloaded + busy == 0) 1 else 0, busy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SaveSyncReport(uploaded = uploaded, downloaded = downloaded, busy = busy,
                failures = listOf(SaveSyncFailure(game.title, errorCode(failure))))
        }
    }

    internal suspend fun fingerprint(game: ScanGame): String? {
        val location = manager.resolveSaveLocation(game)
        if (!location.available || location.directory == null) throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
        val job = currentCoroutineContext()
        return SaveContentHash.files(location.directory, manager.listSaveFiles(game, strict = true)) { job.ensureActive() }
    }

    internal fun hasPendingPromotion(game: ScanGame, config: WebDavConfig): Boolean =
        states.load(localKey(game)).optLong("promotionAt") > states.load(checkpointKey(game, config)).optLong("promotedAt")

    private suspend fun captureLocked(
        game: ScanGame,
        restored: Boolean = false,
        refreshExisting: Boolean = false,
        finalizeRestore: Boolean = false,
        previousCreatedAt: Long? = null,
    ): SaveVersion? {
        if (!finalizeRestore) requireEnabled()
        snapshots.maintain(localKey(game))
        val before = fingerprint(game) ?: return null
        snapshots.list(localKey(game)).firstOrNull { it.contentHash == before }?.let {
            val job = currentCoroutineContext()
            if (SaveContentHash.archive(snapshots.file(localKey(game), it.id)) { job.ensureActive() } != before) {
                throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
            }
            return if (refreshExisting) snapshots.refreshVersion(localKey(game), it, nextTimestamp(game), previousCreatedAt) else it
        }
        val temp = temporaryZip()
        try {
            // Reuse TYN's engine-aware exclusions and existing ZIP exporter.
            manager.exportToZip(game, Uri.fromFile(temp))
            if (!finalizeRestore) requireEnabled()
            if (fingerprint(game) != before) throw SaveSyncException(SaveSyncError.GAME_RUNNING)
            val job = currentCoroutineContext()
            return snapshots.add(localKey(game), temp, nextTimestamp(game), SaveVersionSource.LOCAL, before,
                restored = restored, refreshExisting = refreshExisting, previousCreatedAt = previousCreatedAt) { job.ensureActive() }
        } finally {
            SaveSyncTemporaryFiles.release(temp)
        }
    }

    private fun nextTimestamp(game: ScanGame): Long =
        maxOf(System.currentTimeMillis(), (snapshots.list(localKey(game)).firstOrNull()?.createdAt ?: 0L) + 1)

    private fun requireIdle(game: ScanGame) {
        if (SaveSyncRuntime.isGameRunning(context, game)) throw SaveSyncException(SaveSyncError.GAME_RUNNING)
    }

    private fun requireEnabled() {
        if (!WebDavSettingsStore.isEnabled(context)) throw CancellationException("WebDAV disabled")
    }

    private fun defaultIdentity(game: ScanGame): String {
        val path = GamePathUtils.safUriToPath(game.uri)
        val folder = path?.let { File(it).name }?.takeIf { it.isNotBlank() }
            ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
        return Normalizer.normalize(folder, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    private fun remoteKey(game: ScanGame) = SaveContentHash.text(game.engine.name + "\n" + identity(game))
    private fun checkpointKey(game: ScanGame, config: WebDavConfig, remoteKey: String = remoteKey(game)) =
        SaveContentHash.text(config.serverUrl.trimEnd('/') + "\n" + config.username + "\n" + remoteKey + "\n" + game.uri)
    private fun localKey(game: ScanGame) = SaveContentHash.text(game.uri)
    private fun temporaryZip(): File = SaveSyncTemporaryFiles.create("save_sync_", ".zip", context.cacheDir)

    companion object {
        private val REMOTE_ORDER = compareBy<RemoteSaveVersion> { it.createdAt }.thenBy { it.contentHash }
        internal fun errorCode(failure: Exception): SaveSyncError = when (failure) {
            is SaveSyncException -> failure.code
            is com.tyranor.next.core.game.save.GameSaveException -> SaveSyncError.LOCAL_IO
            else -> SaveSyncError.LOCAL_IO
        }
    }
}
