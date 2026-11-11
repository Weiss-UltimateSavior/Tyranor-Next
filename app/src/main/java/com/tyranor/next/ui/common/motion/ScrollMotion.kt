package com.tyranor.next.ui.common.motion

import android.animation.ValueAnimator
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 滚动果冻：列表 / 宫格滚得越快，里面的卡片沿滚动方向被「拉长」一点，停下时 Q 弹地回到原样（静止 = 原样）。
 *
 * 用法：在滚动容器上 `Modifier.scrollJellyHost(state)`，在每个想要形变的卡片上 `Modifier.scrollJelly(state)`。
 * 卡片只在绘制图层里读 [amount]，滚动中不触发重组。「普通」档与系统关闭动画时不生效。
 */
class ScrollJellyState internal constructor(
    private val scope: CoroutineScope,
    private val maxVelocityPx: Float,
) {
    private var live by mutableFloatStateOf(0f)
    private var lastNanos = 0L
    private var smoothed = 0f
    private var watcher: Job? = null
    private var releasing = false

    /** 当前拉伸量：0 = 静止，1 = 高速滚动；回弹的下冲段为小幅负值（轻微压扁）。只在图层 / 绘制块里读取。 */
    val amount: Float get() = live

    internal val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y != 0f) onScrolled(consumed.y)
            return Offset.Zero
        }
    }

    private fun onScrolled(dy: Float) {
        val now = System.nanoTime()
        var dt = (now - lastNanos) / 1_000_000_000f
        lastNanos = now
        if (releasing) {
            // 回弹途中又开始滚动：从回弹当前的拉伸量接着走，不跳变
            watcher?.cancel()
            watcher = null
            releasing = false
            smoothed = live.coerceAtLeast(0f) * maxVelocityPx
        }
        if (dt > NEW_GESTURE_SECONDS) {
            // 新的一次滚动（距上一次事件很久）：用一帧的时间估算速度，避免巨大的 dt 把速度压成 0
            smoothed = live.coerceAtLeast(0f) * maxVelocityPx
            dt = FRAME_SECONDS
        }
        val velocity = abs(dy) / dt.coerceAtLeast(0.004f)
        smoothed += (velocity - smoothed) * SMOOTHING
        live = (smoothed / maxVelocityPx).coerceIn(0f, 1f)
        // 只在没有等待者时启动一个：它按最后一次滚动事件的时间戳判停，不必每个事件都取消 / 重启协程
        if (watcher == null) watcher = scope.launch { releaseWhenIdle() }
    }

    private suspend fun releaseWhenIdle() {
        while (true) {
            val waitNanos = IDLE_NANOS - (System.nanoTime() - lastNanos)
            if (waitNanos <= 0L) break
            delay(waitNanos / 1_000_000L + 1L)
        }
        releasing = true
        smoothed = 0f
        // 欠阻尼回弹：允许下冲到小幅负值（轻微压扁后再落稳）
        animate(
            initialValue = live,
            targetValue = 0f,
            animationSpec = MotionTokens.release(0.001f),
        ) { value, _ -> live = value.coerceAtLeast(-RELEASE_UNDERSHOOT) }
        releasing = false
        watcher = null
    }

    private companion object {
        const val NEW_GESTURE_SECONDS = 0.12f
        const val FRAME_SECONDS = 0.016f
        const val SMOOTHING = 0.3f
        const val IDLE_NANOS = 48_000_000L
        const val RELEASE_UNDERSHOOT = 0.6f
    }
}

/** 创建并记住 [ScrollJellyState]；`fastScrollDpPerSecond` 是视为「高速滚动」的速度。 */
@Composable
fun rememberScrollJellyState(fastScrollDpPerSecond: Float = 3200f): ScrollJellyState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    return remember(density, fastScrollDpPerSecond) { ScrollJellyState(scope, fastScrollDpPerSecond * density) }
}

/** 挂在滚动容器上：观察（不消费）垂直滚动量。「普通」档与系统关闭动画时不加任何节点。 */
fun Modifier.scrollJellyHost(state: ScrollJellyState): Modifier =
    if (MotionMode.enhanced && ValueAnimator.areAnimatorsEnabled()) this.nestedScroll(state.connection) else this

/** 单张卡片的最大拉伸量：高卡片按绝对量封顶，不会挤到相邻卡片。 */
private const val MAX_STRETCH_DP = 5f

/**
 * 挂在列表项上：滚动越快沿竖直方向拉长越多（[strength] 为最高速时的拉伸比例，同时受 [MAX_STRETCH_DP] 封顶），
 * 横向同时略收；静止时不形变。[state] 为 null 或「普通」档时不加图层。
 */
fun Modifier.scrollJelly(state: ScrollJellyState?, strength: Float = 0.035f): Modifier =
    if (state == null || !MotionMode.enhanced) this else this.graphicsLayer {
        val s = state.amount
        if (s != 0f) {
            val h = size.height
            val k = if (h > 0f) minOf(strength, MAX_STRETCH_DP.dp.toPx() / h) else strength
            scaleY = 1f + k * s
            scaleX = 1f - k * 0.5f * s
        }
    }
