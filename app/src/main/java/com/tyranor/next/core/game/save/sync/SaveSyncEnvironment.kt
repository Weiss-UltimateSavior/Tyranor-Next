package com.tyranor.next.core.game.save.sync

import android.content.Context
import android.net.Uri
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.game.save.RpgSaveSyncState
import com.tyranor.next.core.game.storage.GameLibraryFacade
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Platform boundary for the orchestration tests; fakes still use the real journal/history. */
internal interface SaveSyncEnvironment {
    val enabled: Boolean
    val cacheDirectory: File
    fun config(): WebDavConfig
    fun generation(): Long = 0L
    fun configRevision(): Long = config().revision
    fun admitGeneration(generation: Long, configRevision: Long, dispatch: () -> Unit) {
        if (!enabled || generation() != generation) throw SaveSyncException(SaveSyncError.DISABLED)
        if (configRevision() != configRevision) throw SaveSyncException(SaveSyncError.LOCAL_STATE_CHANGED)
        dispatch()
    }
    fun games(): List<ScanGame>
    fun available(game: ScanGame): Boolean
    fun running(game: ScanGame): Boolean
    fun activeKey(game: ScanGame): String = game.uri
    suspend fun fingerprint(game: ScanGame): String?
    suspend fun fingerprint(game: ScanGame, activeKey: String): String? {
        requireSameSaveDirectory(activeKey, activeKey(game))
        return fingerprint(game).also { requireSameSaveDirectory(activeKey, activeKey(game)) }
    }
    suspend fun export(game: ScanGame, archive: File)
    suspend fun export(game: ScanGame, archive: File, activeKey: String) {
        requireSameSaveDirectory(activeKey, activeKey(game))
        export(game, archive)
        requireSameSaveDirectory(activeKey, activeKey(game))
    }
    suspend fun import(game: ScanGame, archive: File, activeKey: String, committed: () -> Unit)
    fun completeImport(game: ScanGame)
    fun completeImport(game: ScanGame, activeKey: String) {
        requireSameSaveDirectory(activeKey, activeKey(game))
        completeImport(game)
        requireSameSaveDirectory(activeKey, activeKey(game))
    }
    fun needsManualRecovery(game: ScanGame): Boolean
    fun repairManualSelection(game: ScanGame, hasCurrent: Boolean): Boolean
    suspend fun recordRestored(game: ScanGame, hash: String, activeKey: String): Boolean
    suspend fun retireSessions(game: ScanGame, activeKey: String) = Unit
    /** Runs inside the same directory fence as private-state removal and launch admission. */
    suspend fun cleanupAppData(game: ScanGame, activeKey: String) = Unit
    fun openClient(config: WebDavConfig): SaveSyncTransport
}

internal interface SaveSyncTransport : AutoCloseable {
    /** A transaction supplies its live admission check, including nested DAV requests. */
    fun setRequestAdmission(admit: suspend (() -> Unit) -> Unit) = Unit
    suspend fun list(game: RemoteSaveGame): List<RemoteSaveVersion>
    suspend fun download(game: RemoteSaveGame, remote: RemoteSaveVersion, destination: File)
    suspend fun upload(game: RemoteSaveGame, version: SaveVersion, source: File): WebDavClient.UploadResult
    suspend fun refresh(game: RemoteSaveGame, previous: RemoteSaveVersion, publication: SaveVersion, source: File): Long
    suspend fun verifyVersion(game: RemoteSaveGame, remote: RemoteSaveVersion): Boolean
    suspend fun discardCorruptPublication(
        game: RemoteSaveGame,
        remote: RemoteSaveVersion,
        beforeDelete: suspend () -> Unit = {},
    ): Boolean
    suspend fun delete(game: RemoteSaveGame, remote: RemoteSaveVersion)
}

internal class AndroidSaveSyncEnvironment(private val context: Context) : SaveSyncEnvironment {
    private val manager = GameSaveManager(context)
    override val enabled get() = WebDavSettingsStore.isEnabled(context)
    override val cacheDirectory: File get() = context.cacheDir
    override fun config() = WebDavSettingsStore.load(context)
    override fun generation() = WebDavSettingsStore.sessionGeneration(context)
    override fun configRevision() = WebDavSettingsStore.configurationRevision(context)
    override fun admitGeneration(generation: Long, configRevision: Long, dispatch: () -> Unit) {
        if (WebDavSettingsStore.whileEnabled(context, generation, configRevision) { dispatch(); true } != true) {
            throw SaveSyncException(if (enabled) SaveSyncError.LOCAL_STATE_CHANGED else SaveSyncError.DISABLED)
        }
    }
    override fun games() = GameLibraryFacade.loadGames(context)
    override fun available(game: ScanGame) = manager.resolveSaveLocation(game).available
    override fun running(game: ScanGame) = SaveSyncRuntime.isGameRunning(context, game)
    override fun activeKey(game: ScanGame) = SaveSyncRuntime.activeKey(context, game)
    override suspend fun fingerprint(game: ScanGame): String? = fingerprint(game, activeKey(game))
    override suspend fun fingerprint(game: ScanGame, activeKey: String): String? {
        val location = manager.resolveSaveLocation(game)
        if (!location.available || location.directory == null) throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
        val job = currentCoroutineContext()
        val directory = manager.syncSaveDirectory(game)
        requireSameSaveDirectory(activeKey, saveDirectoryIdentity(game) { directory })
        return SaveContentHash.files(directory, manager.listSaveFiles(game, strict = true, expectedDirectory = directory)) {
            job.ensureActive()
        }.also { requireSameSaveDirectory(activeKey, activeKey(game)) }
    }
    override suspend fun export(game: ScanGame, archive: File) {
        export(game, archive, activeKey(game))
    }
    override suspend fun export(game: ScanGame, archive: File, activeKey: String) {
        val directory = manager.syncSaveDirectory(game)
        requireSameSaveDirectory(activeKey, saveDirectoryIdentity(game) { directory })
        manager.exportToZip(game, Uri.fromFile(archive), strict = true, expectedDirectory = directory)
        requireSameSaveDirectory(activeKey, activeKey(game))
    }
    override suspend fun import(game: ScanGame, archive: File, activeKey: String, committed: () -> Unit) {
        val directory = manager.syncSaveDirectory(game)
        requireSameSaveDirectory(activeKey, saveDirectoryIdentity(game) { directory })
        manager.importFromZip(game, Uri.fromFile(archive), manualSelection = true,
            expectedDirectory = directory, onDirectoryCommitted = committed)
    }
    override fun completeImport(game: ScanGame) = manager.completePendingVersionImport(game)
    override fun completeImport(game: ScanGame, activeKey: String) {
        val directory = manager.syncSaveDirectory(game)
        requireSameSaveDirectory(activeKey, saveDirectoryIdentity(game) { directory })
        manager.completePendingVersionImport(game, expectedDirectory = directory)
        requireSameSaveDirectory(activeKey, activeKey(game))
    }
    override fun needsManualRecovery(game: ScanGame) = RpgSaveFormat.isRpgWebEngine(game.engine) &&
        RpgSaveSyncState.forContext(context).needsManualSelectionRecovery(game.uri)
    override fun repairManualSelection(game: ScanGame, hasCurrent: Boolean): Boolean {
        if (!RpgSaveFormat.isRpgWebEngine(game.engine)) return true
        val state = RpgSaveSyncState.forContext(context)
        return if (hasCurrent) state.markManualSelection(game.uri) else state.clearManualSelection(game.uri)
    }
    override suspend fun recordRestored(game: ScanGame, hash: String, activeKey: String) =
        SaveSyncRuntime.recordRestoredVersion(context, game, hash, activeKey)
    override suspend fun retireSessions(game: ScanGame, activeKey: String) =
        SaveSyncRuntime.retireDirectorySessions(context, activeKey)
    override suspend fun cleanupAppData(game: ScanGame, activeKey: String) {
        val directory = manager.resolveSaveLocation(game).directory
        requireSameSaveDirectory(activeKey, saveDirectoryIdentity(game) { directory })
        manager.cleanupAppData(game, directory)
        requireSameSaveDirectory(activeKey, activeKey(game))
    }
    override fun openClient(config: WebDavConfig): SaveSyncTransport {
        val client = WebDavClient(config, cacheDirectory) { enabled }
        return WebDavSaveSyncTransport(client)
    }
}

/** The same request-guard forwarding is used on Android and in service/HTTP regressions. */
internal class WebDavSaveSyncTransport(private val client: WebDavClient) : SaveSyncTransport {
    override fun setRequestAdmission(admit: suspend (() -> Unit) -> Unit) = client.setRequestAdmission(admit)
    override suspend fun list(game: RemoteSaveGame) = client.list(game)
    override suspend fun download(game: RemoteSaveGame, remote: RemoteSaveVersion, destination: File) = client.download(game, remote, destination)
    override suspend fun upload(game: RemoteSaveGame, version: SaveVersion, source: File) = client.upload(game, version, source)
    override suspend fun refresh(game: RemoteSaveGame, previous: RemoteSaveVersion, publication: SaveVersion, source: File) = client.refresh(game, previous, publication, source)
    override suspend fun verifyVersion(game: RemoteSaveGame, remote: RemoteSaveVersion) = client.verifyVersion(game, remote)
    override suspend fun discardCorruptPublication(game: RemoteSaveGame, remote: RemoteSaveVersion,
        beforeDelete: suspend () -> Unit) = client.discardCorruptPublication(game, remote, beforeDelete)
    override suspend fun delete(game: RemoteSaveGame, remote: RemoteSaveVersion) = client.delete(game, remote)
    override fun close() = client.close()
}
