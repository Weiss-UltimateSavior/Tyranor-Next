package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.game.model.ScanGame
import java.io.File

/** History stays URI-scoped; writers and launch admission share the actual save directory. */
internal fun saveDirectoryIdentity(game: ScanGame, directory: (ScanGame) -> File?): String =
    directory(game)?.canonicalPath?.let { "save-directory:$it" } ?: game.uri

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
