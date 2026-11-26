package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
 * [maxFlingPages] 是一次甩动最多越过的页数：默认 1（整页卡片）；书架这类窄条目传更大的值，甩一下能翻过一串。
 * [bouncy] 为 true 时（增强档）吸附用明显回弹的弹簧（[pagerBouncySnap]）：松手后冲过一点再弹回停位，翻页手感更「Q」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPagerSnapFling(state: PagerState, maxFlingPages: Int = 1, bouncy: Boolean = false): TargetedFlingBehavior {
    val snapDistance = PagerSnapDistance.atMost(maxFlingPages)
    return if (MotionMode.enhanced) {
        PagerDefaults.flingBehavior(
            state = state,
            pagerSnapDistance = snapDistance,
            snapAnimationSpec = if (bouncy) pagerBouncySnap(0.5f) else MotionTokens.layout(0.5f),
        )
    } else {
        PagerDefaults.flingBehavior(state = state, pagerSnapDistance = snapDistance)
    }
}

/**
 * Q 弹翻页的吸附弹簧：松手后明显冲过一截再弹回停位（[rememberPagerSnapFling] 的 bouncy、书架的程序化跳转与
 * 两头橡皮筋的弹回共用）。
 */
fun <T> pagerBouncySnap(visibilityThreshold: T? = null): SpringSpec<T> =
    MotionSprings.spring(BOUNCY_SNAP_MILLIS, BOUNCY_SNAP_BOUNCE, visibilityThreshold)

/**
 * Q 弹吸附：响应时长与回弹量（0.3 ≈ 阻尼比 0.7：冲过停位约 5% 再顺势回来，没有可见的回摆）。
 * 真机录屏逐帧量过：0.5 会来回摆两三次，像在抽搐；0.42 只弹一下但用户觉得回弹太大，按要求调小。
 */
private const val BOUNCY_SNAP_MILLIS = 600
private const val BOUNCY_SNAP_BOUNCE = 0.3f

/**
 * 翻页触觉：手指拖动 / 甩动使当前页发生变化时轻点一下（「嗒」）。程序化滚动、首次组合、
 * 手指之外的变化不震（门面按「最近有触摸」门控）。边缘越界不震。[muted] 为 true 期间不震
 * （例如书架进度轨道点按跳转：只在点按时震一次，途经的页不再逐页震）。
 */
@Composable
fun PagerTickHaptics(state: PagerState, muted: () -> Boolean = { false }) {
    val isMuted by rememberUpdatedState(muted)
    LaunchedEffect(state) {
        var last = state.currentPage
        snapshotFlow { state.currentPage }.collect { page ->
            if (page != last && state.isScrollInProgress && !isMuted()) {
                MotionHaptics.perform(MotionHaptics.Kind.Tick)
            }
            last = page
        }
    }
}
