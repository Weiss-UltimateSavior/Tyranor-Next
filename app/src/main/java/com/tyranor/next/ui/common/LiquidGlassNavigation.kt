package com.tyranor.next.ui.common

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.glass.GlassBottomBarSpec
import com.tyranor.next.ui.common.glass.GlassShaderSupport
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.GlassEdgeStrokeAlpha
import com.tyranor.next.theme.DarkGrey
import com.tyranor.next.theme.glassEdgeStroke

/** 底部导航栏的液态玻璃导航项（文字 + 图标资源）。 */
@Immutable
data class LiquidGlassNavItem(
    val label: String,
    @DrawableRes val iconRes: Int,
)

/**
 * 液态玻璃 · 经典底栏（参考 RinneMobile 流体玻璃导航样式）：
 * 通过 [com.kyant.backdrop] 对页面内容做 vibrancy + blur 采样，呈现“看穿”的毛玻璃质感；
 * 选中项仅通过图标颜色变化标识，无焦点胶囊；圆角取「应用圆角调整」的统一值。
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
    // 玻璃表面色随外观模式：深色模式用深色表面，浅色模式用白色表面
    val surfaceColor = if (AppThemeColors.isDark) DarkGrey else Color.White
    val mutedColor = unselectedColor
    // 圆角纳入「应用圆角调整」统一控制
    val shape = AppComponentShape
    // 宽度与「液态玻璃 · 透镜」共用同一套几何：手机取自然宽度（N×76dp + 8dp）并居中，
    // 宽屏拉伸铺满可用宽度；内部每槽宽度随栏宽自适应。
    val spec = GlassBottomBarSpec.Default
    val windowWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val barWidth = spec.clampedBarWidth(items.size, windowWidthDp, stretch = isWideScreen())
    // blur 依赖 RenderEffect（Android 12+ 才生效）；低版本使用轻量实色表面。
    val backdropSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // 高版本表面半透明以透出模糊内容
    // 呈现液态玻璃质感；低版本（<12）无实时模糊，直接用不透明实底，避免文字等内容透出。
    val glassSurfaceAlpha = if (backdropSupported) 0.55f else 1f
    val navigationSurfaceModifier = if (backdropSupported) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // 效果链保持常驻，避免切页结束时重新编译 blur 产生百毫秒级 Draw 长帧。
                vibrancy()
                blur(with(density) { 12.dp.toPx() })
            },
            // 不使用库自带 Highlight：它在高圆角下会在栏体内侧留下一圈白色弧线（表现为“第二层”）。
            // 边缘改用下方统一的 glassEdgeStroke 单层描边。
            highlight = null,
            shadow = { Shadow.Default.copy(alpha = 0.8f) },
            onDrawSurface = {
                drawRect(surfaceColor.copy(alpha = glassSurfaceAlpha))
            },
        )
    } else {
        Modifier.clip(shape).background(surfaceColor.copy(alpha = 0.96f))
    }

    // 单层边缘描边：浅色档中性黑发丝线，深色档白色发丝线；不再叠加库 Highlight，
    // 避免高圆角时描边被裁剪成内侧第二层轮廓。
    val edgeStrokeModifier = if (AppThemeColors.isDark) {
        Modifier.glassEdgeStroke(shape, color = Color.White.copy(alpha = GlassEdgeStrokeAlpha))
    } else {
        Modifier.glassEdgeStroke(shape)
    }

    // drawBackdrop 的裁剪形状在布局阶段写入图层；运行期只改圆角会触发重绘而不重新布局，
    // 旧图层的圆角会残留成内侧弧线（重启后重新布局才消失）。
    // 以圆角值作 key：变化时重建整栏，强制重新测量并写入新的裁剪层。
    key(AppThemeColors.appCornerRadius) {
        Box(
            modifier = modifier
                .width(barWidth)
                .navigationBarsPadding()
                .padding(vertical = 12.dp)
                .height(64.dp)
                .then(navigationSurfaceModifier)
                .then(edgeStrokeModifier),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    // 与透镜档同一内边距：4dp，图标槽宽随栏宽均分
                    .padding(horizontal = spec.barInnerPadding),
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
    Box(
        modifier = modifier
            .height(56.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 与「经典 MD」底栏同一套选中效果：自下而上的渐变填充动画 + 颜色切换
        NavigationTabIcon(
            iconRes = item.iconRes,
            contentDescription = item.label,
            selected = selected,
            unselectedColor = mutedColor,
            selectedColor = primaryColor,
            size = 26.dp,
            animationLabel = "liquidGlassNavIcon_${item.label}",
        )
    }
}

/**
 * 当前实际生效的导航栏样式：用户显式选择的样式原样生效；未显式设置时按外观风格解析
 * （默认外观 → 液态玻璃 · 经典，玻璃系外观 → 经典 MD），随外观风格切换即时重组。
 */
@Composable
fun effectiveNavStyle(): String {
    val navStyle by AppSettingsStore.navStyleState.collectAsState()
    return AppSettingsStore.resolveNavStyle(
        style = navStyle,
        enhancedSupported = AppSettingsStore.supportsLiquidGlassEnhanced,
        glassAppearance = AppThemeColors.isGlass,
    )
}

/**
 * 悬浮导航栏的列表底部滚动留白：
 * 内容可滚动经过玻璃后面（沉浸），但列表尾部预留导航高度，保证滚动到底时最后一项完全露出不被遮挡。
 * - 平板侧栏布局：导航在侧边占布局宽度，底部留白为 0（返回 0）；
 * - 液态玻璃 · 经典：栏高 64 + 上下各 12 外边距 = 88dp；
 * - 液态玻璃 · 透镜：栏高 64 + 底部 12 外边距 = 76dp（无上边距）；
 * - 悬浮按钮：收起态按钮 64 + 底部 16 外边距 + 8 余量 = 88dp（与玻璃系一致）；
 * - 玻璃外观风格下的悬浮默认导航条：无文字后栏高收窄至 64 + 上下各 12 外边距；
 * - 其余情况返回 0（导航栏占布局高度）。
 */
@Composable
fun glassNavBottomInset(): Dp {
    // 平板侧栏：导航在侧边且占布局宽度，底部不需要留白
    if (isSideRailLayout()) return 0.dp
    val navStyle = effectiveNavStyle()
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return when (navStyle) {
        AppSettingsStore.NAV_STYLE_LIQUID_GLASS_ENHANCED -> {
            // 窗口窄到组件自己不渲染时不能留白，否则底部会空出一条
            val spec = GlassBottomBarSpec.Default
            val density = LocalDensity.current
            val windowWidthDp = with(density) {
                LocalWindowInfo.current.containerSize.width.toDp()
            }.takeIf { it > 0.dp } ?: LocalConfiguration.current.screenWidthDp.dp
            when {
                // 窗口窄到组件自己不渲染时不能留白，否则底部会空出一条
                !spec.canRender(windowWidthDp) -> 0.dp
                // 运行期 AGSL 不可用时 MainScreen 会退回经典档，留白必须跟着按经典档算
                !GlassShaderSupport.isRuntimeShaderUsable -> navBarInset + 88.dp
                else -> navBarInset + spec.hostBottomInset()
            }
        }
        // 经典档：栏高 64 + 上下各 12
        AppSettingsStore.NAV_STYLE_LIQUID_GLASS -> navBarInset + 88.dp
        // 悬浮按钮：收起态按钮 64 + 底部 16 外边距 + 8 余量（与玻璃系一致）
        AppSettingsStore.NAV_STYLE_FLOATING_BUTTON -> navBarInset + 88.dp
        else -> if (AppThemeColors.isGlass) navBarInset + 88.dp else 0.dp
    }
}
