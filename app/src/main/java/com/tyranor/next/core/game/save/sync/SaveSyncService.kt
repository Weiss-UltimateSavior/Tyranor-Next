package com.tyranor.next.core.game.save.sync

import android.content.Context
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
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
    private val directoryActivations = SaveDirectoryActivationStore(root)

    suspend fun listVersions(game: ScanGame): List<SaveVersion> = withContext(Dispatchers.IO) {
        if (!environment.enabled) return@withContext emptyList()
        SaveSyncLocks.versions(game.uri) {
            requireEnabled()
            maintainSnapshots(game)
            val pending = activations.load(localKey(game))
            if (pending != null && states.load(localKey(game)).optString("completedActivation") != pending.token &&
                snapshots.list(localKey(game)).none { it.contentHash == pending.selected.contentHash }) {
                snapshots.add(localKey(game), activations.archive(localKey(game)), pending.selected.createdAt,
                    pending.selected.source, pending.selected.contentHash, restored = pending.selected.restored,
                    previousCreatedAt = pending.selected.previousCreatedAt, protectedHashes = pendingProtection(game))
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
            val directoryKey = environment.activeKey(game)
            SaveSyncLocks.active(directoryKey) {
                SaveSyncLocks.mutation {
                    requireSameSaveDirectory(directoryKey, environment.activeKey(game))
                    directoryActivations.owned(game.uri).forEach { requireSameSaveDirectory(it.activeKey, directoryKey) }
                    requireIdle(game)
                    val key = localKey(game)
                    val directoryOwners = directoryActivations.owned(game.uri)
                    for (owner in directoryOwners) {
                        val committed = owner.committed ?: true
                        if (committed) environment.retireSessions(game, directoryKey)
                        directoryActivations.prepareCompletion(directoryKey, game.uri, owner.token, committed)
                    }
                    val legacyCheckpoint = try { checkpointKey(game, environment.config()) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    // An unreadable ownership index cannot establish that cleanup is complete.
                    val index = states.load(key).optJSONArray("checkpointKeys")
                    val owned = (0 until (index?.length() ?: 0)).map { index!!.getString(it) }.toSet()
                    val failures = ArrayList<Exception>()
                    suspend fun clear(action: suspend () -> Unit) {
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
                    SaveSyncLocks.session(key) {
                        clear { SaveSyncStateStore(File(root, "sessions")).remove(key) }
                    }
                    clear { SaveSyncStateStore(File(root, "baselines")).remove(key) }
                    for (pending in directoryOwners) {
                        if (failures.isEmpty()) clear {
                            directoryActivations.complete(pending.activeKey, game.uri, pending.token, pending.committed ?: true)
                        }
                    }
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
    }

    fun needsSelectionRecovery(game: ScanGame): Boolean = try {
        activations.load(localKey(game)) != null || activations.archive(localKey(game)).exists() ||
            environment.needsManualRecovery(game) || directoryActivations.owned(game.uri).isNotEmpty() ||
            promotionNeedsRecovery(game, states.load(localKey(game)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

    /** Local recovery remains available after WebDAV is disabled; it never imports an archive. */
    suspend fun recoverSelection(game: ScanGame) = withContext(Dispatchers.IO) {
        try {
            SaveSyncLocks.versions(game.uri) {
                val directoryKey = environment.activeKey(game)
                SaveSyncLocks.active(directoryKey) {
                    SaveSyncLocks.mutation {
                        requireIdle(game)
                        if (needsSelectionRecovery(game)) {
                            invalidateTransfers(game)
                            abandonActivationLocked(game, directoryKey)
                        }
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
            val directoryKey = environment.activeKey(game)
            SaveSyncLocks.active(directoryKey) {
                requireSameSaveDirectory(directoryKey, environment.activeKey(game))
                requireEnabled()
                requireIdle(game)
                directoryActivations.requireAvailable(directoryKey, game.uri)
                invalidateTransfers(game)
                val completedCleanup = finishPreparedDirectoryCleanup(game, directoryKey)
                maintainSnapshots(game)
                val key = localKey(game)
                val pending = activations.load(key)
                for (owner in directoryActivations.owned(game.uri)) {
                    requireSameSaveDirectory(owner.activeKey, directoryKey)
                    if (owner.token != pending?.token) throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
                }
                if (pending != null && pending.activeKey == null) {
                    throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
                }
                pending?.activeKey?.let { requireSameSaveDirectory(it, directoryKey) }
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
                        val earlyResult = SaveSyncLocks.mutation {
                            requireIdle(game)
                            requireEnabled()
                            requireSameSaveDirectory(directoryKey, environment.activeKey(game))
                            environment.completeImport(game, directoryKey)
                            SaveActivationRecovery.beforeSelection(
                                sameContent = pending.selected.contentHash == selected.contentHash,
                                canFinalize = pending.canFinalize(environment.fingerprint(game, directoryKey)),
                                discardPrevious = discardPrevious,
                                currentMatchesSelection = environment.fingerprint(game, directoryKey) == selected.contentHash,
                                finish = { tryFinishActivation(game, pending, directoryKey) },
                                discard = { abandonActivationLocked(game, directoryKey) },
                            )
                        }
                        if (earlyResult != null) return@active earlyResult
                        retryingPrepared = true
                    }
                    val before = environment.fingerprint(game, directoryKey)
                    val recoveryNeeded = environment.needsManualRecovery(game)
                    val previousState = states.load(key)
                    val unboundRestore = previousState.optLong("promotionAt") > 0L &&
                        (previousState.optString("promotionActiveKey") != directoryKey ||
                            previousState.optString("promotionDirectoryRevision") != directoryActivations.revision(directoryKey))
                    if (!retryingPrepared && !recoveryNeeded && !unboundRestore && before == selected.contentHash) {
                        return@active if (completedCleanup) SaveActivationResult.APPLIED else SaveActivationResult.UNCHANGED
                    }
                    // Consume the protected staging copy before a sixth history version can evict S.
                    activations.prepare(key, selected, temp, before, directoryKey) { job.ensureActive() }
                    var committed = false
                    try {
                        // Once the active save changes, leaving this page must not cancel its
                        // matching version metadata or the pending automatic update.
                        SaveSyncLocks.mutation {
                            requireIdle(game)
                            requireEnabled()
                            requireSameSaveDirectory(directoryKey, environment.activeKey(game))
                            captureLocked(game, finalizeRestore = true, directoryKey = directoryKey)
                            job.ensureActive()
                            withContext(NonCancellable) {
                                try {
                                    // Re-check after waiting for a disabled launch: it may have
                                    // started the engine, and the feature may have been disabled.
                                    requireIdle(game)
                                    requireEnabled()
                                    requireSameSaveDirectory(directoryKey, environment.activeKey(game))
                                    directoryActivations.begin(directoryKey, game.uri, activations.load(key)!!.token)
                                    environment.import(game, activations.archive(key), directoryKey) { committed = true }
                                    tryFinishActivation(game, activations.markCommitted(key), directoryKey)
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
                        }
                    } catch (failure: Exception) {
                        if (!committed) withContext(NonCancellable) {
                            SaveSyncLocks.mutation {
                                try {
                                    // Disabling/cancelling does not cancel required rollback.
                                    // A launch or a directory change does: keep the recovery material.
                                    requireIdle(game)
                                    requireSameSaveDirectory(directoryKey, environment.activeKey(game))
                                    activations.discardUncommitted(key) { archive, version ->
                                        environment.completeImport(game, directoryKey)
                                        snapshots.add(key, archive, version.createdAt, version.source, version.contentHash,
                                            restored = version.restored, previousCreatedAt = version.previousCreatedAt,
                                            protectedHashes = pendingProtection(game))
                                        prepareDirectoryRollback(game, directoryKey)
                                    }
                                    finishPreparedDirectoryCleanup(game, directoryKey)
                                } catch (cleanup: Exception) {
                                    if (cleanup !== failure) {
                                        // Preserve the original cancellation object.
                                        if (cleanup is CancellationException && failure !is CancellationException) {
                                            cleanup.addSuppressed(failure)
                                            throw cleanup
                                        }
                                        failure.addSuppressed(cleanup)
                                    }
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
    private suspend fun abandonActivationLocked(game: ScanGame, directoryKey: String) {
        val job = currentCoroutineContext()
        requireSameSaveDirectory(directoryKey, environment.activeKey(game))
        directoryActivations.requireAvailable(directoryKey, game.uri)
        directoryActivations.owned(game.uri).forEach { requireSameSaveDirectory(it.activeKey, directoryKey) }
        val key = localKey(game)
        var damagedRecord = false
        val pending = try {
            activations.load(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SaveSyncException) {
            damagedRecord = true
            null
        }
        pending?.activeKey?.let { requireSameSaveDirectory(it, directoryKey) }
        if (pending != null && directoryActivations.owned(game.uri).any { it.token != pending.token }) {
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
        environment.completeImport(game, directoryKey)
        val current = environment.fingerprint(game, directoryKey)
        val archive = activations.archive(key)
        // Parseable metadata can still lie about the ZIP's hash. Independently validate its bytes
        // and keep the only protected source until both it and the active save are durable.
        val archiveHash = try {
            if (archive.exists()) SaveContentHash.archive(archive) { job.ensureActive() } else null
        } catch (failure: SaveSyncException) {
            if (failure.code != SaveSyncError.CORRUPT_ARCHIVE) throw failure
            null
        }
        // A staging copy is expendable only after a separately verified history copy exists.
        val historicalSource = if (pending != null && archiveHash != pending.selected.contentHash) {
            snapshots.list(key).firstOrNull {
                it.contentHash == pending.selected.contentHash && snapshots.isValid(key, it) { job.ensureActive() }
            }
        } else null
        val sourceHash = archiveHash ?: historicalSource?.contentHash
            ?: current?.takeIf { it == pending?.selected?.contentHash }
        if ((pending != null || damagedRecord || archive.exists()) && sourceHash == null) {
            throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
        }
        val state = states.load(key)
        val unboundPromotion = promotionNeedsRecovery(game, state)
        val directoryOwner = directoryActivations.owned(game.uri).firstOrNull { it.activeKey == directoryKey }
        val legacySource = state.optString("promotionFromHash").takeIf { hash ->
            (unboundPromotion || directoryOwner != null || state.optBoolean("promotionAwaitingChange")) && snapshots.list(key).any {
                it.contentHash == hash && snapshots.isValid(key, it) { job.ensureActive() }
            }
        }
        val beforeSource = pending?.beforeHash?.takeIf { hash ->
            hash == current || snapshots.list(key).any { it.contentHash == hash && snapshots.isValid(key, it) { job.ensureActive() } }
        }
        val protected = setOfNotNull(sourceHash, historicalSource?.contentHash, current, beforeSource, legacySource)
        if (current != null) captureLocked(game, finalizeRestore = true, protectedHashes = protected,
            directoryKey = directoryKey, protectPending = false)
        if (archiveHash != null) {
            val trusted = pending?.selected?.takeIf { it.contentHash == archiveHash }
            snapshots.add(key, archive, trusted?.createdAt ?: maxOf(1L, archive.lastModified()),
                trusted?.source ?: SaveVersionSource.LOCAL, archiveHash,
                restored = trusted?.restored ?: false, previousCreatedAt = trusted?.previousCreatedAt,
                protectedHashes = protected) { job.ensureActive() }
        }
        for (hash in protected) {
            val retained = snapshots.list(key).firstOrNull { it.contentHash == hash }
                ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
            if (!snapshots.isValid(key, retained) { job.ensureActive() }) {
                throw SaveSyncException(SaveSyncError.CORRUPT_ARCHIVE)
            }
        }
        requireSameSaveDirectory(directoryKey, environment.activeKey(game))
        if (!environment.repairManualSelection(game, current != null)) {
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
        // Unknown staging bytes are rescued separately; they cannot invalidate a verified
        // selected S or prove that an orphaned, uncommitted choice replaced the prior chain.
        val selectedHash = pending?.selected?.contentHash?.takeIf { it in protected }
        val activationEvidence = pending != null || damagedRecord || unboundPromotion
        val priorChainSurvivesAbort = directoryOwner?.committed == false &&
            state.optString("promotionActiveKey") == directoryKey &&
            state.has("promotionDirectoryRevision") &&
            state.optString("promotionDirectoryRevision") == directoryOwner.previousRevision.orEmpty()
        val sameCommittedChain =
            state.optString("promotionActiveKey") == directoryKey &&
            state.optString("promotionDirectoryRevision") == directoryActivations.revision(directoryKey) &&
            ((pending != null && pending.activeKey != null && state.optString("completedActivation") == pending.token &&
                selectedHash != null && state.optString("promotionFromHash") == selectedHash &&
                state.optLong("promotionFromTime") == pending.selected.createdAt) ||
                (pending == null && directoryOwner?.committed == true &&
                    state.optString("completedActivation") == directoryOwner.token)) &&
            state.optLong("promotionAt") > 0L
        val committed = directoryOwner?.committed ?: (pending == null || pending.canFinalize(current))
        if (activationEvidence && !sameCommittedChain && !priorChainSurvivesAbort && (pending == null ||
            committed || state.optString("completedActivation") == pending.token)) {
            listOf("completedActivation", "activationHash", "promotionFromHash", "promotionFromTime", "promotionAt",
                "promotionBaseHash", "promotionAwaitingChange", "promotionContentHash", "promotionActiveKey",
                "promotionDirectoryRevision")
                .forEach(state::remove)
            states.save(key, state)
        }
        if (sameCommittedChain && !environment.recordRestored(game, state.getString("activationHash"), directoryKey)) {
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
        completeDirectoryActivation(game, directoryKey, committed = committed,
            retireSessions = !sameCommittedChain)
    }

    private suspend fun completeDirectoryActivation(
        game: ScanGame,
        directoryKey: String,
        committed: Boolean,
        retireSessions: Boolean = true,
    ) {
        val owners = directoryActivations.owned(game.uri)
        for (pending in owners) {
            requireSameSaveDirectory(pending.activeKey, directoryKey)
            if (committed && retireSessions) environment.retireSessions(game, directoryKey)
            directoryActivations.prepareCompletion(directoryKey, game.uri, pending.token, committed)
        }
        activations.clear(localKey(game))
        for (pending in owners) {
            directoryActivations.complete(directoryKey, game.uri, pending.token, committed)
        }
    }

    private fun prepareDirectoryRollback(game: ScanGame, directoryKey: String) {
        for (pending in directoryActivations.owned(game.uri)) {
            requireSameSaveDirectory(pending.activeKey, directoryKey)
            directoryActivations.prepareCompletion(directoryKey, game.uri, pending.token, committed = false)
        }
    }

    /** Also handles a crash after the journal disappeared but before the shared fence released. */
    private fun finishPreparedDirectoryCleanup(game: ScanGame, directoryKey: String): Boolean {
        var completed = false
        for (owner in directoryActivations.owned(game.uri)) {
            requireSameSaveDirectory(owner.activeKey, directoryKey)
            val committed = owner.committed ?: continue
            val pending = activations.load(localKey(game))
            if (pending != null && pending.token != owner.token) throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
            activations.clear(localKey(game))
            directoryActivations.complete(directoryKey, game.uri, owner.token, committed)
            completed = completed || committed
        }
        return completed
    }

    private suspend fun tryFinishActivation(
        game: ScanGame,
        pending: SaveActivationJournal.Pending,
        directoryKey: String,
    ): SaveActivationResult =
        try {
            finishActivation(game, pending, directoryKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SaveActivationResult.METADATA_PENDING
        }

    /** Called while both game locks are held; this never activates another archive. */
    private suspend fun finishActivation(
        game: ScanGame,
        pending: SaveActivationJournal.Pending,
        directoryKey: String,
    ): SaveActivationResult {
        val boundDirectory = pending.activeKey ?: throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        requireSameSaveDirectory(boundDirectory, directoryKey)
        requireSameSaveDirectory(directoryKey, environment.activeKey(game))
        val key = localKey(game)
        directoryActivations.resume(directoryKey, game.uri, pending.token)
        environment.completeImport(game, directoryKey)
        val state = states.load(key)
        if (state.optString("completedActivation") != pending.token) {
            val current = environment.fingerprint(game, directoryKey) ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
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
                previousCreatedAt = pending.selected.createdAt, protectedHashes = pendingProtection(game))
            val active = if (current == selected.contentHash) selected else {
                captureLocked(game, restored = true, refreshExisting = true, finalizeRestore = true,
                    previousCreatedAt = pending.selected.createdAt, protectedHashes = setOf(selected.contentHash),
                    directoryKey = directoryKey)
                    ?: throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
            }
            state.put("promotionFromHash", pending.selected.contentHash)
                .put("promotionFromTime", pending.selected.createdAt).put("promotionAt", active.createdAt)
                .put("promotionBaseHash", active.contentHash).put("promotionAwaitingChange", true)
                .put("promotionContentHash", active.contentHash)
                .put("promotionActiveKey", directoryKey)
                .put("promotionDirectoryRevision", directoryActivations.revision(directoryKey))
                .put("completedActivation", pending.token).put("activationHash", active.contentHash)
            states.save(key, state)
        }
        if (state.optString("completedActivation") == pending.token && !state.has("promotionActiveKey")) {
            state.put("promotionActiveKey", directoryKey)
                .put("promotionDirectoryRevision", directoryActivations.revision(directoryKey))
            states.save(key, state)
        }
        if (!environment.recordRestored(game, state.getString("activationHash"), directoryKey)) {
            return SaveActivationResult.METADATA_PENDING
        }
        completeDirectoryActivation(game, directoryKey, committed = true, retireSessions = false)
        return SaveActivationResult.APPLIED
    }

    suspend fun syncAll(direction: SaveSyncDirection, excludedUris: Set<String> = emptySet()): SaveSyncReport = withContext(Dispatchers.IO) {
        requireEnabled()
        // Validate once before walking the library. No archive transfer is needed for unchanged games.
        val config = environment.config()
        val generation = environment.generation()
        if (!config.configured) throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
        var report = SaveSyncReport()
        val keys = LinkedHashMap<String, MutableList<ScanGame>>()
        for (game in environment.games()) {
            currentCoroutineContext().ensureActive()
            requireEnabled()
            // Engines without a file-save interface have no cloud identity to reserve.
            // Supported games with temporarily unavailable storage must still participate.
            if (!GameSaveManager.supportsFileSaves(game.engine)) continue
            try {
                val remoteKey = remoteKeyForConflict(game)
                if (remoteKey != null) keys.getOrPut(remoteKey) { ArrayList() }.add(game)
                else if (environment.available(game)) throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (game.uri !in excludedUris) {
                    report += SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, errorCode(failure), game.uri)))
                }
            }
        }
        environment.openClient(config).use { client ->
            for ((_, games) in keys) for (game in games) {
                currentCoroutineContext().ensureActive()
                requireEnabled()
                if (game.uri in excludedUris) continue
                report += if (games.size > 1) {
                    SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, SaveSyncError.IDENTITY_CONFLICT, game.uri)))
                } else syncOne(game, direction, config, client, expectedGeneration = generation)
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
        expectedActiveKey: String? = null,
        expectedContentHash: String? = null,
        isCurrent: () -> Boolean,
    ): SaveSyncReport = withContext(Dispatchers.IO) {
        requireEnabled()
        val config = environment.config()
        val generation = environment.generation()
        if (!config.configured) throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
        environment.openClient(config)
            .use { syncOne(game, direction, config, it, isCurrent, expectedActiveKey, expectedContentHash, generation) }
    }

    private suspend fun syncOne(
        game: ScanGame,
        direction: SaveSyncDirection,
        config: WebDavConfig,
        client: SaveSyncTransport,
        isCurrent: () -> Boolean = { true },
        expectedActiveKey: String? = null,
        expectedContentHash: String? = null,
        expectedGeneration: Long = environment.generation(),
    ): SaveSyncReport = SaveSyncLocks.network(game.uri) {
        val key = localKey(game)
        var revision: String? = null
        var uploadArchive: File? = null
        var capturedDirectory: String? = null
        var capturedRevision: String? = null
        var uploadPrepared = false
        var deleteAttempts = 0
        var runningAtNanos = 0L
        var runningValue = false
        // Host probing resolves the library and every related save directory; cache it briefly so
        // per-request admission does not repeat that work while still noticing a game start.
        fun gameRunning(force: Boolean = false): Boolean {
            val now = System.nanoTime()
            if (!force && now - runningAtNanos in 0..RUNNING_PROBE_CACHE_NANOS) return runningValue
            runningValue = environment.running(game)
            runningAtNanos = now
            return runningValue
        }
        suspend fun <T> local(forceRunning: Boolean = false, block: suspend () -> T): T = SaveSyncLocks.versions(game.uri) {
            suspend fun checked(): T {
                requireEnabled()
                if (environment.generation() != expectedGeneration) throw LocalStateChanged()
                if (environment.configRevision() != config.revision) throw LocalStateChanged()
                if (!isCurrent()) throw LocalStateChanged()
                if (revision != null && states.load(key).optString("revision") != revision) throw LocalStateChanged()
                capturedDirectory?.let { directory ->
                    if (capturedRevision != null && directoryActivations.revision(directory) != capturedRevision) throw LocalStateChanged()
                    if (uploadPrepared) {
                        directoryActivations.requireAvailable(directory)
                        if (gameRunning(forceRunning)) throw GameBecameBusy()
                    }
                    requireSameSaveDirectory(directory, environment.activeKey(game))
                }
                return block()
            }
            // Checkpoint/snapshot writes share the selection mutex across URI aliases.
            val directory = capturedDirectory
            if (directory == null) checked() else SaveSyncLocks.active(directory) { checked() }
        }
        suspend fun checkCurrent() = local { Unit }
        suspend fun beforeDelete() {
            if (deleteAttempts >= MAX_REMOTE_DELETES_PER_ROUND) throw CleanupPending()
            // Deletion is destructive: do not trust the short-lived running cache here.
            local(forceRunning = true) { Unit }
            deleteAttempts++
        }
        suspend fun saveCheckpoint(key: String, value: org.json.JSONObject) = local { states.save(key, value) }
        var downloaded = 0
        var uploaded = 0
        var busy = 0
        var cleanupPending = 0
        try {
            client.setRequestAdmission { dispatch -> local {
                environment.admitGeneration(expectedGeneration, config.revision) {
                    if (!isCurrent()) throw LocalStateChanged()
                    dispatch()
                }
            } }
            if (!local { environment.available(game) }) return@network SaveSyncReport()
            local { capturedDirectory = expectedActiveKey ?: environment.activeKey(game) }
            val remoteGame = local {
                capturedRevision = directoryActivations.revision(checkNotNull(capturedDirectory))
                if (environment.games().none { it.uri == game.uri }) throw LocalStateChanged()
                val state = states.load(key)
                revision = state.optString("revision").ifBlank { UUID.randomUUID().toString() }
                state.put("revision", revision)
                states.save(key, state)
                maintainSnapshots(game)
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
                                    newest.contentHash, restored = newest.restored, previousCreatedAt = newest.previousCreatedAt,
                                    protectedHashes = pendingProtection(game)) {
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
                val directoryKey = checkNotNull(capturedDirectory)
                val admitted = local {
                    requireEnabled()
                    requireSameSaveDirectory(directoryKey, environment.activeKey(game))
                    directoryActivations.requireAvailable(directoryKey, game.uri)
                    // A related URI can retire this session while we wait for the shared
                    // directory lock, after local() has already checked admission.
                    if (!isCurrent()) throw LocalStateChanged()
                    if (gameRunning(force = true)) {
                        busy++
                        false
                    } else {
                        SaveSyncLocks.mutation {
                            if (gameRunning(force = true)) throw GameBecameBusy()
                            requireEnabled()
                            requireSameSaveDirectory(directoryKey, environment.activeKey(game))
                            if (!isCurrent()) throw LocalStateChanged()
                            finishPreparedDirectoryCleanup(game, directoryKey)
                            activations.load(localKey(game))?.let { pending ->
                                if (tryFinishActivation(game, pending, directoryKey) != SaveActivationResult.APPLIED) {
                                    throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
                                }
                            }
                        }
                        directoryActivations.requireAvailable(directoryKey)
                        requireEnabled()
                        requirePromotionDirectory(states.load(key), checkpoint.optLong("promotedAt"), directoryKey)
                        capturedRevision = directoryActivations.revision(directoryKey)
                        uploadPrepared = true
                        true
                    }
                }
                if (!admitted) return@network SaveSyncReport(downloaded = downloaded, busy = busy)

                // A fresh cloud verification authorizes cleanup independently of the active
                // save tree and any upload copy. Every DELETE still rechecks the transaction.
                suspend fun finishPublicationCleanup(publication: RemoteSaveVersion) {
                    val state = local { states.load(key) }
                    val publicationPromotionAt = if (checkpoint.has("publicationPromotionAt")) {
                        checkpoint.optLong("publicationPromotionAt")
                    } else if (checkpoint.optString("promotionPublication") == publication.name) {
                        checkpoint.optLong("publishingPromotionAt")
                    } else 0L
                    val completesPromotion = publicationPromotionAt > 0 && state.optLong("promotionAt") == publicationPromotionAt
                    if (completesPromotion) {
                        val publishedVersion = SaveVersion(publication.name, publication.contentHash,
                            publication.createdAt, publication.size, restored = publication.restored,
                            previousCreatedAt = publication.previousCreatedAt)
                        val superseded = SavePublicationPolicy.superseded(remote, publishedVersion, state.optString("promotionFromHash"))
                        for (old in superseded) {
                            beforeDelete()
                            client.delete(remoteGame, old)
                        }
                        remote = remote - superseded.toSet()
                    }
                    if (config.remoteLimit > 0) {
                        val excess = remote.sortedWith(REMOTE_ORDER.reversed()).drop(config.remoteLimit)
                        for (old in excess) {
                            beforeDelete()
                            client.delete(remoteGame, old)
                        }
                        remote = remote - excess.toSet()
                    }
                    // A bounded partial round must not commit either side of the promotion.
                    if (completesPromotion) {
                        local {
                            snapshots.list(key).firstOrNull { it.contentHash == publication.contentHash && snapshots.isValid(key, it) }
                                ?.let { snapshots.refreshVersion(key, it, publication.createdAt, publication.previousCreatedAt) }
                            if (state.optBoolean("promotionAwaitingChange") && publication.contentHash != state.optString("promotionBaseHash")) {
                                state.put("promotionAwaitingChange", false)
                                states.save(key, state)
                            }
                        }
                        checkpoint.put("promotedAt", publicationPromotionAt)
                    }
                    if (checkpoint.optString("promotionPublication") == publication.name) {
                        checkpoint.remove("promotionPublication")
                        checkpoint.remove("publishingPromotionAt")
                    }
                    checkpoint.put("uploaded", publication.contentHash)
                    remote.filter { it.contentHash == publication.contentHash }.maxWithOrNull(REMOTE_ORDER)?.let { head ->
                        val seen = SaveVersionNames.parse(checkpoint.optString("seen"))
                        if (seen == null || REMOTE_ORDER.compare(head, seen) > 0) checkpoint.put("seen", head.name)
                    }
                    checkpoint.remove("needsPrune")
                    checkpoint.remove("publicationIntent")
                    checkpoint.remove("publicationConfirmed")
                    checkpoint.remove("publicationPromotionAt")
                    checkpoint.remove("publicationTargetHash")
                    checkpoint.remove("publicationTargetSize")
                    checkpoint.remove("publicationTargetEtag")
                    saveCheckpoint(checkpointKey, checkpoint)
                    cleanupPending = 0
                }

                var unverifiedTarget: RemoteSaveVersion? = null
                var confirmedCleanupTarget: RemoteSaveVersion? = null
                if (checkpoint.optBoolean("needsPrune")) {
                    val intent = checkpoint.optString("publicationIntent").ifBlank { checkpoint.optString("promotionPublication") }
                    val target = remote.firstOrNull { !it.legacy && it.name == intent }
                    if (SavePublicationPolicy.isBlocked(checkpoint, target)) {
                        throw SaveSyncException(SaveSyncError.CLOUD_REPAIR_REQUIRED)
                    }
                    val confirmedHash = checkpoint.optString("publicationTargetHash")
                    val confirmedSize = checkpoint.optLong("publicationTargetSize", -1L)
                    val confirmedEtag = checkpoint.optString("publicationTargetEtag")
                    val matchesConfirmed = checkpoint.optBoolean("publicationConfirmed") && target != null &&
                        confirmedHash.isNotBlank() && isStrongEtag(confirmedEtag) &&
                        target.contentHash == confirmedHash && target.size == confirmedSize && target.etag == confirmedEtag
                    val verified = matchesConfirmed || (target != null && client.verifyVersion(remoteGame, target))
                    checkpoint.put("publicationConfirmed", verified)
                    if (verified && target != null) {
                        checkpoint.put("publicationTargetHash", target.contentHash)
                            .put("publicationTargetSize", target.size)
                            .put("publicationTargetEtag", target.etag.orEmpty())
                    }
                    saveCheckpoint(checkpointKey, checkpoint)
                    if (verified) {
                        confirmedCleanupTarget = checkNotNull(target)
                        try {
                            finishPublicationCleanup(target)
                        } catch (_: CleanupPending) {
                            cleanupPending = 1
                        }
                    } else unverifiedTarget = target
                }
                unverifiedTarget?.let { target ->
                    // Retire a proven-bad target before the empty-save bail-out; otherwise a
                    // corrupt publication would pin needsPrune and fail every later round.
                    try {
                        if (!client.discardCorruptPublication(remoteGame, target, ::beforeDelete)) {
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
                    checkpoint.remove("needsPrune")
                    checkpoint.remove("publicationConfirmed")
                    checkpoint.remove("publicationPromotionAt")
                    checkpoint.remove("publicationTargetHash")
                    checkpoint.remove("publicationTargetSize")
                    checkpoint.remove("publicationTargetEtag")
                    saveCheckpoint(checkpointKey, checkpoint)
                }
                val version = local {
                    SaveSyncLocks.mutation {
                        if (gameRunning(force = true)) throw GameBecameBusy()
                        requireEnabled()
                        requireSameSaveDirectory(directoryKey, environment.activeKey(game))
                        if (!isCurrent()) throw LocalStateChanged()
                        captureLocked(game, expectedContentHash = expectedContentHash, directoryKey = directoryKey)
                    }
                }
                if (cleanupPending > 0 && (version == null || version.contentHash == confirmedCleanupTarget?.contentHash)) {
                    return@network SaveSyncReport(downloaded = downloaded, cleanupPending = 1,
                        cleanupPendingGames = setOf(game.uri))
                }
                if (version == null) {
                    if (checkpoint.optBoolean("needsPrune")) {
                        // A fresh listing already showed the intended publication is absent and
                        // there is no local content to authorize a replacement; retire the stale
                        // marker instead of failing every later sync.
                        checkpoint.remove("needsPrune")
                        checkpoint.remove("publicationIntent")
                        checkpoint.remove("publicationConfirmed")
                        checkpoint.remove("publicationPromotionAt")
                        checkpoint.remove("publicationTargetHash")
                        checkpoint.remove("publicationTargetSize")
                        checkpoint.remove("publicationTargetEtag")
                        saveCheckpoint(checkpointKey, checkpoint)
                    }
                    return@network SaveSyncReport(downloaded = downloaded, unchanged = if (downloaded == 0) 1 else 0)
                }
                val localState = local { states.load(key) }
                val promotionAt = localState.optLong("promotionAt")
                val promoting = promotionAt > checkpoint.optLong("promotedAt")
                // Continuing to save after choosing S intentionally promotes the latest N from S.
                val unfinished = checkpoint.optBoolean("needsPrune") && !checkpoint.optBoolean("publicationConfirmed")
                if (SavePublicationPolicy.needsUpload(version, checkpoint.optString("uploaded"), promoting, remote) ||
                    unfinished) {
                    if (promoting || unfinished || remote.none { it.contentHash == version.contentHash }) {
                        val archive = local {
                            SaveSyncTemporaryFiles.copy(snapshots.file(key, version.id), "save_sync_", ".zip", environment.cacheDirectory)
                                .also { uploadArchive = it }
                        }
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
                        checkpoint.put("publicationTargetHash", confirmed.contentHash)
                            .put("publicationTargetSize", confirmed.size)
                            .put("publicationTargetEtag", confirmed.etag.orEmpty())
                        saveCheckpoint(checkpointKey, checkpoint)
                        if (promoting && uploaded == 0) uploaded++ // A DAV MOVE is one completed logical upload.
                        finishPublicationCleanup(confirmed)
                    }
                    checkpoint.put("uploaded", version.contentHash)
                    // Our own upload must not be downloaded again on the next app opening.
                    remote.filter { it.contentHash == version.contentHash }.maxWithOrNull(REMOTE_ORDER)?.let {
                        val seen = SaveVersionNames.parse(checkpoint.optString("seen"))
                        if (seen == null || REMOTE_ORDER.compare(it, seen) > 0) checkpoint.put("seen", it.name)
                    }
                    saveCheckpoint(checkpointKey, checkpoint)
                }
            }
            SaveSyncReport(uploaded, downloaded, if (uploaded + downloaded + busy + cleanupPending == 0) 1 else 0, busy,
                cleanupPending = cleanupPending,
                cleanupPendingGames = if (cleanupPending > 0) setOf(game.uri) else emptySet())
        } catch (_: CleanupPending) {
            SaveSyncReport(uploaded = uploaded, downloaded = downloaded, busy = busy, cleanupPending = 1,
                cleanupPendingGames = setOf(game.uri))
        } catch (_: GameBecameBusy) {
            SaveSyncReport(uploaded = uploaded, downloaded = downloaded, busy = busy + 1, cleanupPending = cleanupPending,
                cleanupPendingGames = if (cleanupPending > 0) setOf(game.uri) else emptySet())
        } catch (_: LocalStateChanged) {
            SaveSyncReport(uploaded = uploaded, downloaded = downloaded, busy = busy,
                failures = listOf(SaveSyncFailure(game.title, SaveSyncError.LOCAL_STATE_CHANGED, game.uri)), cleanupPending = cleanupPending,
                cleanupPendingGames = if (cleanupPending > 0) setOf(game.uri) else emptySet())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SaveSyncReport(uploaded = uploaded, downloaded = downloaded, busy = busy,
                failures = listOf(SaveSyncFailure(game.title, errorCode(failure), game.uri)), cleanupPending = cleanupPending,
                cleanupPendingGames = if (cleanupPending > 0) setOf(game.uri) else emptySet())
        } finally {
            uploadArchive?.let(SaveSyncTemporaryFiles::release)
        }
    }

    internal suspend fun fingerprint(game: ScanGame): String? {
        return environment.fingerprint(game)
    }

    internal fun hasPendingPromotion(game: ScanGame, config: WebDavConfig): Boolean {
        val directoryKey = environment.activeKey(game)
        directoryActivations.requireAvailable(directoryKey)
        val state = states.load(localKey(game))
        val checkpoint = states.load(checkpointKey(game, config))
        val promotedAt = checkpoint.optLong("promotedAt")
        requirePromotionDirectory(state, promotedAt, directoryKey)
        return activations.load(localKey(game)) != null || state.optLong("promotionAt") > promotedAt ||
            checkpoint.optBoolean("needsPrune")
    }

    private fun requirePromotionDirectory(state: org.json.JSONObject, promotedAt: Long, directoryKey: String) {
        if (state.optBoolean("promotionAwaitingChange") || state.optLong("promotionAt") > promotedAt) {
            requireSameSaveDirectory(state.optString("promotionActiveKey"), directoryKey)
            if (state.optString("promotionDirectoryRevision") != directoryActivations.revision(directoryKey)) {
                throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
            }
        }
    }

    private fun promotionNeedsRecovery(game: ScanGame, state: org.json.JSONObject): Boolean = state.optLong("promotionAt") > 0L &&
        (state.optString("promotionActiveKey").isBlank() || state.optString("promotionDirectoryRevision").isBlank() ||
            state.optString("promotionActiveKey") != environment.activeKey(game) ||
            state.optString("promotionDirectoryRevision") != directoryActivations.revision(environment.activeKey(game)))

    private suspend fun captureLocked(
        game: ScanGame,
        restored: Boolean = false,
        refreshExisting: Boolean = false,
        finalizeRestore: Boolean = false,
        previousCreatedAt: Long? = null,
        protectedHashes: Set<String> = emptySet(),
        expectedContentHash: String? = null,
        directoryKey: String = environment.activeKey(game),
        protectPending: Boolean = true,
    ): SaveVersion? {
        if (!finalizeRestore) requireEnabled()
        requireSameSaveDirectory(directoryKey, environment.activeKey(game))
        val protected = protectedHashes + if (protectPending) pendingProtection(game) else emptySet()
        val job = currentCoroutineContext()
        snapshots.maintain(localKey(game), protected) { job.ensureActive() }
        val before = environment.fingerprint(game, directoryKey) ?: return null
        if (expectedContentHash != null && before != expectedContentHash) throw LocalStateChanged()
        val state = states.load(localKey(game))
        if (!finalizeRestore && state.optBoolean("promotionAwaitingChange")) {
            requirePromotionDirectory(state, 0L, directoryKey)
        }
        val continuingRestore = !finalizeRestore && !restored && state.optBoolean("promotionAwaitingChange") &&
            before != state.optString("promotionContentHash").ifBlank { state.optString("promotionBaseHash") }
        if (continuingRestore) {
            val version = captureLocked(game, restored = true, refreshExisting = true, finalizeRestore = true,
                previousCreatedAt = state.optLong("promotionFromTime").takeIf { it > 0 },
                protectedHashes = protectedHashes, expectedContentHash = expectedContentHash, directoryKey = directoryKey)
            if (version != null) {
                state.put("promotionAt", version.createdAt).put("promotionContentHash", version.contentHash)
                states.save(localKey(game), state)
            }
            return version
        }
        snapshots.list(localKey(game)).firstOrNull { it.contentHash == before &&
            snapshots.isValid(localKey(game), it) { job.ensureActive() } }?.let {
            return if (refreshExisting) snapshots.refreshVersion(localKey(game), it, nextTimestamp(game), previousCreatedAt) else it
        }
        val temp = temporaryZip()
        try {
            // Reuse TYN's engine-aware exclusions and existing ZIP exporter.
            environment.export(game, temp, directoryKey)
            if (!finalizeRestore) requireEnabled()
            if (environment.fingerprint(game, directoryKey) != before) throw SaveSyncException(SaveSyncError.GAME_RUNNING)
            val job = currentCoroutineContext()
            return snapshots.add(localKey(game), temp, nextTimestamp(game), SaveVersionSource.LOCAL, before,
                restored = restored, refreshExisting = refreshExisting, previousCreatedAt = previousCreatedAt,
                protectedHashes = protected) { job.ensureActive() }
        } finally {
            SaveSyncTemporaryFiles.release(temp)
        }
    }

    private suspend fun maintainSnapshots(game: ScanGame, protectedHashes: Set<String> = emptySet()) {
        val job = currentCoroutineContext()
        snapshots.maintain(localKey(game), protectedHashes + pendingProtection(game)) { job.ensureActive() }
    }

    /** Every eviction entry point protects the journal's source before touching the quota. */
    private fun pendingProtection(game: ScanGame): Set<String> {
        val key = localKey(game)
        val pending = try { activations.load(key) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SaveSyncException) {
            // An unreadable journal cannot prove which historical source is expendable.
            throw SaveSyncException(SaveSyncError.SELECTION_INCOMPLETE)
        }
        val state = states.load(key)
        val promotionSource = state.optString("promotionFromHash").takeIf {
            it.isNotBlank() && (state.optBoolean("promotionAwaitingChange") || promotionNeedsRecovery(game, state))
        }
        return setOfNotNull(pending?.selected?.contentHash, pending?.beforeHash, promotionSource)
    }

    private fun nextTimestamp(game: ScanGame): Long {
        val now = System.currentTimeMillis()
        val previous = snapshots.list(localKey(game)).firstOrNull()?.createdAt ?: 0L
        return maxOf(now, minOf(previous, now + 86_400_000L) + 1)
    }

    private class LocalStateChanged : Exception()
    private class CleanupPending : Exception()
    private class GameBecameBusy : Exception()

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
        internal const val MAX_REMOTE_DELETES_PER_ROUND = 20
        private const val RUNNING_PROBE_CACHE_NANOS = 1_000_000_000L
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
                else -> if (failure.indicatesStorageFull()) SaveSyncError.STORAGE_FULL else SaveSyncError.LOCAL_IO
            }
            else -> if (failure.indicatesStorageFull()) SaveSyncError.STORAGE_FULL else SaveSyncError.LOCAL_IO
        }
    }
}
