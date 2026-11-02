package com.tyranor.next.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
@NonSkippableComposable
fun TyranorNextTheme(
  primaryColor: Color? = null,
  content: @Composable () -> Unit,
) {
  // 固定配色，不随系统深/浅色模式或壁纸取色变化；primaryColor 由应用设置「色调轮盘」提供
  AppThemeColors.ensureLoaded(LocalContext.current)
  // 在函数体内直接读取全局主题色：配合 @NonSkippableComposable，主题色变更时本主题
  // 必然重组并向整棵组合树提供新 colorScheme（不依赖调用点对默认参数的订阅）。
  val primary = primaryColor ?: AppThemeColors.primary
  // 深/浅色由应用设置「外观模式」控制；背景/文字用随模式的动态常量（Color.kt getter）
  val dark = AppThemeColors.isDark
  val colorScheme = if (dark) {
    darkColorScheme(
      primary = primary,
      secondary = Teal40,
      tertiary = Amber40,
      background = PageGrey,
      surface = PageGrey,
      onBackground = TextColor,
      onSurface = TextColor,
    )
  } else {
    lightColorScheme(
      primary = primary,
      secondary = Teal40,
      tertiary = Amber40,
      // 页面背景与组件色取 theme/Color.kt 常量（随深色模式 / 玻璃外观切换）
      background = PageGrey,
      surface = PageGrey,
      onBackground = TextColor,
      onSurface = TextColor,
    )
  }
  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content,
  )
}
