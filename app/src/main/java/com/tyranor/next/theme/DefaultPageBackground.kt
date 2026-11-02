package com.tyranor.next.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 默认外观风格的页面背景：纯色底（取主题 `background`，即 `PageGrey` 档）。
 *
 * - 仅**默认外观风格**生效；玻璃系外观风格有独立的页面背景实现（[GlassBackground]），本组件为 no-op。
 * - 背景层必须是**独立空层**（不含页面内容），由页面根作为第一个子节点挂载。
 */
@Composable
fun DefaultPageBackgroundLayer(modifier: Modifier = Modifier) {
    if (AppThemeColors.isGlass) return
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}
