package com.tyranor.next.ui.common

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DarkGrey
import kotlin.math.abs
import kotlin.math.roundToInt

/** 底部导航栏的液态玻璃导航项（文字 + 图标资源）。 */
@Immutable
data class LiquidGlassNavItem(
    val label: String,
    @DrawableRes val iconRes: Int,
)

/**
 * 圆角液态玻璃底部导航栏（参考 RinneMobile 流体玻璃导航样式）：
 * 通过 [com.kyant.backdrop] 对页面内容做 vibrancy + blur 采样，呈现“看穿”的毛玻璃质感；
 * 选中项有跟随的主题色玻璃焦点胶囊；支持长按后左右拖动切换页面（移植自 RinneMobile）。
 * 悬浮于内容之上，圆角 16dp。
 */
@Composable
fun LiquidGlassNavigationBar(
    backdrop: Backdrop,
    selectedIndex: Int,
    primaryColor: Color,
    unselectedColor: Color,
    items: List<LiquidGlassNavItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 玻璃表面色随外观模式：深色模式用深色表面
    val surfaceColor = if (AppThemeColors.isDark) DarkGrey else Color.White
    val mutedColor = unselectedColor
    // 统一圆角（AGENT.md）：圆角组件一律 8dp；液态玻璃导航在 8dp 基础上加大 8dp，视觉更圆润
    val shape = RoundedCornerShape(16.dp)
    // blur 依赖 RenderEffect（Android 12+ 才生效）；低版本使用轻量实色表面。
    val backdropSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // 高版本表面半透明以透出模糊内容
    // 呈现液态玻璃质感；低版本（<12）无实时模糊，直接用不透明实底，避免文字等内容透出。
    val glassSurfaceAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.55f else 1f
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    var navWidth by remember { mutableIntStateOf(0) }
    // 长按滑动切换：拖动期间焦点胶囊跟随手指，松手回弹到选中项（移植自 RinneMobile）
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val focusWidth = 76.dp
    val focusWidthPx = with(density) { focusWidth.toPx() }
    // onSizeChanged 在 Box 的水平 padding 之后测量，navWidth 即 item 分布的内容宽度（仅拖动估算用）
    val itemWidth = if (items.isEmpty()) 0f else navWidth / items.size.toFloat()

    fun centerAt(position: Float): Float {
        return itemWidth * (position + 0.5f)
    }

    val focusTargetX = dragPosition?.let { position ->
        centerAt(position) - focusWidthPx / 2f
    } ?: if (itemWidth > 0f) {
        centerAt(currentSelectedIndex.toFloat()) - focusWidthPx / 2f
    } else {
        0f
    }
    // stiffness 提高、阻尼加大：指示器跟随更干脆，点击切换不拖沓
    val focusXState = animateFloatAsState(
        targetValue = focusTargetX,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 1200f),
        label = "liquidGlassFocus",
    )
    val navigationSurfaceModifier = if (backdropSupported) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // 效果链保持常驻，避免切页结束时重新编译 blur 产生百毫秒级 Draw 长帧。
                vibrancy()
                blur(with(density) { 12.dp.toPx() })
            },
            highlight = { Highlight.Default.copy(alpha = 0.85f) },
            shadow = { Shadow.Default.copy(alpha = 0.8f) },
            onDrawSurface = {
                drawRect(surfaceColor.copy(alpha = glassSurfaceAlpha))
            },
        )
    } else {
        Modifier.clip(shape).background(surfaceColor.copy(alpha = 0.96f))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .height(64.dp)
            .then(navigationSurfaceModifier)
            .onSizeChanged { if (navWidth != it.width) navWidth = it.width }
            .pointerInput(itemWidth, items.size) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        if (itemWidth <= 0f) return@detectDragGesturesAfterLongPress
                        val index = (position.x / itemWidth)
                            .toInt()
                            .coerceIn(items.indices)
                        dragPosition = index.toFloat()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (itemWidth > 0f) {
                            val position = ((dragPosition ?: currentSelectedIndex.toFloat()) +
                                dragAmount.x / itemWidth)
                                .coerceIn(0f, items.lastIndex.toFloat())
                            dragPosition = position
                        }
                    },
                    onDragEnd = {
                        val targetIndex = dragPosition?.roundToInt()?.coerceIn(items.indices)
                        dragPosition = null
                        if (targetIndex != null && targetIndex != currentSelectedIndex) {
                            currentOnItemClick(targetIndex)
                        }
                    },
                    onDragCancel = {
                        dragPosition = null
                    },
                )
            },
    ) {
        // 选中焦点胶囊：主题色半透明的液态玻璃效果
        // 用 offset 而非 graphicsLayer 平移：offset 会更新布局坐标，
        // drawBackdrop 采样与实际位置一致，避免胶囊中间出现错位线条
        // 焦点只做主题色流体胶囊；外层已提供真实玻璃，避免焦点再运行第二套 blur 管线。
        val focusSurfaceModifier = Modifier
            .clip(shape)
            .background(primaryColor.copy(alpha = if (backdropSupported) 0.32f else 0.45f))
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                // offset 移动位置（布局坐标跟随，drawBackdrop 采样不偏移）；
                // graphicsLayer 只做流体拉伸缩放（纯视觉，不影响采样坐标）
                .offset { IntOffset(focusXState.value.roundToInt(), 0) }
                .graphicsLayer {
                    // 动画值只在 layout/draw 阶段读取，焦点弹簧不再逐帧重组整个导航栏。
                    val movement = if (itemWidth > 0f) {
                        abs(focusTargetX - focusXState.value) / itemWidth
                    } else {
                        0f
                    }
                    val stretch = movement.coerceIn(0f, 1f) * 0.42f
                    scaleX = 1f + stretch
                    scaleY = 1f - stretch * 0.25f
                }
                .width(focusWidth)
                .height(48.dp)
                .then(focusSurfaceModifier),
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                LiquidGlassNavItemView(
                    item = item,
                    selected = index == selectedIndex,
                    primaryColor = primaryColor,
                    mutedColor = mutedColor,
                    onClick = { onItemClick(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LiquidGlassNavItemView(
    item: LiquidGlassNavItem,
    selected: Boolean,
    primaryColor: Color,
    mutedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor = if (selected) primaryColor else mutedColor
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(item.iconRes),
            contentDescription = item.label,
            modifier = Modifier.size(26.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(contentColor),
        )
    }
}

/**
 * 液态玻璃导航悬浮时的列表底部滚动留白：
 * 内容可滚动经过玻璃后面（沉浸），但列表尾部预留导航高度（64+12*2+系统导航条），
 * 保证滚动到底时最后一项完全露出不被遮挡。非液态玻璃模式返回 0。
 */
@Composable
fun glassNavBottomInset(): Dp {
    val navStyle by AppSettingsStore.navStyleState.collectAsState()
    return if (navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp
    } else {
        0.dp
    }
}

/** 宽屏判定：横屏或宽设备（screenWidthDp / smallestScreenWidthDp ≥ 600），用于大屏布局适配（如游戏页 6 列网格）。 */
@Composable
fun isWideScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp >= 600 ||
        configuration.smallestScreenWidthDp >= 600
}
