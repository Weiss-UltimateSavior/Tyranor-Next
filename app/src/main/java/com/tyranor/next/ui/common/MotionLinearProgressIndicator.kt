// Drawing adapted from Miuix 0.9.2 ProgressIndicator.kt.
// Copyright 2025, compose-miuix-ui contributors. SPDX-License-Identifier: Apache-2.0
package com.tyranor.next.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix 原有几何与取色；增强档只在绘制阶段读取平滑进度，普通档和不定态仍用库组件。 */
@Composable
internal fun MotionLinearProgressIndicator(progress: Float?, modifier: Modifier = Modifier) {
    // 始终保留同一个动画状态，确定/不定态切换时延续原来的进度轨迹。
    val shown = animateFloatAsState(progress ?: 0f, MotionTokens.value(), label = "updateProgress")
    if (!MotionMode.enhanced || progress == null) {
        LinearProgressIndicator(modifier = modifier, progress = progress)
        return
    }
    val foreground = MiuixTheme.colorScheme.primary
    val background = MiuixTheme.colorScheme.secondaryContainer
    Canvas(
        modifier = modifier
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f) }
            .fillMaxWidth()
            .height(ProgressIndicatorDefaults.DefaultLinearProgressIndicatorHeight),
    ) {
        val radius = size.height / 2f
        val corners = CornerRadius(radius)
        drawRoundRect(background, size = size, cornerRadius = corners)
        val minWidth = radius * 2f
        val width = minWidth + (size.width - minWidth) * shown.value.coerceIn(0f, 1f)
        drawRoundRect(foreground, size = Size(width, size.height), cornerRadius = corners)
    }
}
