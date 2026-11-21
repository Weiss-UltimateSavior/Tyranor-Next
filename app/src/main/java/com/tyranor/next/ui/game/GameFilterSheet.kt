package com.tyranor.next.ui.game

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.SheetSecondaryTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppBottomSheet
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.Slider
import com.tyranor.next.ui.common.SliderValueHeader
import com.tyranor.next.ui.common.SlidingTabRow
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.rememberLastNonNull
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 游戏页「筛选与排序」抽屉。
 *
 * 外壳复用 [AppBottomSheet]；引擎类型复用 [SlidingTabRow] 横向选择，
 * 排序和游玩状态使用 Miuix 的窗口下拉选择项（抽屉是独立窗口）。
 *
 * 「重置」恢复字母序、全部引擎和全部状态。短窗中跟随内容滚动，其余时候固定在底部。
 * 网格与瀑布的当前方向列数独立设置，重置时保留。
 *
 * @param sortMode 当前排序模式（`AppSettingsStore.GAME_SORT_*`）
 * @param engineFilter 引擎过滤（`EngineType.name`；[AppSettingsStore.GAME_FILTER_ENGINE_ALL] = 全部）
 * @param playedFilter 游玩状态过滤（`AppSettingsStore.GAME_FILTER_PLAYED_*`）
 * @param availableEngines 库中实际存在的引擎（调用方去重并按枚举顺序排好）
 * @param searchQuery 当前搜索词：非空时提示「重置不清搜索词」
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun GameFilterSheet(
    sortMode: String,
    engineFilter: String,
    playedFilter: String,
    availableEngines: List<EngineType>,
    searchQuery: String,
    onSortChange: (String) -> Unit,
    onEngineChange: (String) -> Unit,
    onPlayedChange: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allLabel = stringResource(R.string.game_filter_all)
    val sortModes = listOf(
        AppSettingsStore.GAME_SORT_ALPHA to stringResource(R.string.settings_sort_alpha),
        AppSettingsStore.GAME_SORT_BRACKET_TAG to stringResource(R.string.settings_sort_bracket_tag),
        AppSettingsStore.GAME_SORT_ENGINE to stringResource(R.string.game_sort_engine),
        AppSettingsStore.GAME_SORT_RECENT to stringResource(R.string.game_sort_recent),
    )
    val sortIndex = sortModes.indexOfFirst { it.first == sortMode }.coerceAtLeast(0)
    val playedOptions = listOf(
        AppSettingsStore.GAME_FILTER_PLAYED_ALL to allLabel,
        AppSettingsStore.GAME_FILTER_PLAYED_ONLY to stringResource(R.string.game_filter_played_only),
        AppSettingsStore.GAME_FILTER_PLAYED_NONE to stringResource(R.string.game_filter_played_none),
    )
    val playedIndex = playedOptions.indexOfFirst { it.first == playedFilter }.coerceAtLeast(0)
    // 引擎名与卡片角标/首页分类同一套口径：UNKNOWN 与 RUNTIME_MODULE 走本地化文案，其余用品牌名。
    // 删除尚未确认时库列表可能暂时缺少当前引擎，抽屉仍需显示真实选中项。
    val activeEngine = EngineType.entries.firstOrNull { it.name == engineFilter }
    val engineChoices = if (activeEngine != null && activeEngine !in availableEngines) {
        (availableEngines + activeEngine).sortedBy { it.ordinal }
    } else {
        availableEngines
    }
    val unknownEngineLabel = stringResource(R.string.engine_name_unknown)
    val runtimeModuleLabel = stringResource(R.string.engine_name_runtime_module)
    val engineKeys = listOf(AppSettingsStore.GAME_FILTER_ENGINE_ALL) + engineChoices.map { it.name }
    val engineLabels = listOf(allLabel) + engineChoices.map { engine ->
        when (engine) {
            EngineType.UNKNOWN -> unknownEngineLabel
            EngineType.RUNTIME_MODULE -> runtimeModuleLabel
            else -> engine.displayName
        }
    }
    val engineIndex = engineKeys.indexOfFirst { it == engineFilter }.coerceAtLeast(0)
    var scrollResetKey by remember { mutableIntStateOf(0) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val reset: () -> Unit = {
        onReset()
        scrollResetKey++
    }
    val cardColor = if (AppThemeColors.isMilkGlass) DialogItemSurface else NavWhite

    AppBottomSheet(
        onDismissRequest = onDismiss,
        confirmValueChange = { target -> target != SheetValue.Hidden || !dropdownExpanded },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            val pinReset = maxHeight >= 340.dp
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.game_filter_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = FilterSheetInset).padding(top = 8.dp),
                        )
                        SlidingTabRow(
                            tabs = engineLabels,
                            selectedIndex = engineIndex,
                            onTabSelected = { onEngineChange(engineKeys[it]) },
                            modifier = Modifier.padding(horizontal = FilterScrollableInset),
                            scrollable = true,
                            scrollResetKey = scrollResetKey,
                        )
                        MiuixSettingsTheme {
                            // Miuix 下拉选项默认 body1=16sp；本抽屉按页面两档制收为 15sp。
                            MiuixTheme(textStyles = MiuixTheme.textStyles.copy(
                                body1 = TextStyle(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                            )) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = FilterSheetInset),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    MiuixCard(
                                        modifier = Modifier.fillMaxWidth().glassBorder(),
                                        cornerRadius = AppComponentCornerRadius,
                                        colors = CardDefaults.defaultColors(color = cardColor),
                                    ) {
                                        OverlayDropdownPreference(
                                            title = stringResource(R.string.game_filter_sort_section),
                                            items = sortModes.map { it.second },
                                            selectedIndex = sortIndex,
                                            onExpandedChange = { dropdownExpanded = it },
                                            onSelectedIndexChange = { sortModes.getOrNull(it)?.first?.let(onSortChange) },
                                        )
                                    }
                                    MiuixCard(
                                        modifier = Modifier.fillMaxWidth().glassBorder(),
                                        cornerRadius = AppComponentCornerRadius,
                                        colors = CardDefaults.defaultColors(color = cardColor),
                                    ) {
                                        OverlayDropdownPreference(
                                            title = stringResource(R.string.game_filter_played_section),
                                            items = playedOptions.map { it.second },
                                            selectedIndex = playedIndex,
                                            onExpandedChange = { dropdownExpanded = it },
                                            onSelectedIndexChange = { playedOptions.getOrNull(it)?.first?.let(onPlayedChange) },
                                        )
                                    }
                                    GameCardColumnsControl(cardColor)
                                }
                            }
                        }
                        if (searchQuery.isNotBlank()) {
                            Text(
                                stringResource(R.string.game_filter_reset_keeps_search),
                                style = MaterialTheme.typography.bodyMedium,
                                color = SheetSecondaryTextColor,
                                modifier = Modifier.padding(horizontal = FilterSheetInset),
                            )
                        }
                    }
                    if (!pinReset) FilterResetButton(reset)
                }
                if (pinReset) FilterResetButton(reset)
            }
        }
    }
}

private val FilterSheetInset = 16.dp
// SlidingTabRow 自带 12dp 内容留白，首尾标签和抽屉条目共用 16dp 对齐线。
private val FilterScrollableInset = 4.dp

private enum class GameCardColumnsSetting(
    val columns: StateFlow<Int>,
    val write: (Context, Int) -> Unit,
    val title: Int,
) {
    GRID_PORTRAIT(
        AppSettingsStore.gameGridPortraitColumnsState,
        AppSettingsStore::setGameGridPortraitColumns,
        R.string.settings_game_card_portrait_columns,
    ),
    GRID_LANDSCAPE(
        AppSettingsStore.gameGridLandscapeColumnsState,
        AppSettingsStore::setGameGridLandscapeColumns,
        R.string.settings_game_card_landscape_columns,
    ),
    WATERFALL_PORTRAIT(
        AppSettingsStore.gameWaterfallPortraitColumnsState,
        AppSettingsStore::setGameWaterfallPortraitColumns,
        R.string.settings_game_card_portrait_columns,
    ),
    WATERFALL_LANDSCAPE(
        AppSettingsStore.gameWaterfallLandscapeColumnsState,
        AppSettingsStore::setGameWaterfallLandscapeColumns,
        R.string.settings_game_card_landscape_columns,
    ),
}

private data class GameCardColumnsSnapshot(val setting: GameCardColumnsSetting, val columns: Int)

@Composable
private fun GameCardColumnsControl(cardColor: Color) {
    val context = LocalContext.current
    val cardStyle by AppSettingsStore.gameCardStyleState.collectSettingAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val setting = when (cardStyle) {
        AppSettingsStore.GAME_CARD_STYLE_GRID ->
            if (landscape) GameCardColumnsSetting.GRID_LANDSCAPE else GameCardColumnsSetting.GRID_PORTRAIT
        AppSettingsStore.GAME_CARD_STYLE_WATERFALL ->
            if (landscape) GameCardColumnsSetting.WATERFALL_LANDSCAPE else GameCardColumnsSetting.WATERFALL_PORTRAIT
        else -> null
    }
    // 预订阅每组列数，旋转时标题和读数由同一组状态同步切换。
    val values = GameCardColumnsSetting.entries.associateWith { it.columns.collectSettingAsState() }
    val snapshot = setting?.let { GameCardColumnsSnapshot(it, values.getValue(it).value) }
    val lastSnapshot = rememberLastNonNull(snapshot)
    val activeSetting by rememberUpdatedState(setting)
    ExpandVisibility(visible = snapshot != null) {
        if (lastSnapshot != null) {
            BlurCrossfade(targetState = lastSnapshot, contentKey = { it.setting }) { shown ->
                val enabled = shown.setting == activeSetting
                GameCardColumnsCard(
                    snapshot = shown,
                    enabled = enabled,
                    cardColor = cardColor,
                    onColumnsChange = { columns ->
                        if (shown.setting == activeSetting && columns != shown.setting.columns.value) {
                            shown.setting.write(context, columns)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GameCardColumnsCard(
    snapshot: GameCardColumnsSnapshot,
    enabled: Boolean,
    cardColor: Color,
    onColumnsChange: (Int) -> Unit,
) {
    val title = stringResource(snapshot.setting.title)
    // 设置项、外部值或可用状态变化时，丢弃尚未提交的草稿。
    var draftColumns by remember(snapshot.setting, snapshot.columns, enabled) { mutableIntStateOf(snapshot.columns) }
    val finishChange: () -> Unit = {
        if (enabled) onColumnsChange(draftColumns)
    }
    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassBorder().then(
            if (enabled) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() },
        ),
        cornerRadius = AppComponentCornerRadius,
        colors = CardDefaults.defaultColors(color = cardColor),
    ) {
        Column(Modifier.padding(horizontal = FilterSheetInset)) {
            SliderValueHeader(
                title = title,
                value = stringResource(R.string.settings_game_card_columns_value, draftColumns),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Slider(
                value = draftColumns.toFloat(),
                onValueChange = { raw ->
                    if (enabled && raw.isFinite()) {
                        draftColumns = AppSettingsStore.normalizeGameCardColumns(raw.roundToInt())
                    }
                },
                onValueChangeFinished = finishChange,
                onValueChangeCancelled = { draftColumns = snapshot.columns },
                modifier = Modifier.padding(vertical = 4.dp)
                    .semantics { contentDescription = title }
                    .onPreviewKeyEvent { event ->
                        if (!enabled) return@onPreviewKeyEvent false
                        val next = when (event.key) {
                            Key.DirectionRight -> draftColumns + if (rtl) -1 else 1
                            Key.DirectionLeft -> draftColumns + if (rtl) 1 else -1
                            Key.DirectionUp -> draftColumns + 1
                            Key.DirectionDown -> draftColumns - 1
                            Key.MoveHome -> AppSettingsStore.MIN_GAME_CARD_COLUMNS
                            Key.MoveEnd -> AppSettingsStore.MAX_GAME_CARD_COLUMNS
                            else -> return@onPreviewKeyEvent false
                        }
                        when (event.type) {
                            KeyEventType.KeyDown -> draftColumns = AppSettingsStore.normalizeGameCardColumns(next)
                            KeyEventType.KeyUp -> finishChange()
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    }
                    .focusable(enabled),
                enabled = enabled,
                valueRange = AppSettingsStore.MIN_GAME_CARD_COLUMNS.toFloat()..AppSettingsStore.MAX_GAME_CARD_COLUMNS.toFloat(),
                steps = AppSettingsStore.MAX_GAME_CARD_COLUMNS - AppSettingsStore.MIN_GAME_CARD_COLUMNS - 1,
                showKeyPoints = true,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
        }
    }
}

@Composable
private fun FilterResetButton(onReset: () -> Unit) {
    NoRippleButton(
        text = stringResource(R.string.game_filter_reset),
        onClick = onReset,
        modifier = Modifier.fillMaxWidth()
            .padding(start = FilterSheetInset, top = 18.dp, end = FilterSheetInset, bottom = 22.dp),
    )
}
