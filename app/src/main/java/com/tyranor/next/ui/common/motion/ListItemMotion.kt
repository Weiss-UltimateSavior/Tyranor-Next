package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/** 两种补位节奏共用相同的增删淡入淡出。 */
enum class ListItemPlacement(internal val spec: SpringSpec<IntOffset>) {
    /** 普通布局变化，例如条件设置卡、加载态与搜索结果。 */
    Layout(MotionTokens.layout(IntOffset.VisibilityThreshold)),

    /** 更快的补位，用于游戏卡片、最近游戏与可删除的游戏目录。 */
    Reflow(MotionTokens.reflow(IntOffset.VisibilityThreshold)),
}

private val itemFadeIn = MotionTokens.fadeIn<Float>()
private val itemFadeOut = MotionTokens.fadeOut<Float>()

private inline fun itemMotion(enabled: Boolean, animate: () -> Modifier): Modifier =
    if (enabled && MotionMode.enhanced) animate() else Modifier

/**
 * 在有稳定 key 的 lazy item 根节点调用，例如 `Modifier.then(listItemMotion())`。
 * 普通档或 [enabled] 为 false 时不挂动画；首屏依次入场与滚动形变由调用方分别处理。
 */
fun LazyItemScope.listItemMotion(
    placement: ListItemPlacement = ListItemPlacement.Layout,
    enabled: Boolean = true,
): Modifier = itemMotion(enabled) {
    Modifier.animateItem(fadeInSpec = itemFadeIn, placementSpec = placement.spec, fadeOutSpec = itemFadeOut)
}

fun LazyGridItemScope.listItemMotion(
    placement: ListItemPlacement = ListItemPlacement.Layout,
    enabled: Boolean = true,
): Modifier = itemMotion(enabled) {
    Modifier.animateItem(fadeInSpec = itemFadeIn, placementSpec = placement.spec, fadeOutSpec = itemFadeOut)
}

fun LazyStaggeredGridItemScope.listItemMotion(
    placement: ListItemPlacement = ListItemPlacement.Layout,
    enabled: Boolean = true,
): Modifier = itemMotion(enabled) {
    Modifier.animateItem(fadeInSpec = itemFadeIn, placementSpec = placement.spec, fadeOutSpec = itemFadeOut)
}
