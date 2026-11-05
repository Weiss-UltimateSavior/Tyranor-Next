package com.tyranor.next.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** 引导步骤与主界面页面/真实控件锚点的稳定映射。 */
internal object CoachAnchor {
    const val HOME = 0
    const val GAME_ADD = 1
    const val GAME_SEARCH = 2
    const val GAME_COVER = 3
    const val GAME_SCAN = 4
    const val ENGINE = 5
    const val SETTINGS = 6

    fun pageOf(index: Int): Int = when (index) {
        HOME -> 0
        in GAME_ADD..GAME_SCAN -> 1
        ENGINE -> 2
        else -> 3
    }
}

internal fun Modifier.coachAnchor(enabled: Boolean, onBounds: (Rect) -> Unit): Modifier =
    if (enabled) onGloballyPositioned { onBounds(it.boundsInRoot()) } else this
