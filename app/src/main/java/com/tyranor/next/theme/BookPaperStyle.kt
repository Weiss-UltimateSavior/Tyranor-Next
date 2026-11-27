package com.tyranor.next.theme

import android.graphics.Typeface
import android.os.Build
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 标记当前组合子树处于「游戏盒 · 翻开的书」书页内。
 * 由书页宿主（`ShelfBookLeafPage`）统一提供为 `true`；公共组件与主题读取它后自动切换为纸墨外观，
 * 使新增书内界面无需额外样板代码即可自然融入书页。
 */
internal val LocalShelfBookLeaf = staticCompositionLocalOf { false }

/**
 * 翻开的书内页「纸墨」色板（同时兼容默认浅/深、复古玻璃、白雾玻璃四套外观主题与深/浅书页）。
 * 保持与主书页目录/管理页一致的纸面、墨色、烫金与朱砂印泥语义，保证 WCAG 可读对比度。
 */
@Immutable
internal data class BookPaperPalette(
    val isDarkPaper: Boolean,
    val paperSurface: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val foilAccent: Color,
    val dangerInk: Color,
    val rule: Color,
    val ruleStrong: Color,
    val ruleSubtle: Color,
    val marker: Color,
    val washSubtle: Color,
    val popupSurface: Color,
    val popupBorder: Color,
    val switchTrackOff: Color,
    val switchBorderOff: Color,
    val switchThumbOff: Color,
    val switchTrackOn: Color,
    val switchBorderOn: Color,
    val switchThumbOn: Color,
    val sliderTrack: Color,
    val sliderActive: Color,
    val sliderThumb: Color,
    val sliderThumbRim: Color,
    val checkboxBorder: Color,
    val checkboxCheckedFill: Color,
    val checkboxCheckedBorder: Color,
    val checkboxCheck: Color,
)

/** 按四套外观主题解析当前书页的「纸墨」色板（纯函数，可用于离线单测）。 */
internal fun bookPaperPalette(
    isGlass: Boolean,
    isMilkGlass: Boolean,
    isDark: Boolean,
): BookPaperPalette {
    val darkPaper = isDark || isGlass
    val paperSurface = shelfBookPaperSurface(isGlass = isGlass, isMilkGlass = isMilkGlass, isDark = isDark)
    val ink = when {
        isGlass -> GlassText
        isMilkGlass -> MilkText
        isDark -> Color(0xFFE3E4E6)
        else -> Color(0xFF1F2329)
    }
    val inkSecondary = when {
        isGlass -> Color(0xFFB5B0A8)
        isMilkGlass -> Color(0xFF4F463B)
        isDark -> Color(0xFFB3ABA0)
        else -> Color(0xFF5A5145)
    }
    val foilAccent = when {
        isGlass -> Color(0xFFD6BE94)
        isDark -> Color(0xFFCBB186)
        isMilkGlass -> Color(0xFF705434)
        else -> Color(0xFF7B5E3B)
    }
    val dangerInk = if (darkPaper) Color(0xFFE88D84) else Color(0xFF8F241E)
    val marker = if (darkPaper) Color(0x2EF0D9A8) else Color(0x38B7873F)
    val washSubtle = if (darkPaper) Color(0x16E8D9BE) else Color(0x146A5337)
    val popupSurface = when {
        isGlass -> Color(0xF51D1C20)
        isDark -> Color(0xFF282521)
        isMilkGlass -> Color(0xF5EFE8DA)
        else -> Color(0xFFF3ECE0)
    }
    val popupBorder = when {
        isGlass -> Color(0x52E2CFAF)
        isDark -> Color(0x4AD4BE98)
        isMilkGlass -> Color(0x5C7D6340)
        else -> Color(0x528A6F4A)
    }
    return BookPaperPalette(
        isDarkPaper = darkPaper,
        paperSurface = paperSurface,
        ink = ink,
        inkSecondary = inkSecondary,
        inkMuted = ink.copy(alpha = 0.38f),
        foilAccent = foilAccent,
        dangerInk = dangerInk,
        rule = ink.copy(alpha = 0.28f),
        ruleStrong = ink.copy(alpha = 0.50f),
        ruleSubtle = ink.copy(alpha = 0.16f),
        marker = marker,
        washSubtle = washSubtle,
        popupSurface = popupSurface,
        popupBorder = popupBorder,
        switchTrackOff = Color.Transparent,
        switchBorderOff = ink.copy(alpha = 0.45f),
        switchThumbOff = inkSecondary,
        switchTrackOn = foilAccent.copy(alpha = 0.22f),
        switchBorderOn = foilAccent,
        switchThumbOn = foilAccent,
        sliderTrack = ink.copy(alpha = 0.24f),
        sliderActive = foilAccent,
        sliderThumb = foilAccent,
        sliderThumbRim = paperSurface,
        checkboxBorder = ink.copy(alpha = 0.55f),
        checkboxCheckedFill = foilAccent.copy(alpha = 0.20f),
        checkboxCheckedBorder = foilAccent,
        checkboxCheck = foilAccent,
    )
}

/** 当前主题下的书页纸墨色板。 */
internal val CurrentBookPaperPalette: BookPaperPalette
    get() = bookPaperPalette(
        isGlass = AppThemeColors.isGlass,
        isMilkGlass = AppThemeColors.isMilkGlass,
        isDark = AppThemeColors.isDark,
    )

/**
 * 书内宋体（衬线体）：直接载入系统 Noto Serif CJK（按语言区简/繁/日/韩字形），
 * 没有该文件时回落到 [FontFamily.Serif]。
 */
internal val BookSerif: FontFamily
    @Composable
    @ReadOnlyComposable
    get() = bookSerif(LocalLocale.current.platformLocale, bold = false)

/** 加粗宋体（书名、章名、小标题、书卷按钮）。 */
internal val BookSerifBold: FontFamily
    @Composable
    @ReadOnlyComposable
    get() = bookSerif(LocalLocale.current.platformLocale, bold = true)

private fun bookSerif(locale: Locale, bold: Boolean): FontFamily {
    val index = bookSerifIndex(locale)
    return bookSerifFamilies.computeIfAbsent(index * 2 + if (bold) 1 else 0) {
        val regular = bookSerifTypeface(index)
        val typeface = if (bold && regular != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(regular, BOOK_SERIF_BOLD_WEIGHT, false)
            } else {
                Typeface.create(regular, Typeface.BOLD)
            }
        } else {
            regular
        }
        typeface?.let { FontFamily(it) } ?: FontFamily.Serif
    }
}

private fun bookSerifIndex(locale: Locale): Int = when {
    locale.language == "ja" -> 0
    locale.language == "ko" -> 1
    locale.language == "zh" && (locale.script == "Hant" || locale.country in BOOK_SERIF_HANT_REGIONS) -> 3
    else -> 2
}

private fun bookSerifTypeface(index: Int): Typeface? = bookSerifTypefaces.computeIfAbsent(index) {
    val file = File(BOOK_SERIF_FILE)
    val typeface = if (file.isFile) runCatching { Typeface.Builder(file).setTtcIndex(index).build() }.getOrNull() else null
    typeface ?: BOOK_SERIF_MISSING
}.takeIf { it !== BOOK_SERIF_MISSING }

/** 提前在后台加载宋体字体集合，避免翻书首帧阻塞。 */
internal fun warmBookSerif(locale: Locale) {
    bookSerif(locale, bold = false)
    bookSerif(locale, bold = true)
}

private val bookSerifFamilies = ConcurrentHashMap<Int, FontFamily>()
private val bookSerifTypefaces = ConcurrentHashMap<Int, Typeface>()
private val BOOK_SERIF_MISSING: Typeface by lazy { Typeface.DEFAULT }
private const val BOOK_SERIF_FILE = "/system/fonts/NotoSerifCJK-Regular.ttc"
private val BOOK_SERIF_HANT_REGIONS = setOf("TW", "HK", "MO")
private const val BOOK_SERIF_BOLD_WEIGHT = 700

/** 保留既有字号与行高（不小于当前两档规范），仅将字体族切换为书卷宋体。 */
internal fun bookTypography(
    base: Typography,
    regular: FontFamily,
    bold: FontFamily,
): Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = bold),
    displayMedium = base.displayMedium.copy(fontFamily = bold),
    displaySmall = base.displaySmall.copy(fontFamily = bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = bold),
    titleLarge = base.titleLarge.copy(fontFamily = bold),
    titleMedium = base.titleMedium.copy(fontFamily = bold),
    titleSmall = base.titleSmall.copy(fontFamily = bold),
    bodyLarge = base.bodyLarge.copy(fontFamily = regular),
    bodyMedium = base.bodyMedium.copy(fontFamily = regular),
    bodySmall = base.bodySmall.copy(fontFamily = regular),
    labelLarge = base.labelLarge.copy(fontFamily = bold),
    labelMedium = base.labelMedium.copy(fontFamily = regular),
    labelSmall = base.labelSmall.copy(fontFamily = regular),
)

/** 保留 Miuix 两档字号（`headline1 = 15.sp` 等），统一替换为书卷宋体。 */
internal fun bookMiuixTextStyles(
    regular: FontFamily,
    bold: FontFamily,
): TextStyles {
    val base = TyranorMiuixTextStyles
    return defaultTextStyles(
        main = base.main.copy(fontFamily = regular),
        paragraph = base.paragraph.copy(fontFamily = regular),
        body1 = base.body1.copy(fontFamily = regular),
        body2 = base.body2.copy(fontFamily = regular),
        button = base.button.copy(fontFamily = bold),
        footnote1 = base.footnote1.copy(fontFamily = regular),
        footnote2 = base.footnote2.copy(fontFamily = regular),
        headline1 = base.headline1.copy(fontFamily = bold),
        headline2 = base.headline2.copy(fontFamily = bold),
        subtitle = base.subtitle.copy(fontFamily = bold),
        title1 = base.title1.copy(fontFamily = bold),
        title2 = base.title2.copy(fontFamily = bold),
        title3 = base.title3.copy(fontFamily = bold),
        title4 = base.title4.copy(fontFamily = bold),
    )
}

/**
 * 书页宿主统一提供的「纸墨」主题覆盖：
 * - 注入 `LocalShelfBookLeaf = true` 与 `LocalAppDialogContent = true`；
 * - 覆盖 Material3 `colorScheme`（墨色前景、烫金强调、朱砂错误、透明底）与 `bookTypography`（全宋体）；
 * - 嵌套 `MiuixSettingsTheme` 同步切为纸墨色板与宋体 `TextStyles`。
 */
@Composable
internal fun ShelfBookLeafTheme(content: @Composable () -> Unit) {
    val palette = CurrentBookPaperPalette
    val regular = BookSerif
    val bold = BookSerifBold
    val baseScheme = MaterialTheme.colorScheme
    val baseTypography = MaterialTheme.typography
    val bookScheme = baseScheme.copy(
        primary = palette.foilAccent,
        onPrimary = palette.paperSurface,
        secondary = palette.inkSecondary,
        onSecondary = palette.paperSurface,
        background = Color.Transparent,
        onBackground = palette.ink,
        surface = Color.Transparent,
        onSurface = palette.ink,
        surfaceVariant = palette.washSubtle,
        onSurfaceVariant = palette.inkSecondary,
        error = palette.dangerInk,
        outline = palette.rule,
        outlineVariant = palette.ruleSubtle,
    )
    val typography = bookTypography(baseTypography, regular, bold)
    CompositionLocalProvider(
        LocalShelfBookLeaf provides true,
        LocalAppDialogContent provides true,
        LocalContentColor provides palette.ink,
    ) {
        MaterialTheme(
            colorScheme = bookScheme,
            typography = typography,
            shapes = MaterialTheme.shapes,
        ) {
            MiuixSettingsTheme(content = content)
        }
    }
}
