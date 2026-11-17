package com.tyranor.next.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.tyranor.next.core.engine.external.ImpactoExternalEngineModule
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.SwitchPreference
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.notice.AppNotice
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MAGES. 引擎（impacto 宿主）全局设置：宿主安装状态 + 引擎在 Android 上有意义的用户配置项，
 * 由顶部保存按钮统一落盘；启动游戏时宿主把生效值合并进该游戏的 `userconfig.toml`。
 */
@Composable
internal fun ImpactoSettingsScreen() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(EngineSettingsStore.loadImpacto(context)) }
    var moduleInstalled by remember {
        mutableStateOf(ExternalEngineLauncher.isPackageInstalled(context, ImpactoExternalEngineModule))
    }

    // 从安装器返回后刷新宿主状态（与 Ruffle / RPGM 设置页一致）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        moduleInstalled = ExternalEngineLauncher.isPackageInstalled(context, ImpactoExternalEngineModule)
    }

    MiuixSettingsTheme {
        Scaffold(
            modifier = Modifier.skipEntranceOnDrag(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.engine_settings_impacto_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.engine_settings_save_content_description),
                            MiuixTheme.colorScheme.primary,
                        ) {
                            EngineSettingsStore.saveImpacto(context, settings)
                            AppNotice.makeText(context, R.string.engine_settings_saved, Toast.LENGTH_SHORT).show()
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
                    // 设置卡片随页面入场弹入（与其他引擎设置页的卡片同一节奏）
                    Box(Modifier.staggeredEntrance(0)) {
                        EngineCard(stringResource(R.string.engine_settings_impacto_card)) {
                            HostStatusRow(moduleInstalled)
                            ExpandVisibility(!moduleInstalled) {
                                NoRippleTextButton(
                                    text = stringResource(R.string.engine_settings_impacto_module_get),
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    onClick = {
                                        if (!ExternalEngineLauncher.openInstallPage(context, ImpactoExternalEngineModule)) {
                                            AppNotice.makeText(
                                                context,
                                                R.string.engine_open_download_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    Box(Modifier.staggeredEntrance(1)) {
                        EngineCard(stringResource(R.string.engine_settings_impacto_card_play)) {
                            ImpactoSwitchRow(
                                stringResource(R.string.engine_settings_impacto_swap_confirm_cancel),
                                settings.swapConfirmCancel,
                            ) { settings = settings.copy(swapConfirmCancel = it) }
                            ImpactoSwitchRow(
                                stringResource(R.string.engine_settings_impacto_vsync),
                                settings.vsync,
                            ) { settings = settings.copy(vsync = it) }
                        }
                    }
                }
                item {
                    Box(Modifier.staggeredEntrance(2)) {
                        EngineCard(stringResource(R.string.engine_settings_impacto_card_video)) {
                            ImpactoSwitchRow(
                                stringResource(R.string.engine_settings_impacto_video_playback),
                                settings.videoPlayback,
                            ) { settings = settings.copy(videoPlayback = it) }
                            DropdownRow(
                                stringResource(R.string.engine_settings_impacto_subtitles),
                                impactoSubtitlesOptions(),
                                settings.subtitles,
                            ) { settings = settings.copy(subtitles = EngineSettingsStore.normalizeImpactoSubtitles(it)) }
                        }
                    }
                }
                item {
                    Box(Modifier.staggeredEntrance(3)) {
                        EngineCard(stringResource(R.string.engine_settings_impacto_card_other)) {
                            ImpactoSwitchRow(
                                stringResource(R.string.engine_settings_impacto_close_backlog),
                                settings.closeBacklogAtEnd,
                            ) { settings = settings.copy(closeBacklogAtEnd = it) }
                            ImpactoSwitchRow(
                                stringResource(R.string.engine_settings_impacto_delusion_mouse),
                                settings.delusionMouse,
                            ) { settings = settings.copy(delusionMouse = it) }
                            DropdownRow(
                                stringResource(R.string.engine_settings_impacto_log_level),
                                impactoLogLevelOptions(),
                                settings.logLevel,
                            ) { settings = settings.copy(logLevel = EngineSettingsStore.normalizeImpactoLogLevel(it)) }
                            Text(
                                stringResource(R.string.engine_settings_impacto_note),
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
}

/** 宿主安装状态行：文字与图标随状态模糊过渡（与 Ruffle 设置页同一写法）。 */
@Composable
private fun HostStatusRow(installed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.engine_settings_impacto_module_status),
                style = MaterialTheme.typography.titleMedium,
            )
            BlurCrossfade(installed) { isInstalled ->
                Text(
                    stringResource(
                        // 版本过旧的宿主也算“未就绪”，与引擎页一致提示「安装或更新」
                        if (isInstalled) R.string.engine_module_installed else R.string.engine_module_install_or_update,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        BlurCrossfade(installed) { isInstalled ->
            Icon(
                imageVector = if (isInstalled) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ImpactoSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreference(
        modifier = Modifier.pressSquish(scale = PressSquishDefaults.RowScale),
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
