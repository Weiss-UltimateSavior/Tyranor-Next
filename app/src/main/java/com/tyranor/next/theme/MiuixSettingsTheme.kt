package com.tyranor.next.theme

import androidx.compose.material3.LocalContentColor as MaterialLocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 弹窗颜色上下文由 AppAlertDialog 提供；输入框与标签栏复用同一动态条目色。 */
internal val LocalAppDialogContent = staticCompositionLocalOf { false }

/**
 * 文字统一规范：页面内容只用 titleMedium(15sp)/bodyMedium(14sp) 两档。
 * Miuix preference 的标题默认用 headline1(17sp)，这里覆盖为 15sp，
 * 使其严格落入两档内，与 Material titleMedium 一致。
 */
internal val TyranorMiuixTextStyles = defaultTextStyles(
    headline1 = TextStyle(fontSize = 15.sp),
)

/**
 * 设置页专用 Miuix 主题：配色与 TyranorNextTheme 对齐，深/浅色由应用设置「外观模式」控制，
 * 页面背景与组件色统一取 `theme/Color.kt` 常量（复古玻璃用深色玻璃，白雾玻璃用亮面白雾条目）。
 * primaryColor 由应用设置「色调轮盘」提供，默认蓝与 TyranorNextTheme 一致。
 */
@Composable
@NonSkippableComposable
fun MiuixSettingsTheme(
    primaryColor: Color? = null,
    textStyles: TextStyles = TyranorMiuixTextStyles,
    content: @Composable () -> Unit,
) {
    AppThemeColors.ensureLoaded(LocalContext.current)
    // 与 TyranorNextTheme 同理：在函数体内直接读取全局主题色，
    // 配合 @NonSkippableComposable 保证轮盘切换主题色时本主题必然重组，
    // 使设置类页面与其余页面同步跟随主题色。
    val primary = primaryColor ?: AppThemeColors.primary
    val dark = AppThemeColors.isDark
    val glass = AppThemeColors.isGlass
    val milk = AppThemeColors.isMilkGlass
    val baseColors = if (dark) {
        darkColorScheme(
            primary = primary,
            background = PageGrey,
            surface = PageGrey,
            surfaceContainer = NavWhite,
            // Miuix 的 preference 标题默认取 onBackground（库内把“页面背景”和“卡片内容”
            // 共用同一色槽）。白雾玻璃的条目是亮面，必须让卡片内文字取深色；页面顶栏
            // 统一显式传 PageTextColor，避免把深色标题画到深色底图上。
            onBackground = if (milk) TextColor else PageTextColor,
            onSurface = TextColor,
            onSurfaceContainer = TextColor,
            sliderKeyPointForeground = Color.White,
        )
    } else {
        lightColorScheme(
            primary = primary,
            background = PageGrey,
            surface = PageGrey,
            surfaceContainer = NavWhite,
            onBackground = if (milk) TextColor else PageTextColor,
            onSurface = TextColor,
            onSurfaceContainer = TextColor,
            sliderKeyPointForeground = Color.White,
        )
    }
    // 复古玻璃：输入框/浮层/分割线/遮罩改用深色玻璃色板；
    // 白雾玻璃：亮面条目 + 深色正文，页面级文字保持近白。
    // 卡片底色（surfaceContainer）与文字（onSurface*）已由动态色常量自动覆盖。
    val colors = when {
        glass -> baseColors.copy(
            surfaceContainerHigh = GlassSurfaceHigh,
            surfaceContainerHighest = GlassSurfaceHigh,
            secondaryContainer = GlassSurfaceHigh,
            onSurfaceContainerHigh = TextColor,
            onSurfaceContainerHighest = TextColor,
            onSecondaryContainer = TextColor,
            onSurfaceContainerVariant = GlassTextSecondary,
            onBackgroundVariant = GlassTextSecondary,
            onSurfaceSecondary = GlassTextSecondary,
            onSurfaceVariantSummary = GlassTextSecondary,
            onSurfaceVariantActions = GlassTextSecondary.copy(alpha = 0.6f),
            outline = GlassBorder,
            dividerLine = GlassBorder,
            windowDimming = Color.Black.copy(alpha = 0.6f),
        )
        milk -> baseColors.copy(
            surfaceContainerHigh = MilkSurfaceHigh,
            surfaceContainerHighest = MilkSurfaceHigh,
            secondaryContainer = MilkSurfaceHigh,
            onSurfaceContainerHigh = TextColor,
            onSurfaceContainerHighest = TextColor,
            onSecondaryContainer = TextColor,
            onSurfaceContainerVariant = MilkTextSecondary,
            onBackgroundVariant = PageSecondaryTextColor,
            onSurfaceSecondary = MilkTextSecondary,
            onSurfaceVariantSummary = MilkTextSecondary,
            onSurfaceVariantActions = MilkTextSecondary.copy(alpha = 0.7f),
            disabledOnSecondary = MilkText.copy(alpha = 0.42f),
            disabledOnSecondaryVariant = MilkText.copy(alpha = 0.42f),
            disabledOnSurface = MilkText.copy(alpha = 0.42f),
            outline = Color.Transparent,
            dividerLine = MilkDivider,
            windowDimming = Color.Black.copy(alpha = 0.45f),
        )
        else -> baseColors
    }
    val inBookLeaf = LocalShelfBookLeaf.current
    val bookPalette = if (inBookLeaf) CurrentBookPaperPalette else null
    val effectiveTextStyles = if (inBookLeaf && textStyles === TyranorMiuixTextStyles) {
        bookMiuixTextStyles(BookSerif, BookSerifBold)
    } else {
        textStyles
    }
    // 只在弹窗内映射反色条目；普通页面保留 Miuix 的输入框底色和图标色。
    // 玻璃页面沿用上面的玻璃色板，弹窗由 DialogItemSurface 选择对应条目色；
    // 位于翻开的书页内时自动切换为纸墨色板（透明页面底 + 纸面浮层 + 墨色文字 + 烫金强调）。
    val dialogColors = when {
        bookPalette != null -> colors.copy(
            primary = bookPalette.foilAccent,
            onPrimary = bookPalette.paperSurface,
            background = Color.Transparent,
            surface = Color.Transparent,
            surfaceContainer = bookPalette.popupSurface,
            surfaceContainerHigh = bookPalette.washSubtle,
            surfaceContainerHighest = bookPalette.popupSurface,
            secondaryContainer = bookPalette.washSubtle,
            onBackground = bookPalette.ink,
            onSurface = bookPalette.ink,
            onSurfaceContainer = bookPalette.ink,
            onSurfaceContainerHigh = bookPalette.ink,
            onSurfaceContainerHighest = bookPalette.ink,
            onSecondaryContainer = bookPalette.ink,
            onSurfaceContainerVariant = bookPalette.inkSecondary,
            onBackgroundVariant = bookPalette.inkSecondary,
            onSurfaceSecondary = bookPalette.inkSecondary,
            onSurfaceVariantSummary = bookPalette.inkSecondary,
            onSurfaceVariantActions = bookPalette.inkSecondary,
            outline = bookPalette.popupBorder,
            dividerLine = bookPalette.rule,
            disabledOnSecondary = bookPalette.inkMuted,
            disabledOnSecondaryVariant = bookPalette.inkMuted,
            disabledOnSurface = bookPalette.inkMuted,
        )
        LocalAppDialogContent.current -> colors.copy(
            surfaceContainerHigh = DialogItemSurface,
            surfaceContainerHighest = DialogItemSurface,
            onSurfaceContainerHigh = TextColor,
            onSurfaceContainerHighest = TextColor,
        )
        else -> colors
    }
    val effectiveContentColor = bookPalette?.ink ?: TextColor
    MiuixTheme(
        colors = dialogColors,
        textStyles = effectiveTextStyles,
    ) {
        // Miuix 的 Card 只提供 Miuix 自己的 LocalContentColor；页面里混用的 Material Text
        // 读的是 material3.LocalContentColor，若不覆盖会继续取根部 onBackground（白雾玻璃下
        // 为近白），于是卡片标题/“扫描深度”等出现白字。Miuix 页的内容底色是亮面条目，
        // 这里统一把 Material 默认内容色也指向 TextColor（书页内为纸墨色）；顶栏等页面级文字仍显式用 PageTextColor。
        CompositionLocalProvider(MaterialLocalContentColor provides effectiveContentColor) {
            WithoutPressIndication(content)
        }
    }
}
