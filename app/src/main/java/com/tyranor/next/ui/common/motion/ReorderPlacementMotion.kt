package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 非懒列表中按稳定 key 排列的行（如封面来源）的重排位移：行在父布局里的位置变化时，先停在旧位置，再以布局弹簧滑到新位置。
 * 连续点「上移 / 下移」会从当前位置与速度直接转向新目标（可打断）；只在放置阶段偏移，测量与静止布局不变。
 * 「普通」档不加任何节点（与原版一样瞬间换位）。
 */
internal fun Modifier.reorderPlacement(): Modifier =
    if (MotionMode.enhanced) this then ReorderPlacementElement else this

private object ReorderPlacementElement : ModifierNodeElement<ReorderPlacementNode>() {
    override fun create(): ReorderPlacementNode = ReorderPlacementNode()

    override fun update(node: ReorderPlacementNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "reorderPlacement"
    }

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = "reorderPlacement".hashCode()
}

private class ReorderPlacementNode : Modifier.Node(), LayoutModifierNode {
    /** 相对新位置的竖直偏移（px）：放置阶段读取，动画期间只重新放置、不重组。 */
    private var offsetY by mutableFloatStateOf(0f)
    private var velocityY = 0f
    private var lastY = Float.NaN
    private var job: Job? = null

    override fun onDetach() {
        job = null
        lastY = Float.NaN
        velocityY = 0f
        offsetY = 0f
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            val y = coordinates?.positionInParent()?.y
            if (y != null) {
                val previous = lastY
                lastY = y
                if (!previous.isNaN() && previous != y && isAttached) {
                    // 视觉位置保持连续：从「当前看到的位置」出发，带着当前速度滑向新位置
                    val start = offsetY + (previous - y)
                    val startVelocity = velocityY
                    offsetY = start
                    job?.cancel()
                    job = coroutineScope.launch {
                        animate(
                            initialValue = start,
                            targetValue = 0f,
                            initialVelocity = startVelocity,
                            animationSpec = MotionTokens.layout(0.5f),
                        ) { value, velocity ->
                            offsetY = value
                            velocityY = velocity
                        }
                        velocityY = 0f
                    }
                }
            }
            placeable.place(0, offsetY.roundToInt())
        }
    }
}
