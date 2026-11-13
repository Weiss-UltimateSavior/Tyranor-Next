package com.tyranor.next.ui.archive

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens

/** 封包从右侧进入，解包从左侧进入；普通档保留原版的默认滑动与 220ms 淡入淡出。 */
internal fun archivePaneTransition(target: ArchiveMode): ContentTransform {
    val direction = if (target == ArchiveMode.PACK) 1 else -1
    return if (MotionMode.enhanced) {
        (slideInHorizontally(MotionTokens.content(IntOffset.VisibilityThreshold)) { direction * it } +
            fadeIn(MotionTokens.fadeIn())) togetherWith
            (slideOutHorizontally(MotionTokens.contentExit(IntOffset.VisibilityThreshold)) { -direction * it } +
                fadeOut(MotionTokens.fadeOut()))
    } else {
        (slideInHorizontally { direction * it } + fadeIn(tween(220))) togetherWith
            (slideOutHorizontally { -direction * it } + fadeOut(tween(220)))
    }
}

internal class ArchiveSelectionColors(val background: State<Color>, val foreground: State<Color>)

/** 模式标签在普通档也有默认的颜色过渡；和列表行的增强动效分开。 */
@Composable
internal fun rememberArchiveTabColors(selected: Boolean): ArchiveSelectionColors {
    val scheme = MaterialTheme.colorScheme
    return ArchiveSelectionColors(
        animateColorAsState(if (selected) scheme.primary.copy(alpha = 0.14f) else Color.Transparent, label = "modeTabBg"),
        animateColorAsState(if (selected) scheme.primary else scheme.onSurfaceVariant, label = "modeTabFg"),
    )
}

/** 列表行仅增强档有选中色过渡；返回状态，让背景可以在绘制阶段取色。 */
@Composable
internal fun rememberArchiveRowColors(selected: Boolean): ArchiveSelectionColors {
    val scheme = MaterialTheme.colorScheme
    return ArchiveSelectionColors(
        rowColor(if (selected) scheme.primary.copy(alpha = 0.14f) else NavWhite, "archiveRowBg"),
        rowColor(if (selected) scheme.primary else scheme.onSurface, "archiveRowTitle"),
    )
}

@Composable
private fun rowColor(target: Color, label: String): State<Color> =
    if (MotionMode.enhanced) animateColorAsState(target, MotionTokens.fadeIn(), label = label)
    else rememberUpdatedState(target)

/** 总进度和当前文件各自追踪，确定 / 不定态交替时不中断；普通档让指示条直接读取字节进度。 */
@Composable
internal fun archiveProgress(target: () -> Float, label: String): () -> Float {
    val shown = animateFloatAsState(target(), MotionTokens.value(0.001f), label = label)
    // 普通档直接画原值，但不丢掉正在运行的弹簧；中途切回增强档仍在原轨迹上。
    return { if (MotionMode.enhanced) shown.value else target() }
}
