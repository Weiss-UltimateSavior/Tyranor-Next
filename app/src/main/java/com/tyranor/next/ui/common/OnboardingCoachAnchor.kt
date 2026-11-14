package com.tyranor.next.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** 引导步骤与主界面页面/真实控件锚点的稳定映射。 */
internal object CoachAnchor {
    const val HOME = 0
    const val GAME_FOLDER = 1
    const val GAME_ADD = 2
    const val GAME_SEARCH = 3
    const val GAME_COVER = 4
    const val GAME_SCAN = 5
    const val ENGINE = 6
    const val SETTINGS = 7
}

internal fun Modifier.coachAnchor(enabled: Boolean, onBounds: (Rect) -> Unit): Modifier =
    if (enabled) onGloballyPositioned { onBounds(it.boundsInRoot()) } else this
