package com.tyranor.next.ui.common.glass

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tyranor.next.theme.AppNavCapsuleShape
import com.tyranor.next.ui.common.LiquidGlassNavItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign

/** 采样副本行的图标缩放：可见行固定 1f，副本行随按压 `1 → iconScaleOnPress`。 */
private val LocalGlassIconScale = staticCompositionLocalOf { { 1f } }

/**
 * 「液态玻璃增强」底部导航栏（本项目独立实现；设计记录见
 * `docs/液态玻璃增强计划方案.md`，参数来源见 [GlassBottomBarSpec] 注释）。
 *
 * 三层采样结构——这是让透镜能折射出**图标**、而不是只放大一块颜色的关键：
 *
 * ```
 * 页面内容 ─────────────────────────────→ pageBackdrop（宿主录制）
 *     │
 *     ├─ A 可见栏：vibrancy → blur → 栏体透镜 → 表面色 → 边缘高光 → 清晰图标
 *     │
 *     ├─ B 采样副本：清除语义 + alpha(0) + layerBackdrop(tabsBackdrop)
 *     │        内部绘制放大并染成强调色的图标
 *     │              └────────────────────→ tabsBackdrop
 *     │
 *     └─ C 移动透镜：采样「页面背景 + 图标副本」的合成背景
 *                   → 局部折射 + 内阴影 + 静止/按压覆盖 + 速度形变
 * ```
 *
 * Modifier 顺序是功能性的，不可调换：副本行必须先 `alpha(0f)` 再 `layerBackdrop`
 * （输出不可见、录制内容是实的），透镜必须晚于副本行绘制（否则会把自己的输出录进采样源）。
 *
 * 交互：按住透镜**立即**进入按压（不等长按、不等 touch slop），左右拖动按槽宽换算目标槽位，
 * 松手吸附并提交；手势被上层接管时取消且不提交。低版本按能力降级（见
 * [GlassBottomBarCapabilities]）；按压体积、图标缩放与整栏缩放都是纯图层变换，各版本都保留。
 *
 * @param backdrop 宿主录制的页面内容采样源（宿主必须用 `Modifier.layerBackdrop` 录制）。
 * @param selectedIndex 当前选中项（受控）。
 * @param colors 主题色契约，见 [rememberGlassBottomBarColors]。
 * @param items 导航项（图标 + 无障碍标签）。
 * @param onItemClick 提交切换：点击与拖动释放都走这里。
 */
@Composable
fun EnhancedLiquidGlassNavigationBar(
    backdrop: Backdrop,
    selectedIndex: Int,
    colors: GlassBottomBarColors,
    items: List<LiquidGlassNavItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    spec: GlassBottomBarSpec = GlassBottomBarSpec.Default,
    capabilities: GlassBottomBarCapabilities = GlassBottomBarCapabilities.current,
) {
    if (items.isEmpty()) return
    val tabs = items.size
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()

    // 宽度：参考单槽宽 × N 居中收窄；窗口放不下时夹取，再窄就直接不渲染（避免留一条残片）
    // 触觉反馈：用 View.performHapticFeedback（无需 VIBRATE 权限，且自动遵循系统「触感反馈」开关）。
    // 兜底：设备没有马达（或查询异常）时**完全不触发**，不做无意义的调用。
    val hapticView = LocalView.current
    val hapticSupported = remember(hapticView) {
        val context = hapticView.context
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)
    }
    val tapTick = remember(hapticView, hapticSupported) {
        if (hapticSupported) {
            { hapticView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        } else {
            {}
        }
    }
    val releaseTick = remember(hapticView, hapticSupported) {
        if (hapticSupported) {
            { hapticView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
        } else {
            {}
        }
    }
    val currentTapTick by rememberUpdatedState(tapTick)

    val configuration = LocalConfiguration.current
    val windowWidthDp = with(density) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }.takeIf { it > 0.dp } ?: configuration.screenWidthDp.dp
    // 平板等宽屏拉伸铺满（与原版液态玻璃导航一致），手机保持参考单槽宽度居中
    val stretchBar = configuration.screenWidthDp >= WideScreenWidthDp
    val barWidth = spec.clampedBarWidth(tabs, windowWidthDp, stretch = stretchBar)
    if (barWidth <= spec.minRenderableBarWidth) return

    val blurEnabled = capabilities.supportsBlur
    val refractionEnabled = capabilities.supportsBlur && capabilities.supportsRefraction

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var barWidthPx by remember { mutableFloatStateOf(0f) }
    val paddingPx = remember(density, spec) { with(density) { spec.barInnerPadding.toPx() } }

    // 整栏横向跟随：拖动距离经 EaseOut 映射后最多 ±spec.panelOffsetMax（参考公式）
    val panelShiftAnimation = remember { Animatable(0f) }
    val panelShift by remember(density, spec) {
        derivedStateOf {
            if (barWidthPx == 0f) {
                0f
            } else {
                val fraction = (panelShiftAnimation.value / barWidthPx).fastCoerceIn(-1f, 1f)
                with(density) {
                    spec.panelOffsetMax.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
    }

    val controller = remember(scope, tabs, density, isLtr, spec) {
        LensMotionController(
            scope = scope,
            initialIndex = selectedIndex.toFloat(),
            indexRange = 0f..(tabs - 1).toFloat(),
            spec = spec,
        )
    }

    // 控制器与手势闭包会被长期持有：只能读快照态或 rememberUpdatedState 的值
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    val currentTabWidthPx by rememberUpdatedState(tabWidthPx)
    val currentBarWidthPx by rememberUpdatedState(barWidthPx)
    val currentIsLtr by rememberUpdatedState(isLtr)
    // 拖动释放已经落到目标槽位；用显式标记判重，避免 settleAt 再补一次按压脉冲
    var indexCommittedByDrag by remember { mutableStateOf<Int?>(null) }

    val commitIndex: (Float) -> Unit = remember(controller, scope, tabs) {
        { target ->
            val index = target.fastRoundToInt().fastCoerceIn(0, tabs - 1)
            // 松手立即反馈：拖动与纯按住一致，不做延迟（延迟会让「按下已选项」显得迟钝）
            releaseTick()
            // 吸附到四舍五入后的槽位，并收起材质；pulse=false 表示不再补一次按压脉冲
            controller.settleAt(index.toFloat(), pulse = false)
            indexCommittedByDrag = index
            if (index != currentSelectedIndex) currentOnItemClick(index)
            scope.launch { panelShiftAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
        }
    }

    // 点击图标：已覆盖的立即反馈；未覆盖的等滑块快滑到位再反馈（更跟手）
    val onTabSelected: (Int) -> Unit = remember(controller, scope, tabs, spec) {
        { index ->
            if (index == currentSelectedIndex) {
                currentTapTick()
            } else {
                currentOnItemClick(index)
                scope.launch {
                    val arrived = withTimeoutOrNull(ArriveTimeoutMillis) {
                        snapshotFlow { controller.index }
                            .first { abs(it - index) < spec.tapArriveThreshold }
                    } != null
                    if (arrived) currentTapTick()
                }
            }
        }
    }

    val dragModifier = remember(controller, commitIndex, scope, paddingPx) {
        Modifier.pointerInput(controller) {
            detectLensPressDrag(
                onPress = { controller.beginPress() },
                onDrag = { position, previous, delta ->
                    val tab = currentTabWidthPx
                    if (tab > 0f) {
                        // 触点换算到栏体坐标系：透镜自身随索引移动，只有仍落在透镜上才继续跟随
                        val index = controller.index
                        val barWidthPx = currentBarWidthPx
                        val base = if (currentIsLtr) {
                            paddingPx + index * tab
                        } else {
                            barWidthPx - paddingPx - tab - index * tab
                        }
                        if (base + position.x in 0f..barWidthPx && base + previous.x in 0f..barWidthPx) {
                            controller.dragBy(delta.x / tab * (if (currentIsLtr) 1f else -1f))
                            scope.launch { panelShiftAnimation.snapTo(panelShiftAnimation.value + delta.x) }
                        }
                    }
                },
                onRelease = { commitIndex(controller.targetIndex) },
                onCancel = {
                    // 取消不提交：回到当前选中项（正式产品语义，见文档 §6 D3）
                    controller.settleAt(currentSelectedIndex.toFloat(), pulse = false)
                    scope.launch { panelShiftAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
            )
        }
    }

    // 选中项变化时把透镜弹到新槽位。首次组合只记录不播放，避免启动时出现一次假按压；
    // 拖动释放已经落到目标槽位时也不再重复播放。
    var lastSyncedIndex by remember(controller) { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedIndex, controller) {
        val previous = lastSyncedIndex
        lastSyncedIndex = selectedIndex
        val committedByDrag = indexCommittedByDrag == selectedIndex
        indexCommittedByDrag = null
        if (previous != null && previous != selectedIndex && !committedByDrag) {
            controller.settleAt(selectedIndex.toFloat())
        }
    }

    val tabsBackdrop = rememberLayerBackdrop()
    // 浅色档用更高的表面不透明度与更强的边缘高光（否则浅底上栏体几乎不可辨，见 D15）
    val surfaceAlpha = if (colors.isDark) spec.surfaceAlpha else spec.surfaceAlphaLight
    val highlightAlpha = if (colors.isDark) spec.barHighlightAlpha else spec.barHighlightAlphaLight
    val containerColor = remember(blurEnabled, colors, spec, surfaceAlpha) {
        if (blurEnabled) colors.surfaceTint.copy(alpha = surfaceAlpha) else colors.fallbackSurface
    }
    // 浅色档补一条暗色发丝描边勾勒胶囊轮廓；深色档 edgeStroke 为透明即不描边
    val edgeStroke: Modifier = remember(colors, spec, density) {
        if (colors.edgeStroke.alpha == 0f) {
            Modifier
        } else {
            val strokeColor = colors.edgeStroke.copy(alpha = spec.edgeStrokeAlpha)
            val strokeWidth = with(density) { spec.edgeStrokeWidth.toPx() }
            Modifier.drawWithContent {
                drawContent()
                drawOutline(
                    outline = AppNavCapsuleShape.createOutline(size, layoutDirection, this),
                    color = strokeColor,
                    style = Stroke(width = strokeWidth),
                )
            }
        }
    }

    // 按压光斑：中心直接取透镜位置（栏体局部坐标，含 4dp 内边距；整栏偏移由图层负责）
    val pressGlow = if (refractionEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember(controller, spec, colors, paddingPx, currentTabWidthPx) {
            PressGlowHighlight(
                progress = { controller.glow },
                center = { size ->
                    val fromStart = paddingPx + (controller.index + 0.5f) * currentTabWidthPx
                    Offset(
                        x = if (isLtr) fromStart else size.width - fromStart,
                        y = size.height / 2f,
                    )
                },
                veilAlpha = spec.pressVeilAlpha,
                glowAlpha = spec.pressGlowAlpha,
                tint = colors.edgeHighlight,
            )
        }
    } else {
        null
    }

    Box(
        modifier = modifier.width(barWidth),
        contentAlignment = Alignment.CenterStart,
    ) {
        // ---- A 可见栏：玻璃栏体 + 清晰图标（图标绘制在栏体之后，不参与模糊）----
        val barEffects: BackdropEffectScope.() -> Unit = remember(blurEnabled, spec) {
            {
                if (blurEnabled) {
                    vibrancy()
                    blur(spec.blurRadius.toPx())
                    lens(spec.barLensRadius.toPx(), spec.barLensRadius.toPx())
                }
            }
        }
        // 边缘高光：参考实现直接使用 Highlight.Default（自带 50% 白），本实现只取其一部分（文档 §6 D12）
        val barHighlight: (() -> Highlight?)? = remember(blurEnabled, highlightAlpha) {
            if (blurEnabled) {
                { Highlight.Default.copy(alpha = highlightAlpha) }
            } else {
                null
            }
        }
        val barSurface: DrawScope.() -> Unit = remember(containerColor) {
            { drawRect(containerColor) }
        }
        // 整栏按压：纯图层缩放，任何 API 版本都保留
        val pressDeltaPx = with(density) { spec.barPressScaleDelta.toPx() }
        val pressDeltaMax = spec.barPressScaleDeltaMax
        val barPressLayer: GraphicsLayerScope.() -> Unit =
            remember(pressDeltaPx, pressDeltaMax, controller) {
                {
                    val width = size.width
                    if (width > 0f) {
                        val extra = (pressDeltaPx / width).fastCoerceIn(0f, pressDeltaMax)
                        val scale = lerp(1f, 1f + extra, controller.pressure)
                        scaleX = scale
                        scaleY = scale
                    }
                }
            }
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .onGloballyPositioned { coords ->
                    barWidthPx = coords.size.width.toFloat()
                    tabWidthPx = (barWidthPx - paddingPx * 2) / tabs
                }
                .graphicsLayer { translationX = panelShift }
                // 悬浮栏整体归导航所有：栏内不属于任何 Tab 的残余手势在此吞掉（Main 阶段，
                // 子项先处理自己的点击），不落到宿主的手势处理上
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
                        }
                    }
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { AppNavCapsuleShape },
                    effects = barEffects,
                    highlight = barHighlight,
                    // **必须显式传 null**：Backdrop 的 shadow 默认值是 Shadow.Default
                    // （24dp 模糊、下移 4dp、黑 10%，且无 API 门槛），漏传就会在栏体周围
                    // 出现一圈暗色轮廓。本项目不使用栏体投影（文档 §6 D11）。
                    shadow = null,
                    layerBlock = barPressLayer,
                    onDrawSurface = barSurface,
                )
                .then(edgeStroke)
                .then(pressGlow?.modifier ?: Modifier)
                .height(spec.barHeight)
                .padding(spec.barInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                VisibleTab(
                    item = item,
                    selected = index == selectedIndex,
                    colors = colors,
                    iconSize = spec.iconSize,
                    onClick = { onTabSelected(index) },
                )
            }
        }

        // ---- B 采样副本：输出不可见（alpha 0），内部内容被录进 tabsBackdrop 供透镜采样 ----
        // 副本行自身的折射强度随按压增长：透镜里的图标会随按压一起被顶起来，
        // 这是复刻观感的组成部分。代价是采样边距随按压变化 → 该离屏图层在按压期间
        // 每帧改变尺寸（文档 §6 D13 记录了这一点与可选的降本方案）。
        val copyEffects: BackdropEffectScope.() -> Unit = remember(blurEnabled, spec, controller) {
            {
                if (blurEnabled) {
                    vibrancy()
                    blur(spec.blurRadius.toPx())
                    val progress = controller.pressure
                    lens(
                        spec.barLensRadius.toPx() * progress,
                        spec.barLensRadius.toPx() * progress,
                    )
                }
            }
        }
        val copyHighlight: (() -> Highlight?)? = remember(blurEnabled, controller) {
            if (blurEnabled) {
                { Highlight.Default.copy(alpha = controller.pressure) }
            } else {
                null
            }
        }
        val iconScale = remember(spec, controller) {
            { lerp(1f, spec.iconScaleOnPress, controller.pressure) }
        }
        CompositionLocalProvider(
            LocalGlassIconScale provides iconScale,
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelShift }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { AppNavCapsuleShape },
                        effects = copyEffects,
                        // 采样副本行同样不画投影（其输出本就不可见，留着只会白建一层离屏层）
                        highlight = copyHighlight,
                        shadow = null,
                        onDrawSurface = barSurface,
                    )
                    .height(spec.lensHeight)
                    .padding(horizontal = spec.barInnerPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    SamplingTab(item = item, colors = colors, iconSize = spec.iconSize)
                }
            }
        }

        // ---- C 移动透镜：采样「页面 + 图标副本」的合成背景做局部折射与体积变化 ----
        if (tabWidthPx > 0f) {
            val lensEffects: BackdropEffectScope.() -> Unit = remember(refractionEnabled, spec, controller) {
                {
                    if (refractionEnabled) {
                        val progress = controller.pressure
                        lens(
                            spec.lensRefractionHeight.toPx() * progress,
                            spec.lensRefractionAmount.toPx() * progress,
                            true,
                        )
                    }
                }
            }
            val lensHighlight: (() -> Highlight?)? = remember(refractionEnabled, controller) {
                if (refractionEnabled) {
                    { Highlight.Default.copy(alpha = controller.pressure) }
                } else {
                    null
                }
            }
            val lensShadow: (() -> Shadow?)? = remember(refractionEnabled, controller) {
                if (refractionEnabled) {
                    { Shadow(alpha = controller.pressure) }
                } else {
                    null
                }
            }
            val lensInnerShadow: (() -> InnerShadow?)? = remember(refractionEnabled, spec, controller) {
                if (refractionEnabled) {
                    {
                        InnerShadow(
                            radius = spec.lensInnerShadowRadius * controller.pressure,
                            alpha = controller.pressure,
                        )
                    }
                } else {
                    null
                }
            }
            // 按压缩放与速度形变都是纯图层变换：各 API 版本都保留（低版本无折射但有体积反馈）
            val lensLayer: GraphicsLayerScope.() -> Unit = remember(spec, controller) {
                {
                    val indexVelocity = controller.velocity / 10f
                    scaleX = controller.scaleX / (1f - (indexVelocity * 0.75f).fastCoerceIn(-0.2f, 0.2f))
                    scaleY = controller.scaleY * (1f - (indexVelocity * 0.25f).fastCoerceIn(-0.2f, 0.2f))
                }
            }
            val lensCover: DrawScope.() -> Unit = remember(refractionEnabled, colors, spec, controller) {
                {
                    if (refractionEnabled) {
                        val progress = controller.pressure
                        drawRect(
                            color = colors.staticLensCover.copy(alpha = spec.staticLensCoverAlpha),
                            alpha = 1f - progress,
                        )
                        drawRect(colors.pressedLensCover.copy(alpha = spec.pressedLensCoverAlpha * progress))
                    } else {
                        // 降级档（API < 33 无折射）：用主题色半透明胶囊保持选中可见性
                        drawRect(colors.lensFallbackTint)
                    }
                }
            }
            // 外层：整槽宽度的**手势热区**（宽屏拉伸后单槽很宽，热区保持整槽才好按）；
            // 内层：视觉透镜，宽度按 spec.lensMaxWidth 封顶并居中，避免变成巨大胶囊
            Box(
                Modifier
                    .padding(horizontal = spec.barInnerPadding)
                    .graphicsLayer {
                        val shift = controller.index * tabWidthPx
                        translationX = if (isLtr) shift + panelShift else -shift + panelShift
                    }
                    .then(dragModifier)
                    .height(spec.lensHeight)
                    .width(with(density) { tabWidthPx.toDp() }),
                contentAlignment = Alignment.Center,
            ) {
                val lensWidth = minOf(with(density) { tabWidthPx.toDp() }, spec.lensMaxWidth)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(lensWidth)
                        .drawBackdrop(
                            backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                            shape = { AppNavCapsuleShape },
                            effects = lensEffects,
                            highlight = lensHighlight,
                            shadow = lensShadow,
                            innerShadow = lensInnerShadow,
                            layerBlock = lensLayer,
                            onDrawSurface = lensCover,
                        ),
                )
            }
        }
    }
}

/** 宽屏判定阈值（dp）：与项目既有 `isWideScreen()` 保持一致，宽屏时底栏拉伸铺满。 */
private const val WideScreenWidthDp = 600

/** 点击后等待滑块到位的上限：超时就不再补反馈（例如用户在滑动途中又点了别处）。 */
private const val ArriveTimeoutMillis = 600L

/** 可见导航项：承担全部语义与点击（Role.Tab + selected），图标保持清晰不参与模糊。 */
@Composable
private fun RowScope.VisibleTab(
    item: LiquidGlassNavItem,
    selected: Boolean,
    colors: GlassBottomBarColors,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .weight(1f)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        TabIcon(
            item = item,
            tint = if (selected) colors.selectedIcon else colors.unselectedIcon,
            iconSize = iconSize,
        )
    }
}

/** 采样副本项：只负责被透镜看到，不声明语义、不接收点击。 */
@Composable
private fun RowScope.SamplingTab(
    item: LiquidGlassNavItem,
    colors: GlassBottomBarColors,
    iconSize: Dp,
) {
    val scale = LocalGlassIconScale.current
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val current = scale()
                scaleX = current
                scaleY = current
            },
        contentAlignment = Alignment.Center,
    ) {
        TabIcon(item = item, tint = colors.lensContentTint, iconSize = iconSize)
    }
}

@Composable
private fun TabIcon(
    item: LiquidGlassNavItem,
    tint: Color,
    iconSize: Dp,
) {
    Image(
        painter = painterResource(item.iconRes),
        contentDescription = item.label,
        modifier = Modifier.size(iconSize),
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
    )
}
