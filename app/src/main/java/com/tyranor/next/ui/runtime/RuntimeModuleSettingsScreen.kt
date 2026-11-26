package com.tyranor.next.ui.runtime

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleDescriptor
import com.tyranor.next.core.engine.runtime.RuntimeModuleDiscoveryResult
import com.tyranor.next.core.engine.runtime.RuntimeModuleRegistry
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingsEntry
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingsParser
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingsSpec
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.LocalAppDialogContent
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.LocalDialogExit
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.SwitchPreference
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.pressSquishGroup
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.notice.AppNotice
import com.tyranor.next.ui.game.ShelfBookLeafBackHandler
import com.tyranor.next.ui.game.rememberGameDialogExitHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.widget.Toast

/** Dynamic, per-game settings declared by a bound third-party runtime module. */
@Composable
fun RuntimeModuleSettingsScreen(game: ScanGame) {
    RuntimeModuleSettingsView(game = game, inBook = false)
}

/**
 * 「游戏盒」翻开的书里的第三方运行时模块设置：与 [RuntimeModuleSettingsScreen] 共用同一份配置解析、
 * 草稿编辑与序列化保存逻辑；书内改为翻页弹窗呈现，有未保存改动时返回或点关闭同样翻出确认书页。
 */
@Composable
internal fun RuntimeModuleSettingsDialog(game: ScanGame, onDismiss: () -> Unit) {
    RuntimeModuleSettingsView(game = game, inBook = true, onDismiss = onDismiss)
}

@Composable
private fun RuntimeModuleSettingsView(
    game: ScanGame,
    inBook: Boolean,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val backDispatcher = if (inBook) null else LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val packageName = game.runtimeModulePackage.orEmpty()
    var refreshEpoch by rememberSaveable(game.uri, packageName) { mutableStateOf(0) }
    var module by remember(game.uri, packageName) { mutableStateOf<RuntimeModuleDescriptor?>(null) }
    var loading by remember(game.uri, packageName) { mutableStateOf(true) }
    var loadError by remember(game.uri, packageName) { mutableStateOf(false) }
    var bindingValid by remember(game.uri, packageName) { mutableStateOf(false) }
    var descriptorConfirmed by remember(game.uri, packageName) { mutableStateOf(false) }
    var baselineJson by rememberSaveable(game.uri, packageName) { mutableStateOf<String?>(null) }
    var draftJson by rememberSaveable(game.uri, packageName) { mutableStateOf<String?>(null) }
    var draftFingerprint by rememberSaveable(game.uri, packageName) { mutableStateOf<String?>(null) }
    var baselineFingerprint by rememberSaveable(game.uri, packageName) { mutableStateOf<String?>(null) }
    var saveRunning by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var saveOutcomeUnknown by rememberSaveable(game.uri, packageName) { mutableStateOf(false) }
    var saveDescriptorChanged by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showRestoreDefaultsDialog by remember { mutableStateOf(false) }
    var discardRequestGeneration by remember { mutableStateOf(0L) }
    var discardRunning by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<RuntimeModuleSettingItemSpec?>(null) }
    var editText by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf(false) }
    var backRequested by remember { mutableStateOf(false) }
    // 提升到书页 key(leaf) 之外的稳定组合路径，确保文件选择器返回前宿主重建时
    // rememberSaveable(picking) 与 ActivityResultLauncher 都能恢复并只交付一次。
    val pickFiles = rememberRuntimeModuleFileImport(game)
    val settings = module?.settings
    val draft = remember(draftJson) { parseObject(draftJson) }
    val effectiveSettings = remember(settings, draftJson) { settings?.resolve(draft) }
    val dirty = (baselineJson != null && draftJson != baselineJson) || saveOutcomeUnknown
    val descriptionConflict = settings != null && draftFingerprint != null &&
        draftFingerprint != settings.fingerprint && dirty
    val screenState = when {
        loading -> RuntimeSettingsScreenState.Loading
        loadError -> RuntimeSettingsScreenState.Error
        settings == null && module?.fileImports.isNullOrEmpty() -> RuntimeSettingsScreenState.Empty
        descriptionConflict -> RuntimeSettingsScreenState.Conflict
        else -> RuntimeSettingsScreenState.Content
    }

    suspend fun reload() {
        loading = true
        loadError = false
        try {
            val refreshResult = withContext(Dispatchers.IO) {
                val discovered = RuntimeModuleRegistry.refresh(context)
                val current = GameLibraryFacade.loadGames(context).firstOrNull { it.uri == game.uri }
                discovered to (current?.engine == EngineType.RUNTIME_MODULE && current.runtimeModulePackage == packageName)
            }
            descriptorConfirmed = refreshResult.first is RuntimeModuleDiscoveryResult.Success
            bindingValid = refreshResult.second
            val discovered = RuntimeModuleRegistry.find(packageName)
            module = discovered?.takeIf { it.enabled }
            if (!bindingValid || !descriptorConfirmed || module == null) {
                loadError = true
                return
            }
            val spec = module!!.settings ?: return
            val stored = withContext(Dispatchers.IO) {
                PerGameSettingsStore.getRuntimeModuleSettings(context, game.uri, packageName)
            }
            val storedJson = stored.toString()
            if (draftJson == null || draftFingerprint == null) {
                baselineJson = storedJson
                draftJson = storedJson
                baselineFingerprint = spec.fingerprint
                draftFingerprint = spec.fingerprint
            } else if (draftFingerprint == spec.fingerprint) {
                // SavedState draft wins over disk after rotation/process recreation.
                baselineJson = baselineJson ?: storedJson
                baselineFingerprint = baselineFingerprint ?: spec.fingerprint
            } else if (!dirty) {
                baselineJson = storedJson
                draftJson = storedJson
                baselineFingerprint = spec.fingerprint
                draftFingerprint = spec.fingerprint
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep a restored draft and the last successful descriptor visible for retry.
            loadError = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(game.uri, packageName, refreshEpoch) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { reload() }
    }

    if (!inBook) BackHandler(enabled = dirty) { showUnsavedDialog = true }
    LaunchedEffect(backRequested, dirty) {
        if (backRequested && !dirty) {
            backRequested = false
            backDispatcher?.onBackPressed()
        }
    }

    fun changeOverride(item: RuntimeModuleSettingItemSpec, value: Any?) {
        val changed = JSONObject(draft.toString())
        if (value == null) changed.remove(item.key) else changed.put(item.key, value)
        draftJson = changed.toString()
        draftFingerprint = settings?.fingerprint
        saveDescriptorChanged = false
        saveError = false
    }

    val settingPathPicker = rememberRuntimeModuleSettingPathPicker(game.uri, packageName) { key, path ->
        val item = settings?.items?.firstOrNull { it.key == key }
        if (item == null || !RuntimeModuleSettingsParser.isValidValue(item, path)) {
            false
        } else {
            changeOverride(item, path)
            true
        }
    }
    val pickSettingPath = settingPathPicker.pick

    fun restoreAllDefaults() {
        settingPathPicker.invalidatePendingRequests()
        draftJson = JSONObject().toString()
        draftFingerprint = settings?.fingerprint
        saveError = false
    }

    suspend fun readStoredSettingsJson(): String = withContext(Dispatchers.IO) {
        // A commit-started awaited write can outlive its cancelled caller. Drain the writer queue
        // before reading the cached Room row, or the read can clear saveOutcomeUnknown too early.
        GameLibraryRepository.awaitPendingWrites()
        PerGameSettingsStore.getRuntimeModuleSettings(context, game.uri, packageName).toString()
    }

    fun cancelDiscardDraft() {
        discardRequestGeneration += 1
        discardRunning = false
        showUnsavedDialog = false
    }

    fun requestDiscardDraft(onDiscarded: () -> Unit) {
        if (discardRunning) return
        val generation = discardRequestGeneration + 1
        discardRequestGeneration = generation
        discardRunning = true
        scope.launch {
            val persistedJson = try {
                if (saveOutcomeUnknown) readStoredSettingsJson() else baselineJson
            } catch (cancelled: CancellationException) {
                if (discardRequestGeneration == generation) discardRunning = false
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (discardRequestGeneration != generation) return@launch
            discardRunning = false
            if (persistedJson == null) {
                saveError = true
                return@launch
            }
            if (saveOutcomeUnknown) {
                baselineJson = persistedJson
                saveOutcomeUnknown = false
            }
            settingPathPicker.invalidatePendingRequests()
            draftJson = persistedJson
            draftFingerprint = baselineFingerprint
            onDiscarded()
        }
    }

    suspend fun readPersistedSettingsAfterFailure(spec: RuntimeModuleSettingsSpec) {
        try {
            baselineJson = readStoredSettingsJson()
            baselineFingerprint = spec.fingerprint
            saveOutcomeUnknown = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the draft dirty until persistence can be read or a later save succeeds.
        }
    }

    suspend fun saveDraft() {
        val spec = settings ?: return
        if (saveRunning || !bindingValid || !descriptorConfirmed || descriptionConflict || draftFingerprint != spec.fingerprint) return
        val submittedDraftJson = draftJson ?: return
        val submittedDraft = parseObject(submittedDraftJson)
        saveRunning = true
        saveError = false
        saveDescriptorChanged = false
        try {
            // Re-discover immediately before persistence. The core store repeats the binding check
            // inside its serialized commit boundary.
            val refreshResult = RuntimeModuleRegistry.refresh(context)
            val current = RuntimeModuleRegistry.find(packageName)
            val libraryGame = withContext(Dispatchers.IO) {
                GameLibraryFacade.loadGames(context).firstOrNull { it.uri == game.uri }
            }
            if (libraryGame?.engine != EngineType.RUNTIME_MODULE || libraryGame.runtimeModulePackage != packageName ||
                refreshResult !is RuntimeModuleDiscoveryResult.Success || current?.enabled != true || current.settings?.fingerprint != spec.fingerprint
            ) {
                bindingValid = libraryGame?.engine == EngineType.RUNTIME_MODULE && libraryGame.runtimeModulePackage == packageName
                descriptorConfirmed = refreshResult is RuntimeModuleDiscoveryResult.Success && current?.enabled == true
                if (descriptorConfirmed) module = current
                loadError = !bindingValid || !descriptorConfirmed || current?.settings?.items.isNullOrEmpty()
                return
            }
            val invalidKeys = spec.resolve(submittedDraft).invalidOverrideKeys
            val json = JSONObject()
            spec.items.forEach { item ->
                if (submittedDraft.has(item.key) && item.key !in invalidKeys) json.put(item.key, submittedDraft.opt(item.key))
            }
            saveOutcomeUnknown = true
            val result = withContext(Dispatchers.IO) {
                PerGameSettingsStore.saveRuntimeModuleSettings(context, game.uri, packageName, spec, json)
            }
            if (result is PerGameSettingsStore.RuntimeModuleSettingsSaveResult.Saved) {
                saveOutcomeUnknown = false
            }
            when (result) {
                is PerGameSettingsStore.RuntimeModuleSettingsSaveResult.Saved -> {
                    val normalized = json.toString()
                    baselineJson = normalized
                    baselineFingerprint = spec.fingerprint
                    if (draftJson == submittedDraftJson) {
                        draftJson = normalized
                        draftFingerprint = spec.fingerprint
                    }
                    AppNotice.makeText(context, R.string.runtime_settings_saved, Toast.LENGTH_SHORT, AppNotice.Kind.Success).show()
                }
                PerGameSettingsStore.RuntimeModuleSettingsSaveResult.DescriptorChanged -> {
                    val refreshed = withContext(Dispatchers.IO) {
                        val discovery = RuntimeModuleRegistry.refresh(context)
                        discovery to RuntimeModuleRegistry.find(packageName)
                    }
                    val current = refreshed.second
                    descriptorConfirmed = refreshed.first is RuntimeModuleDiscoveryResult.Success && current?.enabled == true
                    if (descriptorConfirmed) module = current
                    saveDescriptorChanged = true
                    saveError = true
                    if (!descriptorConfirmed || current?.settings == null) loadError = true
                }
                is PerGameSettingsStore.RuntimeModuleSettingsSaveResult.StorageFailure -> {
                    saveError = true
                    saveOutcomeUnknown = true
                    readPersistedSettingsAfterFailure(spec)
                }
                else -> {
                    saveError = true
                    when (result) {
                        PerGameSettingsStore.RuntimeModuleSettingsSaveResult.GameMissing,
                        PerGameSettingsStore.RuntimeModuleSettingsSaveResult.BindingChanged -> bindingValid = false
                        PerGameSettingsStore.RuntimeModuleSettingsSaveResult.ModuleDisabled -> descriptorConfirmed = false
                        else -> Unit
                    }
                    if (!bindingValid || !descriptorConfirmed) loadError = true
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            saveError = true
        } finally {
            saveRunning = false
        }
    }

    val canSave = dirty && bindingValid && descriptorConfirmed && !saveRunning && !descriptionConflict

    fun saveNow() {
        if (!saveRunning && !loading && bindingValid && !descriptionConflict) {
            scope.launch { saveDraft() }
        }
    }

    @Composable
    fun RuntimeSettingsList(
        pickFiles: ((com.tyranor.next.core.engine.runtime.RuntimeModuleFileImportSpec) -> Unit)?,
        modifier: Modifier,
        contentPadding: PaddingValues,
    ) {
        LazyColumn(
            modifier = modifier,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (screenState != RuntimeSettingsScreenState.Content) item(key = "runtime_settings_state") {
                Column(Modifier.then(listItemMotion())) {
                    BlurCrossfade(targetState = screenState, label = "runtimeSettingsState") { state ->
                        when (state) {
                            RuntimeSettingsScreenState.Loading -> StatusCard(stringResource(R.string.runtime_settings_loading))
                            RuntimeSettingsScreenState.Error -> StatusCard(
                                text = stringResource(
                                    when {
                                        !bindingValid -> R.string.runtime_settings_binding_changed
                                        descriptorConfirmed -> R.string.runtime_settings_unavailable
                                        else -> R.string.runtime_settings_load_failed
                                    },
                                ),
                                action = stringResource(R.string.runtime_settings_retry),
                                onAction = { refreshEpoch++ },
                            )
                            RuntimeSettingsScreenState.Empty -> StatusCard(stringResource(R.string.runtime_settings_empty))
                            RuntimeSettingsScreenState.Conflict -> StatusCard(
                                text = stringResource(R.string.runtime_settings_description_changed),
                                action = stringResource(R.string.runtime_settings_discard_draft),
                                onAction = {
                                    draftJson = baselineJson ?: "{}"
                                    draftFingerprint = baselineFingerprint
                                    refreshEpoch++
                                },
                            )
                            RuntimeSettingsScreenState.Content -> Unit
                        }
                    }
                }
            }
            item(key = "runtime_settings_save_error") {
                Column(Modifier.then(listItemMotion())) {
                    ExpandVisibility(visible = saveError) {
                        StatusCard(stringResource(if (saveDescriptorChanged) R.string.runtime_settings_descriptor_changed else R.string.runtime_settings_save_failed))
                    }
                }
            }
            if (settings != null && !descriptionConflict) {
                if (settings.items.isNotEmpty()) item(key = "runtime_settings_restore_all_defaults") {
                    val restoreEnabled = bindingValid && descriptorConfirmed && !loadError
                    MiuixCard(
                        cornerRadius = AppComponentCornerRadius,
                        modifier = Modifier.then(listItemMotion()).pressSquishGroup().fillMaxWidth().glassBorder(),
                    ) {
                        AppNavItem(
                            title = stringResource(R.string.runtime_settings_restore_all_title),
                            titleTextStyle = MaterialTheme.typography.titleMedium,
                            drawSurface = false,
                            showLeadingIcon = false,
                            showArrow = true,
                            onClick = if (restoreEnabled) ({ showRestoreDefaultsDialog = true }) else null,
                        )
                    }
                }
                if (settings.items.isNotEmpty() && effectiveSettings?.invalidOverrideKeys?.isNotEmpty() == true) item {
                    Column(Modifier.then(listItemMotion())) {
                        StatusCard(stringResource(R.string.runtime_settings_invalid_overrides))
                    }
                }
                items(settings.entries, key = { it.key }) { entry ->
                    val enabled = bindingValid && descriptorConfirmed
                    when (entry) {
                        is RuntimeModuleSettingsEntry.Item -> {
                            val item = entry.spec
                            val value = effectiveSettings?.values?.get(item.key) ?: item.defaultValue
                            RuntimeSettingItemCard(
                                modifier = Modifier.then(listItemMotion()),
                                item = item,
                                value = value,
                                enabled = enabled,
                                onChange = { next -> changeOverride(item, next) },
                                onEdit = {
                                    editItem = item
                                    editText = value.toString()
                                    inputError = false
                                },
                                onPickPath = { pickSettingPath(item) },
                            )
                        }
                        is RuntimeModuleSettingsEntry.Card -> RuntimeSettingsGroupCard(
                            modifier = Modifier.then(listItemMotion()),
                            spec = entry.spec,
                            effectiveValues = effectiveSettings?.values.orEmpty(),
                            enabled = enabled,
                            onChange = ::changeOverride,
                            onEdit = { item ->
                                editItem = item
                                editText = effectiveSettings?.values?.get(item.key)?.toString() ?: item.defaultValue.toString()
                                inputError = false
                            },
                            onPickPath = pickSettingPath,
                        )
                    }
                }
            }
            if (bindingValid && descriptorConfirmed && !loadError) {
                items(module?.fileImports.orEmpty(), key = { "runtime_file_import:${it.key}" }) { item ->
                    val enabled = pickFiles != null && !loading && !saveRunning
                    com.tyranor.next.ui.common.AppSectionCard(
                        modifier = Modifier.pressSquishGroup().then(listItemMotion()),
                    ) {
                        com.tyranor.next.ui.common.AppNavItem(
                            title = item.title,
                            summary = stringResource(R.string.runtime_file_import_destination, item.displayPath),
                            drawSurface = false,
                            showLeadingIcon = false,
                            showArrow = false,
                            onClick = if (enabled) ({ pickFiles(item) }) else null,
                        )
                    }
                }
            }
        }
    }

    if (inBook) {
        AppAlertDialog(
            onDismissRequest = {
                if (!saveRunning) {
                    if (dirty) showUnsavedDialog = true else onDismiss()
                }
            },
            dismissEnabledNow = { !saveRunning },
            title = { Text(stringResource(R.string.runtime_settings_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                ShelfBookLeafBackHandler(enabled = dirty) { showUnsavedDialog = true }
                MiuixSettingsTheme {
                    RuntimeSettingsList(
                        pickFiles = pickFiles,
                        modifier = Modifier.fillMaxSize().skipEntranceOnDrag(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    )
                }
            },
            buttonArrangement = Arrangement.SpaceBetween,
            dismissButton = {
                val exit = LocalDialogExit.current
                CompositionLocalProvider(LocalDialogExit provides null) {
                    DialogTextButton(text = stringResource(R.string.common_close), enabled = !saveRunning) {
                        if (saveRunning) return@DialogTextButton
                        if (dirty) {
                            showUnsavedDialog = true
                        } else if (exit != null) {
                            exit.closeThen(onDismiss)
                        } else {
                            onDismiss()
                        }
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.runtime_settings_save),
                    enabled = canSave,
                    onClick = ::saveNow,
                )
            },
        )
    } else {
        MiuixSettingsTheme {
            ProvideEntrance {
                MiuixScaffold(
                    modifier = Modifier.fillMaxSize().skipEntranceOnDrag(),
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentWindowInsets = WindowInsets(0.dp),
                    topBar = {
                        AppSecondaryTopBar(
                            title = stringResource(R.string.runtime_settings_title),
                            background = androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = PageTextColor,
                            trailing = {
                                if (canSave) TopBarIcon(
                                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_save),
                                    contentDescription = stringResource(R.string.runtime_settings_save),
                                    tint = MiuixTheme.colorScheme.primary,
                                    onClick = ::saveNow,
                                )
                            },
                        )
                    },
                ) { innerPadding ->
                    RuntimeSettingsList(
                        pickFiles = pickFiles,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = innerPadding.calculateTopPadding()),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    )
                }
            }
        }
    }

    editItem?.let { item ->
        AppAlertDialog(
            onDismissRequest = { editItem = null },
            title = { Text(item.title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    AppSearchField(
                        query = editText,
                        onQueryChange = { editText = it; inputError = false },
                        imeAction = ImeAction.Done,
                        keyboardType = if (item.type == RuntimeModuleSettingItemSpec.Type.INTEGER && (item.min ?: 0) >= 0) {
                            KeyboardType.Number
                        } else KeyboardType.Text,
                        onSearch = { },
                    )
                    if (inputError) Text(stringResource(R.string.runtime_settings_input_invalid), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = {
                        val value = parseRuntimeSettingInput(item, editText)
                        if (value == null) inputError = true else { changeOverride(item, value); editItem = null }
                    },
                )
            },
            dismissButton = { DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { editItem = null }) },
        )
    }

    if (showRestoreDefaultsDialog) {
        AppAlertDialog(
            onDismissRequest = { showRestoreDefaultsDialog = false },
            title = { Text(stringResource(R.string.runtime_settings_restore_all_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    stringResource(R.string.runtime_settings_restore_all_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    enabled = !saveRunning && bindingValid && descriptorConfirmed,
                    onClick = {
                        restoreAllDefaults()
                        showRestoreDefaultsDialog = false
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { showRestoreDefaultsDialog = false },
                )
            },
        )
    }
    if (showUnsavedDialog) {
        val unsavedExitHandle = rememberGameDialogExitHandle()
        AppAlertDialog(
            onDismissRequest = ::cancelDiscardDraft,
            title = { Text(stringResource(R.string.runtime_settings_unsaved_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.runtime_settings_unsaved_message)) },
            confirmButton = {
                DialogTextButton(text = stringResource(R.string.common_confirm), onClick = {
                    requestDiscardDraft {
                        unsavedExitHandle.closeThen {
                            showUnsavedDialog = false
                            if (inBook) onDismiss() else backRequested = true
                        }
                    }
                })
            },
            dismissButton = {
                unsavedExitHandle.capture()
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = ::cancelDiscardDraft)
            },
        )
    }
}

@Composable
private fun RuntimeSettingRow(
    modifier: Modifier = Modifier,
    item: RuntimeModuleSettingItemSpec,
    value: Any,
    overridden: Boolean,
    enabled: Boolean,
    onChange: (Any) -> Unit,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
) {
    com.tyranor.next.ui.common.AppSectionCard(
        modifier = modifier.pressSquishGroup(),
    ) {
        val inBook = com.tyranor.next.theme.LocalShelfBookLeaf.current
        val bodyPad = if (inBook) Modifier.fillMaxWidth().padding(vertical = 4.dp) else Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        Column(bodyPad) {
            when (item.type) {
                RuntimeModuleSettingItemSpec.Type.BOOLEAN -> SwitchPreference(
                    checked = value as Boolean,
                    onCheckedChange = onChange,
                    title = item.title,
                    summary = item.description,
                    enabled = enabled,
                )
                RuntimeModuleSettingItemSpec.Type.ENUM -> {
                    val selected = item.options.indexOfFirst { it.value == value }.coerceAtLeast(0)
                    OverlayDropdownPreference(
                        items = item.options.map { it.title },
                        selectedIndex = selected,
                        title = item.title,
                        summary = item.description,
                        enabled = enabled,
                        onSelectedIndexChange = { index -> item.options.getOrNull(index)?.let { onChange(it.value) } },
                    )
                }
                RuntimeModuleSettingItemSpec.Type.TEXT, RuntimeModuleSettingItemSpec.Type.INTEGER -> {
                    com.tyranor.next.ui.common.AppNavItem(
                        title = item.title,
                        summary = value.toString(),
                        drawSurface = false,
                        showLeadingIcon = false,
                        showArrow = enabled,
                        onClick = if (enabled) onEdit else null,
                    )
                }
            }
            item.description?.takeIf { item.type != RuntimeModuleSettingItemSpec.Type.BOOLEAN && item.type != RuntimeModuleSettingItemSpec.Type.ENUM }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            if (overridden) NoRippleTextButton(
                text = stringResource(R.string.runtime_settings_restore_default),
                enabled = enabled,
                onClick = onRestore,
            )
        }
    }
}

@Composable
private fun StatusCard(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val inBook = com.tyranor.next.theme.LocalShelfBookLeaf.current
    com.tyranor.next.ui.common.AppSectionCard {
        Column(Modifier.fillMaxWidth().padding(if (inBook) 4.dp else 16.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (inBook) com.tyranor.next.theme.CurrentBookPaperPalette.ink else TextColor,
            )
            if (action != null && onAction != null) NoRippleTextButton(text = action, onClick = onAction)
        }
    }
}

private fun parseObject(value: String?): JSONObject = try {
    value?.let(::JSONObject) ?: JSONObject()
} catch (_: Exception) {
    JSONObject()
}

private enum class RuntimeSettingsScreenState { Loading, Error, Empty, Conflict, Content }

internal fun parseRuntimeSettingInput(item: RuntimeModuleSettingItemSpec, raw: String): Any? = when (item.type) {
    RuntimeModuleSettingItemSpec.Type.TEXT -> raw.takeIf { RuntimeModuleSettingsParser.isValidValue(item, it) }
    RuntimeModuleSettingItemSpec.Type.INTEGER -> raw.toIntOrNull()?.takeIf { RuntimeModuleSettingsParser.isValidValue(item, it) }
    RuntimeModuleSettingItemSpec.Type.BOOLEAN,
    RuntimeModuleSettingItemSpec.Type.ENUM,
    RuntimeModuleSettingItemSpec.Type.SLIDER,
    RuntimeModuleSettingItemSpec.Type.FILE,
    RuntimeModuleSettingItemSpec.Type.FOLDER -> null
}
