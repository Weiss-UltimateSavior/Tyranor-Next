package com.tyranor.next.ui.runtime

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec.Type
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.ui.common.notice.AppNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberRuntimeModuleSettingPathPicker(
    gameUri: String,
    packageName: String,
    onPathSelected: (key: String, path: String) -> Boolean,
): (RuntimeModuleSettingItemSpec) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unavailableMessage = stringResource(R.string.runtime_settings_path_unavailable)
    var pendingKey by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }

    fun showUnavailable() {
        AppNotice.makeText(context, unavailableMessage, Toast.LENGTH_LONG, AppNotice.Kind.Failure).show()
    }

    fun complete(uri: Uri?) {
        val key = pendingKey ?: return
        if (uri == null) {
            pendingKey = null
            return
        }
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    GamePathUtils.resolveLocalPath(context, uri.toString())?.takeIf { it.startsWith('/') }
                }
                if (path == null || !onPathSelected(key, path)) showUnavailable()
            } finally {
                if (pendingKey == key) pendingKey = null
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> complete(uri) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> complete(uri) }

    return { item ->
        if (pendingKey == null && item.type in setOf(Type.FILE, Type.FOLDER)) {
            pendingKey = item.key
            try {
                if (item.type == Type.FILE) filePicker.launch(arrayOf("*/*")) else folderPicker.launch(null)
            } catch (_: ActivityNotFoundException) {
                pendingKey = null
                showUnavailable()
            }
        }
    }
}
