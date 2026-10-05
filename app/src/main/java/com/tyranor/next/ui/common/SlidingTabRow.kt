package com.tyranor.next.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AdvancedGlassSurfaceHigh
import com.tyranor.next.theme.AdvancedGlassTextSecondary
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassSurfaceHigh
import com.tyranor.next.theme.GlassText
import com.tyranor.next.theme.GlassTextSecondary
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder

/** 标签高度（两形态统一）。 */
private val SlidingTabHeight = 42.dp

/** 等分宽度形态的标签间距。 */
private val FixedTabSpacing = 6.dp

/** 内容宽度横向滚动形态的标签间距。 */
private val ScrollableTabSpacing = 8.dp

/** 滚动态两端留白：滚动到边缘时标签不贴屏幕边。 */
private val ScrollableTabContentPadding = 12.dp

/** 指示器滑动时长（与页面 Tab 切换动画同一时长的线性运动）。 */
private const val IndicatorDurationMillis = 200

/**
 * 标签栏统一组件（AGENT.md「标签栏统一规范」的唯一入口）。
 *
 * 选中项是**独立背景指示器**（不是逐项背景），切换时指示器以 200ms 线性动画滑到目标标签，
 * 宽度同步动画（滚动态标签宽度不齐时宽度也平滑过渡）。指示器与文字颜色由组件内部按
 * 外观风格分派（默认 / 复古玻璃 / 高级玻璃），圆角统一 [AppComponentShape]，
 * 玻璃系由 [glassBorder] 补 0.5dp 描边；调用方**不得**自行传色值或手写指示器。
 *
 * 两种形态：
 * - [scrollable] = false（默认）：标签等分宽度，适合数量固定、宽度一致的分页（如引擎页顶部分页）；
 * - [scrollable] = true：标签按内容宽度横向滚动，适合数量多/宽度不齐的分类（如首页分类栏）；
 *   两端内置 12dp 留白，选中项在屏幕外（程序化回退/进程恢复）时自动滚动到可见（首次直接定位，
 *   之后带动画）。
 *
 * 无障碍：标签使用 `selectable(selected, role = Tab)`；文字统一 titleMedium，选中加粗。
 *
 * @param tabs 标签文案列表
 * @param selectedIndex 当前选中下标（越界自动收敛）
 * @param onTabSelected 点击标签回调
 * @param scrollable 是否横向滚动形态
 */
@Composable
fun SlidingTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
) {
    if (tabs.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    if (scrollable) {
        ScrollableSlidingTabRow(tabs, safeIndex, onTabSelected, modifier)
    } else {
        FixedSlidingTabRow(tabs, safeIndex, onTabSelected, modifier)
    }
}

@Composable
private fun FixedSlidingTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val shape = AppComponentShape
    BoxWithConstraints(modifier.fillMaxWidth().height(SlidingTabHeight)) {
        val tabWidth = (maxWidth - FixedTabSpacing * (tabs.size - 1)) / tabs.size
        val indicatorOffset by animateDpAsState(
            targetValue = (tabWidth + FixedTabSpacing) * selectedIndex,
            animationSpec = tween(durationMillis = IndicatorDurationMillis, easing = LinearEasing),
            label = "slidingTabIndicatorOffset",
        )
        TabIndicator(
            modifier = Modifier.offset(x = indicatorOffset).width(tabWidth),
            shape = shape,
        )
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(FixedTabSpacing),
        ) {
            tabs.forEachIndexed { index, label ->
                TabLabel(
                    label = label,
                    selected = index == selectedIndex,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** 滚动态标签的已测量几何（相对内容坐标系的 x 与宽度，px）。 */
private data class TabGeometry(val x: Float, val width: Float)

@Composable
private fun ScrollableSlidingTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    val shape = AppComponentShape
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val geometry = remember { mutableStateMapOf<Int, TabGeometry>() }
    // 首次自动定位（快照）与后续选中变化（动画）的区分
    var positionedOnce by remember { mutableStateOf(false) }
    // 标签集合变化（引擎分类增删）后重新测量，避免沿用旧坐标
    LaunchedEffect(tabs.size) { geometry.clear() }
    // 选中项在可视区外时滚动到可见（首次直接定位；用户点击的标签必然可见，不触发）
    LaunchedEffect(selectedIndex, geometry.size, scrollState.viewportSize) {
        val target = geometry[selectedIndex] ?: return@LaunchedEffect
        val viewport = scrollState.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val scroll = scrollState.value.toFloat()
        val visible = target.x >= scroll && target.x + target.width <= scroll + viewport
        if (!visible) {
            val margin = with(density) { ScrollableTabContentPadding.toPx() }
            val targetScroll = (target.x - margin).coerceAtLeast(0f).toInt()
            if (positionedOnce) scrollState.animateScrollTo(targetScroll) else scrollState.scrollTo(targetScroll)
        }
        positionedOnce = true
    }
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
            // 内容坐标系：指示器与标签行同为一个 Box 的子节点，坐标原点一致
            Box(Modifier.height(SlidingTabHeight)) {
                geometry[selectedIndex]?.let { target ->
                    val targetX = with(density) { target.x.toDp() }
                    val targetWidth = with(density) { target.width.toDp() }
                    val animatedX by animateDpAsState(
                        targetValue = targetX,
                        animationSpec = tween(durationMillis = IndicatorDurationMillis, easing = LinearEasing),
                        label = "slidingTabIndicatorX",
                    )
                    val animatedWidth by animateDpAsState(
                        targetValue = targetWidth,
                        animationSpec = tween(durationMillis = IndicatorDurationMillis, easing = LinearEasing),
                        label = "slidingTabIndicatorWidth",
                    )
                    TabIndicator(
                        modifier = Modifier.offset(x = animatedX).width(animatedWidth),
                        shape = shape,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(ScrollableTabSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(ScrollableTabContentPadding))
                    tabs.forEachIndexed { index, label ->
                        TabLabel(
                            label = label,
                            selected = index == selectedIndex,
                            onClick = { onTabSelected(index) },
                            modifier = Modifier
                                .fillMaxHeight()
                                // 记录相对内容坐标系的几何：onGloballyPositioned 放在链首，
                                // 取到的是含内边距的整块标签位置/宽度（与指示器同坐标系）
                                .onGloballyPositioned { coords ->
                                    val x = coords.positionInParent().x
                                    val width = coords.size.width.toFloat()
                                    val current = geometry[index]
                                    if (current == null || current.x != x || current.width != width) {
                                        geometry[index] = TabGeometry(x, width)
                                    }
                                },
                        )
                    }
                    Spacer(Modifier.width(ScrollableTabContentPadding))
                }
            }
        }
    }
}

/** 选中指示器：背景层 + 圆角 + 玻璃系描边（绘制在标签文字之下）。 */
@Composable
private fun TabIndicator(
    modifier: Modifier,
    shape: Shape,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(tabIndicatorContainer())
            .glassBorder(shape = shape),
    )
}

/** 单个标签：透明可点区域，选中态仅靠指示器与加粗文字区分。 */
@Composable
private fun TabLabel(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(AppComponentShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = tabContentColor(selected),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 指示器底色：默认 [NavWhite]，复古玻璃 [GlassSurfaceHigh]，高级玻璃 [AdvancedGlassSurfaceHigh]。 */
@Composable
private fun tabIndicatorContainer(): Color = when {
    AppThemeColors.isAdvancedGlass -> AdvancedGlassSurfaceHigh
    AppThemeColors.isGlass -> GlassSurfaceHigh
    else -> NavWhite
}

/** 文字色：选中不跟随主题色（默认 [TextColor]），玻璃系固定浅色；未选中用对应次级文字色。 */
@Composable
private fun tabContentColor(selected: Boolean): Color = when {
    selected && AppThemeColors.isGlass -> GlassText
    selected -> TextColor
    AppThemeColors.isAdvancedGlass -> AdvancedGlassTextSecondary
    AppThemeColors.isGlass -> GlassTextSecondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
