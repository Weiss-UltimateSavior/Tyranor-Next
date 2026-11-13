package com.tyranor.next.ui.common.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable

/**
 * 区块按高度、位移和透明度展开，不额外包图层或加模糊。
 * 首次读盘可用 [animate] 跳过转场；需要等待退场结束的行传入自己的 [visibleState]。
 */
@Composable
internal fun ExpandSectionVisibility(
    visible: Boolean,
    label: String,
    animate: Boolean = true,
    visibleState: MutableTransitionState<Boolean>? = null,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val play = animate && MotionMode.enhanced
    val enter = if (play) expandBlurEnter() else EnterTransition.None
    val exit = if (play) shrinkBlurExit() else ExitTransition.None
    if (visibleState == null) {
        AnimatedVisibility(visible = visible, enter = enter, exit = exit, label = label, content = content)
    } else {
        visibleState.targetState = visible
        AnimatedVisibility(visibleState = visibleState, enter = enter, exit = exit, label = label, content = content)
    }
}
