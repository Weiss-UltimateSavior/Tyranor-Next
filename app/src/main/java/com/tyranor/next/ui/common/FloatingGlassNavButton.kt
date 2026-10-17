package com.tyranor.next.ui.common

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.tyranor.next.R
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.FloatingNavGlassUnselectedDark
import com.tyranor.next.theme.GlassUnselected
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private val FloatingNavMainSize = 64.dp
private val FloatingNavItemSize = 52.dp
private val FloatingNavArcRadius = 129.dp
private val FloatingNavEdgeMargin = 20.dp

private class FloatingNavSession {
    var startedExpanded = false
    var origin = Offset.Zero
    var ballOrigin = Offset.Zero
    var selecting = false
    var moving = false
}

private class FloatingNavSelectionState {
    var generation = 0L
    var feedback: Job? = null
}

private class FloatingNavGestureActions(
    val down: (Offset) -> Boolean,
    val move: (Offset, Boolean) -> Unit,
    val hold: () -> Unit,
    val up: (Offset, Boolean, Boolean) -> Unit,
    val cancel: () -> Unit,
)

/**
 * 点击、长按滑选和展开后移动共用同一指针；单点子项保持展开，滑选确认后收起。
 * API 31+ 使用 backdrop 采样；较低版本或没有采样源时回退为实底玻璃。
 */
@Composable
internal fun FloatingGlassNavButton(
    backdrop: Backdrop?,
    items: List<LiquidGlassNavItem>,
    selectedIndex: Int,
    primaryColor: Color,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    outsideTapBridge: FloatingNavOutsideTapBridge? = null,
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    val persistedPosition by AppSettingsStore.floatingNavPositionState.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var mainPressed by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragPx by remember { mutableStateOf<Offset?>(null) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var armedIndex by remember { mutableIntStateOf(-1) }
    var committedIndex by remember { mutableIntStateOf(-1) }
    var committing by remember { mutableStateOf(false) }
    val session = remember { FloatingNavSession() }
    val selectionState = remember { FloatingNavSelectionState() }
    val mainMotion = remember { FloatingNavOrbMotion() }
    val motions = remember(items.size) { List(items.size) { FloatingNavOrbMotion() } }
    val lastTick = remember { longArrayOf(0L) }
    val mainSizePx = with(density) { FloatingNavMainSize.toPx() }
    val marginPx = with(density) { FloatingNavEdgeMargin.toPx() }
    val dark = AppThemeColors.isDark

    fun tick(kind: Int = HapticFeedbackConstants.CLOCK_TICK) {
        val now = SystemClock.uptimeMillis()
        val minimumInterval = if (kind == HapticFeedbackConstants.CLOCK_TICK) 130L else 90L
        if (now - lastTick[0] >= minimumInterval) {
            if (view.performHapticFeedback(kind)) lastTick[0] = now
        }
    }
    fun position(): Offset = clampFloatingNavPosition(
        dragPx ?: floatingNavPositionPx(
            persistedPosition, containerSize.width, containerSize.height, mainSizePx, marginPx,
        ), containerSize.width, containerSize.height, mainSizePx, marginPx,
    )
    fun mainCenter() = position() + Offset(mainSizePx / 2f, mainSizePx / 2f)
    fun startAngle(): Float {
        val center = mainCenter()
        return floatingNavArcStartAngle(center.x, center.y, containerSize.width, containerSize.height)
    }
    fun availableRadius(): Float {
        val center = mainCenter()
        val availableX = if (center.x >= containerSize.width / 2f) center.x else containerSize.width - center.x
        val availableY = if (center.y >= containerSize.height / 2f) center.y else containerSize.height - center.y
        val reserve = with(density) { 48.dp.toPx() }
        return minOf(
            (availableX - reserve) / 1.08f, (availableY - reserve) / 1.08f,
        ).coerceAtLeast(0f)
    }
    fun radius(): Float = minOf(with(density) { FloatingNavArcRadius.toPx() }, availableRadius())
    fun itemSizePx(): Float {
        // Resting centres and diameters depend only on position/window geometry.
        val spacing = if (items.size > 1) {
            2f * radius() * sin(Math.PI / (4.0 * (items.size - 1))).toFloat()
        } else Float.MAX_VALUE
        val diameter = (spacing - with(density) { 4.dp.toPx() }) / 1.16f
        return minOf(diameter, with(density) { FloatingNavItemSize.toPx() }).coerceAtLeast(1f)
    }
    fun scaleLimit(index: Int): Float {
        if (items.size < 2) return 1.42f
        val spacing = 2f * radius() * sin(Math.PI / (4.0 * (items.size - 1))).toFloat()
        val extraBudget = ((spacing - with(density) { 4.dp.toPx() }) /
            (itemSizePx() / 2f) - 2f).coerceAtLeast(0f)
        val mine = (motions[index].rawScale.value - 1f).coerceAtLeast(0f)
        var fraction = 1f
        for (other in maxOf(0, index - 1)..minOf(motions.lastIndex, index + 1)) {
            if (other == index) continue
            val total = mine + (motions[other].rawScale.value - 1f).coerceAtLeast(0f)
            if (total > extraBudget && total > 0f) fraction = minOf(fraction, extraBudget / total)
        }
        // Limit only enlargement; no centre can be displaced by another orb's touch.
        return 1f + mine * fraction
    }
    fun itemCenter(index: Int): Offset {
        // Use the endpoints too, giving four compact targets 30 degrees of separation.
        return mainCenter() + floatingNavArcOffset(
            index, items.size, radius(), motions[index].expansion.value.coerceIn(0f, 1.08f), startAngle(),
        )
    }
    fun clearApproach() {
        finger = null
        armedIndex = -1
        motions.forEach { it.proximity = 0f; it.touching = false }
    }
    fun approach(point: Offset?) {
        if (point == null || !expanded || dragging || committing) {
            if (!committing) {
                armedIndex = -1
                motions.forEach { it.proximity = 0f; it.touching = false }
            }
            return
        }
        val baseRadius = itemSizePx() / 2f
        val reach = baseRadius + with(density) { 76.dp.toPx() }
        var nearest = -1
        var nearestDistance = Float.MAX_VALUE
        motions.forEachIndexed { index, motion ->
            val delta = point - itemCenter(index)
            val distance = delta.getDistance()
            val proximity = (1f - distance / reach).coerceIn(0f, 1f)
            motion.proximity = proximity * proximity * (3f - 2f * proximity)
            if (distance > 0.01f) motion.direction = delta
            val entranceScale = 0.8f + motion.expansion.value.coerceIn(0f, 1f) * 0.2f
            val hitRadius = baseRadius * motion.visualScale.value * entranceScale
            if (motion.expansion.value > 0.65f && distance <= hitRadius && distance < nearestDistance) {
                nearest = index
                nearestDistance = distance
            }
        }
        armedIndex = nearest
        motions.forEachIndexed { index, motion -> motion.touching = index == nearest }
    }
    fun persistPosition() {
        val px = dragPx ?: return
        val normalized = floatingNavNormalizedPosition(
            px, containerSize.width, containerSize.height, mainSizePx, marginPx,
        )
        AppSettingsStore.setFloatingNavPosition(context, normalized.first, normalized.second)
    }
    fun dismiss() {
        if (!committing && !dragging) {
            expanded = false
            mainPressed = false
            clearApproach()
        }
    }
    fun finishSelection(index: Int, keepExpanded: Boolean = false) {
        if (committing || index !in items.indices) return
        val generation = ++selectionState.generation
        selectionState.feedback?.cancel()
        committing = true
        committedIndex = index
        motions[index].pulseKey++
        tick(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.KEYBOARD_TAP,
        )
        selectionState.feedback = scope.launch {
            try {
                delay(110)
                currentOnItemClick(index)
                if (!keepExpanded) expanded = false
                clearApproach()
                // Repeated taps become available as soon as this selection is dispatched.
                if (keepExpanded) committing = false
                delay(if (keepExpanded) 160 else 220)
            } finally {
                if (generation == selectionState.generation) {
                    committedIndex = -1
                    committing = false
                    clearApproach()
                }
            }
        }
    }
    fun toggle() {
        if (committing) return
        mainMotion.pulseKey++
        tick()
        expanded = !expanded
        if (!expanded) clearApproach()
    }
    fun cancelGesture() {
        if (session.moving) persistPosition()
        session.moving = false
        dragging = false
        mainPressed = false
        mainMotion.proximity = 0f
        clearApproach()
    }
    fun actions(source: Int) = FloatingNavGestureActions(
        down = { point ->
            val hit = if (source < 0) (point - mainCenter()).getDistance() <= mainSizePx * 0.55f else expanded
            if (committing || !hit) false else {
                // New input owns the feedback now; an older pulse cannot clear its finger state.
                selectionState.generation++
                selectionState.feedback?.cancel()
                committedIndex = -1
                session.startedExpanded = expanded
                session.origin = point
                session.ballOrigin = position()
                session.selecting = source >= 0
                session.moving = false
                finger = point
                mainPressed = source < 0
                mainMotion.direction = point - mainCenter()
                if (source >= 0) approach(point)
                true
            }
        },
        move = { point, moved ->
            if (session.moving) {
                dragPx = clampFloatingNavPosition(
                    session.ballOrigin + point - session.origin,
                    containerSize.width, containerSize.height, mainSizePx, marginPx,
                )
                mainMotion.direction = point - mainCenter()
            } else {
                if (source < 0 && moved && !session.selecting) {
                    session.selecting = true
                    expanded = true
                    mainPressed = false
                    mainMotion.pulseKey++
                    if (!session.startedExpanded) tick()
                }
                finger = point
            }
        },
        hold = {
            if (source < 0) {
                mainPressed = false
                mainMotion.pulseKey++
                tick(HapticFeedbackConstants.LONG_PRESS)
                if (session.startedExpanded) {
                    session.moving = true
                    session.selecting = false
                    dragging = true
                    expanded = false
                    clearApproach()
                    mainMotion.proximity = 0.45f
                } else {
                    session.selecting = true
                    expanded = true
                    approach(finger)
                }
            }
        },
        up = { point, moved, held ->
            mainPressed = false
            if (session.moving) {
                dragPx = clampFloatingNavPosition(
                    session.ballOrigin + point - session.origin,
                    containerSize.width, containerSize.height, mainSizePx, marginPx,
                )
                persistPosition()
                session.moving = false
                dragging = false
                mainMotion.proximity = 0f
                mainMotion.pulseKey++
                if (moved) tick()
                clearApproach()
            } else if (session.selecting) {
                approach(point)
                val target = armedIndex
                if (target >= 0) finishSelection(target, keepExpanded = source >= 0 && !moved && !held)
                else if (source >= 0 && !moved && !held) dismiss()
                else clearApproach()
            } else {
                clearApproach()
                toggle()
            }
        },
        cancel = { cancelGesture() },
    )

    BackHandler(enabled = expanded) { dismiss() }
    LaunchedEffect(persistedPosition) { if (!dragging) dragPx = null }
    val fingerDown by remember { derivedStateOf { finger != null } }
    // Coalesce approach work to display frames, only while a finger owns the expanded fan.
    LaunchedEffect(containerSize, density, motions, expanded, fingerDown, dragging) {
        if (!expanded || !fingerDown || dragging) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { approach(finger) }
        }
    }
    val fanVisible by remember(motions) {
        derivedStateOf { expanded || motions.any { it.expansion.isRunning || it.expansion.value > 0.001f } }
    }

    val mainProgress by animateFloatAsState(
        if (expanded) 1f else 0f,
        spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow),
        label = "floatingMainIcon",
    )
    val mainDescription = stringResource(
        if (expanded) R.string.nav_floating_collapse else R.string.nav_floating_expand,
    )
    val currentDismiss by rememberUpdatedState({
        selectionState.generation++
        selectionState.feedback?.cancel()
        committing = false
        committedIndex = -1
        dismiss()
    })
    val currentContainsControls by rememberUpdatedState<(Offset) -> Boolean>({ windowPoint ->
        val coordinates = containerCoordinates
        if (coordinates?.isAttached != true) {
            false
        } else {
            val point = coordinates.windowToLocal(windowPoint)
            val mainDelta = point - mainCenter()
            val onMain = (abs(mainDelta.x) <= mainSizePx / 2f && abs(mainDelta.y) <= mainSizePx / 2f) ||
                mainDelta.getDistance() <= mainSizePx * mainMotion.visualScale.value / 2f
            val itemHalf = itemSizePx() / 2f
            val touchHalf = maxOf(itemHalf, with(density) { 24.dp.toPx() })
            val onItem = expanded && motions.indices.any { index ->
                val delta = point - itemCenter(index)
                val progress = motions[index].expansion.value.coerceIn(0f, 1f)
                val visibleRadius = itemHalf * motions[index].visualScale.value * (0.8f + progress * 0.2f)
                (abs(delta.x) <= touchHalf && abs(delta.y) <= touchHalf) || delta.getDistance() <= visibleRadius
            }
            onMain || onItem
        }
    })
    val currentOutsideStart by rememberUpdatedState<(Offset) -> Boolean>({ windowPoint ->
        expanded && !dragging && containerCoordinates?.isAttached == true && !currentContainsControls(windowPoint)
    })
    DisposableEffect(outsideTapBridge) {
        val handler = FloatingNavOutsideTapHandler(
            containsControls = { currentContainsControls(it) },
            startsOutside = { currentOutsideStart(it) },
            dismiss = { currentDismiss() },
        )
        outsideTapBridge?.handler = handler
        onDispose {
            if (outsideTapBridge?.handler === handler) outsideTapBridge.handler = null
        }
    }
    Box(
        modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            .onSizeChanged { containerSize = it }
            .onGloballyPositioned { containerCoordinates = it },
    ) {
        if (fanVisible) items.forEachIndexed { index, item ->
            val motion = motions[index]
            motion.maximumScale = { scaleLimit(index) }
            LaunchedEffect(expanded) {
                delay(if (expanded) index * 26L else (items.lastIndex - index) * 14L)
                motion.expansion.animateTo(
                    if (expanded) 1f else 0f,
                    spring(
                        if (expanded) {
                            (Spring.DampingRatioMediumBouncy + Spring.DampingRatioLowBouncy) / 2f
                        } else Spring.DampingRatioLowBouncy,
                        Spring.StiffnessMediumLow,
                    ),
                )
            }
            val sizeDp = with(density) { itemSizePx().toDp() }
            val touchSize = maxOf(sizeDp, 48.dp)
            FloatingNavTouchTarget(
                container = { containerCoordinates }, containerSize = containerSize,
                enabled = expanded, description = item.label,
                selectedItem = index == selectedIndex, actions = actions(index),
                activate = { finishSelection(index, keepExpanded = true) },
                modifier = Modifier.offset {
                    val center = itemCenter(index)
                    val half = with(density) { touchSize.toPx() } / 2f
                    IntOffset((center.x - half).roundToInt(), (center.y - half).roundToInt())
                }.size(touchSize).zIndex(if (armedIndex == index || committedIndex == index) 2f else 1f),
            ) {
                FloatingNavOrb(
                    motion, backdrop, primaryColor,
                    if (index == selectedIndex) primaryColor else if (dark) FloatingNavGlassUnselectedDark else GlassUnselected,
                    dark, Modifier.size(sizeDp), committed = committedIndex == index,
                ) { tint -> FloatingNavIcon(item.iconRes, tint, Modifier.size(28.dp)) }
            }
        }
        FloatingNavTouchTarget(
            container = { containerCoordinates }, containerSize = containerSize,
            enabled = true, description = mainDescription, selectedItem = null,
            actions = actions(-1), activate = { toggle() },
            modifier = Modifier.offset {
                val point = position()
                IntOffset(point.x.roundToInt(), point.y.roundToInt())
            }.size(FloatingNavMainSize).zIndex(3f),
        ) {
            FloatingNavOrb(
                mainMotion, backdrop, primaryColor, primaryColor, dark, Modifier.fillMaxSize(),
                main = true, pressed = mainPressed, dragging = dragging,
            ) { tint ->
                val iconRes = items[selectedIndex.coerceIn(items.indices)].iconRes
                FloatingNavIcon(
                    iconRes, tint, Modifier.size(28.dp).graphicsLayer {
                        alpha = (1f - mainProgress).coerceIn(0f, 1f)
                        rotationZ = -30f * mainProgress
                    },
                )
                FloatingNavIcon(
                    iconRes, tint, Modifier.size(28.dp).graphicsLayer {
                        alpha = mainProgress.coerceIn(0f, 1f)
                        rotationZ = -90f * (1f - mainProgress)
                    }, closing = true,
                )
            }
        }
    }
}

/** Input only lives on the orbs; transparent space stays available to the page. */
@Composable
private fun FloatingNavTouchTarget(
    container: () -> LayoutCoordinates?,
    containerSize: IntSize,
    enabled: Boolean,
    description: String,
    selectedItem: Boolean?,
    actions: FloatingNavGestureActions,
    activate: () -> Unit,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentActions by rememberUpdatedState(actions)
    val currentContainer by rememberUpdatedState(container)
    Box(
        modifier.onGloballyPositioned { coordinates = it }.then(
            if (enabled) Modifier.pointerInput(containerSize) {
                detectFloatingNavGesture(
                    toContainer = { point ->
                        val parent = currentContainer()
                        val local = coordinates
                        if (parent?.isAttached == true && local?.isAttached == true) {
                            parent.localPositionOf(local, point)
                        } else Offset.Zero
                    },
                    onDown = { currentActions.down(it) },
                    onMove = { point, moved -> currentActions.move(point, moved) },
                    onHold = { currentActions.hold() },
                    onUp = { point, moved, held -> currentActions.up(point, moved, held) },
                    onCancel = { currentActions.cancel() },
                )
            } else Modifier,
        ).onKeyEvent { event ->
            val activationKey = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                event.key == Key.DirectionCenter || event.key == Key.Spacebar
            if (enabled && activationKey) {
                if (event.type == KeyEventType.KeyUp) activate()
                true
            } else false
        }.focusable(enabled).then(
            if (enabled) Modifier.semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (selectedItem != null) selected = selectedItem
                onClick { activate(); true }
            } else Modifier.clearAndSetSemantics { },
        ),
        contentAlignment = Alignment.Center, content = content,
    )
}

/** Endpoint spacing gives compact targets room to enlarge; one target stays centred in the arc. */
internal fun floatingNavArcOffset(
    index: Int, count: Int, radiusPx: Float, progress: Float, startAngleDeg: Float = 90f,
): Offset {
    if (count <= 0) return Offset.Zero
    val angle = startAngleDeg + if (count > 1) index.coerceIn(0, count - 1) * 90f / (count - 1) else 45f
    val radians = Math.toRadians(angle.toDouble())
    val distance = radiusPx * progress
    if (distance == 0f) return Offset.Zero
    return Offset((cos(radians) * distance).toFloat(), (-sin(radians) * distance).toFloat())
}

internal fun floatingNavArcStartAngle(centerX: Float, centerY: Float, containerWidth: Int, containerHeight: Int): Float {
    val rightHalf = centerX >= containerWidth / 2f
    val bottomHalf = centerY >= containerHeight / 2f
    return when {
        rightHalf && bottomHalf -> 90f
        rightHalf -> 180f
        bottomHalf -> 0f
        else -> -90f
    }
}

internal fun floatingNavPositionPx(
    normalized: Pair<Float, Float>, containerWidth: Int, containerHeight: Int, buttonSizePx: Float, marginPx: Float,
): Offset {
    val freeX = (containerWidth - buttonSizePx - 2f * marginPx).coerceAtLeast(0f)
    val freeY = (containerHeight - buttonSizePx - 2f * marginPx).coerceAtLeast(0f)
    return Offset(marginPx + normalized.first.coerceIn(0f, 1f) * freeX, marginPx + normalized.second.coerceIn(0f, 1f) * freeY)
}

internal fun clampFloatingNavPosition(
    position: Offset, containerWidth: Int, containerHeight: Int, buttonSizePx: Float, marginPx: Float,
): Offset {
    val maxX = (containerWidth - buttonSizePx - marginPx).coerceAtLeast(marginPx)
    val maxY = (containerHeight - buttonSizePx - marginPx).coerceAtLeast(marginPx)
    return Offset(position.x.coerceIn(marginPx, maxX), position.y.coerceIn(marginPx, maxY))
}

internal fun floatingNavNormalizedPosition(
    position: Offset, containerWidth: Int, containerHeight: Int, buttonSizePx: Float, marginPx: Float,
): Pair<Float, Float> {
    val freeX = (containerWidth - buttonSizePx - 2f * marginPx).coerceAtLeast(0f)
    val freeY = (containerHeight - buttonSizePx - 2f * marginPx).coerceAtLeast(0f)
    return (if (freeX > 0f) ((position.x - marginPx) / freeX).coerceIn(0f, 1f) else 1f) to
        (if (freeY > 0f) ((position.y - marginPx) / freeY).coerceIn(0f, 1f) else 1f)
}
