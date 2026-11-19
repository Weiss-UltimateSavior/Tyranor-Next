package com.tyranor.next.ui.common.privacy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tyranor.next.core.privacy.PatternHasher
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.ui.common.motion.MotionHaptics

/**
 * 3×3 图案输入盘（应用锁的自绘控件，设置与解锁共用）：
 * 按下拾取圆点、连线跟随手指，松手时按**实际按下顺序**校验点数（[PatternHasher.MIN_POINTS]）
 * 后回调原点序；不补中间点、不按形状归一化——0→2 与 0→1→2 是两个不同的图案。
 * 点数不足 / 非法点序静默复位（错误反馈由父层统一做：横摇 + 拒绝触觉 + 提示文案）。
 * 手势被系统取消（窗口失焦 / 父容器接管）时同样静默复位，不把未完成的图案当成一次
 * 输入去触发拒绝触觉。
 *
 * 拾取新点给一次刻度触觉（越过离散边界）；当前图案与手指位置只在绘制阶段读取，手势期间不重组。
 *
 * @param resetKey 变化时清空当前图案（父层在校验失败后递增失败计数并作为 resetKey 传入）。
 * @param contentDescription 无障碍描述（调用方经 stringResource 传入）。
 */
@Composable
fun PatternPad(
    resetKey: Any?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String,
    onPatternEntered: (List<Int>) -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val idleColor = PageSecondaryTextColor
    val selected = remember { mutableStateOf(emptyList<Int>()) }
    // 手指位置逐帧变化，用独立可变状态由绘制阶段读取
    val fingerState = remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(resetKey) {
        selected.value = emptyList()
        fingerState.value = null
    }

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 触点半径按盘面尺寸推导，与绘制保持同一几何
                    val cell = size.width / 3f
                    val hitRadius = cell * HIT_RADIUS_FACTOR
                    // 按下位置本身就是一次选点：awaitFirstDown 的事件不会再进入下面的
                    // 移动循环，不在这里处理的话「起点正好落在点上」的图案会丢首点
                    // （例如 [0,1,2,5] 被收成 [1,2,5]，点数不足而被静默拒绝）。
                    fingerState.value = down.position
                    hitTest(down.position, cell, hitRadius)?.let { point ->
                        if (point !in selected.value) {
                            selected.value = selected.value + point
                            MotionHaptics.perform(MotionHaptics.Kind.Tick)
                        }
                    }
                    down.consume()
                    var canceled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed }
                        if (change == null) {
                            // 系统取消由 Compose 合成一个没有底层 MotionEvent 的抬手事件；
                            // 正常松手始终带 MotionEvent。取消只复位，不触发 Reject。
                            canceled = event.motionEvent == null
                            break
                        }
                        fingerState.value = change.position
                        val point = hitTest(change.position, cell, hitRadius)
                        if (point != null && point !in selected.value) {
                            selected.value = selected.value + point
                            MotionHaptics.perform(MotionHaptics.Kind.Tick)
                        }
                        change.consume()
                    }
                    // 松手 / 取消：按实际按下顺序判点数与合法性，不补中间点、不按形状归一化。
                    // 快速划过时未命中的点不会自动补入；设置门两次绘制的相等比较也使用原点序。
                    // 点数不足 / 非法时给一次轻拒绝触觉：这是「这次没算数」的信号，去归一化后
                    // 快速漏点不会再被静默接受；不摇动、不提示，与图案错误的 MISMATCH 反馈区分。
                    fingerState.value = null
                    val pattern = selected.value
                    if (!canceled) {
                        if (PatternHasher.isValidPattern(pattern)) {
                            onPatternEntered(pattern)
                        } else if (pattern.isNotEmpty()) {
                            MotionHaptics.perform(MotionHaptics.Kind.Reject)
                        }
                    }
                    selected.value = emptyList()
                }
            },
    ) {
        // 绘制阶段读取状态：手势期间只重绘，不重组
        val cell = size.width / 3f
        val dotRadius = 6.dp.toPx()
        val selectedRadius = 7.dp.toPx()
        val strokeWidth = 4.dp.toPx()
        val centers = List(PatternHasher.PATTERN_POINT_COUNT) { index ->
            centerOf(index, cell)
        }
        val current = selected.value
        // 已拾取点之间的连线 + 末点到手指的引线
        if (current.isNotEmpty()) {
            for (i in 0 until current.lastIndex) {
                drawLine(primaryColor, centers[current[i]], centers[current[i + 1]], strokeWidth, StrokeCap.Round)
            }
            fingerState.value?.let { position ->
                drawLine(primaryColor, centers[current.last()], position, strokeWidth, StrokeCap.Round)
            }
        }
        centers.forEachIndexed { index, center ->
            if (index in current) {
                drawCircle(primaryColor, selectedRadius, center)
            } else {
                drawCircle(idleColor, dotRadius, center)
            }
        }
    }
}

/** 点序下标（0..8，行优先）对应的圆心。 */
private fun centerOf(index: Int, cell: Float): Offset =
    Offset((index % 3 + 0.5f) * cell, (index / 3 + 0.5f) * cell)

/** 命中测试：返回手指位置命中的点序下标，未命中返回 null。 */
private fun hitTest(position: Offset, cell: Float, hitRadius: Float): Int? {
    val column = (position.x / cell).toInt().coerceIn(0, 2)
    val row = (position.y / cell).toInt().coerceIn(0, 2)
    val index = row * 3 + column
    val center = centerOf(index, cell)
    return if ((position - center).getDistance() <= hitRadius) index else null
}

/** 命中半径系数（相对格子边长）：约为三分之一格，手感宽容但不互相吞点。 */
private const val HIT_RADIUS_FACTOR = 0.33f
