package com.tyranor.next.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.BlurTextWith
import com.tyranor.next.ui.common.motion.withRowPressSquish
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

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

/** Both motion modes share text-only hold selection; committed selection keeps its accessibility semantics. */
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
    val expanded = remember { mutableStateOf(false) }
    val holdDown = remember { mutableStateOf(false) }
    val currentEntry by rememberUpdatedState(entry)
    val currentExpandedChange by rememberUpdatedState(onExpandedChange)
    val actualEnabled = enabled && entry.enabled && entry.items.isNotEmpty()
    val currentEnabled by rememberUpdatedState(actualEnabled)
    val epoch = remember { DropdownSelectionEpoch() }
    val setExpanded: (Boolean) -> Unit = remember {
        { show ->
            if (expanded.value != show) {
                epoch.invalidate()
                expanded.value = show
                holdDown.value = show
                currentExpandedChange?.invoke(show)
            }
        }
    }
    val configuration = LocalConfiguration.current
    val slide = remember(
        entry.items.map { it.text to it.enabled }, actualEnabled,
        configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation,
        LocalDensity.current, LocalLayoutDirection.current,
    ) { DropdownSlideState() }
    val currentSlide by rememberUpdatedState(slide)
    val haptic = LocalHapticFeedback.current
    val currentHaptic by rememberUpdatedState(haptic)
    val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val actionColor = if (actualEnabled) MiuixTheme.colorScheme.onSurfaceVariantActions
        else MiuixTheme.colorScheme.disabledOnSecondaryVariant

    // Preview is the only haptic event. Opening, clicking, committing and closing are silent.
    val hit: (Offset) -> Int = { position ->
        slide.hit(position, currentEntry.items.map { currentEnabled && it.enabled })
    }
    val currentHit by rememberUpdatedState(hit)
    val preview: (Offset) -> Unit = { position ->
        val previous = slide.highlightedIndex(currentEntry.items.indexOfFirst { it.selected })
        val candidate = hit(position)
        slide.hovered = candidate
        if (candidate >= 0 && candidate != previous) {
            currentHaptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    val currentPreview by rememberUpdatedState(preview)
    val pick: (Int) -> Unit = pick@{ index ->
        val item = currentEntry.items.getOrNull(index)
        if (!currentEnabled || item == null || !item.enabled || slide.closing) return@pick
        val selection = epoch.beginSelection()
        slide.confirmed = index
        slide.active = false
        slide.closing = true
        try {
            item.onClick?.invoke()
        } finally {
            if (epoch.isCurrent(selection)) {
                if (collapseOnSelection) setExpanded(false) else currentSlide.resetGesture()
            }
        }
    }
    val currentPick by rememberUpdatedState(pick)
    LaunchedEffect(actualEnabled) {
        if (!actualEnabled) {
            slide.reset()
            setExpanded(false)
        }
    }

    BasicComponent(
        modifier = modifier.withRowPressSquish(enabled = actualEnabled)
            .onGloballyPositioned { slide.rowCoords = it }
            .pointerInput(slide, longPressMillis) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!currentEnabled || expanded.value || down.isConsumed) return@awaitEachGesture
                    if (!awaitDropdownHold(down, longPressMillis)) return@awaitEachGesture
                    val session = slide.beginGesture(currentEntry.items.indexOfFirst { it.selected })
                    setExpanded(true)
                    try {
                        trackDropdownHold(down.id, { slide.rowCoords }, slide, session, currentPreview, currentHit, currentPick)
                    } finally {
                        slide.cancelGesture(session)
                    }
                }
            },
        interactionSource = interactionSource,
        insideMargin = insideMargin,
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        bottomAction = bottomAction,
        onClick = { if (currentEnabled) setExpanded(!expanded.value) },
        role = Role.DropdownList,
        holdDownState = holdDown.value,
        enabled = actualEnabled,
        endActions = {
            if (showValue && entry.items.isNotEmpty()) {
                val text = entry.items.firstOrNull { it.selected }?.text
                if (!text.isNullOrEmpty()) {
                    BlurTextWith(
                        text = text,
                        modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically).weight(1f, fill = false),
                        animateOnLocaleChange = true,
                        partial = false,
                    ) { value, valueModifier ->
                        Text(value, modifier = valueModifier, fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = actionColor, textAlign = TextAlign.End)
                    }
                }
            }
            DropdownArrowEndAction(actionColor = actionColor)
            if (entry.items.isNotEmpty()) key(slide) {
                HoldSelectDropdownPopup(
                    entry = entry,
                    enabled = actualEnabled,
                    show = expanded.value,
                    slide = slide,
                    onPreview = preview,
                    onHit = hit,
                    onPick = pick,
                    onDismiss = { slide.resetGesture(); setExpanded(false) },
                    onDismissFinished = { holdDown.value = false; slide.reset() },
                    maxHeight = maxHeight,
                    dropdownColors = dropdownColors,
                    renderInRootScaffold = renderInRootScaffold,
                )
            }
        },
    )
}

/** Keep the original popup host, including the independent Window used by a sheet. */
@Composable
private fun DropdownListPopup(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    maxHeight: Dp?,
    renderInRootScaffold: Boolean,
    content: @Composable () -> Unit,
) {
    if (LocalAppSheetContent.current) {
        WindowListPopup(show = show, alignment = PopupPositionProvider.Align.End,
            onDismissRequest = onDismissRequest, onDismissFinished = onDismissFinished,
            maxHeight = maxHeight, minWidth = 0.dp, content = content)
    } else {
        OverlayListPopup(show = show, alignment = PopupPositionProvider.Align.End,
            onDismissRequest = onDismissRequest, onDismissFinished = onDismissFinished,
            maxHeight = maxHeight, minWidth = 0.dp, renderInRootScaffold = renderInRootScaffold,
            content = content)
    }
}

@Composable
private fun HoldSelectDropdownPopup(
    entry: DropdownEntry,
    enabled: Boolean,
    show: Boolean,
    slide: DropdownSlideState,
    onPreview: (Offset) -> Unit,
    onHit: (Offset) -> Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    maxHeight: Dp?,
    dropdownColors: DropdownColors,
    renderInRootScaffold: Boolean,
) {
    val currentPreview by rememberUpdatedState(onPreview)
    val currentHit by rememberUpdatedState(onHit)
    val currentPick by rememberUpdatedState(onPick)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSelected by rememberUpdatedState(entry.items.indexOfFirst { it.selected })
    val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val highlighted = slide.highlightedIndex(entry.items.indexOfFirst { it.selected })
    DropdownListPopup(show, onDismiss, onDismissFinished, maxHeight, renderInRootScaffold) {
        ListPopupColumn {
            entry.items.forEachIndexed { index, item ->
                val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
                val textColor = if (index == highlighted) MiuixTheme.colorScheme.primary else dropdownColors.contentColor
                Box(
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned {
                            coordinates[0] = it
                            // Use clipped bounds so offscreen rows in a scrolling menu cannot win.
                            val bounds = it.boundsInWindow()
                            val origin = it.localToScreen(Offset.Zero) - it.localToWindow(Offset.Zero)
                            slide.itemBounds[index] = bounds.translate(origin)
                            slide.rowClipped[index] = bounds.isEmpty || bounds.height < it.size.height - 1f
                        }
                        .pointerInput(slide, index, longPressMillis) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (!currentEnabled || slide.closing || down.isConsumed) return@awaitEachGesture
                                // A list that fits has nothing to scroll: the touch itself is the selection, with
                                // no timing. Only a scrolling list keeps the long-press guard that protects it.
                                val fits = slide.allRowsVisible(entry.items.size)
                                val started = if (fits) {
                                    currentEvent.changes.count { it.pressed } == 1
                                } else {
                                    awaitDropdownHold(down, longPressMillis)
                                }
                                if (!started) return@awaitEachGesture
                                // The touched row is the candidate at once; Miuix's own press state stays out.
                                if (fits) down.consume()
                                val session = slide.beginGesture(currentSelected)
                                try {
                                    trackDropdownHold(down.id, { coordinates[0] }, slide, session, currentPreview, currentHit, currentPick)
                                } finally {
                                    slide.cancelGesture(session)
                                }
                            }
                        },
                    propagateMinConstraints = true,
                ) {
                    DropdownImpl(
                        item = item,
                        optionSize = entry.items.size,
                        isSelected = item.selected,
                        index = index,
                        dropdownColors = dropdownColors.copy(
                            contentColor = textColor,
                            selectedContentColor = textColor,
                            selectedSummaryColor = dropdownColors.summaryColor,
                            selectedContainerColor = dropdownColors.containerColor,
                            selectedIndicatorColor = Color.Transparent,
                        ),
                        enabled = enabled && item.enabled,
                        dialogMode = false,
                        hasSubmenu = false,
                        isFirst = index == 0,
                        isLast = index == entry.items.lastIndex,
                        onSelectedIndexChange = { currentPick(index) },
                    )
                }
            }
        }
    }
}

/**
 * Only for a surface that can scroll (the page behind the preference row, or a long popup): a stationary
 * single finger must survive the timeout without a scroll claiming it.
 */
private suspend fun AwaitPointerEventScope.awaitDropdownHold(
    down: PointerInputChange,
    timeoutMillis: Long,
): Boolean {
    if (currentEvent.changes.count { it.pressed } != 1) return false
    val early = withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (event.changes.count { it.pressed } > 1 || change == null || !change.pressed ||
                (change.isConsumed && change.uptimeMillis != down.uptimeMillis) ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            ) return@withTimeoutOrNull false
            val final = awaitPointerEvent(PointerEventPass.Final)
            if (final.changes.any {
                    it.id == down.id && it.isConsumed && it.uptimeMillis != down.uptimeMillis
                }) return@withTimeoutOrNull false
        }
    }
    return early == null
}

/** Only this gesture's original pointer can preview or commit; cancellation never selects. */
private suspend fun AwaitPointerEventScope.trackDropdownHold(
    pointer: PointerId,
    coordinates: () -> LayoutCoordinates?,
    slide: DropdownSlideState,
    session: Long,
    preview: (Offset) -> Unit,
    hit: (Offset) -> Int,
    pick: (Int) -> Unit,
) {
    val initial = currentEvent.changes.firstOrNull { it.id == pointer } ?: return
    val initialCoordinates = coordinates()?.takeIf { it.isAttached } ?: return
    preview(initialCoordinates.localToScreen(initial.position))
    while (slide.isCurrent(session)) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (!slide.isCurrent(session)) return
        if (event.changes.count { it.pressed } > 1) return
        val change = event.changes.firstOrNull { it.id == pointer } ?: return
        val coords = coordinates()?.takeIf { it.isAttached } ?: return
        if (change.isConsumed) return
        val position = coords.localToScreen(change.position)
        change.consume()
        if (!change.pressed) {
            val chosen = slide.hovered
            // UP validates the last preview; it cannot create a new candidate or haptic.
            if (chosen >= 0 && hit(position) == chosen) pick(chosen)
            return
        }
        preview(position)
    }
}

/** Synchronous selection cannot finish a newer popup opened by its callback. */
internal class DropdownSelectionEpoch {
    private var generation = 0L
    fun invalidate() { generation++ }
    fun beginSelection(): Long = ++generation
    fun isCurrent(selection: Long): Boolean = generation == selection
}

/** Preview state is separate from the caller's committed selection. */
internal class DropdownSlideState {
    var active by mutableStateOf(false)
    var hovered by mutableIntStateOf(-1)
    var confirmed by mutableIntStateOf(-1)
    var closing by mutableStateOf(false)
    val itemBounds = mutableStateMapOf<Int, Rect>()
    val rowClipped = mutableStateMapOf<Int, Boolean>()
    var rowCoords: LayoutCoordinates? = null
    private var generation = 0L

    fun beginGesture(selected: Int = -1): Long {
        resetGesture()
        active = true
        hovered = selected
        return generation
    }

    fun isCurrent(session: Long): Boolean = generation == session

    fun cancelGesture(session: Long) {
        if (isCurrent(session) && confirmed < 0) resetGesture()
    }

    /** Every row is laid out and fully inside its clip, so the popup has nothing to scroll. */
    fun allRowsVisible(count: Int): Boolean = count > 0 && (0 until count).all { rowClipped[it] == false }

    fun highlightedIndex(selected: Int): Int = when {
        confirmed >= 0 -> confirmed
        active -> hovered
        else -> selected
    }

    fun hit(position: Offset, enabled: List<Boolean>): Int = itemBounds.entries.firstOrNull {
        enabled.getOrNull(it.key) == true && !it.value.isEmpty && it.value.contains(position)
    }?.key ?: -1

    fun resetGesture() {
        generation++
        active = false
        hovered = -1
        confirmed = -1
        closing = false
    }

    fun reset() {
        resetGesture()
        itemBounds.clear()
        rowClipped.clear()
    }
}
