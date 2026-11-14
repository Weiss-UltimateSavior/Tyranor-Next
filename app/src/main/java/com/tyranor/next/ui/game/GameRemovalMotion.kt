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
import kotlinx.coroutines.delay
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
        state.releaseAfterGrace()
    }
}

internal fun CoroutineScope.removeRecentGame(state: DissolveState, remove: () -> Unit) {
    if (MotionMode.enhanced) {
        launch {
            state.finishRemoval(remove)
            state.releaseAfterGrace()
        }
    } else {
        remove()
    }
}

/**
 * 删除确认并执行之后，留一段「可能被回滚」的宽限：删除失败时数据层会把游戏恢复回来，若同一张卡片还在组合里
 * （退场淡出没走完），就要把它从消散状态放回来；删除成功则卡片早已离开组合，这一步没有可见效果。
 * 宽限需长于列表项的退场时长，免得卡片在淡出途中被放回来而闪一下。
 */
private suspend fun DissolveState.releaseAfterGrace() {
    delay(RollbackGraceMillis)
    release()
}

private const val RollbackGraceMillis = 800L

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
