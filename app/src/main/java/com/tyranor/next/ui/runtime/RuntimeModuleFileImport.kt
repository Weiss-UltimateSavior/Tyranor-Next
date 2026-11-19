package com.tyranor.next.ui.runtime

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.runtime.RuntimeModuleFileImportSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleFileImporter
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.notice.AppNotice
import kotlinx.coroutines.launch

@Composable
internal fun rememberRuntimeModuleFileImport(game: ScanGame): ((RuntimeModuleFileImportSpec) -> Unit)? {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var picking by rememberSaveable(game.uri, game.runtimeModulePackage) {
        mutableStateOf<RuntimeModuleFileImportSpec?>(null)
    }
    var working by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RuntimeModuleFileImporter.Preparation.Ready?>(null) }

    fun showUnavailable() {
        AppNotice.makeText(context, R.string.runtime_file_import_unavailable, Toast.LENGTH_LONG, AppNotice.Kind.Failure).show()
    }

    fun receive(sources: List<Uri>) {
        val spec = picking ?: return
        picking = null
        if (sources.isEmpty()) return
        working = true
        scope.launch {
            try {
                when (val result = RuntimeModuleFileImporter.prepare(context, game, spec, sources)) {
                    is RuntimeModuleFileImporter.Preparation.Ready -> pending = result
                    RuntimeModuleFileImporter.Preparation.Unavailable -> showUnavailable()
                }
            } finally {
                working = false
            }
        }
    }

    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        receive(listOfNotNull(source))
    }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::receive)

    pending?.let { plan ->
        AppAlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.runtime_file_import_confirm), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    plan.entries.forEach { entry ->
                        Text(
                            stringResource(
                                if (entry.replacesFile) R.string.runtime_file_import_replace else R.string.runtime_file_import_add,
                                entry.relativePath,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                DialogTextButton(text = stringResource(R.string.common_confirm), onClick = {
                    if (pending === plan && !working) {
                        pending = null
                        working = true
                        scope.launch {
                            try {
                                val (message, kind) = when (val result = RuntimeModuleFileImporter.write(context, game, plan)) {
                                    RuntimeModuleFileImporter.Result.Success ->
                                        resources.getString(R.string.runtime_file_import_success, plan.entries.size) to AppNotice.Kind.Success
                                    is RuntimeModuleFileImporter.Result.Failed ->
                                        resources.getString(R.string.runtime_file_import_failed, result.imported, plan.entries.size) to AppNotice.Kind.Failure
                                }
                                AppNotice.makeText(context, message, Toast.LENGTH_LONG, kind).show()
                            } finally {
                                working = false
                            }
                        }
                    }
                })
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { pending = null })
            },
        )
    }

    return if (working || picking != null || pending != null) null else { spec ->
        if (!working && picking == null && pending == null) {
            picking = spec
            try {
                (if (spec.multiple) multiple else single).launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                picking = null
                showUnavailable()
            }
        }
    }
}
