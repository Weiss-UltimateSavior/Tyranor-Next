// Drawing adapted from Miuix 0.9.2 ProgressIndicator.kt.
// Copyright 2025, compose-miuix-ui contributors. SPDX-License-Identifier: Apache-2.0
package com.tyranor.next.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.first
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

/** 进度条画到多满时才播完成脉冲（与 value 弹簧的停止阈值无关，避免等尾巴）。 */
private const val COMPLETE_PULSE_FILL = 0.995f

/** 完成脉冲的初速度：pop 弹簧下峰值约 1。 */
private const val COMPLETE_PULSE_KICK = 36f

/** 完成脉冲峰值时条身纵向放大的比例。 */
private const val COMPLETE_PULSE_SCALE = 0.5f

/** Miuix 原有几何与取色；增强档只在绘制阶段读取平滑进度，普通档和不定态仍用库组件。 */
@Composable
internal fun MotionLinearProgressIndicator(progress: Float?, modifier: Modifier = Modifier) {
    // 始终保留同一个动画状态，确定/不定态切换时延续原来的进度轨迹。
    val shown = animateFloatAsState(progress ?: 0f, MotionTokens.value(), label = "updateProgress")
    // 完成态落点：进度从未满变为满、且进度条真正画满之后，条身纵向「鼓」一下再 Q 弹收回
    // （只改绘制图层，停稳为原样；首次组合即为满时不播）
    val completePulse = remember { Animatable(0f) }
    val complete = progress != null && progress >= 1f
    val wasComplete = remember { booleanArrayOf(complete) }
    LaunchedEffect(complete) {
        val justCompleted = complete && !wasComplete[0]
        wasComplete[0] = complete
        if (!justCompleted || !MotionMode.enhanced) return@LaunchedEffect
        snapshotFlow { shown.value }.first { it >= COMPLETE_PULSE_FILL }
        // 欠阻尼弹簧 + 初速度：目标就是原位，自然鼓起再回落（可被下一次完成打断，从当前值与速度继续）
        completePulse.animateTo(0f, MotionTokens.pop(0.001f), initialVelocity = COMPLETE_PULSE_KICK)
    }
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
            .height(ProgressIndicatorDefaults.DefaultLinearProgressIndicatorHeight)
            .graphicsLayer {
                val k = completePulse.value
                if (k != 0f) scaleY = 1f + COMPLETE_PULSE_SCALE * k
            },
    ) {
        val radius = size.height / 2f
        val corners = CornerRadius(radius)
        drawRoundRect(background, size = size, cornerRadius = corners)
        val minWidth = radius * 2f
        val width = minWidth + (size.width - minWidth) * shown.value.coerceIn(0f, 1f)
        drawRoundRect(foreground, size = Size(width, size.height), cornerRadius = corners)
    }
}
