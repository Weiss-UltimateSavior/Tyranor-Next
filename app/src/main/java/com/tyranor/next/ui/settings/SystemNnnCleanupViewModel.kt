package com.tyranor.next.ui.settings

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.GameSaveManager.SystemNnnResourceCleanup
import com.tyranor.next.core.game.save.SystemNnnCleanupQueue
import com.tyranor.next.core.game.storage.GameLibraryFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retains cleanup work and its retry state when the settings screen is recreated. */
internal class SystemNnnCleanupViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val running: Boolean = false,
        val result: SystemNnnResourceCleanup? = null,
        val failed: Boolean = false,
        val canRetry: Boolean = false,
    )

    private val appContext = application.applicationContext
    private val manager = GameSaveManager(appContext)
    private var pendingGames = emptyList<ScanGame>()
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun removeRoot(root: String) = start(finishAfterClose = true) {
        val removed = GameLibraryFacade.removeRootAndGames(appContext, Uri.parse(root))
        if (removed.isEmpty()) null else {
            pendingGames = (pendingGames + removed).distinctBy(ScanGame::uri)
            clearPendingGames()
        }
    }

    fun retry() = start {
        pendingGames = emptyList()
        manager.drainSystemNnnCleanupQueue()
    }

    private fun start(finishAfterClose: Boolean = false, operation: suspend () -> SystemNnnResourceCleanup?) {
        val previous = mutableState.value
        if (previous.running) return
        mutableState.value = previous.copy(running = true)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val worker = if (finishAfterClose) Dispatchers.IO + NonCancellable else Dispatchers.IO
                val result = withContext(worker) { operation() }
                mutableState.value = State(result = result, canRetry = hasPendingWork())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("SystemNnnCleanup", "Could not finish SystemNNN resource cleanup", failure)
                mutableState.value = State(failed = true, canRetry = hasPendingWork())
            } finally {
                mutableState.value = mutableState.value.copy(running = false)
            }
        }
    }

    private fun hasPendingWork() =
        pendingGames.isNotEmpty() || SystemNnnCleanupQueue.pending(appContext).isNotEmpty()

    private suspend fun clearPendingGames(): SystemNnnResourceCleanup =
        manager.clearSystemNnnResourceTargets(pendingGames).also { pendingGames = it.failedGames }
}
