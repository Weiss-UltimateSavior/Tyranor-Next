package com.tyranor.next.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.CoachOverlayScrim
import com.tyranor.next.ui.common.CoachAnchor
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens

/** Geometry is retained across anchor changes. The hit target always stays with the real control. */
internal class CoachSpotlight {
    private val scrimAlpha = Animatable(if (MotionMode.enhanced) 0f else 1f)
    private val ringRect = Animatable(Rect.Zero, Rect.VectorConverter, Rect.VisibilityThreshold)
    private val ringPulse = Animatable(0f)
    private var step = -1
    private var gliding = false

    internal suspend fun enter() {
        scrimAlpha.animateTo(1f, MotionTokens.fadeIn(0.001f))
    }

    @Composable
    fun Draw(
        safeStep: Int,
        ringTarget: Rect,
        showTarget: Boolean,
        primary: Color,
        overlayOrigin: Offset,
        tooltipBounds: () -> Rect?,
    ) {
        val enhanced = MotionMode.enhanced
        LaunchedEffect(ringTarget, showTarget, safeStep, enhanced) {
            if (!showTarget) return@LaunchedEffect
            val stepChanged = step != safeStep
            step = safeStep
            if (!enhanced || ringRect.value.isEmpty) {
                // 首次出现（尚无上一处位置）：直接落在目标上
                gliding = false
                ringRect.snapTo(ringTarget)
            } else if (stepChanged || gliding) {
                gliding = true
                ringRect.animateTo(ringTarget, MotionTokens.layout(Rect.VisibilityThreshold))
                // 只有正常落稳才结束滑动；被新目标打断时保持 gliding，下一轮从当前位置与速度继续
                gliding = false
            } else {
                ringRect.snapTo(ringTarget)
            }
        }
        LaunchedEffect(safeStep, showTarget, enhanced) {
            if (enhanced && showTarget) {
                ringPulse.animateTo(0f, MotionTokens.pop(0.01f), initialVelocity = COACH_RING_PULSE_VELOCITY)
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 让被讲解的真实控件保持清晰，避免整屏灰蒙蒙。
                    compositingStrategy = CompositingStrategy.Offscreen
                    if (MotionMode.enhanced) alpha = scrimAlpha.value
                }
        ) {
            // 高亮位置：增强档取弹簧中的高亮环（只在绘制阶段读取），普通档与原版相同直接取目标
            val drawnTarget = if (MotionMode.enhanced) {
                ringRect.value.takeUnless { it.isEmpty } ?: ringTarget
            } else {
                Rect(ringTarget.topLeft, ringTarget.size)
            }
            // 优先使用真实控件上报的 root 坐标；首帧尚未测量时才使用保守回退值。
            val cornerRadius = minOf(AppComponentCornerRadius.toPx(), drawnTarget.height * 0.22f)
            drawRect(CoachOverlayScrim)
            if (showTarget) {
                val targetSize = Size(drawnTarget.width, drawnTarget.height)
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(drawnTarget.left, drawnTarget.top),
                    size = targetSize,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
                    blendMode = BlendMode.Clear,
                )
                // 高亮环外扩量：静止为 0，与原版逐像素一致
                val inflate = if (MotionMode.enhanced) ringPulse.value.dp.toPx() else 0f
                drawRoundRect(
                    color = primary.copy(alpha = 0.86f),
                    topLeft = Offset(drawnTarget.left - inflate, drawnTarget.top - inflate),
                    size = Size(
                        (drawnTarget.width + inflate * 2f).coerceAtLeast(0f),
                        (drawnTarget.height + inflate * 2f).coerceAtLeast(0f),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius((cornerRadius + inflate).coerceAtLeast(0f)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = (if (safeStep == CoachAnchor.GAME_FOLDER) 2.dp else 3.dp).toPx(),
                    ),
                )
                tooltipBounds()?.let {
                    val tooltip = Rect(
                        left = it.left - overlayOrigin.x,
                        top = it.top - overlayOrigin.y,
                        right = it.right - overlayOrigin.x,
                        bottom = it.bottom - overlayOrigin.y,
                    )
                    val connector = connectorEnds(drawnTarget, tooltip)
                    drawLine(
                        color = primary.copy(alpha = 0.72f),
                        start = connector.first,
                        end = connector.second,
                        strokeWidth = (if (safeStep == CoachAnchor.GAME_FOLDER) 1.5.dp else 2.5.dp).toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberCoachSpotlight(): CoachSpotlight {
    val spotlight = remember { CoachSpotlight() }
    LaunchedEffect(Unit) { spotlight.enter() }
    return spotlight
}

private fun connectorEnds(drawnTarget: Rect, tooltip: Rect): Pair<Offset, Offset> {
    val horizontalOverlap = drawnTarget.left <= tooltip.right &&
        drawnTarget.right >= tooltip.left
    return when {
        // 目标在提示卡片上方：两端分别落在提示卡片上边和目标下边，
        // 每个端点都限制在自身矩形内，避免连线悬空在卡片外。
        drawnTarget.bottom <= tooltip.top -> {
            if (horizontalOverlap) {
                Offset(tooltip.center.x, tooltip.top) to
                    Offset(drawnTarget.center.x, drawnTarget.bottom)
            } else {
                Offset(
                    drawnTarget.center.x.coerceIn(tooltip.left, tooltip.right),
                    tooltip.top,
                ) to Offset(
                    tooltip.center.x.coerceIn(drawnTarget.left, drawnTarget.right),
                    drawnTarget.bottom,
                )
            }
        }
        // 目标在提示卡片下方。
        drawnTarget.top >= tooltip.bottom -> {
            if (horizontalOverlap) {
                Offset(tooltip.center.x, tooltip.bottom) to
                    Offset(drawnTarget.center.x, drawnTarget.top)
            } else {
                Offset(
                    drawnTarget.center.x.coerceIn(tooltip.left, tooltip.right),
                    tooltip.bottom,
                ) to Offset(
                    tooltip.center.x.coerceIn(drawnTarget.left, drawnTarget.right),
                    drawnTarget.top,
                )
            }
        }
        // 两者高度相交时，按左右关系连接相邻边缘。
        drawnTarget.left >= tooltip.right -> {
            Offset(tooltip.right, tooltip.center.y) to
                Offset(drawnTarget.left, drawnTarget.center.y)
        }
        drawnTarget.right <= tooltip.left -> {
            Offset(tooltip.left, tooltip.center.y) to
                Offset(drawnTarget.right, drawnTarget.center.y)
        }
        else -> {
            Offset(tooltip.center.x, tooltip.center.y) to
                Offset(drawnTarget.center.x, drawnTarget.center.y)
        }
    }
}

private const val COACH_RING_PULSE_VELOCITY = 140f
