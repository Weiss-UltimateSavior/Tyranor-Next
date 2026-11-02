package com.tyranor.next.ui.save

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.save.GameSaveException
import com.tyranor.next.core.game.save.sync.SaveSyncDirection
import com.tyranor.next.core.game.save.sync.SaveSyncError
import com.tyranor.next.core.game.save.sync.SaveSyncException
import com.tyranor.next.core.game.save.sync.SaveSyncMode
import com.tyranor.next.core.game.save.sync.SaveSyncRetention
import com.tyranor.next.core.game.save.sync.SaveSyncService
import com.tyranor.next.core.game.save.sync.WebDavClient
import com.tyranor.next.core.game.save.sync.WebDavConfig
import com.tyranor.next.core.game.save.sync.WebDavSettingsStore
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.shouldShowSaveSyncFeedback
import com.tyranor.next.ui.common.toSaveSyncMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class WebDavSettingsActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { WebDavSettingsScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, WebDavSettingsActivity::class.java)
    }
}

private enum class WebDavTextField(
    val title: Int,
    val icon: Int,
    val keyboardType: KeyboardType,
    val autofillContentType: ContentType? = null,
) {
    URL(R.string.webdav_server_url, R.drawable.ic_web_link, KeyboardType.Uri),
    USERNAME(
        R.string.webdav_username,
        R.drawable.ic_sheet_rename,
        KeyboardType.Text,
        ContentType.Username,
    ),
    PASSWORD(
        R.string.webdav_password,
        R.drawable.ic_save_key,
        KeyboardType.Password,
        ContentType.Password,
    ),
}

@Composable
private fun WebDavSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by WebDavSettingsStore.enabled.collectAsState()
    // Credentials deliberately stay out of saved instance state.
    var config by remember { mutableStateOf(WebDavConfig()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var masterTarget by remember { mutableStateOf<Boolean?>(null) }
    var editing by remember { mutableStateOf<WebDavTextField?>(null) }
    var editText by remember { mutableStateOf("") }
    var manualDirectionName by rememberSaveable { mutableStateOf(SaveSyncDirection.PULL.name) }
    val manualDirection = SaveSyncDirection.entries.firstOrNull { it.name == manualDirectionName }
        ?: SaveSyncDirection.PULL
    var showResetConfirm by remember { mutableStateOf(false) }
    val available = enabled && config.enabled && loaded && loadError == null && !busy
    val saveOperationFailedMessage = stringResource(R.string.save_operation_failed)
    val notSetMessage = stringResource(R.string.webdav_not_set)
    val passwordSetMessage = stringResource(R.string.webdav_password_set)
    val connectionOkMessage = stringResource(R.string.webdav_connection_ok)
    // 恢复入口只在「本机读不出已存载荷」时出现，且与其余配置一样受总开关与在途任务约束
    val canResetConfig = loadError != null && loaded && enabled && config.enabled &&
        !busy && masterTarget == null
    val masterAvailable = loaded && masterTarget == null
    val masterChecked = masterTarget ?: enabled
    val pageError = if (editing == null) configError ?: loadError else null

    fun failureMessage(failure: Exception): String = when (failure) {
        is SaveSyncException -> failure.code.toSaveSyncMessage(context)
        is GameSaveException -> failure.toSaveErrorMessage(context, saveOperationFailedMessage)
        else -> saveOperationFailedMessage
    }

    fun resetStoredConfig() {
        if (!canResetConfig || !WebDavSettingsStore.isEnabled(context)) return
        showResetConfirm = false
        busy = true
        scope.launch {
            try {
                val stillEnabled = withContext(Dispatchers.IO) {
                    WebDavSettingsStore.reset(context)
                    WebDavSettingsStore.isEnabled(context)
                }
                // 载荷已清除：回到「未设置」的空配置，总开关与已保留的历史不受影响
                config = WebDavConfig(enabled = stillEnabled)
                loadError = null
                configError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // 恢复失败如实报告并保持禁用态：加密载荷仍在，可再次尝试
                loadError = failureMessage(failure)
            } finally {
                busy = false
            }
        }
    }

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun canInteract(): Boolean = enabled && config.enabled && loaded && loadError == null && !busy &&
        WebDavSettingsStore.isEnabled(context)

    fun persistConfig(candidate: WebDavConfig, onSuccess: () -> Unit = {}) {
        if (!canInteract()) return
        val value = candidate.copy(enabled = config.enabled)
        configError = null
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WebDavSettingsStore.save(context, value)
                }
                config = value.copy(enabled = config.enabled)
                configError = null
                if (config.enabled && WebDavSettingsStore.isEnabled(context)) onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (config.enabled && WebDavSettingsStore.isEnabled(context)) {
                    configError = failureMessage(failure)
                }
            } finally {
                busy = false
            }
        }
    }

    fun runOperation(
        requireServer: Boolean,
        syncOperation: Boolean = false,
        block: suspend (WebDavConfig) -> String,
    ) {
        if (!canInteract()) return
        val value = config
        busy = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (requireServer && !value.configured) {
                        throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
                    }
                    block(value)
                }
                if (!config.enabled || !WebDavSettingsStore.isEnabled(context)) return@launch
                if (!syncOperation || shouldShowSaveSyncFeedback(context)) showToast(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!config.enabled || !WebDavSettingsStore.isEnabled(context)) return@launch
                if (!syncOperation || shouldShowSaveSyncFeedback(context)) {
                    showToast(failureMessage(failure))
                }
            } finally {
                busy = false
                operationJob = null
            }
        }
        operationJob = job
        job.start()
    }

    fun beginEdit(field: WebDavTextField) {
        if (!canInteract()) return
        configError = null
        editText = when (field) {
            WebDavTextField.URL -> config.serverUrl
            WebDavTextField.USERNAME -> config.username
            WebDavTextField.PASSWORD -> config.password
        }
        editing = field
    }

    fun commitEdit(field: WebDavTextField) {
        if (!canInteract()) return
        val candidate = when (field) {
            WebDavTextField.URL -> config.copy(serverUrl = editText.trim())
            WebDavTextField.USERNAME -> config.copy(username = editText)
            WebDavTextField.PASSWORD -> config.copy(password = editText)
        }
        persistConfig(
            candidate,
            onSuccess = {
                editing = null
                editText = ""
            },
        )
    }

    /** 取消编辑：候选值的校验错误只服务于当前输入框，不得残留成页面级错误。 */
    fun cancelEdit() {
        editing = null
        editText = ""
        configError = null
    }

    fun setMasterEnabled(value: Boolean) {
        if (!loaded || masterTarget != null || value == enabled) return
        val previousConfigEnabled = config.enabled
        if (!value) {
            operationJob?.cancel()
            config = config.copy(enabled = false)
            editing = null
            editText = ""
        }
        masterTarget = value
        configError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WebDavSettingsStore.setEnabled(context, value)
                }
                config = config.copy(enabled = value)
                if (!value) {
                    editing = null
                    editText = ""
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                config = config.copy(enabled = previousConfigEnabled)
                configError = failureMessage(failure)
            } finally {
                masterTarget = null
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            config = withContext(Dispatchers.IO) {
                WebDavSettingsStore.load(context)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Keep the encrypted payload untouched when decryption fails.
            loadError = failureMessage(failure)
        } finally {
            loaded = true
        }
    }

    LaunchedEffect(enabled) {
        config = config.copy(enabled = enabled)
        if (!enabled) {
            operationJob?.cancel()
            editing = null
            editText = ""
            showResetConfirm = false
        }
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.webdav_title),
                    background = ComposeColor.Transparent,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            SwitchPreference(
                                title = stringResource(R.string.webdav_enabled),
                                checked = masterChecked,
                                enabled = masterAvailable,
                                onCheckedChange = { setMasterEnabled(it) },
                            )
                        }
                    }
                }
                pageError?.let { error ->
                    item {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Column {
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                                // 仅在「本机读不出已存加密载荷」时提供恢复入口；总开关关闭或有任务在途时禁用
                                if (loadError != null) {
                                    AppNavItem(
                                        title = stringResource(R.string.webdav_reset_config),
                                        showLeadingIcon = false,
                                        showArrow = false,
                                        drawSurface = false,
                                        titleColor = MaterialTheme.colorScheme.error,
                                        onClick = if (!canResetConfig) null else ({ showResetConfirm = true }),
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ArrowPreference(
                                title = stringResource(R.string.webdav_server_url),
                                endActions = {
                                    PreferenceValue(config.serverUrl.ifBlank { notSetMessage })
                                },
                                enabled = available,
                                onClick = { beginEdit(WebDavTextField.URL) },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.webdav_username),
                                endActions = {
                                    PreferenceValue(config.username.ifBlank { notSetMessage })
                                },
                                enabled = available,
                                onClick = { beginEdit(WebDavTextField.USERNAME) },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.webdav_password),
                                endActions = {
                                    PreferenceValue(
                                        if (config.password.isEmpty()) notSetMessage else passwordSetMessage,
                                    )
                                },
                                enabled = available,
                                onClick = { beginEdit(WebDavTextField.PASSWORD) },
                            )
                            AppNavItem(
                                title = stringResource(R.string.webdav_test_connection),
                                showLeadingIcon = false,
                                showArrow = false,
                                drawSurface = false,
                                onClick = if (!available) null else ({
                                    runOperation(requireServer = true) { value ->
                                        WebDavClient(value, context.cacheDir) { WebDavSettingsStore.isEnabled(context) }
                                            .use { it.testConnection() }
                                        connectionOkMessage
                                    }
                                }),
                            )
                        }
                    }
                }
                item {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            val modes = SaveSyncMode.entries
                            OverlayDropdownPreference(
                                title = stringResource(R.string.webdav_mode),
                                summary = if (config.mode == SaveSyncMode.MANUAL) {
                                    stringResource(R.string.webdav_manual_note)
                                } else {
                                    null
                                },
                                items = modes.map { stringResource(it.titleResource()) },
                                selectedIndex = modes.indexOf(config.mode).coerceAtLeast(0),
                                enabled = available,
                                onSelectedIndexChange = { index ->
                                    modes.getOrNull(index)
                                        ?.takeIf { it != config.mode }
                                        ?.let { mode ->
                                            persistConfig(
                                                config.copy(mode = mode),
                                            )
                                        }
                                },
                            )
                            val retentionOptions = SaveSyncRetention.choices
                            OverlayDropdownPreference(
                                title = stringResource(R.string.webdav_retention),
                                summary = stringResource(R.string.webdav_retention_hint),
                                items = retentionOptions.map { limit ->
                                    if (limit == 0) {
                                        stringResource(R.string.webdav_unlimited)
                                    } else {
                                        stringResource(R.string.webdav_retention_count, limit)
                                    }
                                },
                                selectedIndex = retentionOptions
                                    .indexOf(config.remoteLimit)
                                    .coerceAtLeast(0),
                                enabled = available,
                                onSelectedIndexChange = { index ->
                                    retentionOptions.getOrNull(index)
                                        ?.takeIf { it != config.remoteLimit }
                                        ?.let { limit ->
                                            persistConfig(
                                                config.copy(remoteLimit = limit),
                                            )
                                        }
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.webdav_show_sync_feedback),
                                checked = config.showSyncFeedback,
                                enabled = available,
                                onCheckedChange = { checked ->
                                    if (checked != config.showSyncFeedback) {
                                        persistConfig(
                                            config.copy(showSyncFeedback = checked),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            val directions = SaveSyncDirection.entries
                            OverlayDropdownPreference(
                                title = stringResource(R.string.webdav_manual_all),
                                items = directions.map { stringResource(it.labelResource()) },
                                selectedIndex = directions.indexOf(manualDirection).coerceAtLeast(0),
                                enabled = available,
                                onSelectedIndexChange = { index ->
                                    if (canInteract()) {
                                        directions.getOrNull(index)?.let { manualDirectionName = it.name }
                                    }
                                },
                            )
                            AppNavItem(
                                title = stringResource(R.string.save_sync_execute),
                                showLeadingIcon = false,
                                showArrow = false,
                                drawSurface = false,
                                onClick = if (!available) null else ({
                                    val direction = manualDirection
                                    runOperation(requireServer = true, syncOperation = true) {
                                        SaveSyncService(context)
                                            .syncAll(direction)
                                            .toSaveSyncMessage(context)
                                    }
                                }),
                            )
                        }
                    }
                }
                item { BottomInsetSpacer() }
            }
        }
    }

    editing?.let { field ->
        AppAlertDialog(
            onDismissRequest = { cancelEdit() },
            title = { Text(stringResource(field.title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (field == WebDavTextField.URL) {
                        Text(
                            stringResource(R.string.webdav_url_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CompositionLocalProvider(LocalIndication provides NoIndication) {
                        AppSearchField(
                            query = editText,
                            onQueryChange = {
                                editText = it
                                configError = null
                            },
                            onSearch = { commitEdit(field) },
                            leadingIcon = painterResource(field.icon),
                            iconContentDescription = stringResource(field.title),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            imeAction = ImeAction.Done,
                            autofillContentType = field.autofillContentType,
                            keyboardType = field.keyboardType,
                            visualTransformation = if (field == WebDavTextField.PASSWORD) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                        )
                    }
                    configError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    enabled = available,
                    onClick = { commitEdit(field) },
                )
            },
            dismissButton = { DialogTextButton(stringResource(R.string.common_cancel)) { cancelEdit() } },
        )
    }

    if (showResetConfirm) {
        AppAlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.webdav_reset_config_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.webdav_reset_config_message), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    enabled = canResetConfig,
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = { resetStoredConfig() },
                )
            },
            dismissButton = { DialogTextButton(stringResource(R.string.common_cancel)) { showResetConfirm = false } },
            textScrollEnabled = true,
        )
    }
}

@Composable
private fun PreferenceValue(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun SaveSyncMode.titleResource(): Int = when (this) {
    SaveSyncMode.MANUAL -> R.string.webdav_mode_manual
    SaveSyncMode.PULL_ONLY -> R.string.webdav_mode_pull
    SaveSyncMode.UPLOAD_ONLY -> R.string.webdav_mode_upload
    SaveSyncMode.BIDIRECTIONAL -> R.string.webdav_mode_both
}
