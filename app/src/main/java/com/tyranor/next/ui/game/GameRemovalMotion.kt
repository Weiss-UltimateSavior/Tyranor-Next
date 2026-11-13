package com.tyranor.next.ui.game

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tyranor.next.ui.common.motion.DissolveState
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.dissolve
import com.tyranor.next.ui.common.motion.dissolvingGames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Library cards share the registered dissolve across layouts; recent records own theirs locally. */
internal fun CoroutineScope.removeLibraryGame(uri: String, remove: () -> Unit) {
    if (!MotionMode.enhanced) {
        remove()
        return
    }
    val state = DissolveState()
    dissolvingGames[uri] = state
    launch {
        try {
            state.finishRemoval(remove)
        } finally {
            dissolvingGames.remove(uri)
        }
    }
}

internal fun CoroutineScope.removeRecentGame(state: DissolveState, remove: () -> Unit) {
    if (MotionMode.enhanced) launch { state.finishRemoval(remove) } else remove()
}

private suspend fun DissolveState.finishRemoval(remove: () -> Unit) {
    try {
        play()
    } finally {
        withContext(NonCancellable) { remove() }
    }
}

@Composable
internal fun Modifier.gameRemovalDust(state: DissolveState?): Modifier = dissolve(
    state,
    listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurfaceVariant),
)
