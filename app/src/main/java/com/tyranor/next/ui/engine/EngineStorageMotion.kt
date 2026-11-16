package com.tyranor.next.ui.engine

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings

/** 存储弧从上一次停稳的占比起扫；列表回收后恢复落点，普通档直接画当前占比。 */
@Composable
internal fun rememberStorageSweep(target: Float): () -> Float {
    val settled = rememberSaveable { mutableFloatStateOf(0f) }
    val sweep = remember { Animatable(settled.floatValue) }
    LaunchedEffect(target) {
        // 只有占比变化才重定向。中途只切档时保留同一条扫弧和速度。
        if (MotionMode.enhanced) sweep.animateTo(target, MotionSprings.smooth(900, 0.001f))
        else sweep.snapTo(target)
        settled.floatValue = target
    }
    return { if (MotionMode.enhanced) sweep.value else target }
}
