package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 删除「消散」效果的播放状态：卡片先晃一下，随后缩小、微倾、糊掉、变红并淡出，同时化成碎屑向四周飘散。
 * 效果只作用于绘制图层；播完后由调用方真正删除数据，卡片随即被移出列表（邻居由 `animateItem` 弹簧补位）。
 *
 * 「普通」档与系统关闭动画时 [play] 立即返回（原版：直接删除）。
 */
@Stable
class DissolveState {
    internal val progress = Animatable(0f)

    /** 正在播放（含已播完、等待数据删除的这段时间）；期间 [dissolve] 修饰符吞掉该卡片的触摸。 */
    var running by mutableStateOf(false)
        private set

    /** 播放消散；播到约一半（[REMOVE_AT]）就返回，调用方此时删除数据（邻居立刻补位，被删的卡片由列表的退场淡出收尾）。 */
    suspend fun play() {
        if (running) return
        if (!MotionMode.enhanced) return
        running = true
        MotionHaptics.perform(MotionHaptics.Kind.Confirm)
        // 播到 REMOVE_AT（约一半）就返回：调用方随即删除数据，邻居立刻开始补位，被删的卡片由列表的退场淡出收尾，
        // 补位与消散重叠进行，整体更快；
        // 固定时长而不是弹簧——弹簧的收敛尾巴会让「等它播完」多等将近一半时间
        progress.animateTo(REMOVE_AT, tween(DISSOLVE_MILLIS, easing = LinearEasing))
    }

}

/** 游戏库里正在播放删除消散的游戏（uri → 状态）；卡片按自己的 uri 查找，不必逐层传参。 */
val dissolvingGames: SnapshotStateMap<String, DissolveState> = mutableStateMapOf()

@Composable
fun rememberDissolveState(): DissolveState = remember { DissolveState() }

/**
 * 消散的整体形变与碎屑。放在卡片修饰符链的最外层（早于 `clip` / `background`）：
 * 碎屑画在卡片图层之外，卡片淡出后仍继续飘散。[state] 为 null 或「普通」档时不加任何节点。
 */
fun Modifier.dissolve(state: DissolveState?, colors: List<Color> = emptyList()): Modifier {
    if (state == null || !MotionMode.enhanced) return this
    return this
        // 删除一经确认，这个对象在逻辑上已经不存在，只是视觉还在播：消散期间的触摸由这里吞掉（并退出无障碍树），
        // 点击 / 长按处理器本身不需要再写「动画中忽略」的守卫
        .then(
            if (state.running) {
                Modifier
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
            } else {
                Modifier
            },
        )
        .drawWithContent {
            drawContent()
            val t = state.progress.value
            if (t > DUST_START && colors.isNotEmpty()) drawDust(t, colors)
        }
        .graphicsLayer {
            val t = state.progress.value
            if (t > 0f) {
                // 先左右晃几下（起手反馈），再收缩消失
                if (t < SHAKE_END) {
                    translationX = sin(t * SHAKE_FREQ) * (1f - t / SHAKE_END) * 5.dp.toPx()
                }
                val e = t * t * (3f - 2f * t)
                val s = 1f - 0.3f * e
                scaleX = s
                scaleY = s
                rotationZ = -6f * e
                alpha = 1f - ((t - FADE_START) / (1f - FADE_START)).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin.Center
            }
        }
        // 糊掉：模糊半径随进度增大（API 31 以下、大面积内容由 motionBlur 自动跳过）
        .motionBlur { state.progress.value * 12.dp.toPx() }
}

/** 卡片被删除时的变红覆盖层（画在卡片内容之上，放在 `clip` 之后，按卡片圆角裁剪）。 */
fun Modifier.dissolveTint(state: DissolveState?, color: Color): Modifier {
    if (state == null || !MotionMode.enhanced) return this
    return this.drawWithContent {
        drawContent()
        val t = state.progress.value
        if (t > 0f) drawRect(color.copy(alpha = 0.38f * (t * 3f).coerceAtMost(1f)))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDust(t: Float, colors: List<Color>) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val phase = ((t - DUST_START) / (1f - DUST_START)).coerceIn(0f, 1f)
    val out = 1f - (1f - phase) * (1f - phase) // 缓出：先快后慢
    val fade = (1f - phase)
    for (i in 0 until DUST_COUNT) {
        val angle = i * GOLDEN_ANGLE
        val spread = 0.35f + 0.65f * fract(i * 0.618f)
        val reach = (36.dp.toPx() + 76.dp.toPx() * spread) * out
        // 起点散在卡片中部区域，终点沿黄金角方向外飘并受一点「重力」下坠
        val x0 = (fract(i * 0.754f) - 0.5f) * size.width * 0.6f
        val y0 = (fract(i * 0.431f) - 0.5f) * size.height * 0.6f
        val gravity = 26.dp.toPx() * phase * phase
        val p = Offset(c.x + x0 + cos(angle) * reach, c.y + y0 + sin(angle) * reach + gravity)
        val r = (2.2f + 3.2f * fract(i * 0.37f)).dp.toPx() * (0.4f + 0.6f * fade)
        drawCircle(colors[i % colors.size].copy(alpha = (fade * 0.9f).coerceIn(0f, 1f)), r, p)
    }
}

private fun fract(x: Float): Float = x - kotlin.math.floor(x)

private const val DISSOLVE_MILLIS = 260
private const val REMOVE_AT = 0.55f
private const val SHAKE_END = 0.2f
private const val SHAKE_FREQ = 95f
private const val FADE_START = 0.28f
private const val DUST_START = 0.12f
private const val DUST_COUNT = 16
private const val GOLDEN_ANGLE = (2.0 * PI * 0.381966).toFloat()
