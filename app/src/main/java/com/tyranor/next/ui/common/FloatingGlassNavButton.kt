package com.tyranor.next.ui.common

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.tyranor.next.R
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.FloatingNavGlassSurface
import com.tyranor.next.theme.GlassUnselected
import com.tyranor.next.theme.glassEdgeStroke
import com.tyranor.next.ui.common.glass.GlassShaderSupport
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 主按钮直径（与默认悬浮玻璃导航条同高，视觉体量一致）。 */
private val FloatingNavMainSize = 64.dp

/** 弧上单个导航项直径。 */
private val FloatingNavItemSize = 52.dp

/** 弧形展开半径：主按钮中心 → 导航项中心，保证两者边缘留出约 40dp 间隙。 */
private val FloatingNavArcRadius = 104.dp

/** 主按钮内的图标尺寸。 */
private val FloatingNavMainIconSize = 28.dp

/** 弧上导航项的图标尺寸（与经典液态玻璃底栏一致）。 */
private val FloatingNavItemIconSize = 26.dp

/** 位置钳制边距：拖动/恢复时按钮与安全区边缘至少留出的间距。 */
private val FloatingNavEdgeMargin = 16.dp

/** 实底路径（API < 31 无采样）的平台 elevation 阴影（主按钮与弧上项统一档位）。 */
private val FloatingNavShadow = 6.dp

/** 长按进入拖动后，抑制随后的松手 click 时间窗（长按未移动时 clickable 仍会收到 tap）。 */
private const val ClickSuppressWindowMs = 500L

/**
 * 固定液态玻璃材质：纯白玻璃膜 + 中性发丝描边，**不随外观风格（复古/高级玻璃）与外观模式
 * （深/浅色）变化**——本导航档是自带固定观感的独立样式，只保留主题色用于选中态。
 * 表面色取自 `theme/Color.kt` 的 [FloatingNavGlassSurface]，不读取 AppThemeColors 的风格分档。
 */
private val FloatingNavSurfaceColor = FloatingNavGlassSurface

/** 有真采样时的玻璃膜不透明度（主按钮与弧上项共用同一档）。 */
private const val FloatingNavSurfaceAlpha = 0.55f

/** 无实时采样（API < 31）的表面不透明度：接近实底，保证图标可读。 */
private const val FloatingNavSolidSurfaceAlpha = 0.96f

/**
 * 悬浮按钮导航（应用设置「导航栏样式」第 4 档）：
 * 右下角一个圆形液态玻璃按钮，点击后四个导航项沿 1/4 圆弧交错展开
 * （`EaseOutBack` 轻微过冲回弹），再点主按钮 / 选中项 / 返回键收起。
 *
 * 交互：
 * - **长按拖动**可移动按钮位置（展开态禁用拖动，避免与选项点击混淆；松手不吸附）；
 *   位置以归一化坐标持久化（`AppSettingsStore`），换分辨率/横竖屏后按新安全区重新钳制，
 *   设置页提供「重置位置」回右下角。
 * - 弧形展开方向按按钮所在象限自适应，始终朝屏幕内侧展开（见 [floatingNavArcStartAngle]）。
 *
 * 材质为**固定样式**：无论外观风格是默认 / 复古玻璃 / 高级玻璃、外观模式是深色还是浅色，
 * 都是同一份纯白玻璃膜 + 中性发丝描边，不读取 AppThemeColors 的风格分档；仅选中态图标沿用主题色。
 *
 * 降级与性能沿用既有底栏约定：API 31+ 且宿主提供采样层时走 `drawBackdrop`
 * （vibrancy + blur + 库 Highlight，受 [GlassShaderSupport] 门控），主按钮与弧上四项
 * **共用同一份玻璃材质**（同一个 remember 的采样 Modifier + 同档白玻璃膜），否则一律退
 * 半透明实底圆面 + 平台 elevation 阴影；展开进度与拖动位置只在 layout/draw 阶段读取，
 * 动画/拖动期间不触发重组（`dragging` 开关只切一次）。
 *
 * [backdrop] 由宿主在**采样层已挂载**时传入（`MainScreen` 的 `bottomBackdropActive`），
 * 传 null 表示无实时模糊可用，组件自行退实底。宿主应传 `fillMaxSize` 修饰符，
 * 组件在安全区内自行摆放（原先由宿主 `align + padding` 定位的逻辑已收敛进组件）。
 */
@Composable
internal fun FloatingGlassNavButton(
    backdrop: Backdrop?,
    items: List<LiquidGlassNavItem>,
    selectedIndex: Int,
    primaryColor: Color,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val persistedPosition by AppSettingsStore.floatingNavPositionState.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // 拖动期间的像素位置覆盖；松手落盘后由 LaunchedEffect(persistedPosition) 清掉（两者同值，无跳变）
    var dragPx by remember { mutableStateOf<Offset?>(null) }
    // 长按拖动后抑制紧随其后的松手 click（长按未移动时 clickable 仍会收到 tap）
    var suppressClickUntil by remember { mutableLongStateOf(0L) }

    // 返回键仅展开时拦截：未展开时让位给页面自身（如 Web 首页的返回）。
    BackHandler(enabled = expanded) { expanded = false }
    // 选中项变化（含从外部切页）后收起，避免展开态停留在新页面上。
    LaunchedEffect(selectedIndex) { expanded = false }
    // 外部位置变更（设置页重置）后清掉本地拖动覆盖
    LaunchedEffect(persistedPosition) { dragPx = null }

    val backdropAvailable = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val buttonSizePx = with(density) { FloatingNavMainSize.toPx() }
    val marginPx = with(density) { FloatingNavEdgeMargin.toPx() }

    // 当前位置（布局期解析）：拖动覆盖优先，否则由归一化坐标换算；容器尺寸未就绪/变化时自动重钳制
    fun currentPositionPx(): Offset = clampFloatingNavPosition(
        position = dragPx ?: floatingNavPositionPx(
            normalized = persistedPosition,
            containerWidth = containerSize.width,
            containerHeight = containerSize.height,
            buttonSizePx = buttonSizePx,
            marginPx = marginPx,
        ),
        containerWidth = containerSize.width,
        containerHeight = containerSize.height,
        buttonSizePx = buttonSizePx,
        marginPx = marginPx,
    )

    fun persistPosition() {
        val px = dragPx ?: return
        val normalized = floatingNavNormalizedPosition(
            position = px,
            containerWidth = containerSize.width,
            containerHeight = containerSize.height,
            buttonSizePx = buttonSizePx,
            marginPx = marginPx,
        )
        AppSettingsStore.setFloatingNavPosition(context, normalized.first, normalized.second)
    }

    val mainProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = EaseOut),
        label = "floatingNavMain",
    )
    // remember：宿主切页动画期间 MainScreen 每帧重组，内联 drawBackdrop 会因 lambda 每次都是新实例
    // 而逐帧重建 vibrancy/blur 管线；复用同一 Modifier 才能让 Backdrop 跳过重建。
    // 主按钮与弧上四项共用**同一个**采样 Modifier，保证材质背景完全一致。
    val glassSurfaceModifier = remember(backdrop, backdropAvailable, density) {
        if (backdropAvailable) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    blur(with(density) { 12.dp.toPx() })
                },
                // Highlight 与经典底栏同档；AGSL 探测失败（33+）时置 null 兜底不崩。
                highlight = if (GlassShaderSupport.highlightAllowed) {
                    { Highlight.Default.copy(alpha = 0.85f) }
                } else {
                    null
                },
                shadow = { Shadow.Default.copy(alpha = 0.8f) },
                onDrawSurface = { drawCircle(FloatingNavSurfaceColor.copy(alpha = FloatingNavSurfaceAlpha)) },
            )
        } else {
            // 实底路径：平台 elevation 阴影（API 28 以下自动无阴影），固定档不依赖外观风格
            Modifier
                .shadow(FloatingNavShadow, CircleShape, clip = false)
                .clip(CircleShape)
                .background(FloatingNavSurfaceColor.copy(alpha = FloatingNavSolidSurfaceAlpha))
        }
    }
    // 固定浅色玻璃的中性发丝描边（与经典底栏浅色档同款），不随外观模式显隐
    val edgeStroke = Modifier.glassEdgeStroke(CircleShape)
    val mainDescription = stringResource(
        if (expanded) R.string.nav_floating_collapse else R.string.nav_floating_expand,
    )
    val radiusPx = with(density) { FloatingNavArcRadius.toPx() }

    Box(
        modifier = modifier
            // 安全区（状态栏/导航条/刘海）由组件统一避让，拖动范围据此钳制
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onSizeChanged { containerSize = it },
    ) {
        // 可拖动簇：位置在 layout 期解析，拖动只触发重新布局，不逐帧重组
        Box(
            modifier = Modifier
                .offset {
                    val position = currentPositionPx()
                    IntOffset(position.x.roundToInt(), position.y.roundToInt())
                }
                .graphicsLayer {
                    // 拖动中轻微放大，给出「已进入拖动」的即时反馈
                    val scale = if (dragging) 1.06f else 1f
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            // 主按钮：收起态显示当前页图标，展开态交叉淡入旋转 45° 的加号（呈叉形作关闭暗示）。
            val mainInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(FloatingNavMainSize)
                    .then(glassSurfaceModifier)
                    .then(edgeStroke)
                    // 长按拖动（展开态禁用）；松手不吸附，直接停在当前位置
                    .pointerInput(expanded, containerSize) {
                        if (expanded) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                suppressClickUntil = System.currentTimeMillis() + ClickSuppressWindowMs
                                dragging = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val base = currentPositionPx()
                                dragPx = clampFloatingNavPosition(
                                    position = Offset(base.x + dragAmount.x, base.y + dragAmount.y),
                                    containerWidth = containerSize.width,
                                    containerHeight = containerSize.height,
                                    buttonSizePx = buttonSizePx,
                                    marginPx = marginPx,
                                )
                            },
                            onDragEnd = {
                                dragging = false
                                persistPosition()
                            },
                            onDragCancel = { dragging = false },
                        )
                    }
                    .clickable(
                        interactionSource = mainInteraction,
                        indication = null,
                    ) {
                        if (System.currentTimeMillis() >= suppressClickUntil) expanded = !expanded
                    }
                    .semantics { contentDescription = mainDescription },
                contentAlignment = Alignment.Center,
            ) {
                val currentItem = items[selectedIndex.coerceIn(items.indices)]
                Box(Modifier.size(FloatingNavMainIconSize)) {
                    Image(
                        painter = painterResource(currentItem.iconRes),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = (1f - mainProgress).coerceIn(0f, 1f) },
                        colorFilter = ColorFilter.tint(primaryColor),
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_game_add),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = mainProgress.coerceIn(0f, 1f)
                                rotationZ = 45f * mainProgress
                            },
                        colorFilter = ColorFilter.tint(primaryColor),
                    )
                }
            }

            // 弧上导航项：从主按钮中心出发，沿所在象限朝屏幕内侧的 1/4 圆弧交错展开；
            // 收起时逆序错峰收回。
            items.forEachIndexed { index, item ->
                val itemProgress by animateFloatAsState(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = if (expanded) {
                        tween(durationMillis = 280, delayMillis = index * 30, easing = EaseOutBack)
                    } else {
                        tween(
                            durationMillis = 160,
                            delayMillis = (items.lastIndex - index) * 18,
                            easing = EaseIn,
                        )
                    },
                    label = "floatingNavItem$index",
                )
                val selected = index == selectedIndex
                val itemInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(FloatingNavItemSize)
                        // offset 更新布局坐标（命中测试跟随），缩放/透明度走图层，均在 layout/draw 期读进度
                        .offset {
                            val position = currentPositionPx()
                            val startAngle = floatingNavArcStartAngle(
                                centerX = position.x + buttonSizePx / 2f,
                                centerY = position.y + buttonSizePx / 2f,
                                containerWidth = containerSize.width,
                                containerHeight = containerSize.height,
                            )
                            val point = floatingNavArcOffset(index, items.size, radiusPx, itemProgress, startAngle)
                            IntOffset(point.x.roundToInt(), point.y.roundToInt())
                        }
                        .graphicsLayer {
                            val progress = itemProgress.coerceIn(0f, 1f)
                            val scale = 0.45f + 0.55f * progress
                            scaleX = scale
                            scaleY = scale
                            alpha = progress
                        }
                        .then(glassSurfaceModifier)
                        .then(edgeStroke)
                        // 仅在展开态挂点击处理：收起时若保留指针节点，会叠在主按钮上把
                        // 命中测试整个截走（disabled clickable 同样参与命中），主按钮就点不动了。
                        .then(
                            if (expanded) {
                                Modifier.clickable(
                                    interactionSource = itemInteraction,
                                    indication = null,
                                ) {
                                    onItemClick(index)
                                    expanded = false
                                }
                            } else {
                                Modifier
                            },
                        )
                        // 收起时把弧上导航项整体移出无障碍树（视觉已 alpha=0）
                        .then(if (expanded) Modifier else Modifier.clearAndSetSemantics { }),
                    contentAlignment = Alignment.Center,
                ) {
                    NavigationTabIcon(
                        iconRes = item.iconRes,
                        contentDescription = item.label,
                        selected = selected,
                        // 固定样式的未选中灰同样是固定中性常量，不随外观模式/玻璃风格变化
                        unselectedColor = GlassUnselected,
                        selectedColor = primaryColor,
                        size = FloatingNavItemIconSize,
                        animationLabel = "floatingNavIconFill$index",
                    )
                }
            }
        }
    }
}

/**
 * 弧形展开坐标（纯函数，便于单测）：以主按钮中心为圆心，[count] 个导航项在
 * 以 [startAngleDeg] 为起点、+90° 的 1/4 圆弧上均分排布
 * （角度 = startAngle + (index + 0.5) × 90°/count），[progress] 从 0 到 1 表示由圆心滑到弧上。
 * 屏幕坐标 y 向下，故纵向取负值（向上）。默认 90° 起（正上方 → 正左方，即右下角锚点的展开方向）。
 */
internal fun floatingNavArcOffset(
    index: Int,
    count: Int,
    radiusPx: Float,
    progress: Float,
    startAngleDeg: Float = 90f,
): Offset {
    if (count <= 0) return Offset.Zero
    val safeIndex = index.coerceIn(0, count - 1)
    val degrees = startAngleDeg + (safeIndex + 0.5f) * (90f / count)
    val radians = Math.toRadians(degrees.toDouble())
    val distance = radiusPx * progress
    // progress = 0 时直接返回零值：cos/sin 乘 0 会得到 -0.0f，其位模式与 Offset.Zero 不相等
    if (distance == 0f) return Offset.Zero
    return Offset(
        x = (cos(radians) * distance).toFloat(),
        y = (-sin(radians) * distance).toFloat(),
    )
}

/**
 * 弧形展开的起始角度（纯函数，便于单测）：按按钮中心相对容器中心的象限，
 * 让扇形始终朝屏幕内侧展开，避免拖到任意位置后四项飞出屏幕：
 * 右下 → 90°（上→左）、右上 → 180°（左→下）、左下 → 0°（右→上）、左上 → -90°（下→右）。
 */
internal fun floatingNavArcStartAngle(
    centerX: Float,
    centerY: Float,
    containerWidth: Int,
    containerHeight: Int,
): Float {
    val rightHalf = centerX >= containerWidth / 2f
    val bottomHalf = centerY >= containerHeight / 2f
    return when {
        rightHalf && bottomHalf -> 90f
        rightHalf -> 180f
        bottomHalf -> 0f
        else -> -90f
    }
}

/**
 * 归一化位置 → 像素位置（纯函数，便于单测）：[normalized] 为 0..1 的 `(x, y)`，
 * 映射到「安全区 - 按钮 - 两侧边距」的可拖动范围；默认 (1,1) 即右下角。
 * 容器小于按钮 + 边距时退化为左上角边距处，不产生负坐标。
 */
internal fun floatingNavPositionPx(
    normalized: Pair<Float, Float>,
    containerWidth: Int,
    containerHeight: Int,
    buttonSizePx: Float,
    marginPx: Float,
): Offset {
    val freeX = (containerWidth - buttonSizePx - 2f * marginPx).coerceAtLeast(0f)
    val freeY = (containerHeight - buttonSizePx - 2f * marginPx).coerceAtLeast(0f)
    val nx = normalized.first.coerceIn(0f, 1f)
    val ny = normalized.second.coerceIn(0f, 1f)
    return Offset(
        x = marginPx + nx * freeX,
        y = marginPx + ny * freeY,
    )
}

/** 像素位置钳制到可拖动范围（拖动中与容器尺寸变化时使用）。 */
internal fun clampFloatingNavPosition(
    position: Offset,
    containerWidth: Int,
    containerHeight: Int,
    buttonSizePx: Float,
    marginPx: Float,
): Offset {
    val maxX = (containerWidth - buttonSizePx - marginPx).coerceAtLeast(marginPx)
    val maxY = (containerHeight - buttonSizePx - marginPx).coerceAtLeast(marginPx)
    return Offset(
        x = position.x.coerceIn(marginPx, maxX),
        y = position.y.coerceIn(marginPx, maxY),
    )
}

/** 像素位置 → 归一化位置（拖动落盘用），与 [floatingNavPositionPx] 互逆。 */
internal fun floatingNavNormalizedPosition(
    position: Offset,
    containerWidth: Int,
    containerHeight: Int,
    buttonSizePx: Float,
    marginPx: Float,
): Pair<Float, Float> {
    val freeX = (containerWidth - buttonSizePx - 2f * marginPx).coerceAtLeast(0f)
    val freeY = (containerHeight - buttonSizePx - 2f * marginPx).coerceAtLeast(0f)
    val nx = if (freeX > 0f) ((position.x - marginPx) / freeX).coerceIn(0f, 1f) else 1f
    val ny = if (freeY > 0f) ((position.y - marginPx) / freeY).coerceIn(0f, 1f) else 1f
    return nx to ny
}
