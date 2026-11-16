package com.tyranor.next.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 在滤镜行的稳定 key 下创建；首屏的行已经可见，之后插入的行才播放入场。 */
@Composable
internal fun rememberGraphicsChainRowMotion(
    editorShown: Boolean,
    moving: Boolean,
    scope: CoroutineScope,
): GraphicsChainRowMotion? {
    if (!MotionMode.enhanced) return null
    val lift = animateFloatAsState(if (moving) 1f else 0f, MotionTokens.control(0.001f), label = "chainRowLift")
    val motion = remember { GraphicsChainRowMotion(editorShown, lift, scope) }
    LaunchedEffect(Unit) { motion.appear() }
    return motion
}

internal class GraphicsChainRowMotion(
    editorShown: Boolean,
    private val lift: State<Float>,
    private val scope: CoroutineScope,
) {
    private val appearance = Animatable(if (editorShown) 0f else 1f)
    private val release = Animatable(0f)

    suspend fun appear() {
        if (appearance.value < 1f) appearance.animateTo(1f, MotionTokens.pop(0.001f))
    }

    /** 在手势清零前接手剩余位移；再次拖动时，叠加仍未回到原位的部分。 */
    fun settle(offset: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            release.snapTo(release.value + offset)
            release.animateTo(0f, MotionTokens.release(0.5f))
        }
    }

    fun layer(moving: Boolean): Modifier = Modifier.graphicsLayer {
        val shown = appearance.value
        val raised = lift.value
        alpha = shown.coerceIn(0f, 1f)
        val scale = (0.9f + 0.1f * shown) * (1f + 0.03f * raised)
        scaleX = scale
        scaleY = scale
        translationY = release.value
        if (moving && raised > 0f && !AppThemeColors.isMilkGlass) {
            shadowElevation = 8.dp.toPx() * raised
            shape = AppComponentShape
            clip = false
        }
    }
}

/** 分隔线与按钮边框取同一档透明度；图标沿同一时钟在自己的端点间切换。 */
@JvmInline
internal value class GraphicsChainActionAlpha(private val progress: Float) {
    val line: Float get() = between(0.2f, 0.5f)
    val icon: Float get() = between(0.38f, 1f)

    private fun between(off: Float, on: Float): Float = when {
        progress >= 1f -> on
        progress <= 0f -> off
        else -> off + (on - off) * progress
    }
}

@Composable
internal fun graphicsChainActionAlpha(enabled: Boolean): GraphicsChainActionAlpha {
    val target = if (enabled) 1f else 0f
    val progress = if (MotionMode.enhanced) {
        animateFloatAsState(target, MotionTokens.fadeIn(0.001f), label = "chainEnabled").value
    } else target
    return GraphicsChainActionAlpha(progress)
}

private class ReorderStackState {
    val slots = HashMap<Any, Animatable<Float, AnimationVector1D>>()
    var height: Animatable<Float, AnimationVector1D>? = null
    var heightTarget = -1
}

/**
 * 滤镜行的纵向堆叠（「增强」档；停稳后与直接排在外层 Column 中逐像素一致）：
 * - 行的位置变化（上移 / 下移 / 删除上方的行 / 拖拽越过）时，行先留在原处再弹簧滑到新位置——周围让位；
 *   [pinned] 是正在拖动的行，跟手位移由编辑器自己负责，这里不再叠加；
 * - 整体高度用弹簧过渡，下方的「添加 / 清空 / 上限说明」随之平滑移动，不跳变。
 * 位移在放置阶段算出并只写图层，被打断时 animateTo 从当前位置与速度转向。「普通」档直接排入外层布局。
 */
@Composable
internal fun GraphicsChainStack(
    pinned: Any?,
    onMeasured: (id: Any, height: Int) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!MotionMode.enhanced) {
        content()
        return
    }
    val state = remember { ReorderStackState() }
    val scope = rememberCoroutineScope()
    Layout(content = content) { measurables, constraints ->
        // 与 Column(Arrangement.Top, Alignment.Start) 相同的子约束与排布
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        measurables.forEachIndexed { i, measurable ->
            measurable.layoutId?.let { onMeasured(it, placeables[i].height) }
        }
        val width = maxOf(placeables.maxOfOrNull { it.width } ?: 0, constraints.minWidth)
        val total = placeables.sumOf { it.height }
        val heightAnim = state.height ?: Animatable(total.toFloat()).also {
            state.height = it
            state.heightTarget = total
        }
        if (total != state.heightTarget) {
            state.heightTarget = total
            scope.launch { heightAnim.animateTo(total.toFloat(), MotionTokens.layout(0.5f)) }
        }
        val shownHeight = heightAnim.value.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, shownHeight) {
            var y = 0
            val placed = HashSet<Any>()
            placeables.forEachIndexed { i, placeable ->
                val id = measurables[i].layoutId
                val top = y
                val topPx = top.toFloat()
                if (id == null) {
                    placeable.placeRelative(0, top)
                } else {
                    placed += id
                    val slot = state.slots.getOrPut(id) { Animatable(topPx) }
                    if (id == pinned) {
                        if (slot.targetValue != topPx || slot.value != topPx) scope.launch { slot.snapTo(topPx) }
                        placeable.placeRelative(0, top)
                    } else {
                        if (slot.targetValue != topPx) scope.launch { slot.animateTo(topPx, MotionTokens.layout(0.5f)) }
                        placeable.placeRelativeWithLayer(0, top) { translationY = slot.value - topPx }
                    }
                }
                y += placeable.height
            }
            state.slots.keys.retainAll(placed)
        }
    }
}
