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
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tyranor.next.theme.AppNavCapsuleShape
import com.tyranor.next.ui.common.LiquidGlassNavItem
import com.tyranor.next.ui.common.isWideScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    val currentReleaseTick by rememberUpdatedState(releaseTick)

    // 宽度：参考单槽宽 × N 居中收窄；窗口放不下时夹取，再窄就直接不渲染（避免留一条残片）
    val configuration = LocalConfiguration.current
    val windowWidthDp = with(density) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }.takeIf { it > 0.dp } ?: configuration.screenWidthDp.dp
    // 平板/横屏拉伸铺满（与原版液态玻璃导航一致）：复用项目既有的宽屏判定，
    // 避免这里再维护一套阈值（横屏或宽度 ≥600dp 都算宽屏）
    val stretchBar = isWideScreen()
    val barWidth = spec.clampedBarWidth(tabs, windowWidthDp, stretch = stretchBar)
    if (barWidth <= spec.minRenderableBarWidth) return

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
    // 拖动释放已经落到目标槽位；用显式标记判重，避免 settleAt 再补一次按压脉冲
    var indexCommittedByDrag by remember { mutableStateOf<Int?>(null) }

    val commitIndex: (Float) -> Unit = remember(controller, scope, tabs, spec) {
        { target ->
            val index = target.fastRoundToInt().fastCoerceIn(0, tabs - 1)
            // 松手立即反馈：拖动与纯按住一致，不做延迟（延迟会让「按下已选项」显得迟钝）
            currentReleaseTick()
            // 吸附到四舍五入后的槽位，并收起材质；pulse=false 表示不再补一次按压脉冲
            controller.settleAt(index.toFloat(), pulse = false)
            indexCommittedByDrag = index
            if (index != currentSelectedIndex) currentOnItemClick(index)
            recenterPanel()
        }
    }

    // 点击图标：未覆盖的等滑块快滑到位再反馈（更跟手）。命中测试是「最上层兄弟独占」，
    // 覆盖中的那个 Tab 由透镜自己接管（其松手反馈在 commitIndex 里给），这里只处理未被覆盖的
    // 到位等待的会话号：连续点不同图标时，只有最后一次点击的等待有效，
    // 避免旧 waiter 对「滑块只是路过」的槽位误触发反馈
    var tapSession by remember { mutableStateOf(0) }
    val onTabSelected: (Int) -> Unit = remember(controller, scope, tabs, spec) {
        { index ->
            if (index == currentSelectedIndex) {
                currentTapTick()
            } else {
                currentOnItemClick(index)
                val session = ++tapSession
                scope.launch {
                    val arrived = withTimeoutOrNull(ArriveTimeoutMillis) {
                        snapshotFlow { controller.index }
                            .first { abs(it - index) < spec.tapArriveThreshold }
                    } != null
                    if (arrived && session == tapSession) currentTapTick()
                }
            }
        }
    }

    // 平台触摸 slop：拖动识别器用它区分「真拖动」与「点击时的手指抖动」
    val touchSlopPx = remember(context) {
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
    val dragModifier = remember(controller, commitIndex, scope, paddingPx, touchSlopPx) {
        Modifier.pointerInput(controller, commitIndex) {
            detectLensPressDrag(
                touchSlopPx = touchSlopPx,
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
                            // 同步累加（不丢同帧的多次位移），并夹取避免越界拖拽下无限增长
                            panelRecenterJob?.cancel()
                            panelShiftAccum = (panelShiftAccum + delta.x)
                                .coerceIn(-barWidthPx, barWidthPx)
                        }
                    }
                },
                // 只有真的拖动过才提交：纯点击（含滑动途中轻点）不改变选中项，
                // 否则会把正在滑动的透镜截停并提交到它当时路过的槽位。
                // 但**无论是否提交都必须收回按压材质**，否则滑块会永久停在按下状态。
                onRelease = { dragged ->
                    if (dragged) {
                        commitIndex(controller.targetIndex)
                    } else {
                        controller.releasePress()
                        currentReleaseTick()
                    }
                },
                onCancel = {
                    // 取消不提交：回到当前选中项（正式产品语义，见文档 §6 D3）
                    controller.settleAt(currentSelectedIndex.toFloat(), pulse = false)
                    recenterPanel()
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
                // 悬浮栏整体归导航所有（与参考实现一致，属前向兼容）：命中测试默认「最上层兄弟独占」，
                // 下层页面本就收不到栏内的触摸，这里额外吞掉未被任何 Tab 处理的残余事件，
                // 避免将来出现「共享命中」时落到宿主的手势处理上
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
                // 与透镜一致：压力为 0 时返回 null，Backdrop 才会跳过这层透明高光的离屏处理
                {
                    val p = controller.pressure
                    if (p < MotionVisiblePressure) null else Highlight.Default.copy(alpha = p)
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
                    {
                        val p = controller.pressure
                        if (p < MotionVisiblePressure) null else Highlight.Default.copy(alpha = p)
                    }
                } else {
                    null
                }
            }
            val lensShadow: (() -> Shadow?)? = remember(refractionEnabled, controller) {
                if (refractionEnabled) {
                    {
                        val p = controller.pressure
                        if (p < MotionVisiblePressure) null else Shadow(alpha = p)
                    }
                } else {
                    null
                }
            }
            val lensInnerShadow: (() -> InnerShadow?)? = remember(refractionEnabled, spec, controller) {
                if (refractionEnabled) {
                    {
                        val p = controller.pressure
                        if (p < MotionVisiblePressure) {
                            null
                        } else {
                            InnerShadow(
                                radius = spec.lensInnerShadowRadius * p,
                                alpha = p,
                            )
                        }
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
                    val indexVelocity = controller.velocity / divisor
                    scaleX = controller.scaleX /
                        (1f - (indexVelocity * wideFactor).fastCoerceIn(-clamp, clamp))
                    scaleY = controller.scaleY *
                        (1f - (indexVelocity * tallFactor).fastCoerceIn(-clamp, clamp))
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

/**
 * 「压力小到看不见」的判定阈值。
 *
 * 压力弹簧没有 snap-to-target，帧循环停下时它会停在 `(0, epsilon]` 区间而**不会精确归零**，
 * 用 `<= 0f` 判断会让这些透明高光层在首次按压后一直录制下去。
 */
private const val MotionVisiblePressure = 1e-3f
