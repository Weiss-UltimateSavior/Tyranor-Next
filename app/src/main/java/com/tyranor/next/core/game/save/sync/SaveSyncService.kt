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
    private val activations = SaveActivationJournal(File(root, "pending"))

    suspend fun listVersions(game: ScanGame): List<SaveVersion> = withContext(Dispatchers.IO) {
        if (!WebDavSettingsStore.isEnabled(context)) return@withContext emptyList()
        SaveSyncLocks.versions(game.uri) {
            requireEnabled()
            snapshots.maintain(localKey(game))
            val pending = activations.load(localKey(game))
            if (pending != null && states.load(localKey(game)).optString("completedActivation") != pending.token &&
                snapshots.list(localKey(game)).none { it.contentHash == pending.selected.contentHash }) {
                snapshots.add(localKey(game), activations.archive(localKey(game)), pending.selected.createdAt,
                    pending.selected.source, pending.selected.contentHash, restored = pending.selected.restored,
                    previousCreatedAt = pending.selected.previousCreatedAt)
            }
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

    suspend fun activateVersion(game: ScanGame, id: String): SaveActivationResult = withContext(Dispatchers.IO) {
        SaveSyncLocks.versions(game.uri) {
            SaveSyncLocks.active(game.uri) {
                requireEnabled()
                requireIdle(game)
                snapshots.maintain(localKey(game))
                val key = localKey(game)
                val pending = activations.load(key)
                val versions = snapshots.list(key)
                val selected = versions.firstOrNull { it.id == id }
                    ?: pending?.selected?.takeIf { it.id == id }
                    ?: versions.firstOrNull { it.contentHash == SaveVersionNames.parse(id)?.contentHash }
                    ?: throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                val temp = temporaryZip()
                try {
                    val source = snapshots.file(key, selected.id).takeIf { it.isFile }
                        ?: activations.archive(key)
                    source.copyTo(temp, overwrite = true)
                    val job = currentCoroutineContext()
                    if (SaveContentHash.archive(temp) { job.ensureActive() } != selected.contentHash) {
                        throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                    }
                    var retryingPrepared = false
                    if (pending != null) {
                        manager.completePendingVersionImport(game)
                        if (pending.canFinalize(fingerprint(game))) {
                            val result = tryFinishActivation(game, pending)
                            if (result != SaveActivationResult.APPLIED || pending.selected.contentHash == selected.contentHash) {
                                return@active result
                            }
                        } else {
                            // A new explicit choice can supersede a failed, uncommitted attempt.
                            // Retain its source before releasing the one bounded staging ZIP.
                            snapshots.add(key, activations.archive(key), pending.selected.createdAt,
                                pending.selected.source, pending.selected.contentHash)
                            activations.clear(key)
                            retryingPrepared = true
                        }
                    }
                    val before = fingerprint(game)
                    val recoveryNeeded = RpgSaveFormat.isRpgWebEngine(game.engine) &&
                        RpgSaveSyncState.forContext(context).needsManualSelectionRecovery(game.uri)
                    if (!retryingPrepared && !recoveryNeeded && before == selected.contentHash) {
                        return@active SaveActivationResult.UNCHANGED
                    }
                    // Consume the protected staging copy before a sixth history version can evict S.
                    activations.prepare(key, selected, temp, before) { job.ensureActive() }
                    var committed = false
                    try {
                        captureLocked(game)
                        job.ensureActive()
                        requireEnabled()
                        // Once the active save changes, leaving this page must not cancel its
                        // matching version metadata or the pending automatic update.
                        withContext(NonCancellable) {
                            try {
                                manager.importFromZip(game, Uri.fromFile(activations.archive(key)), manualSelection = true,
                                    onDirectoryCommitted = { committed = true })
                                tryFinishActivation(game, activations.markCommitted(key))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                if (!committed) throw failure
                                // The save is already active. Retain enough information to retry only
                                // resource/metadata completion instead of claiming that import failed.
                                try { activations.markCommitted(key) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* The staged source and prepared record remain. */ }
                                SaveActivationResult.METADATA_PENDING
                            }
                        }
                    } catch (failure: Exception) {
                        if (!committed) withContext(NonCancellable) {
                            try {
                                activations.discardUncommitted(key) { archive, version ->
                                    manager.completePendingVersionImport(game)
                                    snapshots.add(key, archive, version.createdAt, version.source, version.contentHash,
                                        restored = version.restored, previousCreatedAt = version.previousCreatedAt)
                                }
                            } catch (cleanup: Exception) {
                                if (cleanup !== failure) {
                                    // Preserve the original cancellation object. A new cancellation
                                    // still propagates, with the original failure retained as context.
                                    if (cleanup is CancellationException && failure !is CancellationException) {
                                        cleanup.addSuppressed(failure)
                                        throw cleanup
                                    }
                                    failure.addSuppressed(cleanup)
                                }
                            }
                        }
                        throw failure
                    }
                } finally {
                    SaveSyncTemporaryFiles.release(temp)
                }
            }
        }
    }

    private suspend fun tryFinishActivation(game: ScanGame, pending: SaveActivationJournal.Pending): SaveActivationResult =
        try {
            finishActivation(game, pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SaveActivationResult.METADATA_PENDING
        }

    /** Called while both game locks are held; this never activates another archive. */
    private suspend fun finishActivation(game: ScanGame, pending: SaveActivationJournal.Pending): SaveActivationResult {
        val key = localKey(game)
        manager.completePendingVersionImport(game)
        val state = states.load(key)
        if (state.optString("completedActivation") != pending.token) {
            val current = fingerprint(game) ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
            if (!pending.canFinalize(current)) return SaveActivationResult.METADATA_PENDING
            if (!pending.committed) activations.markCommitted(key)
            val archive = activations.archive(key)
            if (SaveContentHash.archive(archive) != pending.selected.contentHash) {
                throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
            }
            if (RpgSaveFormat.isRpgWebEngine(game.engine) &&
                !RpgSaveSyncState.forContext(context).markManualSelection(game.uri)) {
                throw com.tyranor.next.core.game.save.GameSaveException(
                    com.tyranor.next.core.game.save.SaveErrorCode.SAVE_SELECTION_INCOMPLETE)
            }
            // Preserve the selected archive even when import normalization changes the bytes/paths.
            val selected = snapshots.add(key, archive, nextTimestamp(game), pending.selected.source,
                pending.selected.contentHash, restored = true, refreshExisting = true,
                previousCreatedAt = pending.selected.createdAt)
            val active = if (current == selected.contentHash) selected else {
                captureLocked(game, restored = true, refreshExisting = true, finalizeRestore = true,
                    previousCreatedAt = pending.selected.createdAt)
                    ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
            }
            state.put("promotionFromHash", pending.selected.contentHash)
                .put("promotionFromTime", pending.selected.createdAt).put("promotionAt", active.createdAt)
                .put("completedActivation", pending.token).put("activationHash", active.contentHash)
            states.save(key, state)
        }
        if (!SaveSyncRuntime.recordRestoredVersion(context, game, state.getString("activationHash"))) {
            return SaveActivationResult.METADATA_PENDING
        }
        activations.clear(key)
        return SaveActivationResult.APPLIED
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
                val cached = newest?.let { head -> snapshots.list(localKey(game)).firstOrNull { it.contentHash == head.contentHash } }
                val cachedValid = cached?.let { snapshots.isValid(localKey(game), it) } ?: false
                if (newest != null && (seen == null || REMOTE_ORDER.compare(newest, seen) > 0 || (cached != null && !cachedValid))) {
                    if (cached == null || !cachedValid) {
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
                val version = SaveSyncLocks.active(game.uri) {
                    requireEnabled()
                    if (SaveSyncRuntime.isGameRunning(context, game)) {
                        busy++
                        null
                    } else {
                        activations.load(localKey(game))?.let { pending ->
                            if (tryFinishActivation(game, pending) != SaveActivationResult.APPLIED) {
                                throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
                            }
                        }
                        requireEnabled()
                        captureLocked(game)
                    }
                }
                val localState = states.load(localKey(game))
                val promotionAt = localState.optLong("promotionAt")
                val promoting = promotionAt > checkpoint.optLong("promotedAt")
                if (version != null && checkpoint.optBoolean("needsPrune")) {
                    val intent = checkpoint.optString("publicationIntent").ifBlank { checkpoint.optString("promotionPublication") }
                    val target = remote.firstOrNull { !it.legacy && it.name == intent }
                    val verified = target != null && client.verifyVersion(remoteGame, target)
                    checkpoint.put("publicationConfirmed", verified)
                    if (verified) {
                        // Retry a failed retention DELETE only while a good replacement exists.
                        if (config.remoteLimit > 0) {
                            val excess = remote.sortedWith(REMOTE_ORDER.reversed()).drop(config.remoteLimit)
                            for (old in excess) {
                                requireEnabled()
                                client.delete(remoteGame, old)
                            }
                            remote = remote - excess.toSet()
                        }
                        checkpoint.remove("needsPrune")
                    } else if (target != null && (target.contentHash != version.contentHash ||
                            checkpoint.optLong("publicationPromotionAt") != if (promoting) promotionAt else 0L)) {
                        // Do not append a succession of half-uploaded targets as the game advances.
                        // A conditional delete of this proven-bad target leaves all good sources intact.
                        if (!client.discardCorruptPublication(remoteGame, target)) throw SaveSyncException(SaveSyncError.SERVER)
                        remote = remote - target
                        checkpoint.remove("publicationIntent")
                    }
                    states.save(checkpointKey, checkpoint)
                }
                // Continuing to save after choosing S intentionally promotes the latest N from S.
                val unfinished = checkpoint.optBoolean("needsPrune") && !checkpoint.optBoolean("publicationConfirmed")
                if (SavePublicationPolicy.needsUpload(version, checkpoint.optString("uploaded"), promoting, remote) ||
                    (version != null && unfinished)) {
                    checkNotNull(version)
                    if (promoting || unfinished || remote.none { it.contentHash == version.contentHash }) {
                        val archive = snapshots.file(localKey(game), version.id)
                        val job = currentCoroutineContext()
                        if (SaveContentHash.archive(archive) { job.ensureActive() } != version.contentHash) {
                            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
                        }
                        val latestRemote = remote.maxOfOrNull { it.createdAt } ?: 0L
                        if (latestRemote == Long.MAX_VALUE) throw SaveSyncException(SaveSyncError.INVALID_RESPONSE)
                        // Advance beyond the observed cloud head even when device clocks differ.
                        val pending = SaveVersionNames.parse(checkpoint.optString("publicationIntent"))
                            ?.takeIf { unfinished && it.contentHash == version.contentHash &&
                                checkpoint.optLong("publicationPromotionAt") == if (promoting) promotionAt else 0L }
                            ?: SaveVersionNames.parse(checkpoint.optString("promotionPublication"))
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
                        checkpoint.put("publicationIntent", publication.id).put("publicationConfirmed", false)
                        checkpoint.put("publicationPromotionAt", if (promoting) promotionAt else 0L)
                        if (promoting) {
                            checkpoint.put("promotionPublication", publication.id)
                            checkpoint.put("publishingPromotionAt", promotionAt)
                        }
                        states.save(checkpointKey, checkpoint)
                        requireEnabled()
                        val existingTarget = remote.firstOrNull { !it.legacy && it.name == publication.id }
                        val identical = remote.filter { it.contentHash == version.contentHash }.maxWithOrNull(REMOTE_ORDER)
                        val expectedSize = when {
                            promoting && existingTarget != null -> client.refresh(remoteGame, existingTarget, publication, archive)
                            promoting && identical != null -> client.refresh(remoteGame, identical, publication, archive)
                            else -> {
                                val result = client.upload(remoteGame, publication, archive)
                                if (result.uploaded) uploaded++
                                result.size
                            }
                        }
                        // Reload after publishing: another device may have added a version meanwhile.
                        remote = client.list(remoteGame)
                        requireEnabled()
                        if (remote.none { !it.legacy && it.name == publication.id && it.size == expectedSize }) {
                            // Never prune older cloud versions before the new archive is visible.
                            throw SaveSyncException(SaveSyncError.SERVER)
                        }
                        checkpoint.put("publicationConfirmed", true)
                        states.save(checkpointKey, checkpoint)
                        if (promoting) {
                            // Only the explicitly selected old save and identical copies are superseded.
                            // A newer concurrent publication is never deleted by this promotion.
                            val old = SavePublicationPolicy.superseded(remote, publication, localState.optString("promotionFromHash"))
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
                if (checkpoint.optBoolean("needsPrune") && checkpoint.optBoolean("publicationConfirmed")) {
                    if (config.remoteLimit > 0) {
                        remote.sortedWith(REMOTE_ORDER.reversed()).drop(config.remoteLimit).forEach {
                            requireEnabled()
                            client.delete(remoteGame, it)
                        }
                    }
                    checkpoint.remove("needsPrune")
                    checkpoint.remove("publicationIntent")
                    checkpoint.remove("publicationConfirmed")
                    checkpoint.remove("publicationPromotionAt")
                    states.save(checkpointKey, checkpoint)
                }
            }
            SaveSyncReport(uploaded, downloaded, if (uploaded + downloaded + busy == 0) 1 else 0, busy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if ((failure as? SaveSyncException)?.code == SaveSyncError.DISABLED) return@versions SaveSyncReport()
            SaveSyncReport(uploaded = uploaded, downloaded = downloaded, busy = busy,
                failures = listOf(SaveSyncFailure(game.title, errorCode(failure))))
        }
    }

    internal suspend fun fingerprint(game: ScanGame): String? {
        val location = manager.resolveSaveLocation(game)
        if (!location.available || location.directory == null) throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
        val job = currentCoroutineContext()
        return SaveContentHash.files(manager.syncSaveDirectory(game), manager.listSaveFiles(game, strict = true)) { job.ensureActive() }
    }

    internal fun hasPendingPromotion(game: ScanGame, config: WebDavConfig): Boolean =
        activations.load(localKey(game)) != null ||
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
        val job = currentCoroutineContext()
        snapshots.list(localKey(game)).firstOrNull { it.contentHash == before &&
            snapshots.isValid(localKey(game), it) { job.ensureActive() } }?.let {
            return if (refreshExisting) snapshots.refreshVersion(localKey(game), it, nextTimestamp(game), previousCreatedAt) else it
        }
        val temp = temporaryZip()
        try {
            // Reuse TYN's engine-aware exclusions and existing ZIP exporter.
            manager.exportToZip(game, Uri.fromFile(temp), strict = true)
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
        if (!WebDavSettingsStore.isEnabled(context)) throw SaveSyncException(SaveSyncError.DISABLED)
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
            is com.tyranor.next.core.game.save.GameSaveException -> when (failure.code) {
                com.tyranor.next.core.game.save.SaveErrorCode.SAVE_SELECTION_INCOMPLETE -> SaveSyncError.SELECTION_INCOMPLETE
                com.tyranor.next.core.game.save.SaveErrorCode.RESOLVE_SAVE_DIR_FAILED,
                com.tyranor.next.core.game.save.SaveErrorCode.SAVE_DIR_UNAVAILABLE,
                com.tyranor.next.core.game.save.SaveErrorCode.NO_EXPORTABLE_FILES -> SaveSyncError.SAVE_UNAVAILABLE
                com.tyranor.next.core.game.save.SaveErrorCode.NO_FILES_IN_ZIP,
                com.tyranor.next.core.game.save.SaveErrorCode.DUPLICATE_ZIP_ENTRY,
                com.tyranor.next.core.game.save.SaveErrorCode.TOO_MANY_ZIP_FILES,
                com.tyranor.next.core.game.save.SaveErrorCode.ILLEGAL_ZIP_PATH,
                com.tyranor.next.core.game.save.SaveErrorCode.ZIP_TOO_LARGE -> SaveSyncError.CORRUPT_ARCHIVE
                else -> SaveSyncError.LOCAL_IO
            }
            else -> SaveSyncError.LOCAL_IO
        }
    }
}
