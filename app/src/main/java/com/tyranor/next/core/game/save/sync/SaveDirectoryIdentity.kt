package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal const val SAVE_DIRECTORY_KEY_PREFIX = "save-directory:"

internal sealed interface SaveSyncLaunchAdmission {
    data object Disabled : SaveSyncLaunchAdmission
    data object Unmanaged : SaveSyncLaunchAdmission
    data object Unresolved : SaveSyncLaunchAdmission
    data class Directory(val key: String) : SaveSyncLaunchAdmission
}

/** Re-admit after waiting; every managed launch shares the save-replacement fence. */
internal suspend fun <T> withSaveSyncLaunchAdmission(
    resolve: () -> SaveSyncLaunchAdmission,
    managed: suspend (String?) -> T,
    unmanaged: suspend () -> T,
): T {
    class Completed(val value: T)
    while (true) {
        currentCoroutineContext().ensureActive()
        val admission = resolve()
        if (admission == SaveSyncLaunchAdmission.Unmanaged) return unmanaged()
        suspend fun admitted(): Completed? {
            if (resolve() != admission) return null
            return Completed(managed((admission as? SaveSyncLaunchAdmission.Directory)?.key))
        }
        val completed = if (admission is SaveSyncLaunchAdmission.Directory) {
            SaveSyncLocks.active(admission.key) { SaveSyncLocks.mutation { admitted() } }
        } else SaveSyncLocks.mutation { admitted() }
        if (completed != null) return completed.value
    }
}

/** History stays URI-scoped; writers and launch admission share the actual save directory. */
internal fun saveDirectoryIdentity(game: ScanGame, directory: (ScanGame) -> File?): String =
    directory(game)?.canonicalPath?.let { SAVE_DIRECTORY_KEY_PREFIX + it } ?: game.uri

/** Optional launch bookkeeping must neither resolve saves while disabled nor prevent an engine start. */
internal fun optionalSaveSyncLaunchAdmission(
    enabled: () -> Boolean,
    resolve: () -> String?,
): SaveSyncLaunchAdmission {
    val optedIn = try {
        enabled()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return SaveSyncLaunchAdmission.Disabled
    }
    if (!optedIn) return SaveSyncLaunchAdmission.Disabled
    val key = try {
        resolve()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    return key?.let { SaveSyncLaunchAdmission.Directory(it) } ?: SaveSyncLaunchAdmission.Unresolved
}

internal fun relatedSaveUris(
    game: ScanGame,
    library: List<ScanGame>,
    directory: (ScanGame) -> File?,
): Set<String> {
    val target = saveDirectoryIdentity(game, directory)
    return library.filter { other ->
        other.uri == game.uri || saveDirectoryIdentity(other, directory) == target
    }.mapTo(mutableSetOf(game.uri)) { it.uri }
}
