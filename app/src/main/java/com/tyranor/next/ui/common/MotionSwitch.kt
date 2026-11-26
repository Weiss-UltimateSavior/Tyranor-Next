package com.tyranor.next.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import com.tyranor.next.ui.common.motion.withRowPressSquish
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.BookSerif
import com.tyranor.next.theme.BookSerifBold
import com.tyranor.next.theme.CurrentBookPaperPalette
import com.tyranor.next.theme.LocalShelfBookLeaf
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.shelfBookMarker
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.preference.SwitchPreference as MiuixSwitchPreference
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * 开关（AGENTS.md「开关按钮统一规范」的行内开关）。
 *
 * 外观与 Miuix `Switch` 完全一致（49×28dp 轨道、20dp 圆形拇指、关 4dp / 开 25dp、按下放大 1.127、同一套取色）；
 * 「增强」动效档换成 iOS 26 的手感：
 * - 拇指由连续的位置弹簧驱动：点一下带速度滑过去，到位果冻回弹；
 * - 左右拖动跟手，拖出两端被橡皮筋拉住，越过中线轻震；松手按位置决定开关；
 * - 按住（含按住拖动）时拇指保持正圆、只放大 1.127，与原版一致，不拉成椭圆 / 胶囊；松手后的滑动与回弹里再按速度略微拉长（果冻感），停稳恢复圆形；
 * - 轨道与拇指颜色随拇指位置连续过渡（关 = 灰，开 = 主题色），不是瞬间变色。
 * 「普通」档直接使用 Miuix 原组件。
 */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val inBook = LocalShelfBookLeaf.current
    if (!MotionMode.enhanced && !inBook) {
        MiuixSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
        return
    }
    JellySwitch(checked, onCheckedChange, modifier, enabled, bookMode = inBook)
}

/**
 * 设置条目式开关：与 Miuix `SwitchPreference` 相同的排版与行为（整行可点、右侧开关），
 * 右侧开关换成 [Switch] 的 iOS 手感；在书页内自动切换为纸墨行与小型墨线开关。「普通」动效档在书外直接使用 Miuix 原组件。
 */
@Composable
fun SwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
    titleTextStyle: TextStyle? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val inBook = LocalShelfBookLeaf.current
    if (inBook) {
        val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
        val palette = CurrentBookPaperPalette
        val interaction = remember { MutableInteractionSource() }
        Column(
            modifier = modifier
                .withRowPressSquish(enabled = enabled)
                .fillMaxWidth()
                .shelfBookMarker(interaction, palette.marker)
                .drawBehind {
                    drawLine(
                        color = palette.ruleSubtle,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 0.6.dp.toPx(),
                    )
                }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Switch,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClick,
                    onClick = { currentOnCheckedChange.takeIf { enabled }?.invoke(!checked) },
                )
                .padding(horizontal = 4.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (startAction != null) {
                    Box(Modifier.padding(end = 10.dp), contentAlignment = Alignment.Center) { startAction() }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = BookSerifBold,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = if (enabled) palette.ink else palette.inkMuted,
                    )
                    if (!summary.isNullOrEmpty()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                            color = if (enabled) palette.inkSecondary else palette.inkMuted,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .padding(start = 8.dp, end = 8.dp)
                        .align(Alignment.CenterVertically)
                        .weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    endActions()
                }
                JellySwitch(
                    checked = checked,
                    onCheckedChange = currentOnCheckedChange,
                    enabled = enabled,
                    bookMode = true,
                    consumeDown = onLongClick != null,
                )
            }
            if (bottomAction != null) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    bottomAction()
                }
            }
        }
        return
    }
    if (titleTextStyle == null && !MotionMode.enhanced && onLongClick == null) {
        MiuixSwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            modifier = modifier,
            titleColor = titleColor,
            summary = summary,
            summaryColor = summaryColor,
            startAction = startAction,
            endActions = endActions,
            bottomAction = bottomAction,
            insideMargin = insideMargin,
            holdDownState = holdDownState,
            enabled = enabled,
        )
        return
    }

    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val toggle = { currentOnCheckedChange(!checked) }
    // 行点击和长按由同一个手势入口裁决，长按成立后松手不会切换开关。
    val rowModifier = modifier.withRowPressSquish(enabled = enabled)
    val rowClickModifier = if (onLongClick == null) {
        rowModifier
    } else {
        rowModifier.combinedClickable(
            enabled = enabled,
            role = Role.Switch,
            onClick = toggle,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
        )
    }

    if (titleTextStyle != null) {
        BasicComponent(
            modifier = rowClickModifier,
            insideMargin = insideMargin,
            startAction = startAction,
            endActions = {
                Row(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .align(Alignment.CenterVertically)
                        .weight(1f, fill = false),
                ) {
                    endActions()
                }
                if (onLongClick == null) {
                    Switch(
                        checked = checked,
                        onCheckedChange = currentOnCheckedChange,
                        enabled = enabled,
                    )
                } else {
                    JellySwitch(
                        checked = checked,
                        onCheckedChange = currentOnCheckedChange,
                        enabled = enabled,
                        consumeDown = true,
                    )
                }
            },
            bottomAction = bottomAction,
            onClick = toggle.takeIf { enabled && onLongClick == null },
            role = Role.Switch,
            holdDownState = holdDownState,
            enabled = enabled,
        ) {
            Text(
                text = title,
                style = titleTextStyle,
                color = if (enabled) titleColor.color else titleColor.disabledColor,
            )
            summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) summaryColor.color else summaryColor.disabledColor,
                )
            }
        }
        return
    }

    BasicComponent(
        // 行级按压反馈内置在组件里（调用处不用再写）：单控件卡片整卡反馈、多控件卡片只有被按的这一行形变
        modifier = rowClickModifier,
        insideMargin = insideMargin,
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = {
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically)
                    .weight(1f, fill = false),
            ) {
                endActions()
            }
            JellySwitch(
                checked = checked,
                onCheckedChange = currentOnCheckedChange,
                enabled = enabled,
                bookMode = false,
                consumeDown = onLongClick != null,
            )
        },
        bottomAction = bottomAction,
        onClick = toggle.takeIf { enabled && onLongClick == null },
        role = Role.Switch,
        holdDownState = holdDownState,
        enabled = enabled,
    )
}

// 与 Miuix Switch 一致的几何（dp）与书内小型墨线开关几何（dp）
private const val TRACK_W = 49f
private const val TRACK_H = 28f
private const val THUMB = 20f
private const val THUMB_OFF_X = 4f
private const val THUMB_ON_X = 25f
private const val BOOK_TRACK_W = 40f
private const val BOOK_TRACK_H = 22f
private const val BOOK_THUMB = 14f
private const val BOOK_THUMB_OFF_X = 4f
private const val BOOK_THUMB_ON_X = 22f
private const val PRESS_SCALE = 1.127f

/**
 * 用连续位置绘制开关，书外保留 Miuix 静止几何与颜色，书内切换为小型墨线开关。
 * 无回调或禁用时不处理手势；取消手势或调用方拒绝切换后恢复到当前真实状态。
 */
@Composable
private fun JellySwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bookMode: Boolean = false,
    consumeDown: Boolean = false,
) {
    val colors = jellySwitchColors(enabled, bookMode = bookMode)
    val enabledColors = if (enabled) colors else jellySwitchColors(enabled = true, bookMode = bookMode)
    val disabledColors = if (enabled) jellySwitchColors(enabled = false, bookMode = bookMode) else colors
    val enabledProgress = rememberControlEnabledProgress(enabled)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onCheckedChange)
    val interactive = enabled && onCheckedChange != null
    val trackW = if (bookMode) BOOK_TRACK_W else TRACK_W
    val trackH = if (bookMode) BOOK_TRACK_H else TRACK_H
    val thumbSize = if (bookMode) BOOK_THUMB else THUMB
    val thumbOffX = if (bookMode) BOOK_THUMB_OFF_X else THUMB_OFF_X
    val thumbOnX = if (bookMode) BOOK_THUMB_ON_X else THUMB_ON_X
    val enhanced = MotionMode.enhanced
    // 开关自己的布局坐标：拖动按「根坐标」计算位移，不受外层行正在做的按压形变（缩放 / 位移）影响，拇指不会被带偏
    val trackCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    // 0 = 关，1 = 开；拖出两端可略超出
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    // 0 = 未按，1 = 按住
    val press = remember { Animatable(0f) }

    // 外部状态变化（整行点击、程序设置）：果冻回弹地滑到新位置
    LaunchedEffect(checked, enhanced) {
        val target = if (checked) 1f else 0f
        if (!enhanced) {
            pos.snapTo(target)
            return@LaunchedEffect
        }
        // 已在朝目标运动（松手时 settleTo 发起）就不打断，保留它的速度
        val heading = if (pos.isRunning) pos.targetValue else pos.value
        if (heading != target) pos.animateTo(target, MotionTokens.control(0.0005f))
    }

    /** 继承释放速度吸附到目标，同时收回按压；调用方拒绝切换时再平滑归位。 */
    fun settleTo(target: Boolean, velocity: Float = 0f) {
        scope.launch {
            val dest = if (target) 1f else 0f
            if (!enhanced) {
                pos.snapTo(dest)
                val actual = if (currentChecked) 1f else 0f
                if (pos.value != actual) pos.snapTo(actual)
            } else {
                // 带着松手时的手指速度继续滑过去（跟手、不「断」）
                pos.animateTo(dest, MotionTokens.control(0.0005f), initialVelocity = velocity)
                // 回调方拒绝了这次切换：再滑回实际状态
                val actual = if (currentChecked) 1f else 0f
                // 0.8 阻尼：被拒绝后的「滑回」刻意更平稳，不并入 control
                if (pos.value != actual) pos.animateTo(actual, MotionSprings.response(0.36f, 0.8f, 0.0005f))
            }
        }
        // 松手回弹按 iOS 手感调过（0.32s / 0.5），比 release 略快、略少回弹，保留
        scope.launch {
            if (enhanced) press.animateTo(0f, MotionSprings.response(0.32f, 0.5f, 0.001f)) else press.snapTo(0f)
        }
    }

    /** 仅在目标不同于真实状态时发出轻震并通知调用方，不直接修改外部状态。 */
    fun commit(target: Boolean) {
        if (target != currentChecked) {
            haptics.performHapticFeedback(if (target) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            currentOnChange?.invoke(target)
        }
    }

    Box(
        modifier = modifier
            .wrapContentSize(Alignment.Center)
            .size(trackW.dp, trackH.dp)
            .clip(CircleShape)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                if (!enabled) disabled()
                if (interactive) {
                    onClick {
                        commit(!currentChecked)
                        true
                    }
                }
            }
            .onGloballyPositioned { trackCoords[0] = it }
            .pointerInput(interactive, trackW, thumbOffX, thumbOnX, consumeDown) {
                if (!interactive) return@pointerInput
                val travel = (thumbOnX - thumbOffX).dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 带行长按时，开关上的按压只交给开关识别，纵向移动仍可由列表接手。
                    if (consumeDown) down.consume()
                    // 指针流被异常取消时释放代码跑不到：finally 兜底回到当前状态
                    var finished = false
                    try {
                        scope.launch { press.animateTo(1f, MotionTokens.press(0.001f)) }
                        val start = pos.value.coerceIn(0f, 1f)
                        val tracker = VelocityTracker()
                        var dx = 0f
                        var dy = 0f
                        val startRoot = trackCoords[0]?.takeIf { it.isAttached }?.localToRoot(down.position)
                        var dragging = false
                        var cancelled = false
                        var side = start > 0.5f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                cancelled = true
                                break
                            }
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val coords = trackCoords[0]
                            if (startRoot != null && coords != null && coords.isAttached) {
                                val root = coords.localToRoot(change.position)
                                dx = root.x - startRoot.x
                                dy = root.y - startRoot.y
                            } else {
                                val delta = change.positionChange()
                                dx += delta.x
                                dy += delta.y
                            }
                            if (!dragging) {
                                if (change.isConsumed || (abs(dy) > slop && abs(dy) > abs(dx))) {
                                    // 纵向滚动接手：不切换
                                    cancelled = true
                                    break
                                }
                                if (abs(dx) > slop) dragging = true
                            }
                            if (dragging) {
                                change.consume()
                                val raw = start + dx / travel
                                val clamped = raw.coerceIn(0f, 1f)
                                val next = clamped + rubberBandOffset(raw - clamped, CONTROL_OVERSHOOT_FRACTION)
                                tracker.addPosition(change.uptimeMillis, Offset(next, 0f))
                                // 同一帧内立即生效，不经协程调度晚一帧
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { pos.snapTo(next) }
                                val now = next > 0.5f
                                if (now != side) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    side = now
                                }
                            }
                        }
                        when {
                            cancelled -> settleTo(currentChecked)
                            dragging -> {
                                val target = pos.value > 0.5f
                                commit(target)
                                settleTo(target, tracker.calculateVelocity().x.coerceIn(-8f, 8f))
                            }
                            else -> {
                                val target = !currentChecked
                                commit(target)
                                settleTo(target)
                            }
                        }
                        finished = true
                    } finally {
                        if (!finished) settleTo(currentChecked)
                    }
                }
            }
            .drawBehind {
                val p = pos.value
                val pc = p.coerceIn(0f, 1f)
                val e = enabledProgress.value
                drawRect(blendSwitchColor(enabledColors, disabledColors, e, pc, track = true))
                if (bookMode) {
                    val palette = CurrentBookPaperPalette
                    val strokeAlpha = 0.42f + (1f - 0.42f) * e
                    val borderColor = lerp(palette.switchBorderOff, palette.switchBorderOn, pc)
                        .copy(alpha = strokeAlpha)
                    val strokePx = 1.25.dp.toPx()
                    val half = strokePx / 2f
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(half, half),
                        size = Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                        style = Stroke(width = strokePx),
                    )
                }
                val k = press.value.coerceAtLeast(0f)
                // 松手后的滑动 / 回弹里，运动越快拉得越长（果冻）；按住时（含按住拖动）保持正圆，与原版一致——
                // 按住的程度 k 越大、拉长越少，松手后随按压收回平滑过渡到果冻形变
                val speed = abs(pos.velocity) * 0.012f
                val stretch = speed.coerceAtMost(0.25f) * (1f - k.coerceIn(0f, 1f))
                val scale = 1f + (PRESS_SCALE - 1f) * k.coerceAtMost(1.2f)
                val d = thumbSize.dp.toPx() * scale
                val w = d * (1f + stretch)
                val h = d * (1f - stretch * 0.18f)
                // 甩动时的过冲只保留一点（≤4%），拇指不会画到轨道外
                val pd = p.coerceIn(-0.04f, 1.04f)
                val cx = thumbOffX.dp.toPx() + thumbSize.dp.toPx() / 2f + pd * (thumbOnX - thumbOffX).dp.toPx()
                val cy = size.height / 2f
                val thumbColor = blendSwitchColor(enabledColors, disabledColors, e, pc, track = false)
                if (stretch < 0.001f) {
                    // 正圆（含按住时放大后的圆）
                    drawCircle(color = thumbColor, radius = d / 2f, center = Offset(cx, cy))
                } else {
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(cx - w / 2f, cy - h / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2f, h / 2f),
                    )
                }
            },
    )
}

/**
 * 按开关位置 [pc] 取轨道 / 拇指色；可用态过渡途中（[e] 不在 0 / 1）再在不可用与可用两套取色之间插值。
 * 停稳时（[e] = 1 / 0）直接走对应一套取色的原有插值，与原来按 enabled 取色的绘制结果完全一致；
 * 按进度而不是按 enabled 取端点，enabled 刚变、动画尚未起步的那一帧也不会先跳到终点色。
 */
private fun blendSwitchColor(
    enabledColors: JellySwitchColors,
    disabledColors: JellySwitchColors,
    e: Float,
    pc: Float,
    track: Boolean,
): Color {
    fun pick(c: JellySwitchColors): Color =
        if (track) lerp(c.uncheckedTrack, c.checkedTrack, pc) else lerp(c.uncheckedThumb, c.checkedThumb, pc)
    if (e >= 1f) return pick(enabledColors)
    if (e <= 0f) return pick(disabledColors)
    return lerp(pick(disabledColors), pick(enabledColors), e)
}

private data class JellySwitchColors(
    val checkedTrack: Color,
    val uncheckedTrack: Color,
    val checkedThumb: Color,
    val uncheckedThumb: Color,
)

/** 书外沿用 Miuix `SwitchDefaults.switchColors()` 取色，书内切为纸墨细框与烫金配色。 */
@Composable
private fun jellySwitchColors(enabled: Boolean, bookMode: Boolean = false): JellySwitchColors {
    if (bookMode) {
        val palette = CurrentBookPaperPalette
        val alpha = if (enabled) 1f else 0.42f
        return JellySwitchColors(
            checkedTrack = palette.switchTrackOn.copy(alpha = palette.switchTrackOn.alpha * alpha),
            uncheckedTrack = palette.switchTrackOff,
            checkedThumb = palette.switchThumbOn.copy(alpha = alpha),
            uncheckedThumb = palette.switchThumbOff.copy(alpha = alpha),
        )
    }
    val c = MiuixTheme.colorScheme
    val dynamic = MiuixTheme.isDynamicColor
    return if (enabled) {
        JellySwitchColors(
            checkedTrack = c.primary,
            uncheckedTrack = c.secondary,
            checkedThumb = c.onPrimary,
            uncheckedThumb = if (dynamic) c.onSurface.copy(alpha = 0.38f) else c.onSecondary,
        )
    } else {
        JellySwitchColors(
            checkedTrack = c.disabledPrimary,
            uncheckedTrack = c.disabledSecondary,
            checkedThumb = if (dynamic) c.surface else c.disabledOnPrimary,
            uncheckedThumb = c.disabledOnSecondary,
        )
    }
}
