package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.i18n.ProvideAppLocale
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.WithoutPressIndication
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 应用设置页 Activity：入口见设置页「应用设置」项。 */
class AppSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val darkMode = AppSettingsStore.isDarkEffective(this)
        enableEdgeToEdge(
            statusBarStyle = if (darkMode) androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkMode) androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            ProvideAppLocale {
                TyranorNextTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        WithoutPressIndication {
                            AppSettingsScreen()
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.page_slide_in_from_top, R.anim.page_slide_out_to_bottom)
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
    var showColorPicker by remember { mutableStateOf(false) }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                Column(modifier = Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)) {
                    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                stringResource(R.string.settings_app_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
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
                    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
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
                    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
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
                                items = themeModes.map { it.second },
                                selectedIndex = modeIndex,
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
                    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            SwitchPreference(
                                title = stringResource(R.string.settings_tone_switch),
                                checked = AppThemeColors.toneSwitchEnabled,
                                onCheckedChange = { checked ->
                                    AppSettingsStore.setToneSwitchEnabled(ctx, checked)
                                    AppThemeColors.refresh(ctx)
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            SwitchPreference(
                                title = stringResource(R.string.settings_liquid_glass_nav),
                                checked = AppSettingsStore.navStyleState.value == AppSettingsStore.NAV_STYLE_LIQUID_GLASS,
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

/** 列表底部占位：避让系统导航栏。 */
@Composable
private fun BottomInsetSpacer() {
    Box(Modifier.fillMaxWidth().height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
}
