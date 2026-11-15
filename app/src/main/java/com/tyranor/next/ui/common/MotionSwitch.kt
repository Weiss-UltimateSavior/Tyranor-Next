package com.tyranor.next.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionTokens
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
    if (!MotionMode.enhanced) {
        MiuixSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
        return
    }
    JellySwitch(checked, onCheckedChange, modifier, enabled)
}

/**
 * 设置条目式开关：与 Miuix `SwitchPreference` 相同的排版与行为（整行可点、右侧开关），
 * 右侧开关换成 [Switch] 的 iOS 手感。「普通」动效档直接使用 Miuix 原组件。
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
) {
    if (!MotionMode.enhanced) {
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
    BasicComponent(
        // 行级按压反馈内置在组件里（调用处不用再写）：单控件卡片整卡反馈、多控件卡片只有被按的这一行形变
        modifier = modifier.withRowPressSquish(enabled = enabled),
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
            )
        },
        bottomAction = bottomAction,
        onClick = {
            currentOnCheckedChange.takeIf { enabled }?.invoke(!checked)
        },
        role = Role.Switch,
        holdDownState = holdDownState,
        enabled = enabled,
    )
}

// 与 Miuix Switch 一致的几何（dp）
private const val TRACK_W = 49f
private const val TRACK_H = 28f
private const val THUMB = 20f
private const val THUMB_OFF_X = 4f
private const val THUMB_ON_X = 25f
private const val PRESS_SCALE = 1.127f

/**
 * 用连续位置绘制增强档开关，保留 Miuix 静止几何与颜色；无回调或禁用时不处理手势。
 * 取消手势或调用方拒绝切换后恢复到当前真实状态，动画值在绘制阶段读取。
 */
@Composable
private fun JellySwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = jellySwitchColors(enabled)
    // 可用 ↔ 不可用（如依赖的总开关被关掉）：两套取色按弹簧进度连续过渡，而不是瞬间变灰；
    // 进度只在绘制阶段读取，停稳在 0 / 1 时直接使用对应取色（与原样逐像素一致）
    val enabledColors = if (enabled) colors else jellySwitchColors(true)
    val disabledColors = if (enabled) jellySwitchColors(false) else colors
    val enabledProgress = rememberControlEnabledProgress(enabled)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onCheckedChange)
    val interactive = enabled && onCheckedChange != null
    // 开关自己的布局坐标：拖动按「根坐标」计算位移，不受外层行正在做的按压形变（缩放 / 位移）影响，拇指不会被带偏
    val trackCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    // 0 = 关，1 = 开；拖出两端可略超出
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    // 0 = 未按，1 = 按住
    val press = remember { Animatable(0f) }

    // 外部状态变化（整行点击、程序设置）：果冻回弹地滑到新位置
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        // 已在朝目标运动（松手时 settleTo 发起）就不打断，保留它的速度
        val heading = if (pos.isRunning) pos.targetValue else pos.value
        if (heading != target) pos.animateTo(target, MotionTokens.control(0.0005f))
    }

    /** 继承释放速度吸附到目标，同时收回按压；调用方拒绝切换时再平滑归位。 */
    fun settleTo(target: Boolean, velocity: Float = 0f) {
        scope.launch {
            // 带着松手时的手指速度继续滑过去（跟手、不「断」）
            pos.animateTo(if (target) 1f else 0f, MotionTokens.control(0.0005f), initialVelocity = velocity)
            // 回调方拒绝了这次切换：再滑回实际状态
            val actual = if (currentChecked) 1f else 0f
            // 0.8 阻尼：被拒绝后的「滑回」刻意更平稳，不并入 control
            if (pos.value != actual) pos.animateTo(actual, MotionSprings.response(0.36f, 0.8f, 0.0005f))
        }
        // 松手回弹按 iOS 手感调过（0.32s / 0.5），比 release 略快、略少回弹，保留
        scope.launch { press.animateTo(0f, MotionSprings.response(0.32f, 0.5f, 0.001f)) }
    }

    /** 仅在目标不同于真实状态时发出轻震并通知调用方，不直接修改外部状态。 */
    fun commit(target: Boolean) {
        if (target != currentChecked) {
            haptics.performHapticFeedback(if (target) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            currentOnChange?.invoke(target)
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .wrapContentSize(Alignment.Center)
            .size(TRACK_W.dp, TRACK_H.dp)
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
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                val travel = (THUMB_ON_X - THUMB_OFF_X).dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
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
                val k = press.value.coerceAtLeast(0f)
                // 松手后的滑动 / 回弹里，运动越快拉得越长（果冻）；按住时（含按住拖动）保持正圆，与原版一致——
                // 按住的程度 k 越大、拉长越少，松手后随按压收回平滑过渡到果冻形变
                val speed = abs(pos.velocity) * 0.012f
                val stretch = speed.coerceAtMost(0.25f) * (1f - k.coerceIn(0f, 1f))
                val scale = 1f + (PRESS_SCALE - 1f) * k.coerceAtMost(1.2f)
                val d = THUMB.dp.toPx() * scale
                val w = d * (1f + stretch)
                val h = d * (1f - stretch * 0.18f)
                // 甩动时的过冲只保留一点（≤4%），拇指不会画到轨道外
                val pd = p.coerceIn(-0.04f, 1.04f)
                val cx = THUMB_OFF_X.dp.toPx() + THUMB.dp.toPx() / 2f + pd * (THUMB_ON_X - THUMB_OFF_X).dp.toPx()
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

/** 与 Miuix `SwitchDefaults.switchColors()` 默认值相同的取色。 */
@Composable
private fun jellySwitchColors(enabled: Boolean): JellySwitchColors {
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
