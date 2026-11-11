package com.tyranor.next.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import com.tyranor.next.ui.common.motion.MotionTokens
import kotlin.math.abs

/** 滑块与开关按行程比例计算越界量，最多额外移动 12%。 */
internal const val CONTROL_OVERSHOOT_FRACTION = 0.12f

/** 带方向的越界位移；[limit] 与 [over] 同单位（行程比例或像素）。 */
internal fun rubberBandOffset(over: Float, limit: Float): Float {
    if (over == 0f || limit <= 0f) return 0f
    val offset = limit * (1f - 1f / (abs(over) / limit + 1f))
    return if (over > 0f) offset else -offset
}

/** 可用态切换时连续过渡；控件在绘制时读取进度，按自己的端点色插值。 */
@Composable
internal fun rememberControlEnabledProgress(enabled: Boolean): State<Float> {
    val target = if (enabled) 1f else 0f
    val progress = remember { Animatable(target) }
    LaunchedEffect(enabled) { progress.animateTo(target, MotionTokens.fadeIn(0.001f)) }
    return progress.asState()
}
