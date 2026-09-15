package com.tyranor.next.ui.common.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 液态玻璃底栏的颜色契约（与 [GlassBottomBarSpec] 的光学参数完全独立）。
 *
 * 报告 §8.9 的硬性约束：**材料参数与主题色必须是两个独立输入**。
 * 折射组件内部不得固化任何具体 RGB、壁纸取色结果或历史蓝色，
 * 也不得在此处解析系统动态色——解析只在 [rememberGlassBottomBarColors] 一处发生。
 *
 * 其中三个「光学中性色」不随主题取色变化：
 * [edgeHighlight]、[staticLensCover]、[pressedLensCover]。
 */
@Immutable
data class GlassBottomBarColors(
    /** 栏体着色层颜色；渲染端只应用一次 `spec.barSurfaceAlphaLight/Dark`，不叠加第二次透明度。 */
    val surfaceTint: Color,
    /** 无实时模糊能力（API < 31）时的不透明实底，保证图标与文字可读。 */
    val fallbackSurface: Color,
    /** 可见选中图标色。 */
    val selectedIcon: Color,
    /** 可见未选中图标色。 */
    val unselectedIcon: Color,
    /** 隐藏副本（透镜内容）图标强调色；副本只染色一次，不叠加第二次 tint。 */
    val lensContentTint: Color,
    /** 无折射能力时的选中指示色（降级档使用，不计入高保真验收）。 */
    val lensFallbackTint: Color,
    /** 栏体边缘高光底色（光学中性）。 */
    val edgeHighlight: Color,
    /** 静止时透镜的中性覆盖色：深色档取白、亮色档取黑。 */
    val staticLensCover: Color,
    /** 按住时透镜的轻微压暗覆盖色（恒黑）。 */
    val pressedLensCover: Color,
    /**
     * 栏体发丝描边的底色：浅色档用中性黑（浅底上白描边不可见），深色档传 [Color.Transparent]
     * 表示不描边、只靠 [edgeHighlight] 亮边。
     */
    val edgeStroke: Color,
    /** 已解析主题是否为深色：决定表面不透明度、高光强度与是否描边。 */
    val isDark: Boolean,
)
