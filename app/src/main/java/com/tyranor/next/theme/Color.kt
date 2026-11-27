package com.tyranor.next.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

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

/** 主题色名牌上的文字（游戏操作抽屉档案卡的引擎名牌）：固定白色。 */
val AccentTagText = Color.White

/** 未读/更新提醒红点：固定亮红，不随主题色与深浅色变化。 */
val NoticeDotRed = Color(0xFFFF2D2D)

// ===== 应用内提示胶囊：浅/深胶囊配对色，按实际背景风格选择 =====
val NoticeLightCapsuleBackground = Color(0xFFF2F2F4)
val NoticeLightCapsuleContent = Color(0xFF17171A)
val NoticeLightCapsuleFailure = Color(0xFFD93025)
val NoticeDarkCapsuleBackground = Color(0xFF1C1C1F)
val NoticeDarkCapsuleContent = Color(0xFFF5F5F7)
val NoticeDarkCapsuleFailure = Color(0xFFFF7B72)

/** 分类栏透镜的中性边缘渐变；不随主题强调色变化。 */
val TabLensRimMiddle = Color(0x14000000)
val TabLensRimBottom = Color(0x24000000)

// ===== 实底危险按钮（删除等破坏性动作）固定色：不随主题色与外观风格变化 =====
// 深色 scheme 的 colorScheme.error 是浅粉（#F2B8B5）+ 深褐红字，放在玻璃深色面板上
// 会发白失焦，与默认/白雾玻璃的深红底白字割裂；实底危险按钮统一取下面这对固定值。

/** 实底危险按钮底色：深红（与浅色 scheme 的 error 一致）。 */
val DangerFill = Color(0xFFB3261E)

/** 实底危险按钮上的文字/图标色：固定白色。 */
val OnDangerFill = Color.White

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

/** 旋转过渡两侧的底色；页面背景透明时，使用接近实际背景的实色。 */
internal val ScreenRotationFill: Color
    get() {
        val base = PageGrey
        return when {
            base.alpha >= 0.99f -> base
            AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop -> Color(0xFF0B0C0E)
            else -> Color(0xFFDDE1E6)
        }
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

/** 抽屉辅助文字：白雾玻璃的亮面板使用深色正文。 */
val SheetSecondaryTextColor: Color
    get() = if (AppThemeColors.isMilkGlass) TextColor else PageSecondaryTextColor

/** 卡片/弹窗内的次要说明文字：与所在面板表面匹配。 */
val SecondaryTextColor: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkTextSecondary
        AppThemeColors.isGlass -> GlassTextSecondary
        AppThemeColors.isDark -> TextColorDark.copy(alpha = 0.72f)
        else -> TextColorLight.copy(alpha = 0.6f)
    }

/** 抽屉遮罩：玻璃面板需要加深与页面之间的层次。 */
val SheetScrimColor: Color
    get() = Color.Black.copy(
        alpha = when {
            AppThemeColors.isGlass -> 0.6f
            AppThemeColors.isMilkGlass -> 0.45f
            else -> 0.32f
        },
    )

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

// ===== 游戏页「书架」卡片风格：光学中性色（不随主题色变化；主题强调色仍取 colorScheme.primary） =====

/** 盒子落在搁板上的接触阴影。 */
val ShelfContactShadow = Color(0x3D000000)

/** 盒子转开时正面 / 书脊背光的压暗色（透明度由转角决定）。 */
val ShelfFaceShade = Color(0xFF000000)

/** 封面表面随转角滑过的斜向反光。 */
val ShelfGloss = Color(0x2EFFFFFF)

/** 游戏盒倒角上的一线高光：上面两个切角迎光更亮，下面两个切角只微微反光。 */
val ShelfChamferLight = Color(0xB8FFFFFF)
val ShelfChamferDim = Color(0x52FFFFFF)

/** 书脊上压在封面图上的轻透柔光纱：顶部微提亮、底部轻压收住脚部，保持书脊色彩明亮。 */
val ShelfSpineScrimTop = Color(0x28FFFFFF)
val ShelfSpineScrimBottom = Color(0x24000000)

/** 书脊文字（压在暗纱 / 引擎色上）。 */
val ShelfSpineText = Color.White

/** 搁板板面：与卡片同一层级的水平面色，在深浅色与玻璃外观下都透出层次与倒影。 */
val ShelfLedgeSurface: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkSurface
        AppThemeColors.isGlass -> GlassSurface.copy(alpha = 0.68f)
        AppThemeColors.isDark -> LighterDark.copy(alpha = 0.85f)
        else -> WhiteLight.copy(alpha = 0.88f)
    }

/** 搁板前沿的发丝高光。 */
val ShelfLedgeRim: Color
    get() = when {
        AppThemeColors.isGlass || AppThemeColors.isDark -> Color(0x4DFFFFFF)
        else -> Color(0xFFFFFFFF)
    }

/** 倒影起始透明度：深色/复古玻璃板面上取适中偏清亮，浅色/白雾玻璃取略高，保证四套外观与视口内每本书都有可辨认倒影。 */
val ShelfReflectionAlpha: Float
    get() = if (AppThemeColors.isDark || AppThemeColors.isGlass) 0.34f else 0.42f

/**
 * 书架书脊的文字与圆弧明暗：深底用象牙白，亮底用炭墨黑（由书脊局部亮度自动选色并保证 4.5:1 以上对比度）；
 * 烫线、小菱饰与顶上的封条取这本书的封面主色。
 */
val ShelfSpineTitle = Color(0xFFFFFBF2)
val ShelfSpineTitleDark = Color(0xFF14161B)
val ShelfSpineRoundLight = Color(0x2EFFFFFF)
val ShelfSpineRoundShade = Color(0x1C000000)

/**
 * 藏书阁里「选定」（进入书架前显示的那一本）的效果：暖金色，不用主题色——细金线双框、书柜板上一汪淡淡的暖光、
 * 封面下书名加粗并垫一小段金线；只有这一本有，其余都不带选定效果。
 */
val ShelfSelectedGilt = Color(0xFFEBD6A4)

/** 藏书阁里书脊的书腰：一圈烟灰色的半透明纸带，印着白色序号（压在书脊上，固定配色）。 */
val ShelfObiSurface = Color(0x8C0B0C10)
val ShelfObiText = Color(0xE6FFFFFF)

/** 书柜背板：书后面的浅色板面（深色 / 玻璃底上很淡）。 */
val ShelfWallSurface: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkSurface.copy(alpha = 0.45f)
        AppThemeColors.isGlass -> Color(0x14FFFFFF)
        AppThemeColors.isDark -> Color(0x12FFFFFF)
        else -> WhiteLight.copy(alpha = 0.55f)
    }

/** 书柜里的暗角与板下软影（背板与搁板相接处、搁板下方、书投在背板上的侧影）。 */
val ShelfDepthShade = Color(0x29000000)

/** 搁板前沿（很薄的一条前端厚度，与水平顶面拉出明暗层次）。 */
val ShelfBoardEdge: Color
    get() = when {
        AppThemeColors.isMilkGlass -> Color(0xFFD9DCE4)
        AppThemeColors.isGlass -> Color(0xCC121418)
        AppThemeColors.isDark -> Color(0xFF17191E)
        else -> Color(0xFFCED4DF)
    }

/** 空气透视：远处的书蒙上的一层与页面背景同色的雾。 */
val ShelfFog: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkSurface
        AppThemeColors.isGlass -> GlassBgMid
        AppThemeColors.isDark -> DarkGrey
        else -> GreyLight
    }

/**
 * 玻璃外观风格（复古玻璃 / 白雾玻璃）下书架卡片与胶囊的玻璃光学：上缘一抹高光、顶边一线镜面反光、底部压一点深，
 * 读起来是一块有厚度的玻璃；默认外观（浅色 / 深色）下是平的卡片，不画这些——两类外观的材质要分得出来。
 */
val ShelfGlassSheen: Color
    get() = if (AppThemeColors.isMilkGlass) Color(0x80FFFFFF) else Color(0x29FFFFFF)
val ShelfGlassSpecular: Color
    get() = if (AppThemeColors.isMilkGlass) Color(0xF2FFFFFF) else Color(0x8CFFFFFF)
val ShelfGlassBase: Color
    get() = if (AppThemeColors.isMilkGlass) Color(0x14000000) else Color(0x40000000)

/**
 * 书架下方卡片（ADV 对话框、「我的书架」卡）的底：默认外观就是页面卡片色（[NavWhite]）；两种玻璃外观更透一些，
 * 配合玻璃光学读出「玻璃」，透出底下封面的色晕。
 */
val ShelfCardSurface: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkSurface.copy(alpha = 0.5f)
        AppThemeColors.isGlass -> GlassSurface.copy(alpha = 0.55f)
        else -> NavWhite
    }

/**
 * 封面上的启动球：与页面卡片同一种材质（[NavWhite]：默认白卡、深色卡、复古玻璃、白雾玻璃），只是更透一些，
 * 不挡封面；球心的 ▶ 用 [TextColor]，不用主题色。
 */
val ShelfPlaySurface: Color
    get() = when {
        AppThemeColors.isMilkGlass -> NavWhite.copy(alpha = PLAY_SURFACE_ALPHA_LIGHT)
        AppThemeColors.isGlass -> NavWhite.copy(alpha = PLAY_SURFACE_ALPHA_DARK)
        // 默认外观：透明度与两种玻璃外观对齐——深色档同复古玻璃，浅色档同白雾玻璃（底色仍是各自的卡片色）
        AppThemeColors.isDark -> NavWhite.copy(alpha = PLAY_SURFACE_ALPHA_DARK)
        else -> NavWhite.copy(alpha = PLAY_SURFACE_ALPHA_LIGHT)
    }

/** 启动球的透明度：深色底（复古玻璃与默认深色）、浅色底（白雾玻璃与默认浅色）各一个值，由主题决定底色、这里只管透明度。 */
private const val PLAY_SURFACE_ALPHA_DARK = 0.48f
private const val PLAY_SURFACE_ALPHA_LIGHT = 0.42f

/** 书签丝带的暗部：把绸面朝它压暗一些（不要太艳），以及它垂在封面上的一点贴身软影。 */
val ShelfRibbonDeep = Color(0xFF2A0F18)
val ShelfRibbonShadow = Color(0x33000000)

/** 书架页面底色是否是深色（深色模式 / 复古玻璃 / 白雾玻璃的图片底）：书架上的玻璃件据此换描边与亮度。 */
private val shelfOnDark: Boolean
    get() = AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop

/**
 * 书架进度轨道的墨色（不用主题色）：浅色底上是压淡的黑、深色底上是白，轨道再按线 / 刻度、翻过与否、在场感乘浓度。
 * 发丝轮廓（旋钮玻璃环、拖动气泡）：浅色底上白玻璃与背景几乎同色，用中性深色勾出（与浅色档悬浮玻璃的
 * glassEdgeStroke 同一思路）；深色底上用白色。
 */
val ShelfTrackInk: Color
    get() = if (shelfOnDark) Color(0xFFFFFFFF) else Color(0xB3000000)
val ShelfTrackOutline: Color
    get() = if (shelfOnDark) Color(0x33FFFFFF) else Color(0x1F000000)

/** 进度旋钮：玻璃环与它的中性柔光（按住时变亮）。 */
val ShelfKnobGlass: Color
    get() = if (shelfOnDark) Color(0x40FFFFFF) else Color(0x99FFFFFF)
val ShelfKnobGlow = Color(0xB3FFFFFF)

/**
 * 翻开的书：四套外观主题专属的纸张底色（不再直接复用纯白 #FFFFFF 或惨白白纱，避免在暗遮罩上刺眼）：
 * - 默认浅色：温润暖象牙轻型纸；
 * - 默认深色：沉静炭墨暖灰特种纸；
 * - 复古玻璃：带细微暖灰纸韵的深色磨砂玻璃纸；
 * - 白雾玻璃：叠在模糊底图上的温润半透明暖米宣纸纱（保留透彩同时压低亮度）。
 */
fun shelfBookPaperSurface(isGlass: Boolean, isMilkGlass: Boolean, isDark: Boolean): Color = when {
    isGlass -> Color(0xE81C1B1E)
    isMilkGlass -> Color(0x9CE8E0D2)
    isDark -> Color(0xFF23211E)
    else -> Color(0xFFEBE4D6)
}

val ShelfBookPaperSurface: Color
    get() = shelfBookPaperSurface(
        isGlass = AppThemeColors.isGlass,
        isMilkGlass = AppThemeColors.isMilkGlass,
        isDark = AppThemeColors.isDark,
    )

/** 书页自然暖色微晕 / 边缘柔和沉降（外缘与底边极轻收暗，减少荧光平白感）。 */
val ShelfBookPaperEdgeTint: Color
    get() = when {
        AppThemeColors.isGlass || AppThemeColors.isDark -> Color(0x28000000)
        AppThemeColors.isMilkGlass -> Color(0x1C786144)
        else -> Color(0x1F856B49)
    }

/** 书页内侧受光与装订拱起处的柔光（偏暖米白，避免冷白刺眼）。 */
val ShelfBookPaperHighlight: Color
    get() = when {
        AppThemeColors.isGlass -> Color(0x0EFFF7E8)
        AppThemeColors.isDark -> Color(0x10FFF7E8)
        AppThemeColors.isMilkGlass -> Color(0x26FFFBF0)
        else -> Color(0x2EFFFBF2)
    }

/** 玻璃外观下书页专用的柔和玻璃光学（克制白雾顶边亮条，让纸张质感占主角）。 */
val ShelfBookGlassSheen: Color
    get() = if (AppThemeColors.isMilkGlass) Color(0x30FFF9EE) else Color(0x18FFFFFF)
val ShelfBookGlassSpecular: Color
    get() = if (AppThemeColors.isMilkGlass) Color(0x66FFFBF2) else Color(0x4CFFFFFF)
val ShelfBookGlassBase: Color
    get() = if (AppThemeColors.isMilkGlass) Color(0x1A5A4834) else Color(0x36000000)

/** 左页衬纸的融合基色（与右页纸色同一色温，不再往纯白提亮发白）。 */
val ShelfBookEndpaperBase: Color
    get() = when {
        AppThemeColors.isGlass || AppThemeColors.isDark -> Color(0xFF1D1B19)
        else -> Color(0xFFE3DACB)
    }

/** 书页叠起来的细边（书页右缘、下缘）：浅色暖褐灰，深色暖米灰。 */
val ShelfBookPageEdge: Color
    get() = when {
        AppThemeColors.isGlass -> Color(0x2CEAE4DA)
        AppThemeColors.isDark -> Color(0x30E6DCCB)
        AppThemeColors.isMilkGlass -> Color(0x325C4F3E)
        else -> Color(0x386B5B45)
    }

/** 立体书芯侧面切口基底色（精装书整摞书页侧面厚度）。 */
val ShelfBookStackSurface: Color
    get() = when {
        AppThemeColors.isGlass -> Color(0xE0161518)
        AppThemeColors.isDark -> Color(0xFF1A1815)
        AppThemeColors.isMilkGlass -> Color(0xC2D6CCB8)
        else -> Color(0xFFDDD4C3)
    }

/** 顶页外缘微光边与书芯亮缝色。 */
val ShelfBookStackHighlight: Color
    get() = when {
        AppThemeColors.isGlass -> Color(0x28FFF7E8)
        AppThemeColors.isDark -> Color(0x24FFF4E0)
        AppThemeColors.isMilkGlass -> Color(0x66FFFDF7)
        else -> Color(0x88FFFDF6)
    }

/** 精装布面硬壳经纬织纹明暗色。 */
val ShelfBookClothWeaveLight = Color(0x16FFF6E8)
val ShelfBookClothWeaveDark = Color(0x22000000)

/** 精装封板内框与切角微烫金 / 压印光泽。 */
val ShelfBookFoilAccent: Color
    get() = when {
        AppThemeColors.isGlass -> Color(0x36E8D9BE)
        AppThemeColors.isDark -> Color(0x30D8C7A8)
        AppThemeColors.isMilkGlass -> Color(0x38C6AF88)
        else -> Color(0x40C9B189)
    }

/** 书脊顶底「堵头布（Headband / Tailband）」基底色与织纹色。 */
val ShelfBookHeadbandBase: Color
    get() = when {
        AppThemeColors.isGlass || AppThemeColors.isDark -> Color(0xFF2B2723)
        else -> Color(0xFFD8CEBC)
    }

val ShelfBookHeadbandStripe: Color
    get() = when {
        AppThemeColors.isGlass || AppThemeColors.isDark -> Color(0x52F5E9D4)
        else -> Color(0x666E573E)
    }

/** 丝带书签锁边与织物压纹高光。 */
val ShelfBookRibbonEdge = Color(0x52FFF9F0)

/** 书页内分栏（「目录 / 管理」）选中藏书票框底衬：温润压凹宣纸/羊皮纸色晕（不随主题强调色变化）。 */
val ShelfBookTabSurface: Color
    get() = when {
        AppThemeColors.isGlass -> Color(0x1AE8D9BE)
        AppThemeColors.isDark -> Color(0x18DFCBA9)
        AppThemeColors.isMilkGlass -> Color(0x1E5E4930)
        else -> Color(0x166A5337)
    }

/** 书页内分栏选中 45° 斜切细线框：古铜 / 香槟金描边。 */
val ShelfBookTabBorder: Color
    get() = when {
        AppThemeColors.isGlass -> Color(0x52E2CFAF)
        AppThemeColors.isDark -> Color(0x4AD4BE98)
        AppThemeColors.isMilkGlass -> Color(0x5C7D6340)
        else -> Color(0x528A6F4A)
    }

/** 书页内分栏底缘雕版笔锋线与中央菱形缀饰：胡桃墨 / 烫古铜色。 */
val ShelfBookTabIndicator: Color
    get() = when {
        AppThemeColors.isGlass -> Color(0xFFD6BE94)
        AppThemeColors.isDark -> Color(0xFFCBB186)
        AppThemeColors.isMilkGlass -> Color(0xFF705434)
        else -> Color(0xFF7B5E3B)
    }

/** 书页内分栏切换时掠过纸面的柔和羊皮纸/墨息光带色。 */
val ShelfBookTabWash: Color
    get() = when {
        AppThemeColors.isGlass -> Color(0xFFE0CCA8)
        AppThemeColors.isDark -> Color(0xFFD4BE98)
        AppThemeColors.isMilkGlass -> Color(0xFF826642)
        else -> Color(0xFF8C6F48)
    }

/** 书页页脚铭牌按钮（「启动游戏」 / 「删除游戏」）的四主题精装配色集，独立于全局圆角与默认亮色强调填充。 */
data class ShelfBookFooterStyle(
    val fillTop: Color,
    val fillBottom: Color,
    val outerBorder: Color,
    val topSpecular: Color,
    val radialSheen: Color,
    val ambientGlow: Color,
    val content: Color,
    val shadowAlpha: Float,
    val clothAlpha: Float,
)

private fun rgbToHsl(color: Color): FloatArray {
    val r = color.red.coerceIn(0f, 1f)
    val g = color.green.coerceIn(0f, 1f)
    val b = color.blue.coerceIn(0f, 1f)
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) * 0.5f
    val d = max - min
    if (d <= 1e-5f) return floatArrayOf(0f, 0f, l)
    val s = if (l > 0.5f) {
        d / (2f - max - min).coerceAtLeast(1e-5f)
    } else {
        d / (max + min).coerceAtLeast(1e-5f)
    }
    val hRaw = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return floatArrayOf(((hRaw * 60f) % 360f + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

private fun hslColor(hueDeg: Float, sat: Float, light: Float, alpha: Float = 1f): Color {
    val h = ((hueDeg % 360f) + 360f) % 360f
    val s = sat.coerceIn(0f, 1f)
    val l = light.coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c * 0.5f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * 跟随「色调轮盘」主题色生成「启动游戏」精装宝石釉面配色（全色相感知亮度归一化，保证四套主题高级通透且高对比）：
 * - 复古玻璃：半透深宝石琉璃 + 清亮同色外框与微光晕；
 * - 默认深色：丝绒精装深宝石釉 + 亚麻布纹肌理；
 * - 白雾玻璃：温润半透中高深琉璃 + 亮釉细边 + 镜面顶光；
 * - 默认浅色：古典精装书脊釉色 + 精细布纹 + 暖白字体。
 */
fun shelfBookLaunchStyle(
    primary: Color,
    isGlass: Boolean,
    isMilkGlass: Boolean,
    isDark: Boolean,
): ShelfBookFooterStyle {
    val hsl = rgbToHsl(primary)
    val useFallback = hsl[1] < 0.08f
    val h = if (useFallback) 216f else hsl[0]
    val s = if (useFallback) 0.34f else hsl[1]
    val hueLum = hslColor(h, 1f, 0.5f).luminance()
    val lTrim = 0.092f * ((hueLum - 0.16f) / 0.78f).coerceAtLeast(0f)
    val sNorm = s.coerceIn(0.34f, 0.76f)
    return when {
        isGlass -> {
            val sFill = (sNorm * 0.80f).coerceIn(0.32f, 0.58f)
            val lTop = (0.345f - lTrim).coerceAtLeast(0.19f)
            val lBot = (0.225f - lTrim * 0.85f).coerceAtLeast(0.13f)
            ShelfBookFooterStyle(
                fillTop = hslColor(h, sFill, lTop, 0.88f),
                fillBottom = hslColor(h, (sFill * 1.06f).coerceAtMost(1f), lBot, 0.94f),
                outerBorder = hslColor(h, (sNorm * 0.94f).coerceAtMost(1f), (0.68f - lTrim * 0.35f).coerceAtMost(0.78f), 0.68f),
                topSpecular = Color(0x66FFFFFF),
                radialSheen = hslColor(h, (sNorm * 0.95f).coerceAtMost(1f), (0.66f - lTrim * 0.20f).coerceAtMost(0.76f), 0.28f),
                ambientGlow = hslColor(h, (sNorm * 0.90f).coerceAtMost(1f), (0.52f - lTrim * 0.20f).coerceAtMost(0.62f), 0.22f),
                content = hslColor(h, (sNorm * 0.30f).coerceAtMost(0.22f), 0.97f, 1f),
                shadowAlpha = 0.36f,
                clothAlpha = 0.22f,
            )
        }
        isDark -> {
            val sFill = (sNorm * 0.76f).coerceIn(0.30f, 0.54f)
            val lTop = (0.335f - lTrim).coerceAtLeast(0.19f)
            val lBot = (0.225f - lTrim * 0.85f).coerceAtLeast(0.13f)
            ShelfBookFooterStyle(
                fillTop = hslColor(h, sFill, lTop, 1f),
                fillBottom = hslColor(h, (sFill * 1.06f).coerceAtMost(1f), lBot, 1f),
                outerBorder = hslColor(h, (sNorm * 0.86f).coerceAtMost(1f), (0.64f - lTrim * 0.35f).coerceAtMost(0.74f), 0.58f),
                topSpecular = Color(0x42FFFBF2),
                radialSheen = hslColor(h, (sNorm * 0.90f).coerceAtMost(1f), (0.62f - lTrim * 0.20f).coerceAtMost(0.72f), 0.20f),
                ambientGlow = hslColor(h, (sNorm * 0.85f).coerceAtMost(1f), (0.48f - lTrim * 0.20f).coerceAtMost(0.58f), 0.14f),
                content = hslColor(h, (sNorm * 0.26f).coerceAtMost(0.18f), 0.96f, 1f),
                shadowAlpha = 0.32f,
                clothAlpha = 0.34f,
            )
        }
        isMilkGlass -> {
            val sFill = (sNorm * 0.76f).coerceIn(0.30f, 0.54f)
            val lTop = (0.360f - lTrim * 1.18f).coerceAtLeast(0.20f)
            val lBot = (0.260f - lTrim * 1.05f).coerceAtLeast(0.14f)
            ShelfBookFooterStyle(
                fillTop = hslColor(h, sFill, lTop, 0.89f),
                fillBottom = hslColor(h, (sFill * 1.06f).coerceAtMost(1f), lBot, 0.94f),
                outerBorder = hslColor(h, (sNorm * 0.75f).coerceAtMost(1f), (lTop + 0.24f).coerceAtMost(0.76f), 0.62f),
                topSpecular = Color(0x73FFFFFF),
                radialSheen = hslColor(h, (sNorm * 0.85f).coerceAtMost(1f), (0.72f - lTrim * 0.20f).coerceAtMost(0.80f), 0.26f),
                ambientGlow = hslColor(h, (sNorm * 0.75f).coerceAtMost(1f), lBot.coerceAtLeast(0.22f), 0.14f),
                content = hslColor(h, (sNorm * 0.22f).coerceAtMost(0.16f), 0.98f, 1f),
                shadowAlpha = 0.22f,
                clothAlpha = 0.22f,
            )
        }
        else -> {
            val sFill = (sNorm * 0.74f).coerceIn(0.30f, 0.52f)
            val lTop = (0.355f - lTrim * 1.10f).coerceAtLeast(0.21f)
            val lBot = (0.260f - lTrim * 0.98f).coerceAtLeast(0.15f)
            ShelfBookFooterStyle(
                fillTop = hslColor(h, sFill, lTop, 1f),
                fillBottom = hslColor(h, (sFill * 1.06f).coerceAtMost(1f), lBot, 1f),
                outerBorder = hslColor(h, (sFill * 1.05f).coerceAtMost(1f), (lBot - 0.05f).coerceAtLeast(0.14f), 0.62f),
                topSpecular = Color(0x40FFFBF0),
                radialSheen = hslColor(h, (sNorm * 0.80f).coerceAtMost(1f), (0.68f - lTrim * 0.20f).coerceAtMost(0.76f), 0.18f),
                ambientGlow = hslColor(h, (sNorm * 0.70f).coerceAtMost(1f), lBot.coerceAtLeast(0.22f), 0.10f),
                content = hslColor(h, (sNorm * 0.20f).coerceAtMost(0.14f), 0.98f, 1f),
                shadowAlpha = 0.20f,
                clothAlpha = 0.36f,
            )
        }
    }
}

/** 展开书籍页脚「启动游戏」主按钮的四主题精装铭牌配色。 */
fun shelfBookLaunchStyle(primary: Color): ShelfBookFooterStyle = shelfBookLaunchStyle(
    primary = primary,
    isGlass = AppThemeColors.isGlass,
    isMilkGlass = AppThemeColors.isMilkGlass,
    isDark = AppThemeColors.isDark,
)

fun shelfBookDeleteStyle(isGlass: Boolean, isMilkGlass: Boolean, isDark: Boolean): ShelfBookFooterStyle = when {
    isGlass -> ShelfBookFooterStyle(
        fillTop = Color(0xDB452322),
        fillBottom = Color(0xE6341918),
        outerBorder = Color(0xB3D46D65),
        topSpecular = Color(0x40FFEBE8),
        radialSheen = Color(0x1EE07068),
        ambientGlow = Color(0x18B5463E),
        content = Color(0xFFF6B5AE),
        shadowAlpha = 0.28f,
        clothAlpha = 0f,
    )
    isDark -> ShelfBookFooterStyle(
        fillTop = Color(0xFF3E2220),
        fillBottom = Color(0xFF311918),
        outerBorder = Color(0x99C4625A),
        topSpecular = Color(0x22FFE4E0),
        radialSheen = Color(0x16D4665E),
        ambientGlow = Color(0x12A13D36),
        content = Color(0xFFF2ACA4),
        shadowAlpha = 0.24f,
        clothAlpha = 0f,
    )
    isMilkGlass -> ShelfBookFooterStyle(
        fillTop = Color(0xDCF5E7E1),
        fillBottom = Color(0xE8ECD6CE),
        outerBorder = Color(0xD6962F28),
        topSpecular = Color(0x66FFF9F5),
        radialSheen = Color(0x28FFF7F2),
        ambientGlow = Color.Transparent,
        content = Color(0xFF7A1D18),
        shadowAlpha = 0.12f,
        clothAlpha = 0f,
    )
    else -> ShelfBookFooterStyle(
        fillTop = Color(0x1CA6362E),
        fillBottom = Color(0x28942A23),
        outerBorder = Color(0xBD962F28),
        topSpecular = Color(0x26FFF5EE),
        radialSheen = Color(0x1AFFF5EE),
        ambientGlow = Color.Transparent,
        content = Color(0xFF82201A),
        shadowAlpha = 0.08f,
        clothAlpha = 0f,
    )
}

/** 展开书籍页脚「删除游戏」警示按钮的四主题精装铭牌配色。 */
val ShelfBookDeleteStyle: ShelfBookFooterStyle
    get() = shelfBookDeleteStyle(
        isGlass = AppThemeColors.isGlass,
        isMilkGlass = AppThemeColors.isMilkGlass,
        isDark = AppThemeColors.isDark,
    )

/** 书页的明暗（装订处的弯曲、封面 / 书页翻动时的背光、被压着的书页、封板与衬纸压暗）：纯黑，浓度由各处按进度给。 */
val ShelfBookShade = Color(0xFF000000)

/** 书的受光（封板上缘受光等）：纯白，浓度由各处给。 */
val ShelfBookLight = Color(0xFFFFFFFF)

/**
 * 目录行 / 管理行按下时那一笔荧光笔的颜色：纸上的暖墨，不用主题色——亮纸（默认浅色、白雾玻璃）是一道发黄的琥珀褐，
 * 暗纸（深色、复古玻璃）是一道奶黄的淡墨；浓度已带在颜色里。
 */
val ShelfBookMarker: Color
    get() = if (AppThemeColors.isDark || AppThemeColors.isGlass) Color(0x2EF0D9A8) else Color(0x38B7873F)

/** 遮罩上这本封面主色柔光的浓度。 */
const val ShelfBookGlowAlpha = 0.38f

/** 书架背景色晕（当前封面柔焦图）的透明度：浅色底上淡一点，深色 / 玻璃底上更淡，避免压过前景。 */
val ShelfBackdropAlpha: Float
    get() = when {
        AppThemeColors.isGlass || AppThemeColors.isMilkGlass -> 0.4f
        AppThemeColors.isDark -> 0.32f
        else -> 0.5f
    }

// ===== 书架全景「藏书阁」色板（随四套外观主题动态分档） =====

/** 藏书阁全景底色：柔和沉静，白雾玻璃交由 `MilkGlassPanelBackground` 叠加轻微暖雾纱。 */
val ShelfCatalogBackdrop: Color
    get() = when {
        AppThemeColors.isMilkGlass -> Color(0x5218171C)
        AppThemeColors.isGlass -> GlassBgMid.copy(alpha = 0.94f)
        AppThemeColors.isDark -> DarkGrey
        else -> GreyLight
    }

/** 藏书阁顶部检索台托盘底色。 */
val ShelfCatalogHeaderSurface: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkSurface
        AppThemeColors.isGlass -> GlassSurface.copy(alpha = 0.88f)
        AppThemeColors.isDark -> LighterDark.copy(alpha = 0.94f)
        else -> WhiteLight.copy(alpha = 0.92f)
    }

/** 藏书阁筛选书签胶囊未选底色。 */
val ShelfCatalogChipSurface: Color
    get() = when {
        AppThemeColors.isMilkGlass -> MilkItemOnPanel
        AppThemeColors.isGlass -> Color(0x16FFFFFF)
        AppThemeColors.isDark -> Color(0x14FFFFFF)
        else -> Color(0x0D000000)
    }

/** 藏书阁筛选书签胶囊已选底色。 */
val ShelfCatalogChipSelectedSurface: Color
    get() = ShelfBookTabSurface

/** 藏书阁选中胶囊动态底色（适配四套主题对比度与强调色融合）。 */
fun shelfCatalogSelectedChipBg(accent: Color): Color = when {
    AppThemeColors.isGlass || AppThemeColors.isDark -> accent.copy(alpha = 0.28f)
    AppThemeColors.isMilkGlass -> accent.copy(alpha = 0.16f)
    else -> accent.copy(alpha = 0.14f)
}

/** 藏书阁选中胶囊动态描边色。 */
fun shelfCatalogSelectedChipBorder(accent: Color): Color = when {
    AppThemeColors.isGlass || AppThemeColors.isDark -> lerp(accent, Color.White, 0.35f).copy(alpha = 0.72f)
    else -> accent.copy(alpha = 0.62f)
}

/** 藏书阁选中胶囊文字/图标高对比前景色：深色/复古玻璃提亮成柔和萤光色调，浅色/白雾保持浓郁墨色调。 */
fun shelfCatalogSelectedChipText(accent: Color): Color = when {
    AppThemeColors.isGlass || AppThemeColors.isDark -> lerp(accent, Color.White, 0.64f)
    else -> if (accent.luminance() > 0.35f) lerp(accent, Color.Black, 0.36f) else accent
}

/** 藏书阁书柜暗腔（封面模式板下书名等页底文字）当前焦点高亮色：玻璃/深色/白雾均落于暗底需提亮，默认浅色保持浓郁强调色。 */
fun shelfCatalogBackdropAccentText(accent: Color): Color = when {
    AppThemeColors.isGlass || AppThemeColors.isDark || AppThemeColors.isMilkGlass ->
        lerp(accent, Color.White, 0.64f)
    else -> if (accent.luminance() > 0.35f) lerp(accent, Color.Black, 0.36f) else accent
}

/** 藏书阁筛选书签胶囊微描边。 */
val ShelfCatalogChipBorder: Color
    get() = when {
        AppThemeColors.isMilkGlass -> Color(0x285E4930)
        AppThemeColors.isGlass -> GlassBorder
        AppThemeColors.isDark -> Color(0x24FFFFFF)
        else -> Color(0x18000000)
    }

/** 藏书阁章节分隔线色。 */
val ShelfCatalogSectionRule: Color
    get() = when {
        AppThemeColors.isMilkGlass -> Color(0x45FFF6E8)
        AppThemeColors.isGlass -> Color(0x2EFFFFFF)
        AppThemeColors.isDark -> Color(0x29FFFFFF)
        else -> Color(0x22000000)
    }

/** 藏书阁顶部检索台面板内的拖拽手柄与分组细线色（白雾亮面下使用暖深色，避免白底白线隐形）。 */
val ShelfCatalogHeaderRule: Color
    get() = when {
        AppThemeColors.isMilkGlass -> Color(0x385E4930)
        AppThemeColors.isGlass -> Color(0x38FFFFFF)
        AppThemeColors.isDark -> Color(0x33FFFFFF)
        else -> Color(0x28000000)
    }

/** 藏书阁书柜左右侧立柱与分隔门框材质色。 */
val ShelfCatalogCabinetFrame: Color
    get() = when {
        AppThemeColors.isMilkGlass -> Color(0x8CE8DDD0)
        AppThemeColors.isGlass -> Color(0x9E171924)
        AppThemeColors.isDark -> Color(0xCC1E1B28)
        else -> Color(0xB8D9D2C8)
    }

/** 藏书阁每层书格内腔上方与侧壁纵深凹槽阴影。 */
val ShelfCatalogAlcoveShadow: Color
    get() = when {
        AppThemeColors.isMilkGlass -> Color(0x26382C1D)
        AppThemeColors.isGlass -> Color(0x48070910)
        AppThemeColors.isDark -> Color(0x4208070D)
        else -> Color(0x1C1A1713)
    }

/** 藏书阁搁板前沿区间铭牌底色。 */
val ShelfCatalogPlaqueSurface: Color
    get() = when {
        AppThemeColors.isMilkGlass -> Color(0xEBFAF4EA)
        AppThemeColors.isGlass -> Color(0xCC1E222E)
        AppThemeColors.isDark -> Color(0xEB262333)
        else -> Color(0xF2F3EFE7)
    }

/** 藏书阁搁板前沿区间铭牌文字色。 */
val ShelfCatalogPlaqueText: Color
    get() = when {
        AppThemeColors.isMilkGlass -> Color(0xFF4F3E2A)
        AppThemeColors.isGlass -> Color(0xDCE8EDF6)
        AppThemeColors.isDark -> Color(0xFFD6D0E8)
        else -> Color(0xFF4E483F)
    }

/** 藏书阁书脊精装质感：顶部凸棱与微框香槟烫金细线。 */
val ShelfCatalogSpineFoil: Color
    get() = when {
        AppThemeColors.isGlass || AppThemeColors.isDark -> Color(0x3CF5E7CE)
        else -> Color(0x4AFFF8EB)
    }

/** 藏书阁书脊 3D 圆柱曲面左侧高光脊色。 */
val ShelfCatalogSpineCylindricalLight = Color(0x1CFFFFFF)

/** 藏书阁书脊 3D 圆柱曲面右侧卷绕暗部色。 */
val ShelfCatalogSpineCylindricalShade = Color(0x4C06070B)

/** 藏书阁书脊底部轻盈半透序号条底色与分隔微线。 */
val ShelfCatalogSpineFooterSurface = Color(0x6E0B0D14)
val ShelfCatalogSpineFooterRule = Color(0x2EFFFFFF)

/** 藏书阁书脊上下两端布面收口柔影色。 */
val ShelfCatalogSpineTopShadow = Color(0x34000000)
