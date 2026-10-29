package com.tyranor.next.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppBottomSheet
import com.tyranor.next.ui.common.appSheetMaxHeight

/**
 * 游戏页「筛选与排序」底部抽屉（issue #118）。
 *
 * 版式：标题行（含重置）→ 排序方式（2×2 选择卡）→ 引擎（横滚标签栏）→ 游玩状态（三段标签栏）。
 * 所有条件都写进 [AppSettingsStore]，游戏页从内存态订阅，关掉抽屉即生效且跨进程保留。
 *
 * 视觉约定：抽屉面板是页面灰底，卡片一律 [NavWhite]（玻璃风格自动换成玻璃面），
 * 选中态用主题色描边 + 淡主题色底，与设置页的选中卡片同一套语汇。
 *
 * @param sortMode 当前排序模式（`AppSettingsStore.GAME_SORT_*`）
 * @param engineFilter 引擎过滤（`EngineType.name`，空串 = 全部）
 * @param playedFilter 游玩状态过滤（`AppSettingsStore.GAME_FILTER_PLAYED_*`）
 * @param availableEngines 库中实际存在的引擎（调用方去重并按枚举顺序排好）
 */
@Composable
internal fun GameFilterSheet(
    sortMode: String,
    engineFilter: String,
    playedFilter: String,
    availableEngines: List<EngineType>,
    onSortChange: (String) -> Unit,
    onEngineChange: (String) -> Unit,
    onPlayedChange: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sortOptions = listOf(
        AppSettingsStore.GAME_SORT_ALPHA to stringResource(R.string.settings_sort_alpha),
        AppSettingsStore.GAME_SORT_BRACKET_TAG to stringResource(R.string.settings_sort_bracket_tag),
        AppSettingsStore.GAME_SORT_ENGINE to stringResource(R.string.game_sort_engine),
        AppSettingsStore.GAME_SORT_RECENT to stringResource(R.string.game_sort_recent),
    )
    val playedOptions = listOf(
        AppSettingsStore.GAME_FILTER_PLAYED_ALL to stringResource(R.string.game_filter_all),
        AppSettingsStore.GAME_FILTER_PLAYED_ONLY to stringResource(R.string.game_filter_played_only),
        AppSettingsStore.GAME_FILTER_PLAYED_NONE to stringResource(R.string.game_filter_played_none),
    )
    val engineAllLabel = stringResource(R.string.game_filter_all)
    // 引擎名与首页分类/卡片角标同一套口径：UNKNOWN 走本地化文案，其余用品牌名
    val unknownEngineLabel = stringResource(R.string.engine_name_unknown)

    AppBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = appSheetMaxHeight())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.game_filter_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                // 抽屉内按钮走常规点击反馈（DialogTextButton 的 indication = null 只属于 AppAlertDialog）；
                // 文字仍用 bodyMedium，守住两档排版。minimumInteractiveComponentSize 把触控区补到 48dp
                Text(
                    stringResource(R.string.game_filter_reset),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(AppComponentShape)
                        .clickable(onClick = onReset)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            FilterSectionTitle(stringResource(R.string.game_filter_sort_section))
            // 2×2 选择卡：比 4 行单选紧凑，选中态一眼可见（主题色描边 + 淡底 + 加粗）
            // selectableGroup 必须包住两行：四个选项共用一个选中状态，TalkBack 才会读成
            // 「单选组，第 1 项，共 4 项」；逐行加会退化成两个各含两项的组。
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sortOptions.chunked(2).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowOptions.forEach { (mode, label) ->
                            FilterChoiceCard(
                                label = label,
                                selected = sortMode == mode,
                                onClick = { onSortChange(mode) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 奇数个时补一个等宽占位，避免最后一张卡被拉宽
                        if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            FilterSectionTitle(stringResource(R.string.game_filter_engine_section))
            if (availableEngines.isEmpty()) {
                Text(
                    stringResource(R.string.game_filter_engine_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                // 与排序卡同一套配色语汇：选中 = 淡主题色底 + 主题色描边 + 主题色粗体。
                // 引擎数量不定，单行横向划屏（不换行，避免把抽屉撑高）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChipCard(
                        label = engineAllLabel,
                        selected = engineFilter.isEmpty(),
                        onClick = { onEngineChange("") },
                    )
                    availableEngines.forEach { engine ->
                        FilterChipCard(
                            label = if (engine == EngineType.UNKNOWN) unknownEngineLabel else engine.displayName,
                            selected = engineFilter == engine.name,
                            onClick = { onEngineChange(engine.name) },
                        )
                    }
                }
            }

            FilterSectionTitle(stringResource(R.string.game_filter_played_section))
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                playedOptions.forEach { (mode, label) ->
                    FilterChoiceCard(
                        label = label,
                        selected = playedFilter == mode,
                        onClick = { onPlayedChange(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 抽屉内的小节标题：用 titleMedium（卡片头一档），比纯灰文字更有层次。 */
@Composable
private fun FilterSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/**
 * 单选卡：白卡底 + 圆角 [AppComponentShape]；选中时淡主题色底、主题色描边与文字加粗。
 * 整卡可点，语义为 radio。
 */
@Composable
private fun FilterChoiceCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChoiceBase(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier.height(46.dp),
        horizontalPadding = 12.dp,
    )
}

/** 与 [FilterChoiceCard] 同一套配色的自适应宽度版本（引擎这类数量不定的选项），未选中为空心标签。 */
@Composable
private fun FilterChipCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChoiceBase(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        horizontalPadding = 14.dp,
        hollow = true,
    )
}

/**
 * 选择控件的公共外观。
 *
 * - 选中：淡主题色底 + 主题色描边 + 主题色粗体（三段统一）；
 * - [hollow] = true 且未选中：**空心标签**（透明底 + 1dp outline；玻璃风格由 `glassBorder()`
 *   画 0.5dp 发丝边），用于横向划屏的引擎行，避免一排实心白块压过排序卡；
 * - 否则：白卡底（抽屉面板是页面灰底，白卡形成反差）+ 玻璃风格的发丝边。
 */
@Composable
private fun FilterChoiceBase(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    horizontalPadding: Dp,
    hollow: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .clip(AppComponentShape)
            .background(
                when {
                    selected -> accent.copy(alpha = 0.12f)
                    hollow -> Color.Transparent
                    else -> NavWhite
                },
            )
            // 玻璃风格下未选中卡片与抽屉面板几乎同色，必须补 0.5dp 发丝描边（AGENTS 3.5，
            // 高级玻璃还带顶边高光）；默认风格自动 no-op
            .glassBorder(AppComponentShape)
            .then(
                when {
                    selected -> Modifier.border(1.dp, accent, AppComponentShape)
                    // 玻璃系已由 glassBorder 画过发丝边，不再叠加；默认风格的空心标签仍需 outline
                    hollow && !AppThemeColors.isGlass -> Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        AppComponentShape,
                    )
                    else -> Modifier
                },
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) accent else TextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
