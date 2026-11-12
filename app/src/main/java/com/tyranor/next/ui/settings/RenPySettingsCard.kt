package com.tyranor.next.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.ui.common.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Ren'Py 外置模块设置卡片（全局）。
 *
 * 仅承载插件 `RenPyConfigurationParser` 实际解析的字段（app.cheats + 8 个 renpy_*）；
 * 版本下拉仍在独立卡片，不在本卡片内。控件只更新本地状态，由外层顶部保存按钮统一落盘。
 * `renpy_dont_use_gl2` 与 JoiPlay 一致采用反选语义：UI 文案为“禁用模型渲染”，checked=!dontUseGl2。
 */
@Composable
internal fun RenPySettingsCard(
    settings: EngineSettingsStore.RenPy,
    onSettings: (EngineSettingsStore.RenPy) -> Unit,
) {
    var advancedExpanded by remember { mutableStateOf(false) }

    EngineCard(stringResource(R.string.engine_settings_renpy_card)) {
        RenPyGroupLabel(stringResource(R.string.engine_settings_renpy_group_performance))
        SwitchPreference(
            title = stringResource(R.string.engine_settings_renpy_hw_video),
            checked = settings.hwVideo,
            onCheckedChange = { onSettings(settings.copy(hwVideo = it)) },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_renpy_vsync),
            checked = settings.vsync,
            onCheckedChange = { onSettings(settings.copy(vsync = it)) },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_renpy_less_memory),
            checked = settings.lessMemory,
            onCheckedChange = { onSettings(settings.copy(lessMemory = it)) },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_renpy_dont_use_gl2),
            checked = !settings.dontUseGl2,
            onCheckedChange = { onSettings(settings.copy(dontUseGl2 = !it)) },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_renpy_phone_small_variant),
            checked = settings.phoneSmallVariant,
            onCheckedChange = { onSettings(settings.copy(phoneSmallVariant = it)) },
        )

        RenPyGroupLabel(stringResource(R.string.engine_settings_renpy_group_save))
        SwitchPreference(
            title = stringResource(R.string.engine_settings_renpy_autosave),
            checked = settings.autosave,
            onCheckedChange = { onSettings(settings.copy(autosave = it)) },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_renpy_less_updates),
            checked = settings.lessUpdates,
            onCheckedChange = { onSettings(settings.copy(lessUpdates = it)) },
        )

        ArrowPreference(
            title = stringResource(R.string.engine_settings_renpy_advanced),
            summary = stringResource(R.string.engine_settings_renpy_advanced_hint),
            onClick = { advancedExpanded = !advancedExpanded },
        )
        if (advancedExpanded) {
            SwitchPreference(
                title = stringResource(R.string.engine_settings_renpy_recompile),
                checked = settings.recompile,
                onCheckedChange = { onSettings(settings.copy(recompile = it)) },
            )
            SwitchPreference(
                title = stringResource(R.string.engine_settings_renpy_cheats),
                checked = settings.cheats,
                onCheckedChange = { onSettings(settings.copy(cheats = it)) },
            )
        }
    }
}

@Composable
private fun RenPyGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}
