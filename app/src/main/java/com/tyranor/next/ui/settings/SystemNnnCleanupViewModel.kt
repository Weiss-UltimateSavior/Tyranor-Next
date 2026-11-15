package com.tyranor.next.ui.settings

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun removeRoot(root: String) = start(finishAfterClose = true) {
        GameLibraryFacade.removeRootAndGames(appContext, Uri.parse(root))
        manager.drainSystemNnnCleanupQueue()
    }

    fun retry() = start {
        if (hasPendingWork()) manager.drainSystemNnnCleanupQueue() else null
    }

    private fun start(finishAfterClose: Boolean = false, operation: suspend () -> SystemNnnResourceCleanup?) {
        val previous = mutableState.value
        if (previous.running) return
        mutableState.value = previous.copy(running = true)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val worker = if (finishAfterClose) Dispatchers.IO + NonCancellable else Dispatchers.IO
                mutableState.value = withContext(worker) {
                    val result = operation()
                    State(result = result, canRetry = hasPendingWork())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("SystemNnnCleanup", "Could not finish SystemNNN resource cleanup", failure)
                val canRetry = withContext(Dispatchers.IO) { runCatching { hasPendingWork() }.getOrDefault(true) }
                mutableState.value = State(failed = true, canRetry = canRetry)
            } finally {
                mutableState.value = mutableState.value.copy(running = false)
            }
        }
    }

    private fun hasPendingWork() = SystemNnnCleanupQueue.open(appContext).pending().isNotEmpty()
}
