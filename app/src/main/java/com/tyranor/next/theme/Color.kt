package com.tyranor.next.theme

import androidx.compose.ui.graphics.Color

val EthornellCoverColor = Color(0xFF526F95)

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

// ===== 封面压字（游戏卡片角标等）固定样式：不随外观模式变化 =====

/** 角标底色：半透明黑（≈56%），保证任意封面上的可读性。 */
val CoverBadgeBackground = Color(0x8F000000)

/** 角标文字/图标色：固定白色。 */
val CoverBadgeText = Color.White

/** 未读/更新提醒红点：固定亮红，不随主题色与深浅色变化。 */
val NoticeDotRed = Color(0xFFFF2D2D)

// ===== 玻璃外观风格色板（固定深色，不随外观模式变化） =====

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

/** 玻璃悬浮默认导航条底色：95% 深色，比普通卡片更实，保证图标清晰且几乎不透出底层内容。 */
val GlassNavSurface = Color(0xF21A1C20)

/** 玻璃 0.5dp 发丝描边：14% 白。 */
val GlassBorder = Color(0x24FFFFFF)

/** 玻璃主文字色。 */
val GlassText = Color(0xFFEDEEF0)

/** 玻璃次要文字色。 */
val GlassTextSecondary = Color(0xFFA6AAB0)

/** 玻璃导航栏未选中灰。 */
val GlassUnselected = Color(0xFF7F858D)

// ===== 白雾玻璃外观风格色板（固定浅色条目，不随外观模式变化） =====

/**
 * 白雾玻璃页面条目底色：约 70% 白纱。
 *
 * 对齐用户给的浅薰衣草色参考（L≈201）：背景 L≈109 时 70% 白纱落在 L≈211，
 * 深色正文对比度约 9:1；既亮又保留背景的低频色场。
 */
val MilkSurface = Color(0xB3FFFFFF)

/** 白雾玻璃高亮条目/输入框：约 72% 白，用于卡片内次级控件与浮层输入。 */
val MilkSurfaceHigh = Color(0xB8FFFFFF)

/** 白雾玻璃高不透明度覆盖层：约 82% 白，用于封面底部信息条等需要压住封面的小面积区域。 */
val MilkSurfaceStrong = Color(0xD1FFFFFF)

/**
 * 弹窗/抽屉内条目：约 45% 白纱。
 *
 * 浮层面板本身已有 50% 白纱，条目再叠 45% 后最终亮度约 L215，与页面 70% 条目
 * （L≈211）一致；因此视觉上仍是同一种亮色透明材质，不是更深的一档。
 */
val MilkItemOnPanel = Color(0x73FFFFFF)

/** 白雾玻璃正文/标题：亮面条目上的深灰黑。 */
val MilkText = TextColorLight

/** 白雾玻璃次要文字：条目上的中灰。 */
val MilkTextSecondary = Color(0xFF5C6168)

/** 白雾玻璃页面标题/状态栏文字：深色底图上的近白。 */
val MilkPageText = Color(0xFFF4F2F3)

/** 白雾玻璃页面辅助文字：深色底图上的半透明近白。 */
val MilkPageSecondary = Color(0xB3F4F2F3)

/** 白雾玻璃分割线：条目/面板上的 8% 黑。 */
val MilkDivider = Color(0x14000000)

// ===== 悬浮按钮导航固定色（只随外观模式深浅取档，不随外观风格变化） =====

/**
 * 悬浮按钮导航的固定玻璃表面（浅色档）：纯白玻璃膜。
 * 本导航档为固定样式（见 `ui/common/FloatingGlassNavButton.kt`），不参与外观风格的动态分档；
 * 仅按外观模式（`AppThemeColors.isDark`）在浅 / 深两档间切换。
 */
val FloatingNavGlassSurface = Color(0xFFFFFFFF)

/** 悬浮按钮导航的固定玻璃表面（深色档）：近黑中性玻璃膜（与默认深色页面对齐）。 */
val FloatingNavGlassSurfaceDark = Color(0xFF17191C)

/** 悬浮按钮导航深色档的未选中图标：独立中性色，不随外观风格调整。 */
val FloatingNavGlassUnselectedDark = Color(0xFFB6BBC3)

// ===== 液态玻璃 · 透镜档的光学中性色（不随主题色变化）=====

/**
 * 光学高光与覆盖用的纯白：透镜档栏体边缘高光、透镜静止覆盖（深色档）、交互光斑底色。
 * 属于「光学材质」中性色，只取纯白/纯黑，不参与主题取色。
 */
val GlassOpticalWhite = Color(0xFFFFFFFF)

/** 光学阴影与覆盖用的纯黑：透镜档透镜按压覆盖、按住时的高光/阴影本体。 */
val GlassOpticalBlack = Color(0xFF000000)

// 页面背景：随深色模式取深/浅；玻璃系风格透明（露出根部背景）
val PageGrey: Color
    get() = when {
        AppThemeColors.isGlass || AppThemeColors.isMilkGlass -> Color.Transparent
        AppThemeColors.isDark -> DarkGrey
        else -> GreyLight
    }
// 卡片/导航栏背景：随深色模式取深/浅；复古玻璃为半透明深色玻璃面，白雾玻璃为半透明白纱
val NavWhite: Color
    get() = when {
        AppThemeColors.isGlass -> GlassSurface
        AppThemeColors.isMilkGlass -> MilkSurface
        AppThemeColors.isDark -> LighterDark
        else -> WhiteLight
    }
// 正文/标题文字色：随深色模式取深/浅；复古玻璃恒浅色，白雾玻璃恒深色
val TextColor: Color
    get() = when {
        AppThemeColors.isGlass -> GlassText
        AppThemeColors.isMilkGlass -> MilkText
        AppThemeColors.isDark -> TextColorDark
        else -> TextColorLight
    }
// 导航栏未选中图标/文字色：随深色模式取深/浅；复古玻璃固定浅灰，白雾玻璃用中灰
val UnselectedGrey: Color
    get() = when {
        AppThemeColors.isGlass -> GlassUnselected
        AppThemeColors.isMilkGlass -> MilkTextSecondary
        AppThemeColors.isDark -> UnselectedGreyDark
        else -> UnselectedGreyLight
    }

/**
 * 页面级前景色：顶栏标题、状态栏旁文字、页面空态等直接落在背景上的文字。
 * 白雾玻璃的条目正文仍是 [TextColor]（深色），两者分离，避免亮卡黑字与深底黑字混淆。
 */
val PageTextColor: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkPageText
        AppThemeColors.isGlass -> GlassText
        AppThemeColors.isDark -> TextColorDark
        else -> TextColorLight
    }

/** 页面级辅助文字：比 [PageTextColor] 低一档的透明度。 */
val PageSecondaryTextColor: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkPageSecondary
        AppThemeColors.isGlass -> GlassTextSecondary
        AppThemeColors.isDark -> TextColorDark.copy(alpha = 0.72f)
        else -> TextColorLight.copy(alpha = 0.6f)
    }

/** 首次使用教练层的统一遮罩色。 */
val CoachOverlayScrim: Color = Color.Black.copy(alpha = 0.46f)

/**
 * 条目右侧箭头色：与设置页 `ArrowPreference` 的箭头保持一致
 * （默认风格 = 40% 前景色；复古玻璃 = 60% 的 [GlassTextSecondary]；白雾玻璃 = 中灰）。
 */
val ItemChevronColor: Color
    get() = when {
        AppThemeColors.isGlass -> GlassTextSecondary.copy(alpha = 0.6f)
        AppThemeColors.isMilkGlass -> MilkText.copy(alpha = 0.45f)
        AppThemeColors.isDark -> Color(0x66FFFFFF)
        else -> Color(0x66000000)
    }
// 弹窗内条目容器色：默认风格 = PageGrey（白底弹窗灰卡）；玻璃风格 = 对应亮玻璃面
val DialogItemSurface: Color
    get() = when {
        AppThemeColors.isGlass -> GlassSurfaceHigh
        AppThemeColors.isMilkGlass -> MilkItemOnPanel
        else -> PageGrey
    }

/** 封面底部信息条等小面积覆盖：白雾玻璃用更实的白纱，避免深色封面把文字吃掉。 */
val NavWhiteOverlay: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkSurfaceStrong
        AppThemeColors.isGlass -> GlassSurface.copy(alpha = 0.9f)
        AppThemeColors.isDark -> LighterDark.copy(alpha = 0.9f)
        else -> WhiteLight.copy(alpha = 0.9f)
    }
