package com.tyranor.next.ui.save

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveException
import com.tyranor.next.core.game.save.sync.SaveActivationResult
import com.tyranor.next.core.game.save.sync.SaveSyncDirection
import com.tyranor.next.core.game.save.sync.SaveSyncError
import com.tyranor.next.core.game.save.sync.SaveSyncException
import com.tyranor.next.core.game.save.sync.SaveSyncFailure
import com.tyranor.next.core.game.save.sync.SaveSyncReport
import com.tyranor.next.core.game.save.sync.SaveSyncService
import com.tyranor.next.core.game.save.sync.SaveVersion
import com.tyranor.next.core.game.save.sync.SaveVersionSource
import com.tyranor.next.core.game.save.sync.WebDavSettingsStore
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.shouldShowSaveSyncFeedback
import com.tyranor.next.ui.common.toSaveSyncMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SaveVersionsPanel(game: ScanGame, taskRunning: Boolean, revision: Int, runTask: (suspend () -> String?) -> Unit) {
    val context = LocalContext.current
    val enabled by WebDavSettingsStore.enabled.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { WebDavSettingsStore.isEnabled(context) }
    }
    SaveSelectionRecoveryPanel(game, taskRunning, revision, runTask)
    if (!enabled) return

    val service = remember(context) { SaveSyncService(context) }
    var versions by remember(game.uri) { mutableStateOf(emptyList<SaveVersion>()) }
    var loading by remember(game.uri) { mutableStateOf(true) }
    var loadError by remember(game.uri) { mutableStateOf<String?>(null) }
    var taskError by remember(game.uri) { mutableStateOf<String?>(null) }
    var pendingVersion by remember(game.uri) { mutableStateOf<SaveVersion?>(null) }
    var discardPrevious by remember(game.uri) { mutableStateOf(false) }
    var selectedId by rememberSaveable(game.uri) { mutableStateOf("") }
    var identity by remember(game.uri) { mutableStateOf("") }
    var editedIdentity by remember(game.uri) { mutableStateOf<String?>(null) }
    val dateFormat = remember { SimpleDateFormat("yy.MM.dd HH:mm", Locale.ROOT) }
    val selectedIndex = versions.indexOfFirst { it.id == selectedId } + 1
    val selectedVersion = versions.firstOrNull { it.id == selectedId }
    val previousUnknown = stringResource(R.string.save_version_previous_unknown)
    val saveOperationFailedMessage = stringResource(R.string.save_operation_failed)
    val metadataPendingMessage = stringResource(R.string.save_version_metadata_pending)
    val previousPendingMessage = stringResource(R.string.save_version_previous_pending)

    fun versionTimes(version: SaveVersion): Pair<String, String> {
        val newFull = dateFormat.format(Date(version.createdAt))
        val oldFull = version.previousCreatedAt?.let { dateFormat.format(Date(it)) }
        val sameDay = oldFull != null && oldFull.substringBefore(' ') == newFull.substringBefore(' ')
        return (oldFull ?: previousUnknown) to if (sameDay) newFull.substringAfter(' ') else newFull
    }

    fun <T> runSilentTask(block: suspend () -> T, onSuccess: (T) -> Unit) {
        if (taskRunning || !WebDavSettingsStore.isEnabled(context)) return
        taskError = null
        runTask {
            try {
                if (!WebDavSettingsStore.isEnabled(context)) return@runTask null
                val result = block()
                if (!WebDavSettingsStore.isEnabled(context)) return@runTask null
                withContext(Dispatchers.Main) {
                    taskError = null
                    onSuccess(result)
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = when (failure) {
                    is SaveSyncException -> failure.code.toSaveSyncMessage(context)
                    is GameSaveException -> failure.toSaveErrorMessage(context, saveOperationFailedMessage)
                    else -> saveOperationFailedMessage
                }
                if (WebDavSettingsStore.isEnabled(context)) {
                    withContext(Dispatchers.Main) { taskError = message }
                }
                null
            }
        }
    }

    fun syncNow() {
        if (taskRunning || !WebDavSettingsStore.isEnabled(context)) return
        taskError = null
        runTask {
            if (!WebDavSettingsStore.isEnabled(context)) return@runTask null
            val report = try {
                service.syncGame(game, SaveSyncDirection.BOTH)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SaveSyncReport(
                    failures = listOf(
                        SaveSyncFailure(
                            game.title,
                            (failure as? SaveSyncException)?.code ?: SaveSyncError.LOCAL_IO,
                        ),
                    ),
                )
            }
            if (!WebDavSettingsStore.isEnabled(context)) return@runTask null
            if (report.failures.firstOrNull()?.error == SaveSyncError.CREDENTIALS_UNAVAILABLE) {
                withContext(Dispatchers.Main) {
                    taskError = SaveSyncError.CREDENTIALS_UNAVAILABLE.toSaveSyncMessage(context)
                }
                return@runTask null
            }
            if (shouldShowSaveSyncFeedback(context)) report.toSaveSyncMessage(context) else null
        }
    }

    fun beginIdentityEdit() {
        if (taskRunning || !WebDavSettingsStore.isEnabled(context)) return
        taskError = null
        editedIdentity = identity
    }

    fun saveIdentity() {
        if (taskRunning || editedIdentity == null) return
        val value = editedIdentity.orEmpty()
        runSilentTask(
            block = { service.setIdentity(game, value) },
            onSuccess = { editedIdentity = null },
        )
    }

    LaunchedEffect(game.uri, revision) {
        loading = true
        loadError = null
        try {
            val loaded = withContext(Dispatchers.IO) { service.listVersions(game) to service.identity(game) }
            versions = loaded.first
            identity = loaded.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = when (failure) {
                is SaveSyncException -> failure.code.toSaveSyncMessage(context)
                is GameSaveException -> failure.toSaveErrorMessage(context, saveOperationFailedMessage)
                else -> saveOperationFailedMessage
            }
        } finally {
            loading = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        loadError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        taskError?.takeIf { pendingVersion == null && editedIdentity == null }?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (loadError == null) {
            MiuixCard(Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    when {
                        loading -> Text(
                            stringResource(R.string.common_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        versions.isEmpty() -> Text(
                            stringResource(R.string.save_versions_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        else -> {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.save_versions_title),
                                summary = selectedVersion?.takeIf { it.restored }?.let { version ->
                                    val (oldTime, newTime) = versionTimes(version)
                                    stringResource(R.string.save_version_time_change, oldTime, newTime)
                                },
                                items = listOf(stringResource(R.string.save_version_choose)) + versions.map { version ->
                                    val source = stringResource(if (version.source == SaveVersionSource.CLOUD) R.string.save_version_cloud else R.string.save_version_local)
                                    val newFull = dateFormat.format(Date(version.createdAt))
                                    if (version.restored) {
                                        val (oldTime, newTime) = versionTimes(version)
                                        stringResource(
                                            R.string.save_version_restored_row,
                                            source,
                                            oldTime,
                                            newTime,
                                        )
                                    } else {
                                        stringResource(R.string.save_version_row, source, newFull)
                                    }
                                },
                                selectedIndex = selectedIndex,
                                enabled = !taskRunning,
                                onSelectedIndexChange = { index ->
                                    if (!taskRunning && WebDavSettingsStore.isEnabled(context)) {
                                        selectedId = versions.getOrNull(index - 1)?.id.orEmpty()
                                    }
                                },
                            )
                            AppNavItem(
                                title = stringResource(R.string.save_version_activate),
                                showLeadingIcon = false,
                                showArrow = false,
                                drawSurface = false,
                                onClick = if (taskRunning || selectedVersion == null) {
                                    null
                                } else {
                                    {
                                        if (WebDavSettingsStore.isEnabled(context)) {
                                            taskError = null
                                            discardPrevious = false
                                            pendingVersion = selectedVersion
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        MiuixCard(Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
            Column(Modifier.padding(vertical = 4.dp)) {
                AppNavItem(
                    title = stringResource(R.string.save_sync_execute),
                    showLeadingIcon = false,
                    showArrow = false,
                    drawSurface = false,
                    onClick = if (taskRunning) null else ({ syncNow() }),
                )
                ArrowPreference(
                    title = stringResource(R.string.save_sync_identity),
                    enabled = !taskRunning,
                    endActions = {
                        Text(
                            identity,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.5f),
                        )
                    },
                    onClick = { beginIdentityEdit() },
                )
            }
        }
    }
    pendingVersion?.let { version ->
        AppAlertDialog(onDismissRequest = { if (!taskRunning) { pendingVersion = null; taskError = null } },
            title = { Text(stringResource(R.string.save_version_switch_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.save_version_row,
                            stringResource(if (version.source == SaveVersionSource.CLOUD) R.string.save_version_cloud else R.string.save_version_local),
                            dateFormat.format(Date(version.createdAt))),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(stringResource(R.string.save_versions_policy), style = MaterialTheme.typography.bodyMedium)
                    taskError?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { DialogTextButton(stringResource(if (discardPrevious) R.string.save_version_discard_switch else R.string.common_confirm), enabled = !taskRunning) {
                runSilentTask(
                    block = { service.activateVersion(game, version.id, discardPrevious) },
                    onSuccess = { result ->
                        if (result != SaveActivationResult.PREVIOUS_PENDING) pendingVersion = null
                        discardPrevious = result == SaveActivationResult.PREVIOUS_PENDING
                        taskError = when (result) {
                            SaveActivationResult.UNCHANGED,
                            SaveActivationResult.APPLIED -> null
                            SaveActivationResult.METADATA_PENDING -> metadataPendingMessage
                            SaveActivationResult.PREVIOUS_PENDING -> previousPendingMessage
                        }
                    },
                )
            } },
            dismissButton = { DialogTextButton(stringResource(R.string.common_cancel), enabled = !taskRunning) {
                pendingVersion = null
                taskError = null
            } })
    }
    if (editedIdentity != null) AppAlertDialog(onDismissRequest = { if (!taskRunning) { editedIdentity = null; taskError = null } },
        title = { Text(stringResource(R.string.save_sync_identity), style = MaterialTheme.typography.titleMedium) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.save_sync_identity_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                AppSearchField(
                    query = editedIdentity.orEmpty(),
                    onQueryChange = {
                        if (!taskRunning) {
                            editedIdentity = it
                            taskError = null
                        }
                    },
                    onSearch = { saveIdentity() },
                    imeAction = ImeAction.Done,
                    leadingIcon = painterResource(R.drawable.ic_sheet_rename),
                    iconContentDescription = stringResource(R.string.save_sync_identity),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
            taskError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        } },
        confirmButton = { DialogTextButton(stringResource(R.string.common_save), enabled = !taskRunning) {
            saveIdentity()
        } },
        dismissButton = { DialogTextButton(stringResource(R.string.common_cancel), enabled = !taskRunning) {
            editedIdentity = null
            taskError = null
        } })
}

@Composable
private fun SaveSelectionRecoveryPanel(
    game: ScanGame,
    taskRunning: Boolean,
    revision: Int,
    runTask: (suspend () -> String?) -> Unit,
) {
    val context = LocalContext.current
    val service = remember(context) { SaveSyncService(context) }
    var needed by remember(game.uri) { mutableStateOf(false) }
    var confirming by remember(game.uri) { mutableStateOf(false) }
    var error by remember(game.uri) { mutableStateOf<String?>(null) }
    val failureMessage = stringResource(R.string.save_operation_failed)
    LaunchedEffect(game.uri, revision) {
        needed = withContext(Dispatchers.IO) { service.needsSelectionRecovery(game) }
    }
    if (!needed) return
    MiuixCard(Modifier.fillMaxWidth().padding(bottom = 12.dp).glassShadow().glassBorder(),
        cornerRadius = AppComponentCornerRadius) {
        AppNavItem(
            title = stringResource(R.string.save_version_recover),
            showLeadingIcon = false,
            showArrow = false,
            drawSurface = false,
            onClick = if (taskRunning) null else ({ confirming = true; error = null }),
        )
    }
    if (confirming) AppAlertDialog(
        onDismissRequest = { if (!taskRunning) confirming = false },
        title = { Text(stringResource(R.string.save_version_recover), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.save_version_recovery_policy), style = MaterialTheme.typography.bodyMedium)
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            DialogTextButton(stringResource(R.string.common_confirm), enabled = !taskRunning) {
                runTask {
                    try {
                        service.recoverSelection(game)
                        withContext(Dispatchers.Main) { confirming = false; needed = false }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val message = when (failure) {
                            is SaveSyncException -> failure.code.toSaveSyncMessage(context)
                            is GameSaveException -> failure.toSaveErrorMessage(context, failureMessage)
                            else -> failureMessage
                        }
                        withContext(Dispatchers.Main) { error = message }
                    }
                    null
                }
            }
        },
        dismissButton = { DialogTextButton(stringResource(R.string.common_cancel), enabled = !taskRunning) { confirming = false } },
    )
}

internal fun SaveSyncDirection.labelResource(): Int = when (this) {
    SaveSyncDirection.PULL -> R.string.save_sync_direction_pull
    SaveSyncDirection.UPLOAD -> R.string.save_sync_direction_upload
    SaveSyncDirection.BOTH -> R.string.save_sync_direction_both
}
