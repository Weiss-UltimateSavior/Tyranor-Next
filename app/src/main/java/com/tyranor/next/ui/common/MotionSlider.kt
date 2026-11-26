package com.tyranor.next.ui.common

import androidx.annotation.IntRange
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.BookPaperPalette
import com.tyranor.next.theme.CurrentBookPaperPalette
import com.tyranor.next.theme.LocalShelfBookLeaf
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * 滑动条（与 Miuix `Slider` 同名同参，页面只需替换 import）。
 *
 * 带刻度的离散滑条（传了 [keyPoints] 或 [steps] > 0，如「扫描深度」「应用圆角调整」）在「增强」动效档下换成
 * iOS 26 的刻度手感，几何与基础取色沿用 Miuix；刻度显示保留用户批准的仅显示未覆盖刻度样式：
 * - 拖动：圆钮连续跟手，越过刻度即切换数值并轻震；拖出两端整条轻微拉伸，松手果冻回弹；
 * - 单点：点哪一格直接跳到最近的刻度，轻震 + 圆钮带回弹落位；
 * - 松手：圆钮从手指位置弹簧吸附到刻度，略冲过再落稳。
 * 连续滑条与「普通」档直接使用 Miuix 原组件。
 */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    @IntRange(from = 0) steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    height: Dp = SliderDefaults.MinHeight,
    hapticEffect: SliderDefaults.SliderHapticEffect = SliderDefaults.DefaultHapticEffect,
    showKeyPoints: Boolean = false,
    keyPoints: List<Float>? = null,
    magnetThreshold: Float = 0.02f,
) {
    // 空自定义刻度等同未提供：有 steps 时使用均分刻度，否则走原有连续滑条。
    val effectiveKeyPoints = keyPoints?.takeIf { it.isNotEmpty() }
    val discrete = effectiveKeyPoints != null || steps > 0
    val inBook = LocalShelfBookLeaf.current
    if (!inBook && (!MotionMode.enhanced || !discrete)) {
        // 用户明确保留本批滑条外观：已覆盖区域的刻度与进度条同色，见 PR #162。
        val coveredDotColor = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledPrimarySlider
        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.semantics {
                if (enabled) {
                    setProgress { target ->
                        onValueChange(target.coerceIn(valueRange.start, valueRange.endInclusive))
                        onValueChangeFinished?.invoke()
                        true
                    }
                }
            },
            enabled = enabled,
            colors = SliderDefaults.sliderColors(keyPointForegroundColor = coveredDotColor),
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            height = height,
            hapticEffect = hapticEffect,
            showKeyPoints = showKeyPoints,
            keyPoints = effectiveKeyPoints,
            magnetThreshold = magnetThreshold,
        )
        return
    }
    if (inBook && !discrete) {
        BookContinuousSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            height = height,
        )
        return
    }
    TickSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        height = height,
        haptics = hapticEffect != SliderDefaults.SliderHapticEffect.None,
        showKeyPoints = showKeyPoints,
        keyPoints = effectiveKeyPoints,
        bookMode = inBook,
    )
}

/** 拖出两端时整条最多拉伸的比例。 */
private const val MAX_STRETCH = 0.035f

/**
 * 增强档离散滑条：手势期间连续跟手、按最近刻度回调数值，释放或取消后恢复到实际状态。
 * 仅在绘制阶段读取动画位置，保留已批准的未覆盖刻度样式与 Miuix 基础几何。
 */
@Composable
private fun TickSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)?,
    height: Dp,
    haptics: Boolean,
    showKeyPoints: Boolean,
    keyPoints: List<Float>?,
    bookMode: Boolean = false,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    // 全部刻度（值 + 位置比例）：自定义刻度点，或 steps 均分（含两端）
    val tickValues: List<Float> = remember(keyPoints, steps, valueRange) {
        keyPoints?.sorted() ?: List(steps + 2) { i -> valueRange.start + span * i / (steps + 1) }
    }
    val tickFractions = remember(tickValues, valueRange) {
        FloatArray(tickValues.size) { ((tickValues[it] - valueRange.start) / span).coerceIn(0f, 1f) }
    }
    // 绘制的圆点刻度：与 Miuix 一致（自定义刻度点总画；否则仅 showKeyPoints 时画 steps 刻度）
    val drawnFractions = if (keyPoints != null || showKeyPoints) tickFractions else FloatArray(0)
    val reverseTrack = bookMode && LocalLayoutDirection.current == LayoutDirection.Rtl

    /** 返回距离目标比例最近的刻度下标；距离相同时保留排序靠前的刻度。 */
    fun nearestIndex(fraction: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in tickFractions.indices) {
            val d = abs(tickFractions[i] - fraction)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    val coerced = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val valueFraction = (coerced - valueRange.start) / span
    // 手势协程里必须读「当前」值：手势被打断（如被侧边栏抢走）时要回到最新数值，而不是手势开始时捕获的旧值
    val currentValueFraction by rememberUpdatedState(valueFraction)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)

    val pos = remember { Animatable(valueFraction) }
    val press = remember { Animatable(1f) }
    val overshoot = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var interacting by remember { mutableStateOf(false) }
    val interaction = remember { SliderInteraction() }

    // 外部数值变化（非手势）：圆钮带回弹地移到新刻度
    LaunchedEffect(valueFraction) {
        if (!interacting && pos.targetValue != valueFraction) {
            pos.animateTo(valueFraction, MotionTokens.control(0.0005f))
        }
    }

    val c = MiuixTheme.colorScheme
    // 取色同原版：可用 primary / sliderBackground / onPrimary，不可用 disabledPrimarySlider / disabledSecondary /
    // disabledOnPrimary；两端点见下方 enabled* / disabled*，绘制时按可用态进度取（停稳即原色）
    val keyPointColor = c.sliderKeyPoint
    // 可用 ↔ 不可用：进度条 / 底轨 / 圆钮颜色按弹簧进度连续过渡（绘制阶段读取）；
    // 停稳在 1 / 0 时直接用对应的原取色，与原样逐像素一致
    val enabledProgress = rememberControlEnabledProgress(enabled)
    // 两套端点色（可用 / 不可用，取色同原版）
    val enabledForeground = c.primary
    val disabledForeground = c.disabledPrimarySlider
    val enabledBackground = c.sliderBackground
    val disabledBackground = c.disabledSecondary
    val enabledThumb = c.onPrimary
    val disabledThumb = c.disabledOnPrimary

    /** 按可用态进度取色（只在绘制阶段调用）：端点直接返回原色，过渡途中才插值。 */
    fun blend(disabledColor: Color, enabledColor: Color): Color {
        val e = enabledProgress.value
        return when {
            e >= 1f -> enabledColor
            e <= 0f -> disabledColor
            else -> lerp(disabledColor, enabledColor, e)
        }
    }
    val dragShade by animateFloatAsState(
        targetValue = if (dragging) 0.044f else 0f,
        animationSpec = if (dragging) MotionTokens.fadeIn(0.001f) else MotionTokens.fadeOut(0.001f),
        label = "TickSliderTrackAlpha",
    )

    Box(
        modifier = modifier
            .then(
                if (enabled) {
                    Modifier.pointerInput(tickValues, valueRange, reverseTrack) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val generation = ++interaction.generation
                            interaction.settleJob?.cancel()
                            val r = size.height / 2f
                            val avail = (size.width - 2f * r).coerceAtLeast(1f)
                            /** 将指针横坐标换算为行程比例，保留越界量供橡皮筋形变使用。 */
                            fun fractionAt(x: Float): Float {
                                val raw = (x - r) / avail
                                return if (reverseTrack) 1f - raw else raw
                            }
                            interacting = true
                            scope.launch { press.animateTo(1.127f, MotionTokens.press(0.001f)) }
                            // 指针流被异常取消时释放代码跑不到：finally 兜底还原交互状态
                            var finished = false
                            try {
                                var index = nearestIndex(pos.value.coerceIn(0f, 1f))
                                var dx = 0f
                                var dy = 0f
                                var moved = false
                                var cancelled = false
                                var lastX = down.position.x
                                val tracker = VelocityTracker()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        cancelled = !moved
                                        break
                                    }
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    val d = change.positionChange()
                                    dx += d.x
                                    dy += d.y
                                    lastX = change.position.x
                                    if (!moved) {
                                        if (change.isConsumed || (abs(dy) > slop && abs(dy) > abs(dx))) {
                                            cancelled = true
                                            break
                                        }
                                        if (abs(dx) > slop) {
                                            moved = true
                                            dragging = true
                                        }
                                    }
                                    if (moved) {
                                        change.consume()
                                        val raw = fractionAt(lastX)
                                        val clamped = raw.coerceIn(0f, 1f)
                                        tracker.addPosition(change.uptimeMillis, Offset(clamped, 0f))
                                        // 同一帧内立即生效，不经协程调度晚一帧
                                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                            pos.snapTo(clamped)
                                            overshoot.snapTo(rubberBandOffset(raw - clamped, CONTROL_OVERSHOOT_FRACTION))
                                        }
                                        val next = nearestIndex(clamped)
                                        if (next != index) {
                                            index = next
                                            onChange(tickValues[next])
                                            if (haptics) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        }
                                    }
                                }
                                if (!cancelled && !moved) {
                                    // 单点：直接跳到最近的刻度
                                    val next = nearestIndex(fractionAt(lastX).coerceIn(0f, 1f))
                                    if (next != index) {
                                        index = next
                                        onChange(tickValues[next])
                                    }
                                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                val target = if (cancelled) currentValueFraction else tickFractions[index]
                                dragging = false
                                // 拖动松手：带着手指速度吸附到刻度
                                val releaseVelocity = if (moved) tracker.calculateVelocity().x.coerceIn(-6f, 6f) else 0f
                                interaction.settleJob = scope.launch {
                                    pos.animateTo(target, MotionTokens.control(0.0005f), initialVelocity = releaseVelocity)
                                    if (generation != interaction.generation) return@launch
                                    interacting = false
                                    // 交互期间数值若被外部改动：落位后再对齐到最新值
                                    if (pos.value != currentValueFraction) {
                                        pos.animateTo(currentValueFraction, MotionTokens.control(0.0005f))
                                    }
                                }
                                scope.launch { overshoot.animateTo(0f, MotionTokens.control(0.0005f)) }
                                scope.launch { press.animateTo(1f, MotionTokens.release(0.001f)) }
                                if (!cancelled) onFinished?.invoke()
                                finished = true
                            } finally {
                                if (!finished && generation == interaction.generation) {
                                    dragging = false
                                    interacting = false
                                    interaction.settleJob = scope.launch { pos.animateTo(currentValueFraction, MotionTokens.control(0.0005f)) }
                                    scope.launch { overshoot.animateTo(0f, MotionTokens.control(0.0005f)) }
                                    scope.launch { press.animateTo(1f, MotionTokens.release(0.001f)) }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(coerced, valueRange.start..valueRange.endInclusive, if (steps > 0) steps else 0)
                if (!enabled) {
                    disabled()
                } else {
                    setProgress { target ->
                        val i = nearestIndex(((target - valueRange.start) / span).coerceIn(0f, 1f))
                        onChange(tickValues[i])
                        onFinished?.invoke()
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (bookMode) {
            val bookPalette = CurrentBookPaperPalette
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .graphicsLayer {
                        val o = overshoot.value
                        if (o != 0f) {
                            val k = (abs(o) / CONTROL_OVERSHOOT_FRACTION).coerceIn(0f, 1f) * MAX_STRETCH
                            scaleX = 1f + k
                            scaleY = 1f - k * 0.6f
                            transformOrigin = TransformOrigin(if ((o > 0f) != reverseTrack) 0f else 1f, 0.5f)
                        }
                    },
            ) {
                drawBookSlider(
                    fraction = pos.value.coerceIn(0f, 1f),
                    palette = bookPalette,
                    enabledProgress = enabledProgress.value,
                    pressScale = 1f + (1f - press.value) * 0.45f,
                    drawnFractions = drawnFractions,
                )
            }
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .graphicsLayer {
                        // 拖出两端：整条朝拖动方向轻微拉长、略变细（停稳为 1，外观不变）
                        val o = overshoot.value
                        if (o != 0f) {
                            val k = (abs(o) / CONTROL_OVERSHOOT_FRACTION).coerceIn(0f, 1f) * MAX_STRETCH
                            scaleX = 1f + k
                            scaleY = 1f - k * 0.6f
                            transformOrigin = TransformOrigin(if (o > 0f) 0f else 1f, 0.5f)
                        }
                    }
                    .clip(CircleShape)
                    .drawBehind {
                        drawRect(blend(disabledBackground, enabledBackground))
                        drawRect(Color.Black, alpha = dragShade)
                    },
            ) {
                val barHeight = size.height
                val barWidth = size.width
                val thumbRadius = barHeight / 2f
                val availableWidth = (barWidth - 2f * thumbRadius).coerceAtLeast(0f)
                val fraction = pos.value.coerceIn(0f, 1f)
                val centerX = thumbRadius + fraction * availableWidth
                drawLine(
                    color = blend(disabledForeground, enabledForeground),
                    start = Offset(0f, barHeight / 2f),
                    end = Offset(centerX, barHeight / 2f),
                    strokeWidth = barHeight,
                    cap = StrokeCap.Round,
                )
                if (drawnFractions.isNotEmpty()) {
                    val keyPointRadius = barHeight / 7.5f
                    for (f in drawnFractions) {
                        val x = thumbRadius + f * availableWidth
                        // 用户明确保留只显示未覆盖刻度的外观。
                        if (x <= centerX) continue
                        drawCircle(
                            color = keyPointColor,
                            radius = keyPointRadius,
                            center = Offset(x, barHeight / 2f),
                        )
                    }
                }
                drawCircle(
                    color = blend(disabledThumb, enabledThumb),
                    radius = thumbRadius * 0.72f * press.value,
                    center = Offset(centerX, barHeight / 2f),
                )
            }
        }
    }
}

/** 书页内连续滑动条：手势/无障碍交互同原生，视觉为纸上细墨轨与烫金圆钮。 */
@Composable
private fun BookContinuousSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (() -> Unit)?,
    height: Dp,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val coerced = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val fraction = ((coerced - valueRange.start) / span).coerceIn(0f, 1f)
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val currentCoerced by rememberUpdatedState(coerced)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val thumbInsetPx = with(LocalDensity.current) { 7.dp.toPx() }
    var width by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val enabledProgress = rememberControlEnabledProgress(enabled)
    val bookPalette = CurrentBookPaperPalette

    fun emitFraction(rawFraction: Float) {
        val next = valueRange.start + rawFraction.coerceIn(0f, 1f) * span
        if (next != currentCoerced) onChange(next)
    }

    fun fromPosition(x: Float) {
        val usable = (width - 2f * thumbInsetPx).takeIf { it > 0f } ?: return
        val raw = ((x - thumbInsetPx) / usable).coerceIn(0f, 1f)
        emitFraction(if (rtl) 1f - raw else raw)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(maxOf(height, 36.dp))
            .onSizeChanged { width = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(currentCoerced, valueRange.start..valueRange.endInclusive, 0)
                if (!enabled) {
                    disabled()
                } else {
                    setProgress { target ->
                        onChange(target.coerceIn(valueRange.start, valueRange.endInclusive))
                        onFinished?.invoke()
                        true
                    }
                }
            }
            .onKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val delta = span * 0.02f
                val next = when (event.key) {
                    Key.DirectionRight -> currentCoerced + if (rtl) -delta else delta
                    Key.DirectionLeft -> currentCoerced + if (rtl) delta else -delta
                    Key.DirectionUp -> currentCoerced + delta
                    Key.DirectionDown -> currentCoerced - delta
                    else -> return@onKeyEvent false
                }.coerceIn(valueRange.start, valueRange.endInclusive)
                onChange(next)
                onFinished?.invoke()
                true
            }
            .focusable(enabled)
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(rtl, thumbInsetPx) {
                            detectTapGestures { pos ->
                                fromPosition(pos.x)
                                onFinished?.invoke()
                            }
                        }
                        .pointerInput(rtl, thumbInsetPx) {
                            detectHorizontalDragGestures(
                                onDragStart = { pos ->
                                    dragging = true
                                    fromPosition(pos.x)
                                },
                                onDragEnd = {
                                    dragging = false
                                    onFinished?.invoke()
                                },
                                onDragCancel = {
                                    dragging = false
                                },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    fromPosition(change.position.x)
                                },
                            )
                        }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            drawBookSlider(
                fraction = fraction,
                palette = bookPalette,
                enabledProgress = enabledProgress.value,
                pressScale = if (dragging) 1.15f else 1f,
            )
        }
    }
}

/** 书页内细墨轨 + 烫金滑动条统一绘制（离散刻度、连续滑条与 DialogMiuixSlider 共用）。 */
internal fun DrawScope.drawBookSlider(
    fraction: Float,
    palette: BookPaperPalette,
    enabledProgress: Float = 1f,
    pressScale: Float = 1f,
    drawnFractions: FloatArray = FloatArray(0),
) {
    val thumbRadius = 6.5.dp.toPx() * pressScale
    val edgeInset = 7.dp.toPx()
    val availableWidth = (size.width - 2f * edgeInset).coerceAtLeast(0f)
    val f = fraction.coerceIn(0f, 1f)
    val rtl = layoutDirection == LayoutDirection.Rtl
    fun positionOf(progress: Float) = edgeInset + (if (rtl) 1f - progress else progress) * availableWidth
    val centerX = positionOf(f)
    val cy = size.height / 2f
    val alphaFactor = lerpAlpha(0.42f, 1f, enabledProgress)
    val trackColor = palette.sliderTrack.copy(alpha = palette.sliderTrack.alpha * alphaFactor)
    val activeColor = lerp(palette.inkMuted, palette.sliderActive, enabledProgress)
    val thumbColor = lerp(palette.inkMuted, palette.sliderThumb, enabledProgress)
    drawLine(
        color = trackColor,
        start = Offset(edgeInset, cy),
        end = Offset(edgeInset + availableWidth, cy),
        strokeWidth = 2.6.dp.toPx(),
        cap = StrokeCap.Round,
    )
    if (f > 0f && availableWidth > 0f) {
        drawLine(
            color = activeColor,
            start = Offset(positionOf(0f), cy),
            end = Offset(centerX, cy),
            strokeWidth = 3.2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    if (drawnFractions.isNotEmpty()) {
        val dotRadius = 2.dp.toPx()
        val dotColor = palette.inkSecondary.copy(alpha = 0.55f * alphaFactor)
        for (dotF in drawnFractions) {
            if (dotF <= f) continue
            val x = positionOf(dotF)
            drawCircle(color = dotColor, radius = dotRadius, center = Offset(x, cy))
        }
    }
    drawCircle(
        color = thumbColor,
        radius = thumbRadius,
        center = Offset(centerX, cy),
    )
    drawCircle(
        color = palette.sliderThumbRim,
        radius = thumbRadius + 0.8.dp.toPx(),
        center = Offset(centerX, cy),
        style = Stroke(width = 1.4.dp.toPx()),
    )
}

private fun lerpAlpha(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction.coerceIn(0f, 1f)

private class SliderInteraction {
    var generation = 0
    var settleJob: Job? = null
}
