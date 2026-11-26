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
import com.tyranor.next.core.engine.external.EasyRpgExternalEngineModule
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.Slider
import com.tyranor.next.ui.common.SliderValueHeader
import com.tyranor.next.ui.common.SwitchPreference
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.notice.AppNotice
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * EasyRPG 外置宿主全局设置：宿主安装状态 + 显示 / 帧率 / 音频 / 兼容性设置，
 * 由顶部保存按钮统一落盘；启动游戏时随 Intent extra 下发给宿主 APK。
 */
@Composable
internal fun EasyRpgSettingsScreen() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(EngineSettingsStore.loadEasyRpg(context)) }
    var moduleInstalled by remember {
        mutableStateOf(ExternalEngineLauncher.isPackageInstalled(context, EasyRpgExternalEngineModule))
    }

    // 从安装器返回后刷新宿主状态（与 Impacto / Ruffle 设置页一致）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        moduleInstalled = ExternalEngineLauncher.isPackageInstalled(context, EasyRpgExternalEngineModule)
    }

    MiuixSettingsTheme {
        Scaffold(
            modifier = Modifier.skipEntranceOnDrag(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.engine_settings_easyrpg_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.engine_settings_save_content_description),
                            MiuixTheme.colorScheme.primary,
                        ) {
                            EngineSettingsStore.saveEasyRpg(context, settings)
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
                    Box(Modifier.staggeredEntrance(0)) {
                        EngineCard(stringResource(R.string.engine_settings_easyrpg_card)) {
                            EasyRpgHostStatusRow(moduleInstalled)
                            ExpandVisibility(!moduleInstalled) {
                                NoRippleTextButton(
                                    text = stringResource(R.string.engine_settings_easyrpg_module_get),
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    onClick = {
                                        if (!ExternalEngineLauncher.openInstallPage(context, EasyRpgExternalEngineModule)) {
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
                        EngineCard(stringResource(R.string.engine_settings_easyrpg_card_display)) {
                            DropdownRow(
                                stringResource(R.string.engine_settings_easyrpg_scaling),
                                easyRpgScalingOptions(),
                                settings.scaling,
                            ) { settings = settings.copy(scaling = EngineSettingsStore.normalizeEasyRpgScaling(it)) }
                            DropdownRow(
                                stringResource(R.string.engine_settings_easyrpg_resolution),
                                easyRpgGameResolutionOptions(),
                                settings.gameResolution,
                            ) { settings = settings.copy(gameResolution = EngineSettingsStore.normalizeEasyRpgGameResolution(it)) }
                            EasyRpgSwitchRow(
                                stringResource(R.string.engine_settings_easyrpg_stretch),
                                settings.stretch,
                            ) { settings = settings.copy(stretch = it) }
                            EasyRpgSwitchRow(
                                stringResource(R.string.engine_settings_easyrpg_vsync),
                                settings.vsync,
                            ) { settings = settings.copy(vsync = it) }
                            EasyRpgIntSliderRow(
                                title = stringResource(R.string.engine_settings_easyrpg_fps_limit),
                                valueText = if (settings.fpsLimit == 0) {
                                    stringResource(R.string.engine_settings_easyrpg_fps_unlimited)
                                } else {
                                    stringResource(R.string.engine_settings_easyrpg_fps_value, settings.fpsLimit)
                                },
                                value = settings.fpsLimit,
                                range = EngineSettingsStore.EASYRPG_FPS_LIMIT_RANGE,
                                onValueChange = { settings = settings.copy(fpsLimit = it) },
                            )
                            EasyRpgSwitchRow(
                                stringResource(R.string.engine_settings_easyrpg_show_fps),
                                settings.showFps,
                            ) { settings = settings.copy(showFps = it) }
                        }
                    }
                }
                item {
                    Box(Modifier.staggeredEntrance(2)) {
                        EngineCard(stringResource(R.string.engine_settings_easyrpg_card_audio)) {
                            EasyRpgSwitchRow(
                                stringResource(R.string.engine_settings_easyrpg_disable_audio),
                                settings.disableAudio,
                            ) { settings = settings.copy(disableAudio = it) }
                            EasyRpgIntSliderRow(
                                title = stringResource(R.string.engine_settings_easyrpg_music_volume),
                                valueText = "${settings.musicVolume}%",
                                value = settings.musicVolume,
                                range = EngineSettingsStore.EASYRPG_VOLUME_RANGE,
                                onValueChange = { settings = settings.copy(musicVolume = it) },
                            )
                            EasyRpgIntSliderRow(
                                title = stringResource(R.string.engine_settings_easyrpg_sound_volume),
                                valueText = "${settings.soundVolume}%",
                                value = settings.soundVolume,
                                range = EngineSettingsStore.EASYRPG_VOLUME_RANGE,
                                onValueChange = { settings = settings.copy(soundVolume = it) },
                            )
                        }
                    }
                }
                item {
                    Box(Modifier.staggeredEntrance(3)) {
                        EngineCard(stringResource(R.string.engine_settings_easyrpg_card_compat)) {
                            DropdownRow(
                                stringResource(R.string.engine_settings_easyrpg_encoding),
                                easyRpgEncodingOptions(),
                                settings.encoding,
                            ) { settings = settings.copy(encoding = EngineSettingsStore.normalizeEasyRpgEncoding(it)) }
                            DropdownRow(
                                stringResource(R.string.engine_settings_easyrpg_patch_mode),
                                easyRpgPatchModeOptions(),
                                settings.patchMode,
                            ) { settings = settings.copy(patchMode = EngineSettingsStore.normalizeEasyRpgPatchMode(it)) }
                            EasyRpgSwitchRow(
                                stringResource(R.string.engine_settings_easyrpg_no_rtp),
                                settings.noRtp,
                            ) { settings = settings.copy(noRtp = it) }
                            EasyRpgSwitchRow(
                                stringResource(R.string.engine_settings_easyrpg_test_play),
                                settings.testPlay,
                            ) { settings = settings.copy(testPlay = it) }
                            Text(
                                stringResource(R.string.engine_settings_easyrpg_note),
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

@Composable
private fun EasyRpgHostStatusRow(installed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.engine_settings_easyrpg_module_status),
                style = MaterialTheme.typography.titleMedium,
            )
            BlurCrossfade(installed) { isInstalled ->
                Text(
                    stringResource(
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
                tint = if (isInstalled) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EasyRpgSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        modifier = Modifier.pressSquish(scale = PressSquishDefaults.RowScale),
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun EasyRpgIntSliderRow(
    title: String,
    valueText: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        SliderValueHeader(
            title = title,
            value = valueText,
            modifier = Modifier.fillMaxWidth(),
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
