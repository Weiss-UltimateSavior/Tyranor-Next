package com.tyranor.next.ui.main

import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import com.tyranor.next.ui.common.motion.EntranceState
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.TabPageMotion

private const val MainTabReplayMinGapMillis = 400L

/** 保留原转场的状态对象，供点击时核对页面是否真的已经离屏；动画值仍只在绘制/派生状态中逐帧读取。 */
internal class MainTabMotion(val offset: State<Float>, val alpha: State<Float>) {
  /** 仅离屏且超过最小间隔的页面重播入场，避免慢速切页途中再次选中导致内容跳变。 */
  fun replayEntranceIfHidden(entrance: EntranceState, timeSinceLeft: Long) {
    val visible = alpha.value > 0f && kotlin.math.abs(offset.value) < 1f
    if (!visible && timeSinceLeft > MainTabReplayMinGapMillis) entrance.replay(quick = true)
  }
}

/** 为每页保留原有位移与透明度动画；增强档用既有弹簧，普通档保留原版缓动。 */
@Composable
internal fun rememberMainTabMotion(
  transition: Transition<Int>,
  pageCount: Int,
  onboarding: Boolean = false,
): List<MainTabMotion> =
  (0 until pageCount).map { page ->
    // 保留 Transition 的时钟；引导和普通档仍使用主界面原来的时长。
    val offset = transition.animateFloat(
      transitionSpec = { if (MotionMode.enhanced) TabPageMotion.slide else tween(durationMillis = if (onboarding) 80 else 200) },
      label = "mainTabOffset$page",
    ) { activePage ->
      when {
        page < activePage -> -1f
        page > activePage -> 1f
        else -> 0f
      }
    }
    val alpha = transition.animateFloat(
      transitionSpec = { if (MotionMode.enhanced) TabPageMotion.fade else tween(durationMillis = if (onboarding) 80 else 160) },
      label = "mainTabAlpha$page",
    ) { activePage -> if (page == activePage) 1f else 0f }
    remember(offset, alpha) { MainTabMotion(offset, alpha) }
  }
