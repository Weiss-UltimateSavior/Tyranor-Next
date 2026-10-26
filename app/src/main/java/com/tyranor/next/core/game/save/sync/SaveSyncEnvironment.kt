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
    fun games(): List<ScanGame>
    fun available(game: ScanGame): Boolean
    fun running(game: ScanGame): Boolean
    suspend fun fingerprint(game: ScanGame): String?
    suspend fun export(game: ScanGame, archive: File)
    suspend fun import(game: ScanGame, archive: File, committed: () -> Unit)
    fun completeImport(game: ScanGame)
    fun needsManualRecovery(game: ScanGame): Boolean
    fun repairManualSelection(game: ScanGame, hasCurrent: Boolean): Boolean
    fun recordRestored(game: ScanGame, hash: String): Boolean
    fun openClient(config: WebDavConfig): SaveSyncTransport
}

internal interface SaveSyncTransport : AutoCloseable {
    suspend fun list(game: RemoteSaveGame): List<RemoteSaveVersion>
    suspend fun download(game: RemoteSaveGame, remote: RemoteSaveVersion, destination: File)
    suspend fun upload(game: RemoteSaveGame, version: SaveVersion, source: File): WebDavClient.UploadResult
    suspend fun refresh(game: RemoteSaveGame, previous: RemoteSaveVersion, publication: SaveVersion, source: File): Long
    suspend fun verifyVersion(game: RemoteSaveGame, remote: RemoteSaveVersion): Boolean
    suspend fun discardCorruptPublication(game: RemoteSaveGame, remote: RemoteSaveVersion): Boolean
    suspend fun delete(game: RemoteSaveGame, remote: RemoteSaveVersion)
}

internal class AndroidSaveSyncEnvironment(private val context: Context) : SaveSyncEnvironment {
    private val manager = GameSaveManager(context)
    override val enabled get() = WebDavSettingsStore.isEnabled(context)
    override val cacheDirectory: File get() = context.cacheDir
    override fun config() = WebDavSettingsStore.load(context)
    override fun games() = GameLibraryFacade.loadGames(context)
    override fun available(game: ScanGame) = manager.resolveSaveLocation(game).available
    override fun running(game: ScanGame) = SaveSyncRuntime.isGameRunning(context, game)
    override suspend fun fingerprint(game: ScanGame): String? {
        val location = manager.resolveSaveLocation(game)
        if (!location.available || location.directory == null) throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
        val job = currentCoroutineContext()
        return SaveContentHash.files(manager.syncSaveDirectory(game), manager.listSaveFiles(game, strict = true)) { job.ensureActive() }
    }
    override suspend fun export(game: ScanGame, archive: File) {
        manager.exportToZip(game, Uri.fromFile(archive), strict = true)
    }
    override suspend fun import(game: ScanGame, archive: File, committed: () -> Unit) {
        manager.importFromZip(game, Uri.fromFile(archive), manualSelection = true, onDirectoryCommitted = committed)
    }
    override fun completeImport(game: ScanGame) = manager.completePendingVersionImport(game)
    override fun needsManualRecovery(game: ScanGame) = RpgSaveFormat.isRpgWebEngine(game.engine) &&
        RpgSaveSyncState.forContext(context).needsManualSelectionRecovery(game.uri)
    override fun repairManualSelection(game: ScanGame, hasCurrent: Boolean): Boolean {
        if (!RpgSaveFormat.isRpgWebEngine(game.engine)) return true
        val state = RpgSaveSyncState.forContext(context)
        return if (hasCurrent) state.markManualSelection(game.uri) else state.clearManualSelection(game.uri)
    }
    override fun recordRestored(game: ScanGame, hash: String) = SaveSyncRuntime.recordRestoredVersion(context, game, hash)
    override fun openClient(config: WebDavConfig): SaveSyncTransport {
        val client = WebDavClient(config, cacheDirectory) { enabled }
        return object : SaveSyncTransport {
            override suspend fun list(game: RemoteSaveGame) = client.list(game)
            override suspend fun download(game: RemoteSaveGame, remote: RemoteSaveVersion, destination: File) = client.download(game, remote, destination)
            override suspend fun upload(game: RemoteSaveGame, version: SaveVersion, source: File) = client.upload(game, version, source)
            override suspend fun refresh(game: RemoteSaveGame, previous: RemoteSaveVersion, publication: SaveVersion, source: File) = client.refresh(game, previous, publication, source)
            override suspend fun verifyVersion(game: RemoteSaveGame, remote: RemoteSaveVersion) = client.verifyVersion(game, remote)
            override suspend fun discardCorruptPublication(game: RemoteSaveGame, remote: RemoteSaveVersion) = client.discardCorruptPublication(game, remote)
            override suspend fun delete(game: RemoteSaveGame, remote: RemoteSaveVersion) = client.delete(game, remote)
            override fun close() = client.close()
        }
    }
}
