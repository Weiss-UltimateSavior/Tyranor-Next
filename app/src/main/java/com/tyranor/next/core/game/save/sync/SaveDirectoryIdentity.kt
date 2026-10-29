package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CancellationException
import java.io.File

internal const val SAVE_DIRECTORY_KEY_PREFIX = "save-directory:"

/** History stays URI-scoped; writers and launch admission share the actual save directory. */
internal fun saveDirectoryIdentity(game: ScanGame, directory: (ScanGame) -> File?): String =
    directory(game)?.canonicalPath?.let { SAVE_DIRECTORY_KEY_PREFIX + it } ?: game.uri

/** A launch key that is only the game URI cannot identify the shared directory lock. */
internal fun isSaveDirectoryLaunchKey(key: String): Boolean = key.startsWith(SAVE_DIRECTORY_KEY_PREFIX)

/** Optional launch bookkeeping must neither resolve saves while disabled nor prevent an engine start. */
internal fun optionalSaveSyncLaunchKey(uri: String, enabled: () -> Boolean, resolve: () -> String): String? {
    val optedIn = try {
        enabled()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!optedIn) return null
    return try {
        resolve()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        uri
    }
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
