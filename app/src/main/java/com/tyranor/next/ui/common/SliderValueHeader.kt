package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tyranor.next.ui.common.motion.BlurText

/**
 * 滑条上方的「标题 + 当前读数」行（扫描深度、应用圆角调整等）：标题居左，读数居右。
 * 读数变化（拖动跨档、被其它设置联动、重置）时只替换真正变化的片段并原地模糊（见 [BlurText]），
 * 新增带读数的滑条请直接使用本组件，不要自己拼 `Row { Text; Text }`。
 *
 * 布局修饰符（宽度、内边距、入场等）由调用方通过 [modifier] 传入，本组件不额外加任何间距。
 * 逐帧变化的连续读数不要用（过渡追不上手指），直接显示即可。
 */
@Composable
fun SliderValueHeader(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        BlurText(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
