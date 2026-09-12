package com.tyranor.next.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 组件圆角统一入口：默认风格 8dp；玻璃外观风格与玻璃悬浮导航条一致（32dp）。
 * 所有卡片/条目/弹窗/抽屉等「组件级」圆角必须引用本文件，不再散落字面量。
 * 例外：顶栏图标 6dp（TopBarIcon）、搜索框胶囊（CircleShape）、液态玻璃导航 16dp（规范豁免）。
 */
val AppComponentShape: Shape
    get() = if (AppThemeColors.isGlass) GlassComponentShape else DefaultComponentShape

/** Miuix 组件（MiuixCard 等）以 Dp 接收圆角，使用本值保证与 [AppComponentShape] 同源。 */
val AppComponentCornerRadius: Dp
    get() = if (AppThemeColors.isGlass) 32.dp else 8.dp

/** 底部抽屉顶部圆角（仅上方两角）。 */
val AppSheetTopShape: Shape
    get() = if (AppThemeColors.isGlass) {
        RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    } else {
        RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    }

private val DefaultComponentShape = RoundedCornerShape(8.dp)
private val GlassComponentShape = RoundedCornerShape(32.dp)
