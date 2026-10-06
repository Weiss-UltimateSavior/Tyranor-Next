package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.isSideRailLayout
import com.tyranor.next.ui.common.BottomInsetSpacer
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.AppearanceStyle
import com.tyranor.next.core.settings.HomeWebUrls
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.ui.common.AppAlertDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val engineTabs by AppSettingsStore.engineTabsState.collectAsState()
    val sideRailEnabled by AppSettingsStore.sideRailState.collectAsState()
    val hideCardTitleTag by AppSettingsStore.gameCardTitleTagState.collectAsState()
    val gameCardStyle by AppSettingsStore.gameCardStyleState.collectAsState()
    val gameCardBadge by AppSettingsStore.gameCardBadgeState.collectAsState()
    val defaultThemeGradient by AppSettingsStore.defaultThemeGradientState.collectAsState()
    val homeStyle by AppSettingsStore.homeStyleState.collectAsState()
    val homeWebUrl by AppSettingsStore.homeWebUrlState.collectAsState()
    val glass = AppThemeColors.isGlass
    // 平板/大窗口 + 侧边栏开关开启：导航以侧栏显示（液态玻璃两档不参与侧栏适配）
    val railLayout = isSideRailLayout()
    var showColorPicker by remember { mutableStateOf(false) }
    var showHomeWebUrlDialog by remember { mutableStateOf(false) }
    var showHomeWebCustomDialog by remember { mutableStateOf(false) }
    // 本页可能先于主界面被组合（进程重建后直接恢复到设置页）：主动加载一次持久化值，
    // 否则下拉/开关会显示成默认档（与磁盘上的真实取值不一致）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            AppSettingsStore.initNavStyle(ctx)
            AppSettingsStore.initSideRail(ctx)
            AppSettingsStore.initGameCardHideTitleTag(ctx)
            AppSettingsStore.initGameCardStyle(ctx)
            AppSettingsStore.initGameCardBadge(ctx)
            AppSettingsStore.initDefaultThemeGradient(ctx)
            AppSettingsStore.initHomeStyle(ctx)
        }
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.settings_app_title),
                    background = ComposeColor.Transparent,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                )
            },
        ) { innerPadding ->
            LazyColumn(
                // 顶栏透明：列表整体垫在顶栏下方（持久 padding），避免滚动时内容穿过顶栏
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
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
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
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
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            // 外观风格：默认 / 复古玻璃 / 高级玻璃（切换即时全 App 生效并持久化）
                            val appearanceStyles = AppearanceStyle.entries
                            val appearanceLabels = appearanceStyles.map { style ->
                                stringResource(
                                    when (style) {
                                        AppearanceStyle.DEFAULT -> R.string.settings_appearance_style_default
                                        AppearanceStyle.RETRO_GLASS -> R.string.settings_appearance_style_glass
                                        AppearanceStyle.ADVANCED_GLASS -> R.string.settings_appearance_style_advanced_glass
                                    },
                                )
                            }
                            val appearanceIndex = appearanceStyles
                                .indexOf(AppThemeColors.appearanceStyle)
                                .coerceAtLeast(0)
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_appearance_style),
                                summary = if (AppThemeColors.isAdvancedGlass) {
                                    stringResource(R.string.settings_appearance_style_advanced_glass_desc)
                                } else {
                                    null
                                },
                                items = appearanceLabels,
                                selectedIndex = appearanceIndex,
                                onSelectedIndexChange = { index ->
                                    appearanceStyles.getOrNull(index)?.let { style ->
                                        AppSettingsStore.setAppearanceStyle(ctx, style)
                                        AppThemeColors.refresh(ctx)
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
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
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
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
                            // 默认外观风格的页面背景：纯色 + 主题色/近似色渐变（关闭回退纯色）
                            SwitchPreference(
                                title = stringResource(R.string.settings_default_theme_gradient),
                                summary = if (glass) stringResource(R.string.settings_disabled_in_glass_style) else null,
                                checked = defaultThemeGradient,
                                enabled = !glass,
                                onCheckedChange = { checked ->
                                    AppSettingsStore.setDefaultThemeGradientEnabled(ctx, checked)
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            // 导航栏样式：默认 / 液态玻璃 · 经典 / 液态玻璃 · 透镜 三选一。
                            // 「透镜」档需要 Android 13+ 的 RuntimeShader，低版本不提供该选项（列表里不出现）。
                            val navStyleOptions = buildList {
                                add(
                                    AppSettingsStore.NAV_STYLE_DEFAULT to
                                        stringResource(R.string.settings_nav_bar_default),
                                )
                                add(
                                    AppSettingsStore.NAV_STYLE_LIQUID_GLASS to
                                        stringResource(R.string.settings_nav_bar_liquid_glass),
                                )
                                if (AppSettingsStore.supportsLiquidGlassEnhanced) {
                                    add(
                                        AppSettingsStore.NAV_STYLE_LIQUID_GLASS_ENHANCED to
                                            stringResource(R.string.settings_nav_bar_liquid_glass_enhanced),
                                    )
                                }
                                add(
                                    AppSettingsStore.NAV_STYLE_FLOATING_BUTTON to
                                        stringResource(R.string.settings_nav_bar_floating_button),
                                )
                            }
                            val navStyleIndex = navStyleOptions
                                .indexOfFirst { it.first == navStyle }
                                .coerceAtLeast(0)
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_nav_bar_title),
                                // 说明只保留「安卓版本提醒」：默认档无需提醒（任何版本可用）
                                summary = when {
                                    // 平板侧栏布局：液态玻璃两档不参与侧栏适配，提示实际以侧栏显示
                                    railLayout && navStyle != AppSettingsStore.NAV_STYLE_DEFAULT ->
                                        stringResource(R.string.settings_nav_bar_desc_rail)
                                    navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS ->
                                        stringResource(R.string.settings_nav_bar_desc_liquid_glass)
                                    navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS_ENHANCED ->
                                        stringResource(R.string.settings_nav_bar_desc_enhanced)
                                    navStyle == AppSettingsStore.NAV_STYLE_FLOATING_BUTTON ->
                                        stringResource(R.string.settings_nav_bar_desc_floating_button)
                                    else -> null
                                },
                                items = navStyleOptions.map { it.second },
                                selectedIndex = navStyleIndex,
                                onSelectedIndexChange = { index ->
                                    navStyleOptions.getOrNull(index)?.first?.let { style ->
                                        AppSettingsStore.setNavStyle(ctx, style)
                                    }
                                },
                            )
                            // 悬浮按钮支持长按拖动；拖动后可用此项一键回右下角
                            if (navStyle == AppSettingsStore.NAV_STYLE_FLOATING_BUTTON) {
                                ArrowPreference(
                                    title = stringResource(R.string.settings_nav_bar_reset_position),
                                    onClick = {
                                        AppSettingsStore.setFloatingNavPosition(ctx, 1f, 1f)
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            // 首页样式：默认（最近打开/快捷启动）/ 网页（内置 WebView 展示所选网址）/
                            // 分类仓库（分类栏 + 游戏页同款网格）
                            val homeStyles = listOf(
                                AppSettingsStore.HOME_STYLE_NATIVE to stringResource(R.string.settings_home_style_native),
                                AppSettingsStore.HOME_STYLE_WEB to stringResource(R.string.settings_home_style_web),
                                AppSettingsStore.HOME_STYLE_CATEGORY to stringResource(R.string.settings_home_style_category),
                            )
                            val homeStyleIndex = homeStyles.indexOfFirst { it.first == homeStyle }
                                .coerceAtLeast(0)
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_home_style),
                                items = homeStyles.map { it.second },
                                selectedIndex = homeStyleIndex,
                                onSelectedIndexChange = { index ->
                                    homeStyles.getOrNull(index)?.first?.let { style ->
                                        AppSettingsStore.setHomeStyle(ctx, style)
                                    }
                                },
                            )
                            // Web 首页地址：预设（鲲Gal / 一起萌）或自定义，点击弹出选择
                            ArrowPreference(
                                title = stringResource(R.string.settings_home_web_url),
                                endActions = {
                                    Text(
                                        homeWebUrl,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = { showHomeWebUrlDialog = true },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            // 平板侧边栏：开启时平板/大窗口下导航移到侧边，关闭则保持底部导航
                            SwitchPreference(
                                title = stringResource(R.string.settings_side_rail),
                                summary = stringResource(R.string.settings_side_rail_summary),
                                checked = sideRailEnabled,
                                onCheckedChange = { checked ->
                                    AppSettingsStore.setSideRailEnabled(ctx, checked)
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            SwitchPreference(
                                title = stringResource(R.string.settings_engine_tabs),
                                checked = engineTabs,
                                onCheckedChange = { checked ->
                                    AppSettingsStore.setEngineTabsEnabled(ctx, checked)
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            // 游戏页卡片风格：网格 / 列表（封面流）
                            val cardStyleModes = listOf(
                                AppSettingsStore.GAME_CARD_STYLE_GRID to stringResource(R.string.settings_game_card_style_grid),
                                AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW to stringResource(R.string.settings_game_card_style_cover_flow),
                            )
                            val cardStyleIndex = cardStyleModes.indexOfFirst { it.first == gameCardStyle }
                                .coerceAtLeast(0)
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_game_card_style),
                                items = cardStyleModes.map { it.second },
                                selectedIndex = cardStyleIndex,
                                onSelectedIndexChange = { index ->
                                    cardStyleModes.getOrNull(index)?.first?.let { style ->
                                        AppSettingsStore.setGameCardStyle(ctx, style)
                                    }
                                },
                            )
                            // 游戏页卡片名称隐藏【】/[] 标签（切换即时生效并持久化）
                            SwitchPreference(
                                title = stringResource(R.string.settings_game_card_hide_title_tag),
                                checked = hideCardTitleTag,
                                onCheckedChange = { checked ->
                                    AppSettingsStore.setGameCardHideTitleTag(ctx, checked)
                                },
                            )
                            // 游戏页卡片左上角引擎类型角标（默认关，切换即时生效并持久化）
                            SwitchPreference(
                                title = stringResource(R.string.settings_game_card_badge),
                                checked = gameCardBadge,
                                onCheckedChange = { checked ->
                                    AppSettingsStore.setGameCardBadgeEnabled(ctx, checked)
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

    if (showHomeWebUrlDialog) {
        HomeWebUrlDialog(
            currentUrl = homeWebUrl,
            onSelectPreset = { url ->
                AppSettingsStore.setHomeWebUrl(ctx, url)
                showHomeWebUrlDialog = false
            },
            onCustom = {
                showHomeWebUrlDialog = false
                showHomeWebCustomDialog = true
            },
            onDismiss = { showHomeWebUrlDialog = false },
        )
    }

    if (showHomeWebCustomDialog) {
        HomeWebCustomUrlDialog(
            // 当前值为预设时不预填（避免误认为已自定义）；自定义值才回显
            initialUrl = if (HomeWebUrls.isPreset(homeWebUrl)) "" else homeWebUrl,
            onConfirm = { url ->
                AppSettingsStore.setHomeWebUrl(ctx, url)
                showHomeWebCustomDialog = false
            },
            onDismiss = { showHomeWebCustomDialog = false },
        )
    }
}

/**
 * Web 首页地址选择弹窗：预设（鲲Gal / 一起萌）+ 自定义地址。
 * 选择预设立即保存；选择自定义进入输入弹窗。
 */
@Composable
private fun HomeWebUrlDialog(
    currentUrl: String,
    onSelectPreset: (String) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isCustom = !HomeWebUrls.isPreset(currentUrl)
    val currentMark = stringResource(R.string.settings_home_web_current)
    fun summaryOf(url: String): String = if (currentUrl == url) "$url · $currentMark" else url
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.settings_home_web_url),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 预设点击即保存（执行动作，无跳转箭头）；自定义进入下一级弹窗（保留箭头）
                AppNavItem(
                    title = stringResource(R.string.home_web_preset_kungal),
                    summary = summaryOf(AppSettingsStore.HOME_WEB_URL_KUNGAL),
                    leadingIcon = R.drawable.ic_web_link,
                    containerColor = DialogItemSurface,
                    showArrow = false,
                    indication = null,
                ) { onSelectPreset(AppSettingsStore.HOME_WEB_URL_KUNGAL) }
                AppNavItem(
                    title = stringResource(R.string.home_web_preset_letmoe),
                    summary = summaryOf(AppSettingsStore.HOME_WEB_URL_LETMOE),
                    leadingIcon = R.drawable.ic_web_link,
                    containerColor = DialogItemSurface,
                    showArrow = false,
                    indication = null,
                ) { onSelectPreset(AppSettingsStore.HOME_WEB_URL_LETMOE) }
                AppNavItem(
                    title = stringResource(R.string.settings_home_web_custom),
                    summary = if (isCustom) {
                        "$currentUrl · $currentMark"
                    } else {
                        stringResource(R.string.settings_home_web_custom_summary)
                    },
                    leadingIcon = R.drawable.ic_sheet_rename,
                    containerColor = DialogItemSurface,
                    indication = null,
                ) { onCustom() }
            }
        },
        confirmButton = {},
        dismissButton = {
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/** Web 首页自定义地址输入弹窗：仅接受 http/https，非法就地提示。 */
@Composable
private fun HomeWebCustomUrlDialog(
    initialUrl: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(initialUrl) }
    var invalid by remember { mutableStateOf(false) }
    val confirm = {
        val url = HomeWebUrls.normalize(input)
        if (url == null) invalid = true else onConfirm(url)
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.settings_home_web_custom),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 弹窗正文内的输入框禁用点击反馈（清空按钮走 LocalIndication）；
                // 语义图标用「重命名/编辑」，不用默认搜索图标
                CompositionLocalProvider(LocalIndication provides NoIndication) {
                    AppSearchField(
                        query = input,
                        onQueryChange = {
                            input = it
                            invalid = false
                        },
                        onSearch = confirm,
                        leadingIcon = painterResource(R.drawable.ic_sheet_rename),
                        iconContentDescription = stringResource(R.string.settings_home_web_custom),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (invalid) {
                    Text(
                        stringResource(R.string.settings_home_web_url_invalid),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            DialogTextButton(text = stringResource(R.string.common_confirm), onClick = confirm)
        },
        dismissButton = {
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
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
