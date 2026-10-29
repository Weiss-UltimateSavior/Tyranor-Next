package com.tyranor.next.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tyranor.next.R
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.RuffleExternalEngineModule
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.TopBarIcon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Ruffle 外置模块全局设置：模块安装状态 + SWF 渲染参数，由顶部保存按钮统一落盘。 */
@Composable
internal fun RuffleSettingsScreen() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(EngineSettingsStore.loadRuffle(context)) }
    var moduleInstalled by remember {
        mutableStateOf(ExternalEngineLauncher.isPackageInstalled(context, RuffleExternalEngineModule))
    }

    // 从安装器返回后刷新模块状态（与 RPGM 设置卡片一致）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        moduleInstalled = ExternalEngineLauncher.isPackageInstalled(context, RuffleExternalEngineModule)
    }

    MiuixSettingsTheme {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.engine_settings_ruffle_title),
                    background = Color.Transparent,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.engine_settings_save_content_description),
                            MiuixTheme.colorScheme.primary,
                        ) {
                            EngineSettingsStore.saveRuffle(context, settings)
                            Toast.makeText(context, R.string.engine_settings_saved, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding()
                    .padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    EngineCard(stringResource(R.string.engine_settings_ruffle_card)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.engine_settings_ruffle_module_status),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(
                                        if (moduleInstalled) {
                                            R.string.engine_module_installed
                                        } else {
                                            R.string.engine_module_not_installed
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Icon(
                                imageVector = if (moduleInstalled) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = if (moduleInstalled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        if (!moduleInstalled) {
                            NoRippleTextButton(
                                text = stringResource(R.string.engine_settings_ruffle_module_get),
                                modifier = Modifier.padding(horizontal = 8.dp),
                                onClick = {
                                    if (!ExternalEngineLauncher.openInstallPage(context, RuffleExternalEngineModule)) {
                                        Toast.makeText(
                                            context,
                                            R.string.engine_open_download_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                        DropdownRow(
                            stringResource(R.string.engine_settings_ruffle_backend),
                            ruffleBackendOptions(),
                            settings.backend,
                        ) { settings = settings.copy(backend = it) }
                        DropdownRow(
                            stringResource(R.string.engine_settings_ruffle_quality),
                            ruffleQualityOptions(),
                            settings.quality,
                        ) { settings = settings.copy(quality = it) }
                        DropdownRow(
                            stringResource(R.string.engine_settings_ruffle_scale_mode),
                            ruffleScaleModeOptions(),
                            settings.scaleMode,
                        ) { settings = settings.copy(scaleMode = it) }
                        SwitchPreference(
                            title = stringResource(R.string.engine_settings_ruffle_letterbox),
                            checked = EngineSettingsStore.normalizeRuffleLetterbox(settings.letterbox) ==
                                EngineSettingsStore.RUFFLE_LETTERBOX_ON,
                            onCheckedChange = { enabled ->
                                settings = settings.copy(
                                    letterbox = if (enabled) {
                                        EngineSettingsStore.RUFFLE_LETTERBOX_ON
                                    } else {
                                        "off"
                                    },
                                )
                            },
                        )
                        DropdownRow(
                            stringResource(R.string.engine_settings_ruffle_load_behavior),
                            ruffleLoadBehaviorOptions(),
                            settings.loadBehavior,
                        ) { settings = settings.copy(loadBehavior = it) }
                        DropdownRow(
                            stringResource(R.string.engine_settings_ruffle_screen_timeout),
                            ruffleScreenTimeoutOptions(),
                            settings.screenTimeout.toString(),
                        ) {
                            settings = settings.copy(
                                screenTimeout = EngineSettingsStore.normalizeRuffleScreenTimeout(it.toIntOrNull()),
                            )
                        }
                        Text(
                            stringResource(R.string.engine_settings_ruffle_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
