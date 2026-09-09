package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 占位页面：顶部栏复用统一组件 [AppTopBar]，正文居中显示标题和一句用于区分的话。
 * 仅用于新页面骨架搭建；真实功能页必须直接使用 [AppTopBar] 而非本组件。
 */
@Composable
internal fun PlaceholderPage(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        AppTopBar(title = title)
        // 正文区域
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}