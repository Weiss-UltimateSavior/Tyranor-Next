package com.tyranor.next.ui.settings

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.GameSaveManager.OrphanResourceCleanup
import com.tyranor.next.core.game.storage.GameLibraryFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retains cleanup work and its retry state when the settings screen is recreated. */
internal class SystemNnnCleanupViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val running: Boolean = false,
        val result: OrphanResourceCleanup? = null,
        val failed: Boolean = false,
        val canRetry: Boolean = false,
    )

    private val appContext = application.applicationContext
    private val manager = GameSaveManager(appContext)
    private var pendingGames = emptyList<ScanGame>()
    private var retryInventory = false
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun removeRoot(root: String) = start {
        val removed = GameLibraryFacade.removeRootAndGames(appContext, Uri.parse(root))
        val targets = removed.filter { it.engine == EngineType.SYSTEMNNN }
        if (targets.isEmpty()) null else {
            pendingGames = (pendingGames + targets).distinctBy(ScanGame::uri)
            clearPendingGames()
        }
    }

    fun clearUnused() = start { sweep() }

    fun retry() = start {
        if (retryInventory) sweep()
        else clearPendingGames().copy(unverifiable = mutableState.value.result?.unverifiable ?: 0)
    }

    private fun start(operation: suspend () -> OrphanResourceCleanup?) {
        val previous = mutableState.value
        if (previous.running) return
        mutableState.value = previous.copy(running = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                mutableState.value = State(result = result ?: previous.result, canRetry = hasPendingWork())
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

    private fun hasPendingWork() = retryInventory || pendingGames.isNotEmpty()

    private suspend fun clearPendingGames(): OrphanResourceCleanup =
        manager.clearSystemNnnResourceTargets(pendingGames).also { pendingGames = it.failedGames }

    private suspend fun sweep(): OrphanResourceCleanup {
        retryInventory = true
        return manager.clearUnreferencedSystemNnnResources(pendingGames).also {
            pendingGames = it.failedGames
            retryInventory = false
        }
    }
}
