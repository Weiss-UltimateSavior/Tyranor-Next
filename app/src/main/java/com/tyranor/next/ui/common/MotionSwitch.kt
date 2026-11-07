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
 * - 按住 / 运动中拇指拉长成胶囊，停稳时恢复圆形；
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
        modifier = modifier,
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

/** 按住时拇指额外拉长成胶囊的比例（停稳为 0，外观与圆形一致）。 */
private const val PRESS_STRETCH = 0.32f

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
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onCheckedChange)
    val interactive = enabled && onCheckedChange != null
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
                            val delta = change.positionChange()
                            dx += delta.x
                            dy += delta.y
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
                                val next = clamped + rubberBand(raw - clamped)
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
                drawRect(lerp(colors.uncheckedTrack, colors.checkedTrack, pc))
                val k = press.value.coerceAtLeast(0f)
                // 运动越快拉得越长；按住时额外拉长成胶囊
                val speed = abs(pos.velocity) * 0.012f
                val stretch = (PRESS_STRETCH * k + speed.coerceAtMost(0.25f))
                val scale = 1f + (PRESS_SCALE - 1f) * k.coerceAtMost(1.2f)
                val d = THUMB.dp.toPx() * scale
                val w = d * (1f + stretch)
                val h = d * (1f - stretch * 0.18f)
                // 甩动时的过冲只保留一点（≤4%），拇指不会画到轨道外
                val pd = p.coerceIn(-0.04f, 1.04f)
                val cx = THUMB_OFF_X.dp.toPx() + THUMB.dp.toPx() / 2f + pd * (THUMB_ON_X - THUMB_OFF_X).dp.toPx()
                val cy = size.height / 2f
                val thumbColor = lerp(colors.uncheckedThumb, colors.checkedThumb, pc)
                if (stretch < 0.001f && k < 0.001f) {
                    drawCircle(color = thumbColor, radius = THUMB.dp.toPx() / 2f, center = Offset(cx, cy))
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

/** 拖出两端的橡皮筋：越拉越难拉，最多约 0.12 个行程。 */
private fun rubberBand(over: Float): Float {
    if (over == 0f) return 0f
    val c = 0.12f
    val a = abs(over)
    val r = c * (1f - 1f / (a / c + 1f))
    return if (over > 0f) r else -r
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
