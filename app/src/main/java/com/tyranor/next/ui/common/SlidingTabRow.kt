package com.tyranor.next.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.abs
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassSurfaceHigh
import com.tyranor.next.theme.GlassText
import com.tyranor.next.theme.GlassTextSecondary
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.LocalAppDialogContent
import com.tyranor.next.theme.UnselectedGrey
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.MotionTokens
import androidx.compose.animation.animateColorAsState
import kotlinx.coroutines.launch

/** 标签高度（两形态统一）。 */
private val SlidingTabHeight = 42.dp

/** 等分宽度形态的标签间距。 */
private val FixedTabSpacing = 6.dp

/** 内容宽度横向滚动形态的标签间距。 */
private val ScrollableTabSpacing = 8.dp

/** 滚动态两端留白：滚动到边缘时标签不贴屏幕边。 */
private val ScrollableTabContentPadding = 12.dp

/**
 * 「蠕动」指示器：前沿（运动方向那条边）用快而略带回弹的弹簧先到，后沿用慢一点的弹簧跟上，
 * 移动途中指示器朝目标拉长、到位时轻轻回弹收拢。停稳后左右边与目标标签完全重合（外观不变）。
 *
 * 返回左右边的动画值；在布局阶段读取（见 [stretchyBounds]），移动途中不逐帧重组整行标签。
 */
@Composable
private fun rememberStretchyIndicator(targetLeft: Dp, targetRight: Dp): StretchyEdges {
    val left = remember { Animatable(targetLeft, Dp.VectorConverter) }
    val right = remember { Animatable(targetRight, Dp.VectorConverter) }
    LaunchedEffect(targetLeft, targetRight) {
        val movingRight = targetLeft > left.value
        // 「普通」动效档：原版 200ms 线性滑动，两边同步（宽度不拉伸）
        val original = tween<Dp>(durationMillis = 200, easing = LinearEasing)
        // Q 弹的「蠕动」：前沿又快又带明显过冲（先冲过头再弹回），后沿慢吞吞地跟上，两条边之间被拉长；
        // 每次切换都从当前位置与速度接着走（连点 / 中途改目标不会顿一下）
        val lead = if (MotionMode.enhanced) spring<Dp>(dampingRatio = 0.5f, stiffness = 560f, visibilityThreshold = IndicatorThreshold) else original
        val trail = if (MotionMode.enhanced) spring<Dp>(dampingRatio = 0.8f, stiffness = 170f, visibilityThreshold = IndicatorThreshold) else original
        launch { left.animateTo(targetLeft, if (movingRight) trail else lead) }
        launch { right.animateTo(targetRight, if (movingRight) lead else trail) }
    }
    return remember(left, right) { StretchyEdges(left, right) }
}

private class StretchyEdges(
    val left: Animatable<Dp, AnimationVector1D>,
    val right: Animatable<Dp, AnimationVector1D>,
) {
    /** 指示器当前宽度相对目标宽度被拉长的比例（≥ 0）；用来做纵向压扁，停稳时为 0。 */
    fun stretch(targetWidth: Dp): Float {
        if (targetWidth.value <= 0f) return 0f
        return ((right.value - left.value).value / targetWidth.value - 1f).coerceIn(0f, 1f)
    }
}

/**
 * 按两条边的当前值摆放指示器（布局阶段读取动画值，只重新布局不重组）；[maxWidth] 不为 null 时把两边夹在标签栏内。
 * 等分标签的边界从逻辑起点计算；滚动标签的测量边界则为物理坐标。
 */
private fun Modifier.stretchyBounds(
    edges: StretchyEdges,
    maxWidth: Dp? = null,
    relativeToStart: Boolean = false,
): Modifier = layout { measurable, constraints ->
    var l = edges.left.value
    var r = edges.right.value
    if (maxWidth != null) {
        l = l.coerceIn(0.dp, maxWidth)
        r = r.coerceIn(l, maxWidth)
    }
    val x = l.roundToPx()
    val w = (r.roundToPx() - x).coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(placeable.width, placeable.height) {
        if (relativeToStart) placeable.placeRelative(x, 0) else placeable.place(x, 0)
    }
}

private val IndicatorThreshold = 0.1.dp

/**
 * 标签栏统一组件（AGENTS.md「标签栏统一规范」的唯一入口）。
 *
 * 选中项是**独立背景指示器**（不是逐项背景），切换时指示器以弹簧「蠕动」滑到目标标签
 * （前沿先到、后沿跟上，途中拉长、到位回弹），宽度随之过渡（滚动态标签宽度不齐时也平滑过渡）。指示器与文字颜色由组件内部按
 * 外观风格分派（默认 / 复古玻璃），圆角统一 [AppComponentShape]，
 * 玻璃系由 [glassBorder] 补 0.5dp 描边；调用方**不得**自行传色值或手写指示器。
 *
 * 两种形态：
 * - [scrollable] = false（默认）：标签等分宽度，适合数量固定、宽度一致的分页（如引擎页顶部分页）；
 * - [scrollable] = true：标签按内容宽度横向滚动，适合数量多/宽度不齐的分类；
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
    /**
     * 与分页内容共用的状态（等分形态可用）：传入后选中块就是底栏「液态玻璃 · 透镜」同款的运动控制器——
     * 点选时带按压脉冲滑过去，长按分类栏 / 标题区唤出玻璃透镜选择、松手才切换；见 [TabPagerState]、[tabDragSelect]。
     * 不传则沿用组件自带的「蠕动」弹簧。
     */
    pager: TabPagerState? = null,
) {
    if (tabs.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    if (scrollable) {
        ScrollableSlidingTabRow(tabs, safeIndex, onTabSelected, modifier)
    } else {
        FixedSlidingTabRow(tabs, safeIndex, onTabSelected, modifier, pager)
    }
}

/** 等分可用宽度并以逻辑起点定位指示器，弹簧过冲限制在标签栏边界内。 */
@Composable
private fun FixedSlidingTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier,
    pager: TabPagerState? = null,
) {
    val shape = AppComponentShape
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(SlidingTabHeight)
            // 上报标签栏的窗口几何，供长按拖动把手指 x 换算成透镜位置
            .onGloballyPositioned { coords ->
                if (pager != null) {
                    pager.rowLeftPx = coords.positionInWindow().x
                    pager.rowWidthPx = coords.size.width.toFloat()
                    pager.spacingPx = with(density) { FixedTabSpacing.toPx() }
                }
            },
    ) {
        val tabWidth = (maxWidth - FixedTabSpacing * (tabs.size - 1)) / tabs.size
        val lensEnabled = pager != null && MotionMode.enhanced
        if (lensEnabled) {
            // 选中块就是底栏同款透镜（见 TabPagerState）：静止时与原来的白色胶囊指示器完全一致，
            // 点选 / 长按时按底栏透镜的运动与按压形变动起来
            TabLensBase(pager!!, tabWidth, FixedTabSpacing, shape)
        } else {
            val targetLeft = (tabWidth + FixedTabSpacing) * selectedIndex
            val edges = rememberStretchyIndicator(targetLeft, targetLeft + tabWidth)
            // 两端标签处前沿回弹不越出标签栏
            TabIndicator(
                modifier = Modifier.stretchyBounds(edges, maxWidth, relativeToStart = true),
                shape = shape,
                squash = { edges.stretch(tabWidth) },
            )
        }
        // 透镜按住期间主行的标签都显示成「未选中」样式（选中样式由透镜里的副本表现，且是在绘制阶段交叉淡入的），
        // 所以滑过标签时主行完全不用重组；只在长按开始和松手时各变一次。平时跟随真正的选中项
        val highlighted by remember(pager, selectedIndex) {
            androidx.compose.runtime.derivedStateOf {
                if (pager != null && pager.lensPressed) -1 else selectedIndex
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(FixedTabSpacing),
        ) {
            tabs.forEachIndexed { index, label ->
                TabLabel(
                    label = label,
                    selected = index == highlighted,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        if (lensEnabled) {
            // 玻璃层预先组合好常驻（未按压时 alpha = 0，不绘制）：长按那一刻不再现场组合 / 排版里面的文字
            TabLensGlass(pager!!, tabs, tabWidth, FixedTabSpacing, maxWidth, shape)
        }
    }
}

private val NoHide: () -> Float = { 0f }

/**
 * 透镜底（标签文字之下）：纯色胶囊，位置与体积由底栏同款控制器驱动。
 * 静止时 = 原来的选中指示器（实心、无阴影、无缩放）；按压时（分类栏不放大）随控制器的体积弹簧并带上柔和阴影、略透明。
 */
@Composable
private fun TabLensBase(pager: TabPagerState, tabWidth: Dp, spacing: Dp, shape: Shape) {
    val container = tabIndicatorContainer()
    Box(
        modifier = Modifier
            .lensBounds(pager, tabWidth, spacing)
            .graphicsLayer {
                val lens = pager.lens
                scaleX = lens.scaleX
                scaleY = lens.scaleY
                // 柔和的系统阴影（随按压压力浮现）
                shadowElevation = 14.dp.toPx() * lens.pressure.coerceIn(0f, 1f)
                this.shape = shape
                clip = false
                ambientShadowColor = Color.Black.copy(alpha = 0.25f)
                spotShadowColor = Color.Black.copy(alpha = 0.32f)
            }
            .clip(shape)
            .drawBehind { drawRect(container, alpha = 1f - 0.2f * pager.lens.pressure.coerceIn(0f, 1f)) }
            .glassBorder(shape = shape),
    )
}

/**
 * 透镜玻璃层（标签文字之上）：按压时才出现——边缘高光与折射晕、顶部柔光（随控制器的光斑弹簧），
 * 以及一份**文字副本**（整行文字原大小对准透镜，只露出透镜里的那一段；分类栏的透镜不放大）。
 * 透镜本身不透明地盖住底下的原文字，里面的副本随透镜位置对准整行文字：飞速经过时文字是连续的、不闪烁；
 * 副本里的「选中样式」（加粗 / 变深）只在透镜静下来时才平滑出现（[TabPagerState.lensSettle]）。
 * 位置 / 体积 / 透明度都在布局与图层阶段读取，移动中不逐帧重组。
 */
@Composable
private fun TabLensGlass(
    pager: TabPagerState,
    tabs: List<String>,
    tabWidth: Dp,
    spacing: Dp,
    rowWidth: Dp,
    shape: Shape,
) {
    // 玻璃层自带近乎不透明的底：它盖在标签文字之上，把底下的原文字完全遮住，只露出里面的那一份——
    // 不再把原文字淡出一部分（透镜落在两个标签之间时会两份文字叠出错位的重影）
    val container = tabIndicatorContainer()
    val fill = remember(container) { container.copy(alpha = maxOf(container.alpha, 0.92f)) }
    // 边缘：上沿亮白高光、下沿淡灰收边（中性色，不带主题色）
    val rim = remember {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.95f),
            0.55f to Color(0x14000000),
            1f to Color(0x24000000),
        )
    }
    val sheen = remember {
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.55f), 0.6f to Color.Transparent)
    }
    val pitch = with(LocalDensity.current) { (tabWidth + spacing).toPx() }
    Box(
        modifier = Modifier
            .lensBounds(pager, tabWidth, spacing)
            .graphicsLayer {
                val lens = pager.lens
                scaleX = lens.scaleX
                scaleY = lens.scaleY
                // 压力涨到一半就完全不透明：尽快遮住底下的原文字
                alpha = (lens.pressure * 2f).coerceIn(0f, 1f)
            }
            .clip(shape)
            .drawBehind {
                drawRect(fill)
                drawRect(sheen, alpha = pager.lens.glow.coerceIn(0f, 1f))
            }
            .drawWithContent {
                drawContent()
                // 边缘描边：圆角连接。不用 Modifier.border——胶囊形的路径在左右正中有零长度线段，斜接的尖角会在那里冒出一小条细线
                val w = 0.8.dp.toPx()
                inset(w / 2f) {
                    drawOutline(
                        shape.createOutline(size, layoutDirection, this),
                        brush = rim,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = w,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    )
                }
            }
            .clearAndSetSemantics { },
    ) {
        Row(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val rowPx = rowWidth.roundToPx()
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(rowPx, constraints.maxHeight))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val left = pager.lens.index.coerceIn(0f, (pager.count - 1).toFloat()) * pitch
                        placeable.place(-left.roundToInt(), 0)
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            tabs.forEachIndexed { index, label ->
                LensLabel(label = label, index = index, pager = pager, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** 透镜按位置摆放：宽 = 一个标签宽，位置随 [TabPagerState.lens] 的连续位置（夹在首尾标签之间），布局阶段读取。 */
private fun Modifier.lensBounds(pager: TabPagerState, tabWidth: Dp, spacing: Dp): Modifier = layout { measurable, constraints ->
    val tw = tabWidth.roundToPx()
    val pitch = (tabWidth + spacing).toPx()
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(tw, constraints.maxHeight))
    layout(placeable.width, placeable.height) {
        val x = pager.lens.index.coerceIn(0f, (pager.count - 1).toFloat()) * pitch
        placeable.placeRelative(x.roundToInt(), 0)
    }
}

/**
 * 透镜里的文字副本（无点击、无按压）：常规与加粗各一份叠放，按 [TabPagerState.lensEmphasis] 在绘制阶段交叉淡入淡出——
 * 透镜压在哪个标签上，哪个标签就是「选中」样式（加粗 / 变深）；滑动中不重组、不重新排版文字。
 */
@Composable
private fun LensLabel(label: String, index: Int, pager: TabPagerState, modifier: Modifier = Modifier) {
    val normalColor = tabContentColor(false)
    val strongColor = tabContentColor(true)
    Box(modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            modifier = Modifier.graphicsLayer { alpha = 1f - pager.lensEmphasis(index) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Normal,
            color = normalColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            modifier = Modifier.graphicsLayer { alpha = pager.lensEmphasis(index) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = strongColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 滚动态标签的已测量几何（相对内容坐标系的 x 与宽度，px）。 */
private data class TabGeometry(val x: Float, val width: Float)

/** 按内容测量每项边界并驱动指示器；选中项不可见时首次直达，之后平滑滚动到可见。 */
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
    // 标签数量变化时同步更换测量表；异步 clear 可能晚于首次布局，把刚写入的几何清空且不再触发测量。
    val geometry = remember(tabs.size) { mutableStateMapOf<Int, TabGeometry>() }
    // 首次自动定位（快照）与后续选中变化（动画）的区分
    var positionedOnce by remember { mutableStateOf(false) }
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
                    val edges = rememberStretchyIndicator(targetX, targetX + targetWidth)
                    TabIndicator(
                        modifier = Modifier.align(AbsoluteAlignment.TopLeft).stretchyBounds(edges),
                        shape = shape,
                        squash = { edges.stretch(targetWidth) },
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
    squash: () -> Float = { 0f },
    alphaOf: () -> Float = { 1f },
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            // 被拉长得越多、纵向压得越扁（最多约 10%），到位回弹时再弹回来——Q 弹的体积感
            .graphicsLayer {
                val s = if (MotionMode.enhanced) squash() * 0.10f else 0f
                scaleY = 1f - s
                this.alpha = alphaOf()
            }
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
    /** 被透镜盖住的程度（0..1），文字按它淡出；在图层阶段读取。 */
    hide: () -> Float = NoHide,
) {
    Box(
        modifier = modifier
            // 按压只做以中心为轴的轻微缩放：不跟手偏移、不被扯动（follow = 0），文字始终钉在指示器正中，
            // 不会随手指落点滑到选框的上下半边
            .pressSquish(scale = 0.92f, follow = 0.dp)
            .clip(AppComponentShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val targetColor = tabContentColor(selected)
        // 刚被选中的标签：文字弹一下（先略放大再落回，带过冲）；首次组合不弹
        val pop = remember { Animatable(1f) }
        val firstPass = remember { booleanArrayOf(true) }
        LaunchedEffect(selected) {
            if (firstPass[0]) {
                firstPass[0] = false
            } else if (selected && MotionMode.enhanced) {
                pop.snapTo(1.14f)
                pop.animateTo(1f, spring(dampingRatio = 0.34f, stiffness = 480f))
            }
        }
        Text(
            modifier = Modifier.graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                alpha = 1f - hide()
            },
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            // 选中 ↔ 未选中：「增强」档文字颜色随指示器滑动弹簧淡变（新选中项变深、旧选中项变浅），「普通」档直接切换
            color = if (MotionMode.enhanced) {
                animateColorAsState(targetColor, MotionTokens.fadeIn(), label = "tabLabelColor").value
            } else {
                targetColor
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 弹窗上下文的局部取色适配：指示器复用反色条目，避免与弹窗面板同色。
 * 由公共弹窗提供上下文，调用方不传色值；页内标签栏保留既有取色分派，玻璃档各用自身动态常量。
 */
@Composable
private fun tabIndicatorContainer(): Color = when {
    LocalAppDialogContent.current -> DialogItemSurface
    AppThemeColors.isGlass -> GlassSurfaceHigh
    else -> NavWhite
}

/** 文字色：选中不跟随主题色（默认 [TextColor]），玻璃系固定浅色；未选中用对应次级文字色。 */
@Composable
private fun tabContentColor(selected: Boolean): Color = when {
    selected && AppThemeColors.isGlass -> GlassText
    selected -> TextColor
    AppThemeColors.isGlass -> GlassTextSecondary
    AppThemeColors.isMilkGlass -> PageSecondaryTextColor
    LocalAppDialogContent.current -> UnselectedGrey
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
