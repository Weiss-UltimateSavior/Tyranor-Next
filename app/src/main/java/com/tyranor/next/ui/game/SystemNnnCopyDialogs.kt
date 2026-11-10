package com.tyranor.next.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.DialogTextButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SystemNnnCopyUi(private val showDialogs: (SystemNnnCopyUi) -> Unit = {}) {
    private var confirmation by mutableStateOf<CompletableDeferred<Boolean>?>(null)
    val confirming: Boolean get() = confirmation != null
    var copying by mutableStateOf(false)
        private set
    var cancelling by mutableStateOf(false)
        private set
    val busy: Boolean get() = confirming || copying

    val interaction = EngineLauncher.SystemNnnCopyInteraction(
        confirmFirstCopy = {
            withContext(Dispatchers.Main.immediate) {
                val response = CompletableDeferred<Boolean>()
                confirmation = response
                try {
                    showDialogs(this@SystemNnnCopyUi)
                    response.await()
                } finally {
                    confirmation = null
                }
            }
        },
        onCopying = { running ->
            withContext(Dispatchers.Main.immediate) {
                copying = running
                cancelling = false
                if (running) showDialogs(this@SystemNnnCopyUi)
            }
        },
    )

    fun answer(approved: Boolean) {
        confirmation?.complete(approved)
    }

    fun cancelCopy(cancelLaunch: () -> Unit) {
        if (!copying || cancelling) return
        cancelling = true
        cancelLaunch()
    }
}

@Composable
internal fun SystemNnnCopyDialogs(state: SystemNnnCopyUi, cancelLaunch: () -> Unit) {
    if (state.confirming) {
        AppAlertDialog(
            onDismissRequest = { state.answer(false) },
            title = { Text(stringResource(R.string.launch_systemnnn_import_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.launch_systemnnn_import_confirm), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                DialogTextButton(stringResource(R.string.launch_systemnnn_import_start)) { state.answer(true) }
            },
            dismissButton = {
                DialogTextButton(stringResource(R.string.common_cancel)) { state.answer(false) }
            },
        )
    }
    if (state.copying) {
        AppAlertDialog(
            onDismissRequest = {},
            dismissEnabled = false,
            title = { Text(stringResource(R.string.launch_systemnnn_import_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    val message = if (state.cancelling) R.string.launch_systemnnn_import_cancelling
                    else R.string.launch_systemnnn_import_progress
                    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                DialogTextButton(stringResource(R.string.common_cancel), enabled = !state.cancelling) {
                    state.cancelCopy(cancelLaunch)
                }
            },
        )
    }
}
