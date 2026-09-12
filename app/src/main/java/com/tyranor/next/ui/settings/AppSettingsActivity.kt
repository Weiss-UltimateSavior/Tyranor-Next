package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 应用设置页 Activity：入口见设置页「应用设置」项。 */
class AppSettingsActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            AppSettingsScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, AppSettingsActivity::class.java)
    }
}

/** 应用设置页：色调轮盘、扫描深度与扫描目录管理、导航栏样式。 */
@Composable
internal fun AppSettingsScreen() {
    val ctx = LocalContext.current
    val navStyle by AppSettingsStore.navStyleState.collectAsState()
    val glass = AppThemeColors.isGlass
    var showColorPicker by remember { mutableStateOf(false) }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.settings_app_title),
                    background = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            var language by remember { mutableStateOf(AppSettingsStore.getLanguage(ctx)) }
                            val languageModes = listOf(
                                AppSettingsStore.LANGUAGE_ZH to stringResource(R.string.settings_language_zh),
                                AppSettingsStore.LANGUAGE_JA to stringResource(R.string.settings_language_ja),
                                AppSettingsStore.LANGUAGE_EN to stringResource(R.string.settings_language_en),
                                AppSettingsStore.LANGUAGE_SYSTEM to stringResource(R.string.settings_language_system),
                            )
                            val languageIndex = languageModes.indexOfFirst { it.first == language }
                                .let { if (it < 0) 0 else it }
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_language_title),
                                items = languageModes.map { it.second },
                                selectedIndex = languageIndex,
                                onSelectedIndexChange = { index ->
                                    languageModes.getOrNull(index)?.first?.let { mode ->
                                        language = mode
                                        AppSettingsStore.setLanguage(ctx, mode)
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ArrowPreference(
                                title = stringResource(R.string.settings_color_wheel),
                                startAction = {
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 6.dp)
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(AppThemeColors.primary),
                                    )
                                },
                                endActions = {
                                    Text(
                                        AppThemeColors.primary.toHex(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = { showColorPicker = true },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            // 外观风格：默认 / 玻璃（切换即时全 App 生效并持久化）
                            val appearanceModes = listOf(
                                AppSettingsStore.APPEARANCE_STYLE_DEFAULT to stringResource(R.string.settings_appearance_style_default),
                                AppSettingsStore.APPEARANCE_STYLE_GLASS to stringResource(R.string.settings_appearance_style_glass),
                            )
                            val appearanceIndex = if (glass) 1 else 0
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_appearance_style),
                                items = appearanceModes.map { it.second },
                                selectedIndex = appearanceIndex,
                                onSelectedIndexChange = { index ->
                                    appearanceModes.getOrNull(index)?.first?.let { style ->
                                        AppSettingsStore.setAppearanceStyle(ctx, style)
                                        AppThemeColors.refresh(ctx)
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            // 状态驱动选中项：跟随系统时系统深浅不变也不会漏刷新下拉展示
                            var themeMode by remember { mutableStateOf(AppSettingsStore.getThemeMode(ctx)) }
                            val themeModes = listOf(
                                AppSettingsStore.THEME_MODE_SYSTEM to stringResource(R.string.common_follow_system),
                                AppSettingsStore.THEME_MODE_LIGHT to stringResource(R.string.settings_theme_mode_light),
                                AppSettingsStore.THEME_MODE_DARK to stringResource(R.string.settings_theme_mode_dark),
                            )
                            val modeIndex = themeModes.indexOfFirst { it.first == themeMode }
                                .let { if (it < 0) 1 else it } // 未知存量值回退浅色
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_theme_mode),
                                summary = if (glass) stringResource(R.string.settings_disabled_in_glass_style) else null,
                                items = themeModes.map { it.second },
                                selectedIndex = modeIndex,
                                enabled = !glass,
                                onSelectedIndexChange = { index ->
                                    themeModes.getOrNull(index)?.first?.let { mode ->
                                        themeMode = mode
                                        AppSettingsStore.setThemeMode(ctx, mode)
                                        AppThemeColors.refresh(ctx)
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            SwitchPreference(
                                title = stringResource(R.string.settings_tone_switch),
                                summary = if (glass) stringResource(R.string.settings_disabled_in_glass_style) else null,
                                checked = AppThemeColors.toneSwitchEnabled,
                                enabled = !glass,
                                onCheckedChange = { checked ->
                                    AppSettingsStore.setToneSwitchEnabled(ctx, checked)
                                    AppThemeColors.refresh(ctx)
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            SwitchPreference(
                                title = stringResource(R.string.settings_liquid_glass_nav),
                                checked = navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS,
                                onCheckedChange = { checked ->
                                    AppSettingsStore.setNavStyle(
                                        ctx,
                                        if (checked) AppSettingsStore.NAV_STYLE_LIQUID_GLASS else AppSettingsStore.NAV_STYLE_DEFAULT,
                                    )
                                },
                            )
                        }
                    }
                }
                item { BottomInsetSpacer() }
            }
        }
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = AppThemeColors.primary,
            onConfirm = { newColor ->
                AppSettingsStore.setThemeColorHex(ctx, newColor.copy(alpha = 1f).toHex())
                AppThemeColors.refresh(ctx)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false },
        )
    }
}

/** 色调轮盘弹窗：内嵌 Miuix ColorPicker，确认后应用并持久化主题色。
 *  不允许透明色与黑白灰色（无色相），非法时禁用「确定」并提示。 */
@Composable
private fun ColorPickerDialog(
    initialColor: ComposeColor,
    onConfirm: (ComposeColor) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickerColor by remember { mutableStateOf(initialColor) }
    val invalid = pickerColor.isTransparentOrGray()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_color_wheel), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                ColorPicker(
                    color = pickerColor,
                    onColorChanged = { pickerColor = it },
                )
                if (invalid) {
                    Text(
                        stringResource(R.string.settings_invalid_theme_color),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = {
            TextButton(enabled = !invalid, onClick = { onConfirm(pickerColor) }) { Text(stringResource(R.string.common_confirm)) }
        },
    )
}

/** 透明（alpha < 1）或黑白灰（RGB 三通道差在阈值内，无色相）视为非法主题色。 */
private fun ComposeColor.isTransparentOrGray(): Boolean {
    if (alpha < 1f) return true
    val maxC = maxOf(red, green, blue)
    val minC = minOf(red, green, blue)
    return maxC - minC <= 0.02f
}

/** Compose Color → #RRGGBB（不含透明度，主题色始终不透明）。 */
private fun ComposeColor.toHex(): String {
    val argb = ((alpha * 255f).roundToInt() shl 24) or
        ((red * 255f).roundToInt() shl 16) or
        ((green * 255f).roundToInt() shl 8) or
        (blue * 255f).roundToInt()
    return String.format("#%06X", argb and 0xFFFFFF)
}
