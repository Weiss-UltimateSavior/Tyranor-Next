package com.tyranor.next.ui.game

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.launch.LaunchResult
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps the SAF confirmation and copy alive across recreation of the launch UI. */
internal class SystemNnnLaunchViewModel : ViewModel() {
    data class Completion(val uri: String, val result: LaunchResult?)

    val copyUi = SystemNnnCopyUi()
    var activeUri by mutableStateOf<String?>(null)
        private set
    var completion by mutableStateOf<Completion?>(null)
        private set
    private var job: Job? = null

    fun launch(context: Context, game: ScanGame) {
        if (activeUri != null) return
        val app = context.applicationContext
        activeUri = game.uri
        completion = null
        job = viewModelScope.launch {
            var result: LaunchResult? = null
            try {
                result = EngineLauncher.launch(app, game, systemNnnCopy = copyUi.interaction)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("SystemNnnLaunch", "Could not launch SystemNNN", failure)
                result = LaunchResult.Failure.StartFailed(null)
            } finally {
                activeUri = null
                completion = Completion(game.uri, result)
            }
        }
    }

    fun dismissCompletion(value: Completion) {
        if (completion === value) completion = null
    }

    fun cancelCopy() { job?.cancel() }
}
