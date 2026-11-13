package com.tyranor.next.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.withFrameNanos
import com.tyranor.next.theme.AppThemeColors
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import com.tyranor.next.ui.common.motion.withRowPressSquish
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.BlurTextWith
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.popup.WindowDropdownPopup
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 与 Miuix `OverlayDropdownPreference`（items 版本）外观与行为一致的下拉条目，仅把右侧「当前值」文字
 * 换成原地模糊替换：选项变化（点选、被其它设置联动、重置）时，旧值在原地糊掉、新值从模糊里清晰起来，
 * 标题、摘要与箭头保持不动。「普通」动效档与首次组合没有过渡（与 Miuix 原组件一致）。
 * 参数与 Miuix 版本同名同义，各页面直接替换 import 即可。
 */
@Composable
fun OverlayDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    startAction: @Composable (() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    renderInRootScaffold: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    val entry = remember(items, selectedIndex, onSelectedIndexChange) {
        DropdownEntry(
            items.mapIndexed { index, item ->
                DropdownItem(
                    text = item,
                    selected = index == selectedIndex,
                    onClick = { onSelectedIndexChange?.invoke(index) },
                )
            },
        )
    }
    OverlayDropdownPreference(
        entry = entry,
        title = title,
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        dropdownColors = dropdownColors,
        startAction = startAction,
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        maxHeight = maxHeight,
        enabled = enabled,
        showValue = showValue,
        renderInRootScaffold = renderInRootScaffold,
        collapseOnSelection = true,
        onExpandedChange = onExpandedChange,
    )
}

/** [DropdownEntry] 版本：与 Miuix 同名重载一致，右侧值文字同样原地模糊替换。 */
@Composable
fun OverlayDropdownPreference(
    entry: DropdownEntry,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    startAction: @Composable (() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    renderInRootScaffold: Boolean = true,
    collapseOnSelection: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isDropdownExpanded = remember { mutableStateOf(false) }
    val isHoldDown = remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
    val currentOnExpandedChange = rememberUpdatedState(onExpandedChange)
    val selectionEpoch = remember { DropdownSelectionEpoch() }
    val setExpanded: (Boolean) -> Unit = remember {
        { expanded ->
            if (isDropdownExpanded.value != expanded) {
                selectionEpoch.invalidate()
                isDropdownExpanded.value = expanded
                currentOnExpandedChange.value?.invoke(expanded)
            }
        }
    }
    val itemsNotEmpty = entry.items.isNotEmpty()
    val actualEnabled = enabled && itemsNotEmpty
    val actionColor = if (actualEnabled) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }
    // 「增强」档：长按条目后手指不抬起、滑到弹层里的某个选项上，松手即选定（普通档与原版一致）
    val slideEnabled = MotionMode.enhanced && actualEnabled
    val rowCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val configuration = LocalConfiguration.current
    // Selection/callback changes keep the session; replacing options or window geometry does not.
    val slide = remember(
        entry.items.map { it.text to it.enabled }, slideEnabled,
        configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation,
        LocalDensity.current, LocalLayoutDirection.current,
    ) { DropdownSlideState() }
    slide.rowCoords = rowCoordinates[0]
    val currentSlide by rememberUpdatedState(slide)
    val slideScope = rememberCoroutineScope()
    val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val currentEntry by rememberUpdatedState(entry)
    // 松手选定：选中项先弹一下，再触发选择并收起弹层（条目长按滑选与弹层内按住滑动共用）
    val pickItem: (Int) -> Unit = { picked ->
        val pickedItem = currentEntry.items.getOrNull(picked)
        if (pickedItem != null && pickedItem.enabled) {
            val selection = selectionEpoch.beginSelection()
            // 同步标记「已确认」：选择块留在选中项上（不会先淡出再出现），手势收尾的清理也不会把它清掉
            slide.confirmed = picked
            slideScope.launch {
                // 震动反馈 + 选项立刻变成「已选定」的样子（主题色文字 + 勾选），而不是直接把它染成主题色
                currentHapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                pickedItem.onClick?.invoke()
                // 选中生效会触发一次较重的重组（有时还牵动整页换皮）：等它那几帧过去再开始弹性动画，
                // 否则动画的头几帧被吞掉，看起来一抽一抽的
                repeat(2) { withFrameNanos { } }
                if (!selectionEpoch.isCurrent(selection)) return@launch
                if (currentSlide === slide) slide.pulseTick++
                // 选择块吸附到位的瞬间就开始收起：选择块与弹层一起渐隐消失，整套退出很利落
                delay(CONFIRM_POP_MILLIS)
                if (!selectionEpoch.isCurrent(selection)) return@launch
                if (collapseOnSelection) {
                    currentSlide.closing = true
                    setExpanded(false)
                }
            }
        }
    }
    val handleClick = remember(actualEnabled) {
        {
            if (actualEnabled) {
                setExpanded(!isDropdownExpanded.value)
                if (isDropdownExpanded.value) {
                    isHoldDown.value = true
                    currentHapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                }
            }
        }
    }
    // 行级按压反馈内置在组件里（调用处不用再写）：单控件卡片整卡反馈、多控件卡片只有被按的这一行形变（见 pressSquishGroup）
    val rowModifier = modifier.withRowPressSquish(enabled = actualEnabled)
    BasicComponent(
        modifier = if (slideEnabled) {
            rowModifier
                .onGloballyPositioned {
                    rowCoordinates[0] = it
                    slide.rowCoords = it
                }
                .pointerInput(longPressMillis, slide) {
                    awaitEachGesture {
                        // Initial 传递：先于条目自身的点击处理；只观察，长按成立后才开始消费
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (isDropdownExpanded.value) return@awaitEachGesture
                        val slop = viewConfiguration.touchSlop
                        // 超时返回 null = 一直按住没动 = 长按；提前返回非 null = 点按或滑动，交还给原有处理
                        val early = withTimeoutOrNull(longPressMillis) {
                            var outcome = true
                            while (outcome) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed ||
                                    (change.position - down.position).getDistance() > slop
                                ) {
                                    outcome = false
                                }
                            }
                            false
                        }
                        if (early != null) return@awaitEachGesture
                        // 长按成立：展开弹层，进入滑选
                        slide.reset()
                        slide.active = true
                        slide.fromRow = true
                        slide.rowTracking = true
                        setExpanded(true)
                        isHoldDown.value = true
                        currentHapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                        try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // 消费：条目自身的点击逻辑看到已消费的抬起，不会再把弹层切回收起
                            change.consume()
                            val coords = slide.rowCoords
                            if (coords != null && coords.isAttached) {
                                // 用 localToScreen 换算：行正在做按压形变（缩放 / 位移）时，位置也是对的
                                val screenPos = coords.localToScreen(change.position)
                                // 横向放宽 48dp：手指不必严格停在弹层宽度内，纵向对准哪一项就选哪一项
                                // 只按纵向位置选项：手指可以在条目任意位置（标题处也行）长按，再直着往下滑，
                                // 不必横向对准弹层
                                slide.fingerY = screenPos.y
                                val hit = slide.itemBounds.entries.firstOrNull {
                                    screenPos.y >= it.value.top && screenPos.y < it.value.bottom
                                }?.key ?: -1
                                if (hit != slide.hovered) {
                                    slide.hovered = hit
                                    if (hit >= 0) {
                                        currentHapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                }
                            }
                            if (!change.pressed) {
                                val picked = slide.hovered
                                slide.active = false
                                pickItem(picked)
                                break
                            }
                        }
                        } finally {
                            // 手势结束 / 被取消：不论如何都撤掉「正在滑选」状态（已确认选中的除外）
                            slide.rowTracking = false
                            if (slide.confirmed < 0) slide.resetGesture()
                        }
                    }
                }
        } else {
            rowModifier
        },
        interactionSource = interactionSource,
        insideMargin = insideMargin,
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = {
            if (showValue && itemsNotEmpty) {
                val text = entry.items.firstOrNull { it.selected }?.text
                if (!text.isNullOrEmpty()) {
                    // 当前值原地模糊替换：只有真正变化的这段文字在动，标题 / 箭头不动
                    BlurTextWith(
                        text = text,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .align(Alignment.CenterVertically)
                            .weight(1f, fill = false),
                        // 语言下拉自己的当前值也要模糊替换（被点选的控件）；其它文字随语言变化不动画
                        animateOnLocaleChange = true,
                        // 选项值整段模糊替换，即使后半截相同（「浅色」→「深色」整个换）
                        partial = false,
                    ) { valueText, valueModifier ->
                        Text(
                            text = valueText,
                            modifier = valueModifier,
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = actionColor,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
            DropdownArrowEndAction(
                actionColor = actionColor,
            )
            if (itemsNotEmpty) {
                if (slideEnabled) {
                    key(slide) {
                        SlideSelectDropdownPopup(
                            entry = entry,
                            show = isDropdownExpanded.value,
                            slide = slide,
                            onPick = pickItem,
                            onDismiss = { setExpanded(false) },
                            onDismissFinished = {
                                isHoldDown.value = false
                                slide.reset()
                            },
                            maxHeight = maxHeight,
                            dropdownColors = dropdownColors,
                            renderInRootScaffold = renderInRootScaffold,
                            collapseOnSelection = collapseOnSelection,
                        )
                    }
                } else if (LocalAppSheetContent.current) {
                    // 抽屉是独立窗口，普通档沿用 WindowDropdownPreference 的窗口弹层。
                    WindowDropdownPopup(
                        entry = entry,
                        show = isDropdownExpanded.value,
                        onDismiss = { setExpanded(false) },
                        onDismissFinished = { isHoldDown.value = false },
                        maxHeight = maxHeight,
                        dropdownColors = dropdownColors,
                        collapseOnSelection = collapseOnSelection,
                    )
                } else {
                    OverlayDropdownPopup(
                        entry = entry,
                        show = isDropdownExpanded.value,
                        onDismiss = { setExpanded(false) },
                        onDismissFinished = { isHoldDown.value = false },
                        maxHeight = maxHeight,
                        dropdownColors = dropdownColors,
                        renderInRootScaffold = renderInRootScaffold,
                        collapseOnSelection = collapseOnSelection,
                    )
                }
            }
        },
        bottomAction = bottomAction,
        onClick = handleClick,
        role = Role.DropdownList,
        holdDownState = isHoldDown.value,
        enabled = actualEnabled,
    )
}

/** 页内与抽屉的滑选共用同一份弹层内容，仅由窗口上下文选择宿主。 */
@Composable
private fun DropdownListPopup(
    show: Boolean,
    alignment: PopupPositionProvider.Align,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    maxHeight: Dp?,
    minWidth: Dp,
    renderInRootScaffold: Boolean,
    content: @Composable () -> Unit,
) {
    if (LocalAppSheetContent.current) {
        WindowListPopup(
            show = show,
            alignment = alignment,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
            maxHeight = maxHeight,
            minWidth = minWidth,
            content = content,
        )
    } else {
        OverlayListPopup(
            show = show,
            alignment = alignment,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
            maxHeight = maxHeight,
            minWidth = minWidth,
            renderInRootScaffold = renderInRootScaffold,
            content = content,
        )
    }
}

/** Delayed selection completion survives a mode switch, but never a reopen or a newer selection. */
internal class DropdownSelectionEpoch {
    private var generation = 0L

    fun invalidate() { generation++ }

    fun beginSelection(): Long = ++generation

    fun isCurrent(selection: Long): Boolean = generation == selection
}

/** 长按滑选的会话状态：悬停项、已确认项，以及弹层里各选项在屏幕坐标系下的区域。 */
internal class DropdownSlideState {
    var active by mutableStateOf(false)
    var hovered by mutableIntStateOf(-1)
    var confirmed by mutableIntStateOf(-1)
    val itemBounds = mutableStateMapOf<Int, Rect>()
    var rowCoords: LayoutCoordinates? = null

    /** 当前滑选是不是从条目那次长按延续下来的（手指一直没抬）；否则是在弹层里重新按下的。 */
    var fromRow: Boolean = false

    /**
     * 条目那次长按的手势是否还活着（手指仍按着、事件还在送达）。长按弹出弹层后，这次触摸常被系统取消：
     * 取消后必须立刻清掉，否则会残留成「仍在滑选」，把之后在弹层里重新按下的滑动全挡掉（选择块就一直不出现）。
     */
    var rowTracking: Boolean = false

    /** 确认动画的触发计数：选中生效后的重组帧过去才 +1，由它启动「鼓起再落稳」。 */
    var pulseTick by mutableIntStateOf(0)

    /** 弹层正在收起（选定之后）：选择块随弹层一起渐隐，且不再响应新触摸。 */
    var closing by mutableStateOf(false)

    /** 手指当前在屏幕坐标里的纵向位置（滑选中每个移动事件更新）：选择块的中心直接跟着它走。 */
    var fingerY by mutableFloatStateOf(0f)

    /**
     * 一行「文字区」的高度（选择块的统一高度基准）。首尾两行比中间行多一段额外内边距，
     * 所以有 3 项及以上时取中间行的实测高度；只有 1 ~ 2 项时按固定比例由总高度推算。
     */
    fun pitch(count: Int): Float? {
        if (count >= 3) return itemBounds[1]?.height
        val first = itemBounds[0] ?: return null
        val last = itemBounds[count - 1] ?: return null
        val total = if (count == 1) first.height else first.height + last.height
        return total / (count + 2 * EDGE_PAD_RATIO)
    }

    /** 第 [index] 项文字区的纵向中心（屏幕坐标）：首行的额外内边距在上方、末行的在下方，据此对齐到文字。 */
    fun centerOf(index: Int, count: Int): Float? {
        val b = itemBounds[index] ?: return null
        val p = pitch(count) ?: return null
        return when {
            count == 1 -> (b.top + b.bottom) / 2f
            index == 0 -> b.bottom - p / 2f
            else -> b.top + p / 2f
        }
    }

    fun resetGesture() {
        active = false
        hovered = -1
        confirmed = -1
        closing = false
        fromRow = false
        rowTracking = false
    }

    fun reset() {
        resetGesture()
        itemBounds.clear()
    }

    fun pillTarget(count: Int): DropdownPillTarget {
        val row = if (confirmed >= 0) confirmed else hovered
        val center = if (row in 0 until count) centerOf(row, count) else null
        val pitch = pitch(count)
        val target = when {
            center == null || pitch == null -> null
            confirmed >= 0 -> center
            active -> center + (fingerY - center).coerceIn(-pitch * 0.5f, pitch * 0.5f) * MAGNET
            else -> null
        }
        return DropdownPillTarget(target, confirmed >= 0)
    }
}

internal data class DropdownPillTarget(val center: Float?, val confirmed: Boolean)

/** The existing integrator, with a subpixel resting threshold so an open popup can stop requesting frames. */
internal class DropdownPillMotion {
    var center = 0f
        private set
    var speed = 0f
        private set
    var alpha = 0f
        private set
    private var alphaSpeed = 0f

    fun settleIfClose(target: DropdownPillTarget): Boolean {
        val alphaTarget = if (target.center != null) 1f else 0f
        if (kotlin.math.abs(alpha - alphaTarget) > 0.001f || kotlin.math.abs(alphaSpeed) > 0.01f) return false
        if (target.center != null &&
            (kotlin.math.abs(center - target.center) > 0.01f || kotlin.math.abs(speed) > 0.1f)) return false
        target.center?.let { center = it }
        speed = 0f
        alpha = alphaTarget
        alphaSpeed = 0f
        return true
    }

    fun step(target: DropdownPillTarget, elapsed: Float) {
        val destination = target.center
        if (destination != null && alpha < 0.02f) {
            center = destination
            speed = 0f
        }
        val k = if (target.confirmed) 650f else 900f
        val zeta = if (target.confirmed) 0.52f else 0.78f
        val alphaTarget = if (destination != null) 1f else 0f
        val ka = if (alphaTarget > 0f) 800f else 280f
        val za = if (alphaTarget > 0f) 0.85f else 1f
        var remaining = elapsed.coerceIn(0f, 0.05f)
        while (remaining > 0f) {
            val h = minOf(remaining, 1f / 240f)
            remaining -= h
            if (destination != null) {
                speed += (-k * (center - destination) - 2f * zeta * kotlin.math.sqrt(k) * speed) * h
                center += speed * h
            } else {
                speed = 0f
            }
            alphaSpeed += (-ka * (alpha - alphaTarget) - 2f * za * kotlin.math.sqrt(ka) * alphaSpeed) * h
            alpha += alphaSpeed * h
        }
    }
}

internal suspend fun runDropdownPillMotion(
    target: () -> DropdownPillTarget,
    apply: (DropdownPillMotion) -> Unit,
) {
    val motion = DropdownPillMotion()
    var last: Long? = null
    while (true) {
        val destination = target()
        if (motion.settleIfClose(destination)) {
            apply(motion)
            // Do not restart an animation at each touch event. Only suspend after the integrator settles.
            snapshotFlow(target).first { it != destination }
            last = null
        } else {
            val now = withFrameNanos { it }
            motion.step(target(), last?.let { (now - it) / 1_000_000_000f } ?: 0f)
            last = now
            apply(motion)
        }
    }
}

/**
 * 与 Miuix 下拉弹层同构（同一个 OverlayListPopup + 同一套选项组件）。滑选时的运动设计：
 * - **选择块跟手**：块的中心直接追随手指的纵向位置，只被所在行的中心轻轻「吸住」（磁吸），
 *   用保留速度的弹簧追随——手指快慢、改向、停下，块都自然衔接，不是逐行「跳」；
 * - **运动越快越被拉长**（同时略微变窄、体积感守恒），停下时恢复，带一点点超调；
 * - **松手吸附 + 回弹**：块弹性地落到选中行的中心并弹开一下，再收起弹层；
 * - 块的高度统一、按文字区居中，首尾行额外的内边距不计入；手指下选项的文字微微前倾并变深色强调色。
 * 点选行为与原版一致。
 *
 * 每个选项外包一层容器用来量位置：容器必须撑满弹层给的宽度，并把这个宽度作为最小宽度传给选项
 * （`propagateMinConstraints`），选项才会像原版一样撑满整行。
 */
@Composable
private fun SlideSelectDropdownPopup(
    entry: DropdownEntry,
    show: Boolean,
    slide: DropdownSlideState,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    maxHeight: Dp?,
    dropdownColors: DropdownColors,
    renderInRootScaffold: Boolean,
    collapseOnSelection: Boolean,
) {
    val haptic = LocalHapticFeedback.current
    val currentOnPick by rememberUpdatedState(onPick)
    val items = entry.items
    val accent = MiuixTheme.colorScheme.primary
    // 文字着色用更深一点的强调色：浅色选择块上对比度足够
    val textAccent = remember(accent) { lerp(accent, Color.Black, 0.32f) }
    // 深色下白色高光 / 描边要淡得多（深底上同样的白会显得又亮又硬）
    val dark = AppThemeColors.isDark
    val sheenAlpha = if (dark) 0.14f else 0.34f
    val rimTop = if (dark) 0.38f else 0.65f
    val rimMid = if (dark) 0.45f else 0.40f
    val rimBottom = if (dark) 0.16f else 0.30f

    // 选择块：中心（屏幕坐标）与显隐由下面的「逐帧积分弹簧」驱动（快照态，绘制阶段读取）；确认弹开 / 渐隐各一个动画值。
    // 不能用 snapshotFlow + collectLatest + animateTo：手指一按下就滑动时目标每个触摸事件都在变，
    // 每次都重启 animateTo，动画每次只走到第一帧就被取消——显隐动画几乎走不起来，选择块就一直透明 / 很浅，手指停下才补上
    var pillCenter by remember { mutableFloatStateOf(0f) }
    var pillSpeed by remember { mutableFloatStateOf(0f) }
    var pillAlpha by remember { mutableFloatStateOf(0f) }
    // Q 弹：确认瞬间脉冲值从 1 弹簧回落到 0（低阻尼，会过冲到负值再回来）——选中项与选择块先「鼓起来」再弹回，带一次反向回弹
    val pulse = remember { Animatable(0f) }
    // 确认后选择块「融入」消失：整体渐隐并略微收小，留下选项自己的「已选定」样子
    val pillFade = remember { Animatable(1f) }
    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        pillAlpha = 0f
        pillSpeed = 0f
        runDropdownPillMotion({ slide.pillTarget(items.size) }) { motion ->
            pillCenter = motion.center
            pillSpeed = motion.speed
            pillAlpha = motion.alpha
        }
    }
    LaunchedEffect(slide.pulseTick) {
        if (slide.pulseTick > 0 && slide.confirmed >= 0) {
            // 先轻轻鼓起（带一点过冲），再柔和落稳；全程连续，没有突变
            pulse.snapTo(0f)
            // 吸附瞬间轻轻一压再落稳（短促，不拖时间）；收起由 pickItem 在约 0.1 秒后触发
            pulse.animateTo(1f, spring(0.6f, 1100f, 0.002f))
            pulse.animateTo(0f, spring(0.8f, 520f, 0.002f))
        }
    }
    LaunchedEffect(slide.confirmed) {
        if (slide.confirmed < 0) {
            pulse.snapTo(0f)
            pillFade.snapTo(1f)
        }
    }
    // 每次弹层重新展开都清掉上一次的「确认 / 收起中」残留：状态绝不能把新一轮的触摸挡掉
    LaunchedEffect(show) {
        if (show) {
            slide.closing = false
            slide.confirmed = -1
        }
    }
    // 随弹层一起渐隐（与弹层自己的退场同时进行，时长相当）
    LaunchedEffect(slide.closing) {
        if (slide.closing) pillFade.animateTo(0f, tween(190, easing = FastOutSlowInEasing))
    }

    DropdownListPopup(
        show = show,
        // 与 Miuix 下拉弹层一致：右对齐到条目、最小宽度不额外撑开
        alignment = PopupPositionProvider.Align.End,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        maxHeight = maxHeight,
        minWidth = 0.dp,
        renderInRootScaffold = renderInRootScaffold,
    ) {
        ListPopupColumn {
            items.forEachIndexed { index, item ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            slide.itemBounds[index] = Rect(
                                coords.positionOnScreen(),
                                Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                            )
                        }
                        // 在弹层里按住并上下滑动：选择块跟着手指走，松手选定手指下的那一项；
                        // 没有滑动（普通点按）时不消费，交给选项自己的点击
                        .pointerInput(slide, index) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                // 不设任何拦截：弹层里的每一次新触摸都直接接管（并覆盖掉旧状态）。
                                // 条目长按弹出弹层后那次触摸常被系统取消、其手势协程可能一直挂着，
                                // 曾经用它当门闩，结果把之后所有在弹层里的滑动都挡住了。
                                // 仅在「确认中」（选定后弹层正在收起）时不再响应新触摸。
                                if (slide.closing) return@awaitEachGesture
                                val slop = viewConfiguration.touchSlop
                                var sliding = false
                                var finished = false
                                // 手指一按下，按住的区域就出现主题色高光（还没滑动也有；松手点按时由选项自己的点击处理）
                                // Keep this open popup's freshly measured geometry for the new gesture.
                                slide.resetGesture()
                                slide.active = true
                                slide.fromRow = false
                                slide.hovered = index
                                slide.fingerY = (slide.itemBounds[index]?.top ?: 0f) + down.position.y
                                try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!sliding && (change.position - down.position).getDistance() > slop) {
                                        sliding = true
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                    if (sliding) {
                                        change.consume()
                                        val top = slide.itemBounds[index]?.top ?: break
                                        val y = top + change.position.y
                                        slide.fingerY = y
                                        val hit = slide.itemBounds.entries.firstOrNull { y >= it.value.top && y < it.value.bottom }?.key ?: -1
                                        if (hit != slide.hovered) {
                                            slide.hovered = hit
                                            if (hit >= 0) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                        }
                                    }
                                    if (!change.pressed) {
                                        if (sliding) {
                                            val picked = slide.hovered
                                            slide.active = false
                                            if (picked >= 0) currentOnPick(picked) else slide.resetGesture()
                                        } else if (slide.confirmed < 0) {
                                            // 普通点按：高光让位给选项自己的点击（它会触发选择并收起）
                                            slide.resetGesture()
                                        }
                                        finished = true
                                        break
                                    }
                                }
                                } finally {
                                    // 手势被取消 / 中断：撤掉高光，状态不残留
                                    if (!finished && slide.confirmed < 0) slide.resetGesture()
                                }
                            }
                        }
                        // 选择块由最后一项在自己的内容之后绘制：块在所有选项之上（选项自带不透明背景，画在下面会被盖住）；
                        // 块是半透明玻璃，下面的文字仍然清晰
                        .then(
                            if (index == items.lastIndex) {
                                Modifier.drawWithContent {
                                    drawContent()
                                    val a = (pillAlpha * pillFade.value).coerceIn(0f, 1f)
                                    val b0 = slide.itemBounds[items.lastIndex]
                                    val pitch = slide.pitch(items.size)
                                    if (a > 0.01f && b0 != null && pitch != null) {
                                        val v = pillSpeed
                                        // 速度 → 拉伸（纵向变长、横向略变窄，体积感守恒），停下恢复
                                        val stretch = (kotlin.math.abs(v) / (pitch * 11f)).coerceIn(0f, 0.2f)
                                        val pulseNow = pulse.value
                                        val baseH = pitch - 4.dp.toPx()
                                        val h = baseH * (1f + stretch) * (0.9f + 0.1f * a) + 3.dp.toPx() * a + 4.dp.toPx() * pulseNow
                                        val insetX = 6.dp.toPx() + baseH * stretch * 0.18f - 2.dp.toPx() * a - 2.dp.toPx() * pulseNow
                                        val w = (size.width - insetX * 2f) * (0.94f + 0.06f * a)
                                        val cy = pillCenter - b0.top
                                        // 完整胶囊：与弹层本身的大圆角协调
                                        val radius = h / 2f
                                        val tl = Offset((size.width - w) / 2f, cy - h / 2f)
                                        val corner = CornerRadius(radius)
                                        val sz = Size(w, h)
                                        // 玻璃质感：上浓下淡的主题色填充 + 顶部一层柔和的高光 + 镜面描边（亮边偏上方、下沿微亮，像透镜边缘的折射）
                                        drawRoundRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(
                                                    accent.copy(alpha = (0.36f * a + 0.08f * pulseNow).coerceIn(0f, 0.6f)),
                                                    accent.copy(alpha = (0.24f * a + 0.08f * pulseNow).coerceIn(0f, 0.6f)),
                                                ),
                                                startY = tl.y,
                                                endY = tl.y + h,
                                            ),
                                            topLeft = tl,
                                            size = sz,
                                            cornerRadius = corner,
                                        )
                                        // 高光画整颗胶囊的形状（渐变在上半部消失）：只画上半截高度的圆角矩形，圆角会被压小，
                                        // 上方两个角会从胶囊的圆角外凸出来（深色下就是左上 / 右上一小块白）
                                        drawRoundRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.White.copy(alpha = sheenAlpha * a), Color.Transparent),
                                                startY = tl.y,
                                                endY = tl.y + h * 0.55f,
                                            ),
                                            topLeft = tl,
                                            size = sz,
                                            cornerRadius = corner,
                                        )
                                        drawRoundRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = rimTop * a),
                                                    accent.copy(alpha = rimMid * a),
                                                    Color.White.copy(alpha = rimBottom * a),
                                                ),
                                                startY = tl.y,
                                                endY = tl.y + h,
                                            ),
                                            topLeft = tl,
                                            size = sz,
                                            cornerRadius = corner,
                                            style = Stroke(width = 1.2.dp.toPx()),
                                        )
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                    propagateMinConstraints = true,
                ) {
                    DropdownImpl(
                        item = item,
                        optionSize = items.size,
                        isSelected = item.selected,
                        index = index,
                        dropdownColors = dropdownColors,
                        enabled = item.enabled,
                        dialogMode = false,
                        hasSubmenu = false,
                        isFirst = index == 0,
                        isLast = index == items.lastIndex,
                        onSelectedIndexChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            item.onClick?.invoke()
                            if (collapseOnSelection) onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** 松手确认的选项「弹一下」的时间，之后弹层收起。 */
private const val CONFIRM_POP_MILLIS = 110L

/** 弹层首尾额外内边距相对一行高的比例（项数太少无法实测时的推算；取自 Miuix 原版弹层的实测几何）。 */
private const val EDGE_PAD_RATIO = 0.175f

/** 选择块被所在行中心「吸住」的程度：0 = 纯跟手指，1 = 纯吸附在行中心。 */
private const val MAGNET = 0.38f
