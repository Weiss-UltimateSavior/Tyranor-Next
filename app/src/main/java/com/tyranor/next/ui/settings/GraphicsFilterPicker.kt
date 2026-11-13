package com.tyranor.next.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.settings.GraphicsFilterCategory
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance

private val FilterCategorySaver = Saver<GraphicsFilterCategory?, String>(
    save = { it?.name },
    restore = { name -> GraphicsFilterCategory.entries.firstOrNull { it.name == name } },
)

/** Two-level algorithm -> preset navigation, shared by global and per-game chain editors. */
@Composable
internal fun GraphicsFilterPicker(
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable(stateSaver = FilterCategorySaver) { mutableStateOf<GraphicsFilterCategory?>(null) }
    val labels = options.toMap()
    LaunchedEffect(enabled) { if (!enabled) { open = false; category = null } }
    // 此设置卡片入口局部隐藏左图标，与相邻设置项和滤镜节点保持 16dp 左对齐。
    // 继续复用公共导航组件并保留右箭头；不改变其他导航入口的图标约定。
    AppNavItem(
        title = stringResource(R.string.graphics_chain_add),
        showLeadingIcon = false,
        // 已在 EngineCard（MiuixCard）内：使用卡内平面样式，避免卡内再叠一层条目表面
        drawSurface = false,
        onClick = if (enabled) ({ category = null; open = true }) else null,
    )
    if (!open) return
    AppAlertDialog(
        onDismissRequest = { open = false; category = null },
        title = { Text(stringResource(R.string.graphics_chain_add_type), style = MaterialTheme.typography.titleMedium) },
        text = {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= (520 * fontScale).dp
                val selected = category
                val choosePreset: (String) -> Unit = { id ->
                    if (enabled && selected != null && id in selected.presetIds) { onSelect(id); open = false }
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterCategories(enabled, Modifier.weight(0.38f)) {
                            if (it.presetIds.size == 1) { onSelect(it.presetIds.single()); open = false } else category = it
                        }
                        // 右栏随所选算法模糊替换（空白 ↔ 预设列表 ↔ 另一组预设），停稳后与直接切换一致
                        BlurCrossfade(selected, Modifier.weight(0.62f), label = "graphicsPresetPane") { shown ->
                            if (shown != null) {
                                FilterPresets(shown, labels, enabled, Modifier.fillMaxWidth(), choosePreset)
                            } else {
                                Spacer(Modifier.fillMaxWidth())
                            }
                        }
                    }
                } else {
                    // 窄屏两级导航：分类 ↔ 预设整页模糊替换，弹窗高度随内容弹簧过渡（「普通」档瞬时切换）
                    BlurCrossfade(selected, label = "graphicsPickerLevel") { shown ->
                        if (shown == null) {
                            FilterCategories(enabled, Modifier.fillMaxWidth()) {
                                if (it.presetIds.size == 1) { onSelect(it.presetIds.single()); open = false } else category = it
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(graphicsFilterCategoryTitle(shown), style = MaterialTheme.typography.titleMedium)
                                FilterPresets(shown, labels, enabled, Modifier.weight(1f, fill = false), choosePreset)
                            }
                        }
                    }
                }
            }
        },
        buttonArrangement = if (category != null) Arrangement.SpaceBetween else Arrangement.End,
        dismissButton = if (category != null) ({
            DialogTextButton(stringResource(R.string.graphics_filter_back_categories), onClick = { category = null })
        }) else null,
        confirmButton = {
            DialogTextButton(stringResource(R.string.common_cancel), onClick = { open = false })
        },
    )
}

@Composable
private fun FilterCategories(enabled: Boolean, modifier: Modifier, onSelect: (GraphicsFilterCategory) -> Unit) {
    // 每次展示（打开弹窗 / 从预设返回）分类条目依次弹入；滚动进来的条目直接显示，拖动列表即收尾
    ProvideEntrance {
    LazyColumn(modifier.skipEntranceOnDrag(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(GraphicsFilterCategory.entries.toList(), key = { _, entry -> entry.name }) { index, category ->
            // 滤镜分类局部隐藏左图标，避免把算法类别误标为参数编辑。
            // 多预设仅用右箭头表示下一级；参数图标仍只属于已添加节点。
            AppNavItem(
                modifier = Modifier.staggeredEntrance(index, distance = 14.dp, stepMillis = 30L),
                title = graphicsFilterCategoryTitle(category),
                showLeadingIcon = false,
                showArrow = category.presetIds.size > 1,
                titleMaxLines = 2,
                indication = null,
                containerColor = DialogItemSurface,
                onClick = if (enabled) ({ onSelect(category) }) else null,
            )
        }
    }
    }
}

@Composable
private fun FilterPresets(
    category: GraphicsFilterCategory,
    labels: Map<String, String>,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    // 进入某一算法（或宽屏右栏换组）时预设条目依次弹入，与分类列表同一节奏
    ProvideEntrance {
    LazyColumn(modifier.skipEntranceOnDrag(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(category.presetIds, key = { _, id -> id }) { index, id ->
            // 预设点击即添加，属于动作项，沿用无图标、无跳转箭头的排版。
            AppNavItem(
                modifier = Modifier.staggeredEntrance(index, distance = 14.dp, stepMillis = 30L),
                title = labels.getValue(id),
                showLeadingIcon = false,
                showArrow = false,
                titleMaxLines = 3,
                indication = null,
                containerColor = DialogItemSurface,
                onClick = if (enabled) ({ onSelect(id) }) else null,
            )
        }
    }
    }
}
