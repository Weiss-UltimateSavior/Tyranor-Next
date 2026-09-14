package com.tyranor.next.ui.common.glass

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import androidx.compose.animation.core.animate
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
import androidx.compose.ui.platform.LocalContext
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
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tyranor.next.theme.AppNavCapsuleShape
import com.tyranor.next.theme.GlassEdgeStrokeAlpha
import com.tyranor.next.theme.GlassEdgeStrokeWidth
import com.tyranor.next.ui.common.LiquidGlassNavItem
import com.tyranor.next.ui.common.isWideScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** 采样副本行的图标缩放：可见行固定 1f，副本行随按压 `1 → iconScaleOnPress`。 */
private val LocalGlassIconScale = staticCompositionLocalOf { { 1f } }

/**
 * 「液态玻璃 · 透镜」底部导航栏（本项目独立实现；设计记录见
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

    val context = LocalContext.current
    // 触觉反馈：用 View.performHapticFeedback（无需 VIBRATE 权限，且自动遵循系统「触感反馈」开关）。
    // 兜底：设备没有马达（或查询异常）时**完全不触发**，不做无意义的调用。
    val hapticView = LocalView.current
    val hapticSupported = remember(hapticView) {
        // 整段探测都包在 runCatching 里：没有马达、服务缺失、厂商实现抛异常都按「不支持」处理
        runCatching {
            val context = hapticView.context
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.hasVibrator() == true
        }.getOrDefault(false)
    }
    /**
     * 每次物理会话只在 DOWN 触发一次（方案 §触觉与提交一致性）。
     *
     * 不再有「到位后补震」与「松手重震」两套物理触觉，避免同一次操作震两下或反馈滞后。
     */
    val pressTick = remember(hapticView, hapticSupported) {
        if (hapticSupported) {
            { hapticView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        } else {
            {}
        }
    }
    /** 无障碍语义激活（TalkBack / 键盘）没有物理 DOWN，单独给一次可感知反馈。 */
    val semanticTick = pressTick
    val currentPressTick by rememberUpdatedState(pressTick)
    val currentSemanticTick by rememberUpdatedState(semanticTick)

    // 宽度：参考单槽宽 × N 居中收窄；窗口放不下时夹取，再窄就直接不渲染（避免留一条残片）
    val configuration = LocalConfiguration.current
    val windowWidthDp = with(density) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }.takeIf { it > 0.dp } ?: configuration.screenWidthDp.dp
    // 平板/横屏拉伸铺满（与原版液态玻璃导航一致）：复用项目既有的宽屏判定，
    // 避免这里再维护一套阈值（横屏或宽度 ≥600dp 都算宽屏）
    val stretchBar = isWideScreen()
    val barWidth = spec.clampedBarWidth(tabs, windowWidthDp, stretch = stretchBar)
    // 窄窗判定基于**最终像素**并要求单槽可点：dp 转 px 后可能正好只剩左右内边距，
    // 这时 tabWidthPx = 0（透镜不画但宿主仍留白）。宿主侧的 canRender() 用同一套判定。
    val renderWidthPx = with(density) { barWidth.toPx() }
    val innerPaddingPx = with(density) { spec.barInnerPadding.toPx() }
    val minTabWidthPx = with(density) { spec.minTabWidth.toPx() }
    if (barWidth <= spec.minRenderableBarWidth ||
        !spec.canRenderLens(renderWidthPx, innerPaddingPx, tabs, minTabWidthPx)
    ) {
        return
    }

    // 平台能力是权威：调用方传入的能力标记只能「再降一级」，不能把低版本设备抬进高能力分支
    val platformCaps = GlassBottomBarCapabilities.current
    val blurEnabled = capabilities.supportsBlur && platformCaps.supportsBlur
    val refractionEnabled = capabilities.supportsRefraction && platformCaps.supportsRefraction

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var barWidthPx by remember { mutableFloatStateOf(0f) }
    val paddingPx = remember(density, spec) { with(density) { spec.barInnerPadding.toPx() } }

    // 整栏横向跟随：拖动距离经 EaseOut 映射后最多 ±spec.panelOffsetMax（参考公式）
    // 整栏跟随的位移（同步累加：同一帧多次拖动事件不会互相覆盖）。
    // 刻意不用 Animatable + snapshotFlow：归位动画与在途的 snapTo 会抢同一个 mutator，
    // 可能把归位动画取消掉、跳过收尾清零，留下微量偏移（评审指出的间歇性竞态）。
    var panelShiftAccum by remember { mutableFloatStateOf(0f) }
    var panelRecenterJob by remember { mutableStateOf<Job?>(null) }
    // 归位弹簧参数兜底：NaN 经 animate/spring 写进跟随位移后，之后所有读数都会是 NaN
    val recenterDamping = spec.panelRecenterDamping
        .safeMotionValue(0.05f, 5f, GlassBottomBarSpec.Default.panelRecenterDamping)
    val recenterStiffness = spec.panelRecenterStiffness
        .safeMotionValue(1f, 10_000f, GlassBottomBarSpec.Default.panelRecenterStiffness)
    val recenterThreshold = spec.panelRecenterThreshold
        .safeMotionValue(0.01f, 10f, GlassBottomBarSpec.Default.panelRecenterThreshold)
    // 归位：把累加器弹回 0。取消上一次动画后再启动，避免两段动画互相覆盖。
    val recenterPanel: () -> Unit = remember(scope, recenterDamping, recenterStiffness, recenterThreshold) {
        {
            panelRecenterJob?.cancel()
            val from = panelShiftAccum
            if (from != 0f) {
                panelRecenterJob = scope.launch {
                    animate(
                        initialValue = from,
                        targetValue = 0f,
                        animationSpec = spring(recenterDamping, recenterStiffness, recenterThreshold),
                    ) { value, _ -> panelShiftAccum = value }
                }
            }
        }
    }

    // 整栏跟随幅度：负值/非有限会让平移方向反转或 NaN，这里兜底一次
    val panelOffsetMaxPx = remember(density, spec) {
        with(density) {
            spec.panelOffsetMax
                .takeIf { it.value.isFinite() && it > 0.dp }
                ?.toPx()
                ?: GlassBottomBarSpec.Default.panelOffsetMax.toPx()
        }
    }
    val panelShift by remember(density, spec) {
        derivedStateOf {
            if (barWidthPx == 0f) {
                0f
            } else {
                val fraction = (panelShiftAccum / barWidthPx).fastCoerceIn(-1f, 1f)
                with(density) {
                    panelOffsetMaxPx * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
    }

    val controller = remember(scope, tabs, spec) {
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

    /**
     * 受控选中：本组件只**请求**切页，权威值永远来自宿主的 [selectedIndex]。
     *
     * 方案 §受控选中状态：请求去重；宿主若拒绝或改写请求，透镜最终回到外部权威选中项，
     * 不让内部目标长期冒充已选页面。
     */
    var pendingRequestedIndex by remember { mutableStateOf<Int?>(null) }
    val requestSelection: (Int) -> Unit = remember(tabs) {
        { index ->
            if (index in 0 until tabs) {
                if (pendingRequestedIndex != index) {
                    pendingRequestedIndex = index
                    currentOnItemClick(index)
                }
            }
        }
    }

    /**
     * 指针 x → 连续索引（固定栏体坐标，不把 panelShift 反向写回命中计算，避免反馈环）。
     */
    val pointerXToIndex: (Float) -> Float = remember(paddingPx, spec, tabs) {
        { x ->
            spec.pointerXToIndex(
                x = x,
                barWidthPx = currentBarWidthPx,
                paddingPx = paddingPx,
                tabWidthPx = currentTabWidthPx,
                tabsCount = tabs,
                isLtr = currentIsLtr,
            )
        }
    }

    // 平台触摸 slop：只用于判断「是否算拖动」，不决定是否开始反馈、也不决定是否提交
    val touchSlopPx = remember(context) {
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }

    /**
     * D 层：全栏物理输入（唯一 pointer owner）。
     *
     * - DOWN 同帧：一次短震 + 以绝对目标启动赴按；
     * - MOVE：绝对坐标更新目标（不累加位移），并按位移累加装饰性整栏跟随；
     * - UP：四舍五入最近槽、请求一次选择、吸附；
     * - CANCEL：回权威选中项，不提交、不补震。
     */
    val gestureModifier = remember(controller, requestSelection, paddingPx, touchSlopPx) {
        Modifier.pointerInput(controller, requestSelection) {
            detectBottomBarPress(
                touchSlopPx = touchSlopPx,
                // 尺寸未就绪时不接管手势，避免用错误的槽宽换算
                isGestureEnabled = { currentTabWidthPx > 0f },
                onDown = { position ->
                    currentPressTick()
                    controller.beginPressAt(pointerXToIndex(position.x))
                },
                onDrag = { position ->
                    controller.updatePressTarget(pointerXToIndex(position.x))
                    val tab = currentTabWidthPx
                    if (tab > 0f) {
                        val dx = pointerXToIndex(position.x) - controller.targetIndex
                        // 装饰性整栏跟随：按绝对位置差累加，夹取避免越界拖拽下无限增长
                        panelRecenterJob?.cancel()
                        panelShiftAccum = (panelShiftAccum + dx * tab)
                            .coerceIn(-currentBarWidthPx, currentBarWidthPx)
                    }
                },
                onUp = { position ->
                    val index = pointerXToIndex(position.x).fastRoundToInt()
                        .fastCoerceIn(0, tabs - 1)
                    controller.endPress(index)
                    recenterPanel()
                    requestSelection(index)
                },
                onCancel = {
                    controller.cancelPress(currentSelectedIndex.toFloat())
                    recenterPanel()
                },
            )
        }
    }

    // 外部权威选中项变化：同步控制器；若变化正是本次手势请求的结果，则不再补一次按压脉冲
    var lastSyncedIndex by remember(controller) { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedIndex, controller) {
        val previous = lastSyncedIndex
        lastSyncedIndex = selectedIndex
        val requested = pendingRequestedIndex == selectedIndex
        pendingRequestedIndex = null
        when {
            previous == null -> Unit // 首次组合只记录，避免启动时一次假按压
            requested -> Unit        // 由本次手势驱动，透镜已在目标上
            else -> controller.syncSelection(selectedIndex.toFloat())
        }
    }

    val tabsBackdrop = rememberLayerBackdrop()
    // ---- 栏体通透材料（方案 §栏体参数）：浅色高透乳白 / 深色烟黑，成对切换 ----
    val isDark = colors.isDark
    val barBlurRadius = if (isDark) spec.barBlurRadiusDark else spec.barBlurRadiusLight
    val barSurfaceAlpha = if (isDark) spec.barSurfaceAlphaDark else spec.barSurfaceAlphaLight
    val barBrightness = if (isDark) spec.barBrightnessDark else spec.barBrightnessLight
    val barContrast = if (isDark) spec.barContrastDark else spec.barContrastLight
    val barSaturation = if (isDark) spec.barSaturationDark else spec.barSaturationLight
    val highlightAlpha = if (isDark) spec.barHighlightAlphaDark else spec.barHighlightAlphaLight
    // surfaceTint 是主题选择后的不透明中性色，alpha 只在 onDrawSurface 应用一次（不叠两层 surface）
    val containerColor = remember(blurEnabled, colors, spec, barSurfaceAlpha) {
        if (blurEnabled) colors.surfaceTint.copy(alpha = barSurfaceAlpha) else colors.fallbackSurface
    }
    // 浅色档补一条暗色发丝描边勾勒胶囊轮廓；深色档 edgeStroke 为透明即不描边。
    // 宽度/不透明度用 theme 里的共享常量，与经典档描边保持同一套数值。
    val edgeStroke: Modifier = remember(colors, density) {
        if (colors.edgeStroke.alpha == 0f) {
            Modifier
        } else {
            val strokeColor = colors.edgeStroke.copy(alpha = GlassEdgeStrokeAlpha)
            val strokeWidth = with(density) { GlassEdgeStrokeWidth.toPx() }
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
        // key 不含宽度：中心点在绘制期读取 updated-state，首帧/旋转无需重建 shader
        remember(controller, spec, colors, paddingPx) {
            PressGlowHighlight(
                progress = { controller.glow },
                center = { size ->
                    val fromStart = paddingPx + (controller.index + 0.5f) * currentTabWidthPx
                    Offset(
                        x = if (currentIsLtr) fromStart else size.width - fromStart,
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
        // A/B 栏体材料：colorControls 分主题 + 主题 blur；**默认不做整栏 lens**
        // （整栏 24dp 透镜会造成横向涂抹，折射只交给 C 层移动透镜，见方案 §移除 B 层重复折射）
        val barEffects: BackdropEffectScope.() -> Unit =
            remember(blurEnabled, spec, barBlurRadius, barBrightness, barContrast, barSaturation) {
                {
                    if (blurEnabled) {
                        colorControls(barBrightness, barContrast, barSaturation)
                        blur(barBlurRadius.toPx())
                    }
                }
            }
        // 边缘高光：参考实现直接使用 Highlight.Default（自带 50% 白），本实现只取其一部分（文档 §6 D12）
        val barHighlight: (() -> Highlight?)? = remember(blurEnabled, highlightAlpha) {
            if (blurEnabled) {
                // 注意这是常量（0.18 / 0.42），只有调用方传入 0 时才会走到 null 分支；
                // 压力相关的那几处才是真正的逐帧判断（Backdrop 只在入参为 null 时早退）
                { if (highlightAlpha <= 0f) null else Highlight.Default.copy(alpha = highlightAlpha) }
            } else {
                null
            }
        }
        val barSurface: DrawScope.() -> Unit = remember(containerColor) {
            { drawRect(containerColor) }
        }
        // 整栏按压：纯图层缩放，任何 API 版本都保留
        // 负的按压增量会让整栏 scale 变成 0（整栏消失）；上限夹到 0.5 免得窗口极窄时被放大到畸形
        val pressDeltaPx = with(density) {
            spec.barPressScaleDelta
                .takeIf { it.value.isFinite() && it > 0.dp }
                ?.toPx()
                ?: GlassBottomBarSpec.Default.barPressScaleDelta.toPx()
        }
        val pressDeltaMax = spec.barPressScaleDeltaMax
            .safeMotionValue(0f, 0.5f, GlassBottomBarSpec.Default.barPressScaleDeltaMax)
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
                // 注意：这里**不再**放任何 pointerInput。
                // 物理指针由 D 层 Overlay 独占（方案 §单一物理输入层）；此前这里的「吞掉全部事件」
                // 节点会把 MOVE 全部消费掉，导致 D 层识别器一移动就判定被接管而中止，
                // 表现为「按下能赴按、但无法拖动跟手」。
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { AppNavCapsuleShape },
                    effects = barEffects,
                    highlight = barHighlight,
                    // 阴影与经典档（`LiquidGlassNavigationBar` 的 `Shadow.Default.copy(alpha = 0.8f)`，
                    // 等效约 8% 黑）**完全一致**，两档观感统一（§6 D26）。参考实现那套
                    // 「浅色 10% / 深色 20% 黑、24dp 模糊、下移 4dp」强 2.5 倍，仍不采用（§6 D11）。
                    shadow = { Shadow.Default.copy(alpha = 0.8f) },
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
                    // A 层不再承担物理触摸（D 层独占）；这里的 onClick 只由无障碍语义触发
                    onClick = {
                        currentSemanticTick()
                        requestSelection(index)
                    },
                )
            }
        }

        // ---- B 采样副本：输出不可见（alpha 0），内部内容被录进 tabsBackdrop 供透镜采样 ----
        // 与 A 层**同一套材料**（方案 §移除 B 层重复折射）：B 不再做任何 lens，
        // 图标的折射只在 C 层发生一次，避免二次折射把蓝色图标拉出尖刺。
        // 同时这也消除了「采样边距随按压逐帧变化 → 离屏层逐帧改尺寸」的旧成本（旧 D13）。
        val copyEffects: BackdropEffectScope.() -> Unit =
            remember(blurEnabled, spec, barBlurRadius, barBrightness, barContrast, barSaturation) {
                {
                    if (blurEnabled) {
                        colorControls(barBrightness, barContrast, barSaturation)
                        blur(barBlurRadius.toPx())
                    }
                }
            }
        // 采样副本的高光按压力插值（静止也保留一点，保证 C 层静止折射时有合理底衬）
        val copyHighlight: (() -> Highlight?)? = remember(blurEnabled, spec, controller) {
            if (blurEnabled) {
                {
                    val p = controller.pressure.coerceIn(0f, 1f)
                    Highlight.Default.copy(
                        alpha = lerp(spec.lensHighlightAlphaRest, spec.lensHighlightAlphaPressed, p),
                    )
                }
            } else {
                null
            }
        }
        val iconScale = remember(spec, controller) {
            val onPress = spec.iconScaleOnPress
                .safeMotionValue(1f, 3f, GlassBottomBarSpec.Default.iconScaleOnPress)
            val scale = { lerp(1f, onPress, controller.pressure) }
            scale
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
            // 静止端点 ↔ 按压端点插值：未按压也有真实折射与轻虹彩（方案 §静止与按压光学分离）。
            // 命名参数显式写清 depthEffect / chromaticAberration——旧代码第三个位置参数
            // 写 `true` 实际打开的是 depthEffect 而非色散（Backdrop 1.0.2 签名）。
            val lensEffects: BackdropEffectScope.() -> Unit = remember(refractionEnabled, spec, controller) {
                {
                    if (refractionEnabled) {
                        val p = controller.pressure.coerceIn(0f, 1f)
                        lens(
                            refractionHeight = lerp(
                                spec.restRefractionHeight.toPx(),
                                spec.pressedRefractionHeight.toPx(),
                                p,
                            ),
                            refractionAmount = lerp(
                                spec.restRefractionAmount.toPx(),
                                spec.pressedRefractionAmount.toPx(),
                                p,
                            ),
                            depthEffect = false,
                            chromaticAberration = true,
                        )
                    }
                }
            }
            // 静止也保留低强度高光（不再在 MotionVisiblePressure 以下返回 null）
            val lensHighlight: (() -> Highlight?)? = remember(refractionEnabled, spec, controller) {
                if (refractionEnabled) {
                    {
                        val p = controller.pressure.coerceIn(0f, 1f)
                        Highlight.Default.copy(
                            alpha = lerp(spec.lensHighlightAlphaRest, spec.lensHighlightAlphaPressed, p),
                        )
                    }
                } else {
                    null
                }
            }
            val lensShadow: (() -> Shadow?)? = remember(refractionEnabled, spec, controller) {
                if (refractionEnabled) {
                    {
                        val p = controller.pressure.coerceIn(0f, 1f)
                        Shadow(alpha = lerp(spec.lensShadowAlphaRest, spec.lensShadowAlphaPressed, p))
                    }
                } else {
                    null
                }
            }
            val lensInnerShadow: (() -> InnerShadow?)? =
                remember(refractionEnabled, spec, controller) {
                if (refractionEnabled) {
                    {
                        val p = controller.pressure.coerceIn(0f, 1f)
                        InnerShadow(
                            // radius 是 Dp 类型（Backdrop 1.0.2 的内联类）：用 Dp.lerp 插值
                            radius = androidx.compose.ui.unit.lerp(
                                spec.lensInnerShadowRadiusRest,
                                spec.lensInnerShadowRadiusPressed,
                                p,
                            ),
                            alpha = lerp(
                                spec.lensInnerShadowAlphaRest,
                                spec.lensInnerShadowAlphaPressed,
                                p,
                            ),
                        )
                    }
                } else {
                    null
                }
            }
            // 按压缩放与速度形变都是纯图层变换：各 API 版本都保留（低版本无折射但有体积反馈）
            val lensLayer: GraphicsLayerScope.() -> Unit = remember(spec, controller) {
                {
                    // 除数为 0 会得到 NaN/±Inf 并直接写进 graphicsLayer（画面整层异常）；
                    // 夹取上限 0.9 是为了让分母 1−clamp 不落到 0
                    val divisor = spec.velocityScaleDivisor
                        .safeMotionValue(0.01f, 1_000f, GlassBottomBarSpec.Default.velocityScaleDivisor)
                    val wideFactor = spec.velocityWideFactor
                        .safeMotionValue(0f, 1f, GlassBottomBarSpec.Default.velocityWideFactor)
                    val tallFactor = spec.velocityTallFactor
                        .safeMotionValue(0f, 1f, GlassBottomBarSpec.Default.velocityTallFactor)
                    val clamp = spec.velocityClamp
                        .safeMotionValue(0f, 0.9f, GlassBottomBarSpec.Default.velocityClamp)
                    val indexVelocity = controller.effectiveVelocity / divisor
                    scaleX = controller.scaleX /
                        (1f - (indexVelocity * wideFactor).fastCoerceIn(-clamp, clamp))
                    scaleY = controller.scaleY *
                        (1f - (indexVelocity * tallFactor).fastCoerceIn(-clamp, clamp))
                }
            }
            val lensCover: DrawScope.() -> Unit = remember(refractionEnabled, colors, spec, controller) {
                {
                    if (refractionEnabled) {
                        val progress = controller.pressure.coerceIn(0f, 1f)
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

        // ---- D 全栏物理输入层（方案 §单一物理输入层）----
        // 最后绘制 = 命中测试最优先；固定覆盖整栏、**不随**透镜与 panelShift 平移；
        // 不声明任何 semantics（避免遮蔽 A 层 Tab 的无障碍节点），只承担物理指针。
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(gestureModifier),
        )
    }
}

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

/**
 * 「压力小到看不见」的判定阈值。
 *
 * 压力弹簧没有 snap-to-target，帧循环停下时它会停在 `(0, epsilon]` 区间而**不会精确归零**，
 * 用 `<= 0f` 判断会让这些透明高光层在首次按压后一直录制下去。
 */
private const val MotionVisiblePressure = 1e-3f
