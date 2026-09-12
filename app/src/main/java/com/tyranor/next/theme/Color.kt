package com.tyranor.next.theme

import androidx.compose.ui.graphics.Color

// 主色
val Blue40 = Color(0xFF307DEF)
val Teal40 = Color(0xFF2E7D78)
val Amber40 = Color(0xFF9A6C1A)

// 浅色模式固定色
private val WhiteLight = Color(0xFFFFFFFF)
private val GreyLight = Color(0xFFF2F3F5)
private val TextColorLight = Color(0xFF1F2329) // 正文/标题文字：深灰黑
private val UnselectedGreyLight = Color(0xFF8A8F98) // 导航栏未选中图标/文字：中性灰

// 深色模式对应色
private val LighterDark = Color(0xFF222529)
val DarkGrey = Color(0xFF17191C)
private val TextColorDark = Color(0xFFE3E4E6)
private val UnselectedGreyDark = Color(0xFF7A8087)

/** 快捷启动卡无封面/封面加载中时的中性兜底底色。 */
val QuickLaunchFallback = Color(0xFF303338)

// ===== 玻璃外观风格色板（固定深色，不随外观模式/色调切换变化） =====

/** 玻璃风格页面渐变背景：起点（顶部冷灰，压暗避免顶部发白）。 */
val GlassBgTop = Color(0xFF24262B)

/** 玻璃风格页面渐变背景：中段过渡色。 */
val GlassBgMid = Color(0xFF17191D)

/** 玻璃风格页面渐变背景：终点（近黑）。 */
val GlassBgBottom = Color(0xFF0A0B0D)

/** 玻璃风格顶部环境光晕：低透明度白色径向渐变（4% 白），只做微弱层次不做提亮。 */
val GlassBgGlow = Color(0x0AFFFFFF)

/** 玻璃卡片/条目容器：80% 深色，叠在渐变上呈毛玻璃感且保证文字可读。 */
val GlassSurface = Color(0xCC191B1F)

/** 玻璃输入框/弹窗内条目容器：90% 深色，比卡片略亮。 */
val GlassSurfaceHigh = Color(0xE61F2126)

/** 玻璃弹窗/抽屉面板：90% 深色，保证浮层上的内容可读。 */
val GlassPanel = Color(0xE6141518)

/** 玻璃底部抽屉的不透明底板：与 [GlassPanel] 同色值但不含透明，避免透出底层内容。 */
val GlassPanelSolid = Color(0xFF141518)

/** 玻璃抽屉内条目的不透明玻璃面：与 NavWhite 玻璃态在底板上的合成色一致，但无任何透明度。 */
val GlassSurfaceSolid = Color(0xFF181A1E)

/** 玻璃 0.5dp 发丝描边：14% 白。 */
val GlassBorder = Color(0x24FFFFFF)

/** 玻璃主文字色。 */
val GlassText = Color(0xFFEDEEF0)

/** 玻璃次要文字色。 */
val GlassTextSecondary = Color(0xFFA6AAB0)

/** 玻璃导航栏未选中灰。 */
val GlassUnselected = Color(0xFF7F858D)

// 页面背景：随深色模式取深/浅；玻璃风格透明（露出根部渐变）
val PageGrey: Color
    get() = when {
        AppThemeColors.isGlass -> Color.Transparent
        AppThemeColors.isDark && AppThemeColors.toneSwitchEnabled -> LighterDark
        AppThemeColors.isDark -> DarkGrey
        AppThemeColors.toneSwitchEnabled -> WhiteLight
        else -> GreyLight
    }
// 卡片/导航栏背景：随深色模式取深/浅；玻璃风格为半透明玻璃面
val NavWhite: Color
    get() = when {
        AppThemeColors.isGlass -> GlassSurface
        AppThemeColors.isDark && AppThemeColors.toneSwitchEnabled -> DarkGrey
        AppThemeColors.isDark -> LighterDark
        AppThemeColors.toneSwitchEnabled -> GreyLight
        else -> WhiteLight
    }
// 正文/标题文字色：随深色模式取深/浅；玻璃风格恒浅色
val TextColor: Color
    get() = when {
        AppThemeColors.isGlass -> GlassText
        AppThemeColors.isDark -> TextColorDark
        else -> TextColorLight
    }
// 导航栏未选中图标/文字色：随深色模式取深/浅；玻璃风格固定浅灰
val UnselectedGrey: Color
    get() = when {
        AppThemeColors.isGlass -> GlassUnselected
        AppThemeColors.isDark -> UnselectedGreyDark
        else -> UnselectedGreyLight
    }
// 弹窗内条目容器色：默认风格 = PageGrey（白底弹窗灰卡）；玻璃风格 = GlassSurfaceHigh（玻璃弹窗亮卡）
val DialogItemSurface: Color
    get() = if (AppThemeColors.isGlass) GlassSurfaceHigh else PageGrey
