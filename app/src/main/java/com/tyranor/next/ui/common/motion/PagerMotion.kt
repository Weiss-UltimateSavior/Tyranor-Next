package com.tyranor.next.ui.common.motion

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.GraphicsLayerScope
import kotlin.math.abs
import kotlinx.coroutines.flow.first

/**
 * 横向翻页（Pager）类的共用动效：层差视差、吸附手感、指示器联动、翻页触觉。
 * 全部由「翻页偏移」驱动、只在图层 / 布局 / 绘制阶段读取（翻页途中 0 重组），偏移为 0（停稳）时所有输出恒等。
 *
 * 偏移 `o`（单位：页）：0 = 停稳在这一页；当前页被拖向下一页时为正，下一页（还在右边）为负；±1 = 整页之外。
 */

/** 本页相对当前翻页位置的偏移（页），在图层块 / 布局块里调用即可只在对应阶段失效。 */
fun PagerState.pageOffsetOf(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

/** 连续的翻页位置（页）：拖到半页时 currentPage 切换，但这个值始终连续。 */
fun PagerState.pagePosition(): Float = currentPage + currentPageOffsetFraction

/** 第 [index] 个指示点离「当前位置」的接近度：1 = 正对、0 = 一页或更远。 */
fun PagerState.dotCloseness(index: Int): Float = (1f - abs(pagePosition() - index)).coerceIn(0f, 1f)

private const val BACKDROP_ZOOM = 0.20f
private const val BACKDROP_SHIFT = 0.09f
private const val FOREGROUND_SHIFT = 0.06f
private const val FOREGROUND_FADE = 0.35f

/**
 * 翻页的「背景层」：封面模糊底这类大面积背景比整张卡走得慢一点（反向补偿位移，像远处的背景）、同时放大
 * （被卡片圆角裁切，放大余量 0.10 宽 ≥ 位移 0.09 宽，不会露边）。放在被卡片裁切的背景层的图层块里。
 */
fun GraphicsLayerScope.pagerBackdropLayer(offset: Float) {
    val o = offset.coerceIn(-1f, 1f)
    val a = abs(o)
    val s = 1f + BACKDROP_ZOOM * a
    scaleX = s
    scaleY = s
    translationX = o * BACKDROP_SHIFT * size.width
}

/**
 * 翻页的「前景层」：文字这类前景比整张卡走得快一点（同向多走一段）并随偏离淡出，
 * 与背景层一慢一快拉开层次。放在卡片内容层的图层块里。
 */
fun GraphicsLayerScope.pagerForegroundLayer(offset: Float) {
    val o = offset.coerceIn(-1f, 1f)
    translationX = -o * FOREGROUND_SHIFT * size.width
    alpha = 1f - FOREGROUND_FADE * abs(o)
}

/**
 * 翻页的「速度」（页 / 秒，带符号：往后翻为正）：逐帧用位置差做低通估计（τ≈60ms），只在滚动期间每帧运行，
 * 停下后读数衰减到 0 即停止（空闲时没有任何逐帧工作）。读取放在图层块里：速度只失效图层，不触发重组。
 * 「普通」档恒为 0。用于封面流的「甩动顺势侧倾」。
 */
@Composable
fun rememberPagerVelocity(state: PagerState): androidx.compose.runtime.State<Float> {
    val velocity = androidx.compose.runtime.remember(state) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    if (!MotionMode.enhanced) return velocity
    LaunchedEffect(state) {
        var lastPos = state.pagePosition()
        var lastNanos = 0L
        while (true) {
            // 空闲：等下一次滚动开始再开始逐帧估计
            if (!state.isScrollInProgress && kotlin.math.abs(velocity.floatValue) < VELOCITY_EPSILON) {
                velocity.floatValue = 0f
                snapshotFlow { state.isScrollInProgress }.first { it }
                lastPos = state.pagePosition()
                lastNanos = 0L
            }
            val now = androidx.compose.runtime.withFrameNanos { it }
            val pos = state.pagePosition()
            if (lastNanos != 0L) {
                val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                val delta = pos - lastPos
                if (kotlin.math.abs(delta) > VELOCITY_JUMP_PAGES) {
                    // 程序化跳页（删除后夹取、scrollToPage）不是手指甩动：丢弃这个样本，避免一帧尖峰让卡片满格侧倾
                    velocity.floatValue = 0f
                } else {
                    val a = 1f - kotlin.math.exp(-dt / VELOCITY_TAU_SECONDS)
                    velocity.floatValue += (delta / dt - velocity.floatValue) * a
                }
            }
            lastPos = pos
            lastNanos = now
        }
    }
    return velocity
}

private const val VELOCITY_TAU_SECONDS = 0.06f
private const val VELOCITY_EPSILON = 0.02f

/** 单帧位置变化超过这么多页就当作跳页而不是滑动。 */
private const val VELOCITY_JUMP_PAGES = 1.5f

/**
 * 翻页吸附手感：增强档用与封面流一致的布局弹簧（带一点回弹地吸附），普通档保持 Pager 默认。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPagerSnapFling(state: PagerState): TargetedFlingBehavior =
    if (MotionMode.enhanced) {
        PagerDefaults.flingBehavior(state = state, snapAnimationSpec = MotionTokens.layout(0.5f))
    } else {
        PagerDefaults.flingBehavior(state = state)
    }

/**
 * 翻页触觉：手指拖动 / 甩动使当前页发生变化时轻点一下（「嗒」）。程序化滚动、首次组合、
 * 手指之外的变化不震（门面按「最近有触摸」门控）。边缘越界不震。
 */
@Composable
fun PagerTickHaptics(state: PagerState) {
    LaunchedEffect(state) {
        var last = state.currentPage
        snapshotFlow { state.currentPage }.collect { page ->
            if (page != last && state.isScrollInProgress) {
                MotionHaptics.perform(MotionHaptics.Kind.Tick)
            }
            last = page
        }
    }
}
