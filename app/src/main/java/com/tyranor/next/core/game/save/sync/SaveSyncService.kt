package com.tyranor.next.core.game.save.sync

import android.content.Context
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** All cloud transfers stage ZIPs. Only [activateVersion], called by the user, imports one. */
class SaveSyncService internal constructor(
    private val root: File,
    private val environment: SaveSyncEnvironment,
) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "save_sync"),
        AndroidSaveSyncEnvironment(context.applicationContext))
    private val snapshots = SaveSnapshotStore(File(root, "versions"))
    private val states = SaveSyncStateStore(File(root, "checkpoints"))
    private val activations = SaveActivationJournal(File(root, "pending"))

    suspend fun listVersions(game: ScanGame): List<SaveVersion> = withContext(Dispatchers.IO) {
        if (!environment.enabled) return@withContext emptyList()
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
            state.put("identity", value).put("revision", UUID.randomUUID().toString())
            states.save(localKey(game), state)
        }
    }

    /** Library removal clears only private metadata/history, never cloud or game files. */
    suspend fun clearGame(game: ScanGame) = withContext(Dispatchers.IO) {
        SaveSyncLocks.versions(game.uri) {
            SaveSyncLocks.active(environment.activeKey(game)) {
                requireIdle(game)
                val key = localKey(game)
                val legacyCheckpoint = try { checkpointKey(game, environment.config()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                // An unreadable ownership index cannot establish that cleanup is complete.
                val index = states.load(key).optJSONArray("checkpointKeys")
                val owned = (0 until (index?.length() ?: 0)).map { index!!.getString(it) }.toSet()
                val failures = ArrayList<Exception>()
                fun clear(action: () -> Unit) {
                    try { action() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { failures += failure }
                }
                for (checkpoint in (states.keys() + owned + listOfNotNull(legacyCheckpoint)).distinct().filter { it != key }) {
                    val declaredOwner = try {
                        states.load(checkpoint).optString("gameKey").takeIf { it.isNotBlank() }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    val belongs = if (declaredOwner != null) declaredOwner == key
                        else checkpoint == legacyCheckpoint || checkpoint in owned
                    if (belongs) clear { states.remove(checkpoint) }
                }
                clear { com.tyranor.next.core.game.save.SavePathSafety.deleteTreeNoFollow(File(root, "versions/$key")) }
                clear { activations.clear(key) }
                clear { SaveSyncStateStore(File(root, "sessions")).remove(key) }
                clear { SaveSyncStateStore(File(root, "baselines")).remove(key) }
                if (failures.isNotEmpty()) {
                    throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failures.first()).also { failure ->
                        failures.drop(1).forEach(failure::addSuppressed)
                    }
                }
                // The identity and ownership index are the retry map for failed child cleanup.
                states.remove(key)
            }
        }
    }

    fun needsSelectionRecovery(game: ScanGame): Boolean = try {
        activations.load(localKey(game)) != null || activations.archive(localKey(game)).exists() ||
            environment.needsManualRecovery(game)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

    /** Local recovery remains available after WebDAV is disabled; it never imports an archive. */
    suspend fun recoverSelection(game: ScanGame) = withContext(Dispatchers.IO) {
        try {
            SaveSyncLocks.versions(game.uri) {
                SaveSyncLocks.active(environment.activeKey(game)) {
                    requireIdle(game)
                    if (needsSelectionRecovery(game)) {
                        invalidateTransfers(game)
                        abandonActivationLocked(game)
                    }
                }
            }
        } catch (failure: java.io.IOException) {
            if (failure is SaveSyncException || failure is com.tyranor.next.core.game.save.GameSaveException) throw failure
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    suspend fun activateVersion(game: ScanGame, id: String, discardPrevious: Boolean = false): SaveActivationResult = withContext(Dispatchers.IO) {
        SaveSyncLocks.versions(game.uri) {
            SaveSyncLocks.active(environment.activeKey(game)) {
                requireEnabled()
                requireIdle(game)
                invalidateTransfers(game)
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
                        environment.completeImport(game)
                        val result = SaveActivationRecovery.beforeSelection(
                            sameContent = pending.selected.contentHash == selected.contentHash,
                            canFinalize = pending.canFinalize(fingerprint(game)),
                            discardPrevious = discardPrevious,
                            currentMatchesSelection = fingerprint(game) == selected.contentHash,
                            finish = { tryFinishActivation(game, pending) },
                            discard = { abandonActivationLocked(game) },
                        )
                        if (result != null) return@active result
                        retryingPrepared = true
                    }
                    val before = fingerprint(game)
                    val recoveryNeeded = environment.needsManualRecovery(game)
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
                                environment.import(game, activations.archive(key)) { committed = true }
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
                                    environment.completeImport(game)
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

    /** Both game locks are held. Preserve usable data before abandoning only bookkeeping. */
    private suspend fun abandonActivationLocked(game: ScanGame) {
        environment.completeImport(game)
        val key = localKey(game)
        val current = fingerprint(game)
        var damagedRecord = false
        val pending = try {
            activations.load(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SaveSyncException) {
            damagedRecord = true
            null
        }
        val archive = activations.archive(key)
        // Parseable metadata can still lie about the ZIP's hash. Independently validate its bytes
        // and keep the only protected source until both it and the active save are durable.
        val archiveHash = if (archive.exists()) SaveContentHash.archive(archive) else null
        if (pending != null && archiveHash == null) {
            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        }
        val protected = setOfNotNull(archiveHash, current)
        if (current != null) captureLocked(game, finalizeRestore = true, protectedHashes = protected)
        if (archiveHash != null) {
            val trusted = pending?.selected?.takeIf { it.contentHash == archiveHash }
            snapshots.add(key, archive, trusted?.createdAt ?: maxOf(1L, archive.lastModified()),
                trusted?.source ?: SaveVersionSource.LOCAL, archiveHash,
                restored = trusted?.restored ?: false, previousCreatedAt = trusted?.previousCreatedAt,
                protectedHashes = protected)
        } else if (damagedRecord) {
            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        }
        for (hash in protected) {
            val retained = snapshots.list(key).firstOrNull { it.contentHash == hash }
                ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
            if (!snapshots.isValid(key, retained)) throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        }
        if (!environment.repairManualSelection(game, current != null)) {
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
        val state = states.load(key)
        val activationEvidence = pending != null || archiveHash != null || damagedRecord
        if (activationEvidence && (pending == null || pending.selected.contentHash != archiveHash ||
            pending.canFinalize(current) || state.optString("completedActivation") == pending.token)) {
            listOf("completedActivation", "activationHash", "promotionFromHash", "promotionFromTime", "promotionAt",
                "promotionBaseHash", "promotionAwaitingChange", "promotionContentHash")
                .forEach(state::remove)
            states.save(key, state)
        }
        activations.clear(key)
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
        environment.completeImport(game)
        val state = states.load(key)
        if (state.optString("completedActivation") != pending.token) {
            val current = fingerprint(game) ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
            if (!pending.canFinalize(current)) return SaveActivationResult.METADATA_PENDING
            if (!pending.committed) activations.markCommitted(key)
            val archive = activations.archive(key)
            if (SaveContentHash.archive(archive) != pending.selected.contentHash) {
                throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
            }
            if (!environment.repairManualSelection(game, true)) {
                throw com.tyranor.next.core.game.save.GameSaveException(
                    com.tyranor.next.core.game.save.SaveErrorCode.SAVE_SELECTION_INCOMPLETE)
            }
            // Preserve the selected archive even when import normalization changes the bytes/paths.
            val selected = snapshots.add(key, archive, nextTimestamp(game), pending.selected.source,
                pending.selected.contentHash, restored = true, refreshExisting = true,
                previousCreatedAt = pending.selected.createdAt)
            val active = if (current == selected.contentHash) selected else {
                captureLocked(game, restored = true, refreshExisting = true, finalizeRestore = true,
                    previousCreatedAt = pending.selected.createdAt, protectedHashes = setOf(selected.contentHash))
                    ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
            }
            state.put("promotionFromHash", pending.selected.contentHash)
                .put("promotionFromTime", pending.selected.createdAt).put("promotionAt", active.createdAt)
                .put("promotionBaseHash", active.contentHash).put("promotionAwaitingChange", true)
                .put("promotionContentHash", active.contentHash)
                .put("completedActivation", pending.token).put("activationHash", active.contentHash)
            states.save(key, state)
        }
        if (!environment.recordRestored(game, state.getString("activationHash"))) {
            return SaveActivationResult.METADATA_PENDING
        }
        activations.clear(key)
        return SaveActivationResult.APPLIED
    }

    suspend fun syncAll(direction: SaveSyncDirection): SaveSyncReport = withContext(Dispatchers.IO) {
        requireEnabled()
        // Validate once before walking the library. No archive transfer is needed for unchanged games.
        val config = environment.config()
        if (!config.configured) throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
        var report = SaveSyncReport()
        val keys = LinkedHashMap<String, MutableList<ScanGame>>()
        for (game in environment.games()) {
            currentCoroutineContext().ensureActive()
            requireEnabled()
            try {
                val remoteKey = remoteKeyForConflict(game)
                if (remoteKey != null) keys.getOrPut(remoteKey) { ArrayList() }.add(game)
                else if (environment.available(game)) throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report += SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, errorCode(failure))))
            }
        }
        environment.openClient(config).use { client ->
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

    suspend fun syncGame(game: ScanGame, direction: SaveSyncDirection): SaveSyncReport =
        syncGameIfCurrent(game, direction) { true }

    /** Automatic flush must not adopt a newer selection after its session was retired. */
    internal suspend fun syncGameIfCurrent(
        game: ScanGame,
        direction: SaveSyncDirection,
        isCurrent: () -> Boolean,
    ): SaveSyncReport = withContext(Dispatchers.IO) {
        requireEnabled()
        val config = environment.config()
        if (!config.configured) throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
        environment.openClient(config)
            .use { syncOne(game, direction, config, it, isCurrent) }
    }

    private suspend fun syncOne(
        game: ScanGame,
        direction: SaveSyncDirection,
        config: WebDavConfig,
        client: SaveSyncTransport,
        isCurrent: () -> Boolean = { true },
    ): SaveSyncReport = SaveSyncLocks.network(game.uri) {
        val key = localKey(game)
        var revision: String? = null
        var uploadArchive: File? = null
        suspend fun <T> local(block: suspend () -> T): T = SaveSyncLocks.versions(game.uri) {
            requireEnabled()
            if (!isCurrent()) throw LocalStateChanged()
            if (revision != null && states.load(key).optString("revision") != revision) throw LocalStateChanged()
            block()
        }
        suspend fun checkCurrent() = local { Unit }
        suspend fun saveCheckpoint(key: String, value: org.json.JSONObject) = local { states.save(key, value) }
        var downloaded = 0
        var uploaded = 0
        var busy = 0
        try {
            if (!local { environment.available(game) }) return@network SaveSyncReport()
            val remoteGame = local {
                if (environment.games().none { it.uri == game.uri }) throw LocalStateChanged()
                val state = states.load(key)
                revision = state.optString("revision").ifBlank { UUID.randomUUID().toString() }
                state.put("revision", revision)
                states.save(key, state)
                snapshots.maintain(key)
                RemoteSaveGame(game.engine.name, identity(game)).also { adopted ->
                    // This runs after network queue admission, under the same lock used to edit
                    // this game's identity. Unavailable saves still own their portable identity.
                    if (environment.games().any { other ->
                        other.uri != game.uri && other.engine == game.engine && remoteKeyForConflict(other) == adopted.key
                    }) throw SaveSyncException(SaveSyncError.IDENTITY_CONFLICT)
                }
            }
            val remoteKey = remoteGame.key
            val checkpointKey = checkpointKey(game, config, remoteKey)
            local {
                val state = states.load(key)
                val index = state.optJSONArray("checkpointKeys") ?: org.json.JSONArray()
                if ((0 until index.length()).none { index.optString(it) == checkpointKey }) index.put(checkpointKey)
                state.put("checkpointKeys", index)
                states.save(key, state)
            }
            val checkpoint = local { states.load(checkpointKey).put("gameKey", key) }
            var remote = client.list(remoteGame)
            checkCurrent()

            if (direction.pulls) {
                val newest = remote.maxWithOrNull(REMOTE_ORDER)
                val seen = SaveVersionNames.parse(checkpoint.optString("seen"))
                val cached = local { newest?.let { head -> snapshots.list(key).firstOrNull { it.contentHash == head.contentHash } } }
                val cachedValid = local { cached?.let { snapshots.isValid(key, it) } ?: false }
                if (newest != null && (seen == null || REMOTE_ORDER.compare(newest, seen) > 0 || (cached == null || !cachedValid))) {
                    if (cached == null || !cachedValid) {
                        val temp = temporaryZip()
                        try {
                            client.download(remoteGame, newest, temp)
                            checkCurrent()
                            val job = currentCoroutineContext()
                            local {
                                snapshots.add(key, temp, newest.createdAt, SaveVersionSource.CLOUD,
                                    newest.contentHash, restored = newest.restored, previousCreatedAt = newest.previousCreatedAt) {
                                    job.ensureActive()
                                }
                            }
                            downloaded++
                        } finally {
                            SaveSyncTemporaryFiles.release(temp)
                        }
                    } else if (newest.restored && (!cached.restored || newest.createdAt > cached.createdAt ||
                            (cached.previousCreatedAt == null && newest.previousCreatedAt != null))) {
                        // Another device promoted bytes that are already cached: only refresh metadata.
                        local { snapshots.refreshVersion(key, cached, newest.createdAt, newest.previousCreatedAt) }
                    }
                    // Advance seen only after the latest cloud bytes are available locally.
                    checkpoint.put("seen", newest.name)
                    saveCheckpoint(checkpointKey, checkpoint)
                }
            }
            if (direction.uploads) {
                val version = local { SaveSyncLocks.active(environment.activeKey(game)) {
                    requireEnabled()
                    // A related URI can retire this session while we wait for the shared
                    // directory lock, after local() has already checked admission.
                    if (!isCurrent()) throw LocalStateChanged()
                    if (environment.running(game)) {
                        busy++
                        null
                    } else {
                        activations.load(localKey(game))?.let { pending ->
                            if (tryFinishActivation(game, pending) != SaveActivationResult.APPLIED) {
                                throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
                            }
                        }
                        requireEnabled()
                        captureLocked(game)?.also { captured ->
                            uploadArchive = temporaryZip().also { pinned ->
                                snapshots.file(key, captured.id).copyTo(pinned, overwrite = true)
                            }
                        }
                    }
                } }
                // A busy/empty game cannot authorize publication cleanup using a prior round's
                // confirmation. Leave the complete checkpoint for a later idle retry.
                if (version == null) return@network SaveSyncReport(downloaded = downloaded, busy = busy,
                    unchanged = if (downloaded + busy == 0) 1 else 0)
                val localState = local { states.load(key) }
                val promotionAt = localState.optLong("promotionAt")
                val promoting = promotionAt > checkpoint.optLong("promotedAt")
                if (checkpoint.optBoolean("needsPrune")) {
                    val intent = checkpoint.optString("publicationIntent").ifBlank { checkpoint.optString("promotionPublication") }
                    val target = remote.firstOrNull { !it.legacy && it.name == intent }
                    if (SavePublicationPolicy.isBlocked(checkpoint, target)) {
                        throw SaveSyncException(SaveSyncError.CLOUD_REPAIR_REQUIRED)
                    }
                    val verified = target != null && client.verifyVersion(remoteGame, target)
                    checkpoint.put("publicationConfirmed", verified)
                    if (verified) {
                        // Retry a failed retention DELETE only while a good replacement exists.
                        if (config.remoteLimit > 0) {
                            val excess = remote.sortedWith(REMOTE_ORDER.reversed()).drop(config.remoteLimit)
                            for (old in excess) {
                                checkCurrent()
                                client.delete(remoteGame, old)
                            }
                            remote = remote - excess.toSet()
                        }
                        checkpoint.remove("needsPrune")
                        checkpoint.remove("publicationIntent")
                        checkpoint.remove("publicationConfirmed")
                        checkpoint.remove("publicationPromotionAt")
                    } else if (target != null) {
                        // Do not append a succession of half-uploaded targets as the game advances.
                        // A conditional delete of this proven-bad target leaves all good sources intact.
                        try {
                            if (!client.discardCorruptPublication(remoteGame, target, ::checkCurrent)) {
                                throw SaveSyncException(SaveSyncError.SERVER)
                            }
                        } catch (failure: SaveSyncException) {
                            if (failure.code != SaveSyncError.CORRUPT_ARCHIVE) throw failure
                            SavePublicationPolicy.block(checkpoint, target.name, target.etag)
                            saveCheckpoint(checkpointKey, checkpoint)
                            throw SaveSyncException(SaveSyncError.CLOUD_REPAIR_REQUIRED, cause = failure)
                        }
                        remote = remote - target
                        checkpoint.remove("publicationIntent")
                    }
                    saveCheckpoint(checkpointKey, checkpoint)
                }
                // Continuing to save after choosing S intentionally promotes the latest N from S.
                val unfinished = checkpoint.optBoolean("needsPrune") && !checkpoint.optBoolean("publicationConfirmed")
                if (SavePublicationPolicy.needsUpload(version, checkpoint.optString("uploaded"), promoting, remote) ||
                    unfinished) {
                    if (promoting || unfinished || remote.none { it.contentHash == version.contentHash }) {
                        val archive = checkNotNull(uploadArchive)
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
                        saveCheckpoint(checkpointKey, checkpoint)
                        checkCurrent()
                        val existingTarget = remote.firstOrNull { !it.legacy && it.name == publication.id }
                        val identical = remote.filter { it.contentHash == version.contentHash }.maxWithOrNull(REMOTE_ORDER)
                        val expectedSize = try {
                            when {
                                promoting && existingTarget != null -> client.refresh(remoteGame, existingTarget, publication, archive)
                                promoting && identical != null -> client.refresh(remoteGame, identical, publication, archive)
                                else -> {
                                    val result = client.upload(remoteGame, publication, archive)
                                    if (result.uploaded) uploaded++
                                    result.size
                                }
                            }
                        } catch (failure: SaveSyncException) {
                            if (failure.code != SaveSyncError.CORRUPT_ARCHIVE) throw failure
                            val failedTarget = client.list(remoteGame).firstOrNull { !it.legacy && it.name == publication.id }
                            if (failedTarget != null) {
                                SavePublicationPolicy.block(checkpoint, failedTarget.name, failedTarget.etag)
                                saveCheckpoint(checkpointKey, checkpoint)
                                throw SaveSyncException(SaveSyncError.CLOUD_REPAIR_REQUIRED, cause = failure)
                            }
                            throw failure
                        }
                        // Reload after publishing: another device may have added a version meanwhile.
                        remote = client.list(remoteGame)
                        checkCurrent()
                        val confirmed = remote.firstOrNull { !it.legacy && it.name == publication.id &&
                            it.size == expectedSize && it.contentHash == publication.contentHash }
                        if (confirmed == null || !client.verifyVersion(remoteGame, confirmed)) {
                            // Never prune older cloud versions before the new archive is visible.
                            throw SaveSyncException(SaveSyncError.SERVER)
                        }
                        checkpoint.put("publicationConfirmed", true)
                        saveCheckpoint(checkpointKey, checkpoint)
                        if (promoting) {
                            // Only the explicitly selected old save and identical copies are superseded.
                            // A newer concurrent publication is never deleted by this promotion.
                            val old = SavePublicationPolicy.superseded(remote, publication, localState.optString("promotionFromHash"))
                            for (candidate in old) {
                                checkCurrent()
                                client.delete(remoteGame, candidate)
                            }
                            remote = remote - old.toSet()
                            local {
                                snapshots.refreshVersion(key, version, publishedAt, publication.previousCreatedAt)
                                if (localState.optBoolean("promotionAwaitingChange") &&
                                    version.contentHash != localState.optString("promotionBaseHash")) {
                                    val currentState = states.load(key)
                                    currentState.put("promotionAwaitingChange", false)
                                    states.save(key, currentState)
                                }
                            }
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
                    saveCheckpoint(checkpointKey, checkpoint)
                }
                // Retention is scoped to this changed game, never applied to unrelated games.
                if (checkpoint.optBoolean("needsPrune") && checkpoint.optBoolean("publicationConfirmed")) {
                    if (config.remoteLimit > 0) {
                        remote.sortedWith(REMOTE_ORDER.reversed()).drop(config.remoteLimit).forEach {
                            checkCurrent()
                            client.delete(remoteGame, it)
                        }
                    }
                    checkpoint.remove("needsPrune")
                    checkpoint.remove("publicationIntent")
                    checkpoint.remove("publicationConfirmed")
                    checkpoint.remove("publicationPromotionAt")
                    saveCheckpoint(checkpointKey, checkpoint)
                }
            }
            SaveSyncReport(uploaded, downloaded, if (uploaded + downloaded + busy == 0) 1 else 0, busy)
        } catch (_: LocalStateChanged) {
            SaveSyncReport(uploaded = uploaded, downloaded = downloaded, busy = busy + 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SaveSyncReport(uploaded = uploaded, downloaded = downloaded, busy = busy,
                failures = listOf(SaveSyncFailure(game.title, errorCode(failure))))
        } finally {
            uploadArchive?.let(SaveSyncTemporaryFiles::release)
        }
    }

    internal suspend fun fingerprint(game: ScanGame): String? {
        return environment.fingerprint(game)
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
        protectedHashes: Set<String> = emptySet(),
    ): SaveVersion? {
        if (!finalizeRestore) requireEnabled()
        snapshots.maintain(localKey(game), protectedHashes)
        val before = fingerprint(game) ?: return null
        val state = states.load(localKey(game))
        val continuingRestore = !finalizeRestore && !restored && state.optBoolean("promotionAwaitingChange") &&
            before != state.optString("promotionContentHash").ifBlank { state.optString("promotionBaseHash") }
        if (continuingRestore) {
            val version = captureLocked(game, restored = true, refreshExisting = true, finalizeRestore = true,
                previousCreatedAt = state.optLong("promotionFromTime").takeIf { it > 0 },
                protectedHashes = protectedHashes)
            if (version != null) {
                state.put("promotionAt", version.createdAt).put("promotionContentHash", version.contentHash)
                states.save(localKey(game), state)
            }
            return version
        }
        val job = currentCoroutineContext()
        snapshots.list(localKey(game)).firstOrNull { it.contentHash == before &&
            snapshots.isValid(localKey(game), it) { job.ensureActive() } }?.let {
            return if (refreshExisting) snapshots.refreshVersion(localKey(game), it, nextTimestamp(game), previousCreatedAt) else it
        }
        val temp = temporaryZip()
        try {
            // Reuse TYN's engine-aware exclusions and existing ZIP exporter.
            environment.export(game, temp)
            if (!finalizeRestore) requireEnabled()
            if (fingerprint(game) != before) throw SaveSyncException(SaveSyncError.GAME_RUNNING)
            val job = currentCoroutineContext()
            return snapshots.add(localKey(game), temp, nextTimestamp(game), SaveVersionSource.LOCAL, before,
                restored = restored, refreshExisting = refreshExisting, previousCreatedAt = previousCreatedAt,
                protectedHashes = protectedHashes) { job.ensureActive() }
        } finally {
            SaveSyncTemporaryFiles.release(temp)
        }
    }

    private fun nextTimestamp(game: ScanGame): Long {
        val now = System.currentTimeMillis()
        val previous = snapshots.list(localKey(game)).firstOrNull()?.createdAt ?: 0L
        return maxOf(now, minOf(previous, now + 86_400_000L) + 1)
    }

    private class LocalStateChanged : Exception()

    private fun invalidateTransfers(game: ScanGame) {
        val state = states.load(localKey(game))
        state.put("revision", UUID.randomUUID().toString())
        states.save(localKey(game), state)
    }

    private fun requireIdle(game: ScanGame) {
        if (environment.running(game)) throw SaveSyncException(SaveSyncError.GAME_RUNNING)
    }

    private fun requireEnabled() {
        if (!environment.enabled) throw SaveSyncException(SaveSyncError.DISABLED)
    }

    private fun defaultIdentity(game: ScanGame): String {
        val path = GamePathUtils.safUriToPath(game.uri)
        val folder = path?.let { File(it).name }?.takeIf { it.isNotBlank() }
            ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
        return Normalizer.normalize(folder, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    private fun remoteKey(game: ScanGame) = SaveContentHash.text(game.engine.name + "\n" + identity(game))
    private fun remoteKeyForConflict(game: ScanGame): String? = try {
        remoteKey(game)
    } catch (failure: SaveSyncException) {
        if (failure.code != SaveSyncError.SAVE_UNAVAILABLE) throw failure
        null // No custom identity and no resolvable folder means no portable identity to reserve.
    }
    private fun checkpointKey(game: ScanGame, config: WebDavConfig, remoteKey: String = remoteKey(game)) =
        SaveContentHash.text(config.serverUrl.trimEnd('/') + "\n" + config.username + "\n" + remoteKey + "\n" + game.uri)
    private fun localKey(game: ScanGame) = SaveContentHash.text(game.uri)
    private fun temporaryZip(): File = SaveSyncTemporaryFiles.create("save_sync_", ".zip", environment.cacheDirectory)

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
