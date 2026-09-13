package com.tyranor.next.ui.common.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DarkGrey
import com.tyranor.next.theme.GlassNavSurface
import com.tyranor.next.theme.GlassOpticalBlack
import com.tyranor.next.theme.GlassOpticalWhite
import com.tyranor.next.theme.GlassSurfaceSolid

/**
 * 主题 → 玻璃颜色适配器（报告 §8.9 的单一取色入口）。
 *
 * 当前阶段把 Tyranor 现有主题语义映射到 [GlassBottomBarColors]；未来接入 MD3 动态取色时，
 * 只需要把这里的输入换成根部统一的 `MaterialTheme.colorScheme`，玻璃渲染核心与交互参数不动。
 *
 * 说明：报告 §8.2 建议把本文件放在 `theme/`，但项目 `theme/` 不依赖 `ui/`
 * （AGENT.md 三层架构与依赖方向纪律），因此适配器与颜色契约同置于 `ui/common/glass/`，
 * 保持依赖方向为 `ui → theme`，取色职责仍收口在单一函数内。
 *
 * @param unselectedColor 可见未选中图标色（调用方传入当前主题的未选中灰）。
 */
@Composable
fun rememberGlassBottomBarColors(
    unselectedColor: Color,
): GlassBottomBarColors {
    // 只在函数体内读取主题：色调轮盘换色时本适配器随重组刷新，渲染核心的动画状态不被重建。
    val primary = MaterialTheme.colorScheme.primary
    val isDark = AppThemeColors.isDark
    val isGlass = AppThemeColors.isGlass
    return remember(primary, unselectedColor, isDark, isGlass) {
        GlassBottomBarColors(
            // 栏体着色：玻璃外观风格沿用悬浮导航的深色玻璃面，其余跟随深浅中性面
            surfaceTint = if (isGlass) GlassNavSurface else if (isDark) DarkGrey else GlassOpticalWhite,
            fallbackSurface = if (isGlass) GlassSurfaceSolid else if (isDark) DarkGrey else GlassOpticalWhite,
            selectedIcon = primary,
            unselectedIcon = unselectedColor,
            lensContentTint = primary,
            // 降级档（API < 33 无折射）：用主题色半透明胶囊维持选中可见性
            lensFallbackTint = primary.copy(alpha = 0.32f),
            edgeHighlight = GlassOpticalWhite,
            staticLensCover = if (isDark) GlassOpticalWhite else GlassOpticalBlack,
            pressedLensCover = GlassOpticalBlack,
        )
    }
}
