package com.tyranor.next.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.SystemNnnSaveLayout
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.SwitchPreference
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.notice.AppNotice
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SystemNnnSettingsScreen() {
    val context = LocalContext.current
    var fullscreen by rememberSaveable { mutableStateOf(EngineSettingsStore.isSystemNnnFullscreen(context)) }
    var readingSize by rememberSaveable { mutableStateOf(EngineSettingsStore.getSystemNnnReadingSize(context)) }
    var exeAnalysis by rememberSaveable { mutableStateOf(EngineSettingsStore.isSystemNnnExeAnalysisEnabled(context)) }
    var debugMode by rememberSaveable { mutableStateOf(EngineSettingsStore.isSystemNnnDebugMode(context)) }
    MiuixSettingsTheme {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.engine_settings_systemnnn_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.engine_settings_save_content_description),
                            MiuixTheme.colorScheme.primary,
                        ) {
                            EngineSettingsStore.saveSystemNnnSettings(context, fullscreen, readingSize, exeAnalysis, debugMode)
                            AppNotice.makeText(context, R.string.engine_settings_saved, Toast.LENGTH_SHORT, AppNotice.Kind.Success).show()
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    EngineCard(EngineType.SYSTEMNNN.displayName) {
                        SwitchPreference(
                            title = stringResource(R.string.engine_settings_systemnnn_fullscreen),
                            checked = fullscreen,
                            onCheckedChange = { fullscreen = it },
                        )
                        DropdownRow(
                            label = stringResource(R.string.engine_settings_systemnnn_reading_size),
                            options = systemNnnReadingSizeChoices(),
                            current = readingSize.toString(),
                            onSelect = { readingSize = it.toInt() },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.engine_settings_systemnnn_exe_analysis),
                            summary = stringResource(R.string.engine_settings_systemnnn_exe_analysis_summary),
                            checked = exeAnalysis,
                            onCheckedChange = { exeAnalysis = it },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.engine_settings_systemnnn_debug_mode),
                            summary = stringResource(R.string.engine_settings_systemnnn_debug_mode_summary),
                            checked = debugMode,
                            onCheckedChange = { debugMode = it },
                        )
                        Text(
                            stringResource(R.string.engine_settings_systemnnn_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun systemNnnReadingSizeChoices(): List<Pair<String, String>> =
    EngineSettingsStore.SYSTEMNNN_READING_SIZES.map { size ->
        size.toString() to stringResource(R.string.engine_settings_systemnnn_reading_value, size)
    }

@Composable
internal fun SystemNnnSaveLayoutSettings(gameUri: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember(gameUri) { mutableStateOf<SystemNnnSaveLayout.Result?>(null) }
    var generating by remember(gameUri) { mutableStateOf(false) }
    var generationFailed by remember(gameUri) { mutableStateOf(false) }
    LaunchedEffect(gameUri) { result = SystemNnnSaveLayout.load(context, gameUri, regenerate = false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.engine_settings_systemnnn_layout), style = MaterialTheme.typography.titleMedium)
        val current = result
        BlurCrossfade(targetState = current, modifier = Modifier.fillMaxWidth()) { displayed ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SystemNnnSaveLayoutDetails(displayed)
            }
        }
        ExpandVisibility(visible = generationFailed) {
            Text(
                stringResource(R.string.engine_settings_systemnnn_layout_write_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        NoRippleButton(
            text = stringResource(
                if (generating) R.string.engine_settings_systemnnn_layout_generating
                else R.string.engine_settings_systemnnn_layout_generate,
            ),
            modifier = Modifier.fillMaxWidth(),
            enabled = current != null && current != SystemNnnSaveLayout.Result.Unavailable && !generating,
            onClick = {
                generating = true
                generationFailed = false
                scope.launch {
                    val updated = SystemNnnSaveLayout.load(context, gameUri, regenerate = true)
                    if (updated is SystemNnnSaveLayout.Result.Available) result = updated
                    else generationFailed = true
                    generating = false
                }
            },
        )
    }
}

@Composable
private fun SystemNnnSaveLayoutDetails(current: SystemNnnSaveLayout.Result?) {
    if (current is SystemNnnSaveLayout.Result.Available) {
        SelectionContainer {
            Text(current.path, style = MaterialTheme.typography.bodyMedium)
        }
        val exe = stringResource(R.string.engine_settings_systemnnn_exe_analysis)
        val files = stringResource(R.string.engine_settings_systemnnn_layout_fallback)
        Text(
            stringResource(
                R.string.engine_settings_systemnnn_layout_layers, current.layerRecords,
                if (current.executableLayout) exe else files,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(
                R.string.engine_settings_systemnnn_layout_dialogue, current.messageWidth,
                if (current.executableMessageWidth) exe else files,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(
                R.string.engine_settings_systemnnn_layout_story_state, current.statusBytes,
                if (current.executableStatusBytes) exe else files,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (current.systemSaveVersion == 0) stringResource(R.string.engine_settings_systemnnn_layout_system_pending)
            else stringResource(
                R.string.engine_settings_systemnnn_layout_system_version,
                current.systemSaveVersion, if (current.systemSaveFromExecutable) exe else files,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        val extStatus = when (current.extensionEnabled) {
            1 -> R.string.engine_settings_systemnnn_layout_ext_on
            0 -> R.string.engine_settings_systemnnn_layout_ext_off
            else -> R.string.engine_settings_systemnnn_layout_ext_pending
        }
        Text(
            stringResource(R.string.engine_settings_systemnnn_layout_ext_status, stringResource(extStatus)),
            style = MaterialTheme.typography.bodyMedium,
        )
        val okikae = if (current.okikae) R.string.engine_settings_systemnnn_layout_okikae_on
            else R.string.engine_settings_systemnnn_layout_okikae_off
        Text(
            stringResource(
                R.string.engine_settings_systemnnn_layout_replacements, stringResource(okikae),
                if (current.executableOkikae) exe else files,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        val extensionBlocks = when (current.extensionStartCode) {
            -1 -> stringResource(
                if (current.extensionEnabled == 1) R.string.engine_settings_systemnnn_layout_ext_unknown
                else R.string.engine_settings_systemnnn_layout_ext_pending,
            )
            0 -> stringResource(R.string.engine_settings_systemnnn_layout_ext_none)
            else -> {
                val unknown = stringResource(R.string.engine_settings_systemnnn_layout_ext_length_unknown)
                val separator = stringResource(R.string.engine_settings_systemnnn_layout_ext_separator)
                current.extensionSizes.ifEmpty { listOf(-1) }.mapIndexed { index, size ->
                    val length = if (size < 0) unknown
                    else stringResource(R.string.engine_settings_systemnnn_layout_ext_length, size)
                    stringResource(R.string.engine_settings_systemnnn_layout_ext_block, current.extensionStartCode + index, length)
                }.joinToString(separator)
            }
        }
        Text(
            stringResource(R.string.engine_settings_systemnnn_layout_ext, extensionBlocks),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (current.extensionStartCode == -1 && current.extensionEnabled == 1) {
            Text(
                stringResource(R.string.engine_settings_systemnnn_layout_ext_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (current.extensionStartCode > 0 && current.extensionSizes.isEmpty()) {
            Text(
                stringResource(R.string.engine_settings_systemnnn_layout_ext_refresh),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        val status = when (current) {
            null -> R.string.engine_settings_systemnnn_layout_loading
            SystemNnnSaveLayout.Result.Missing -> R.string.engine_settings_systemnnn_layout_missing
            else -> R.string.engine_settings_systemnnn_layout_unavailable
        }
        Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
    }
}
