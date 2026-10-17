package com.tyranor.next.ui.common.glass

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorFilter
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

/**
 * 「液态玻璃 · 透镜」底部导航栏（本项目独立实现；设计记录见
 * `docs/液态玻璃增强计划方案.md`，参数来源见 [GlassBottomBarSpec] 注释）。
 *
 * 栏体与移动透镜保留各自的材质采样；图标只有一个可见行、一次绘制。
 * 图标行在材质之后合成，由本文件私有 shader 按实际透镜轮廓同时完成局部着色与折射，
 * 轮廓外保留原色原形，边界处折射位移归零，避免副本错位与延迟变色。
 *
 * 交互与低版本能力降级见 [GlassBottomBarCapabilities]；物理输入仍由最后的覆盖层独占。
 *
 * @param backdrop 宿主录制的页面内容采样源（宿主必须用 `Modifier.layerBackdrop` 录制）。
 * @param selectedIndex 当前选中项（受控）。
 * @param colors 主题色契约，见 [rememberGlassBottomBarColors]。
 * @param items 导航项（图标 + 无障碍标签）。
 * @param onItemClick 提交切换：点击与拖动释放都走这里。
 */
/**
 * 三层 backdrop 共用的胶囊形状提供者。
 *
 * 注意：这只是省掉每次重组新建一个 lambda；库内 `drawBackdrop` 仍会按引用比较并新建它自己的
 * `ShapeProvider`，因此**不能**据此宣称「避免了管线重建」。真正的逐帧重建来自按压期间读取
 * `controller.pressure` 的 `effects`（见文档 D13 / P2，已真机实测未超帧预算）。
 */
private val NavCapsuleShape: () -> Shape = { AppNavCapsuleShape }

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
    // 同一次反馈既服务于物理 DOWN，也服务于无障碍语义激活（TalkBack / 键盘没有物理 DOWN，
    // 但仍应给一次可感知反馈）——两者共用同一个 tick，不另设可调参数。
    val currentPressTick by rememberUpdatedState(pressTick)

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

    // 本透镜对齐酷安 16.6.4 的 7dp / 9.8dp；保留调用方 spec 的相对强度。
    val restRefractionHeight = spec.restRefractionHeight * (7f / 6f)
    val restRefractionAmount = spec.restRefractionAmount * (9.8f / 8f)
    val pressedRefractionHeight = spec.pressedRefractionHeight * 0.7f
    val pressedRefractionAmount = spec.pressedRefractionAmount * (9.8f / 12f)

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
    val panelShiftModifier = remember(density, spec) {
        Modifier.graphicsLayer { translationX = panelShift }
    }
    val barSizeModifier = remember(paddingPx, tabs) {
        Modifier.onGloballyPositioned { coords ->
            val width = coords.size.width.toFloat()
            barWidthPx = width
            tabWidthPx = (width - paddingPx * 2) / tabs
        }
    }

    val controller = remember(scope, tabs, spec) {
        LensMotionController(
            scope = scope,
            initialIndex = selectedIndex.toFloat(),
            indexRange = 0f..(tabs - 1).toFloat(),
            // 长按召唤速度对齐酷安：默认 6000 → 1000，仅作用于本透镜。
            spec = spec.copy(
                pressJumpStiffness = spec.pressJumpStiffness
                    .safeMotionValue(200f, 50_000f, GlassBottomBarSpec.Default.pressJumpStiffness) / 6f,
                pressTrackingStiffness = spec.pressTrackingStiffness
                    .safeMotionValue(100f, 50_000f, GlassBottomBarSpec.Default.pressTrackingStiffness) * (1000f / 2400f),
            ),
        )
    }

    // 先启动按压，再同帧设置移动目标：像酷安一样边移动边放大，不等待到位。
    fun beginMovingPress(target: Float) {
        controller.beginPressAt(controller.index)
        controller.updatePressTarget(target)
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
    val lensMaxWidthPx = with(density) { spec.lensMaxWidth.toPx() }
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
    // 会话状态：本次按下是否已经进入「按下/抓取」（决定了松手走 PR81 的点击路径还是吸附路径）
    var pointerIndex by remember { mutableFloatStateOf(0f) }
    var sessionGrabbed by remember { mutableStateOf(false) }
    var lastPointerX by remember { mutableFloatStateOf(0f) }

    /**
     * 按点是否落在滑块矩形内。
     *
     * 几何判定收口在契约的纯函数 [GlassBottomBarSpec.isInsideLens]（便于 LTR/RTL 与边界单测），
     * 这里只负责把当前布局参数喂进去。
     */
    fun isInsideLens(x: Float): Boolean =
        spec.isInsideLens(
            x = x,
            barWidthPx = currentBarWidthPx,
            paddingPx = paddingPx,
            tabWidthPx = currentTabWidthPx,
            lensIndex = controller.index,
            lensMaxWidthPx = lensMaxWidthPx,
            isLtr = currentIsLtr,
        )

    val gestureModifier = remember(controller, requestSelection, paddingPx, touchSlopPx, spec) {
        Modifier.pointerInput(controller, requestSelection) {
            detectBottomBarPress(
                touchSlopPx = touchSlopPx,
                // 尺寸未就绪时不接管手势，避免用错误的槽宽换算
                isGestureEnabled = { currentTabWidthPx > 0f },
                onDown = { position ->
                    currentPressTick()
                    sessionGrabbed = false
                    lastPointerX = position.x
                    pointerIndex = pointerXToIndex(position.x)
                    if (isInsideLens(position.x)) {
                        // 按在滑块上：立即原地按压（与 PR81 的 beginPress() 一致），无需等待
                        sessionGrabbed = true
                        controller.beginPressAt(controller.index)
                    } else {
                        // 酷安在 DOWN 即开始移动和放大；本透镜不再额外等待抓取延时。
                        sessionGrabbed = true
                        beginMovingPress(pointerIndex)
                    }
                },
                onDrag = { position, dragged ->
                    // F2（评审发现）：未过 slop 的位移一律忽略——否则 1~3px 手指抖动会被判成拖动，
                    // 轻点就会走成抓取路径（滑块飞走、且松手没有点击脉冲）。
                    if (!dragged) return@detectBottomBarPress
                    val deltaX = position.x - lastPointerX
                    lastPointerX = position.x
                    pointerIndex = pointerXToIndex(position.x)
                    if (!sessionGrabbed) {
                        // 一开始拖动就立刻抓取（跟手），并取消「轻点」判定
                        sessionGrabbed = true
                        beginMovingPress(pointerIndex)
                    }
                    controller.updatePressTarget(pointerIndex)
                    // 装饰性整栏跟随：按**指针位移**累加（同步累加不丢同帧多次位移）
                    if (currentTabWidthPx > 0f) {
                        panelRecenterJob?.cancel()
                        panelShiftAccum = (panelShiftAccum + deltaX)
                            .coerceIn(-currentBarWidthPx, currentBarWidthPx)
                    }
                },
                onUp = { position, _ ->
                    pointerIndex = pointerXToIndex(position.x)
                    // 路径分流交给纯函数（见 glassPressRelease）：两条路都必须把压力收回，
                    // 「轻点什么都不做」会让滑块永久停在按下态（本轮回归的成因）。
                    val release = glassPressRelease(
                        grabbed = sessionGrabbed,
                        pointerIndex = pointerIndex,
                        selectedIndex = currentSelectedIndex,
                        tabs = tabs,
                    )
                    applyGlassPressRelease(controller, release)
                    sessionGrabbed = false
                    recenterPanel()
                    requestSelection(release.index)
                },
                onCancel = {
                    if (sessionGrabbed) {
                        controller.cancelPress(currentSelectedIndex.toFloat())
                    }
                    sessionGrabbed = false
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
            requested -> Unit        // 由本次手势驱动，脉冲已在 endPress 里给过
            // 外部（非本组件手势）驱动的选中变化：与 PR 81 最新版一致，走带脉冲的 settleAt ——
            // 滑块飞到新槽位的过程中就挤压变大，到位后收回
            else -> controller.settleAt(selectedIndex.toFloat())
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
    // **浅色档专属**色散增强：浅底对比低，折射量需要放大才有可见的彩色分离；深色档恒为 1
    val dispersionBoost =
        if (isDark) 1f else spec.lightDispersionBoost.safeMotionValue(1f, 3f, 1f)
    // surfaceTint 是主题选择后的不透明中性色，alpha 只在 onDrawSurface 应用一次（不叠两层 surface）
    // ---- 主题自适应色彩控制：**一次性构建** ColorFilter 并复用 ----
    // 不能在 effects lambda 里调用 colorControls(...)：Backdrop 1.0.2 的实现每次都会新建
    // 一个 android.graphics.ColorFilter，而 Backdrop 会在读取到「变化的值」时重建
    // RenderEffect 管线——结果是内容滚动时逐帧重建，表现为底栏内部异常闪烁。
    // 这里改为 remember 一个 android.graphics.ColorMatrixColorFilter 实例，管线保持稳定。
    val barColorFilter = remember(barBrightness, barContrast, barSaturation) {
        buildBarColorFilter(barBrightness, barContrast, barSaturation)
    }
    val containerColor = remember(blurEnabled, colors, spec, barSurfaceAlpha) {
        if (blurEnabled) colors.surfaceTint.copy(alpha = barSurfaceAlpha) else colors.fallbackSurface
    }
    // 浅色档补一条暗色发丝描边勾勒胶囊轮廓；深色档 edgeStroke 为透明即不描边。
    // 宽度/不透明度用 theme 里的共享常量，与经典档描边保持同一套数值。
    // 浅色档栏体发丝边：比经典档略强，勾出上下轮廓（深色档仍走 colors.edgeStroke 的规则）
    val enhancedEdgeAlpha = if (isDark) GlassEdgeStrokeAlpha else spec.barEdgeStrokeAlphaLight
    val edgeStroke: Modifier = remember(colors, density, enhancedEdgeAlpha) {
        if (colors.edgeStroke.alpha == 0f) {
            Modifier
        } else {
            val strokeColor = colors.edgeStroke.copy(alpha = enhancedEdgeAlpha)
            val strokeWidth = with(density) { GlassEdgeStrokeWidth.toPx() }
            val stroke = Stroke(width = strokeWidth)
            Modifier.drawWithCache {
                val outline = AppNavCapsuleShape.createOutline(size, layoutDirection, this)
                onDrawWithContent {
                    drawContent()
                    drawOutline(
                        outline = outline,
                        color = strokeColor,
                        style = stroke,
                    )
                }
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
        // ---- A 可见栏体：只绘制玻璃材质，唯一图标行在移动透镜之后合成 ----
        // A/B 栏体材料：轻微模糊 + 轻微遮罩 + **克制的内部折射**（液态玻璃质感）。
        // 折射单独受 refractionEnabled 门控（API 33 以下不得创建 RuntimeShader）。
        val barLensHeightPx = with(density) { spec.barLensHeight.toPx() } * dispersionBoost
        val barLensAmountPx = with(density) { spec.barLensAmount.toPx() } * dispersionBoost
        val barEffects: BackdropEffectScope.() -> Unit =
            remember(blurEnabled, refractionEnabled, spec, barBlurRadius, barColorFilter) {
                {
                    if (blurEnabled) {
                        colorFilter(barColorFilter)
                        blur(barBlurRadius.toPx())
                    }
                    if (refractionEnabled && GlassShaderSupport.allowShaderWork) {
                        // 栏体：折射 + 色散 —— 上下边缘那条**连贯自然**的彩虹带就是这里来的
                        try {
                            lens(
                                refractionHeight = barLensHeightPx,
                                refractionAmount = barLensAmountPx,
                                depthEffect = false,
                                chromaticAberration = spec.barLensChromatic,
                            )
                        } catch (error: Exception) {
                            // 库的 RuntimeShader 就在本调用栈里构造：这里抛出的异常若冒泡到
                            // onAttach/updateEffects，崩的是整个主界面。就地熔断，退化为无折射。
                            GlassShaderSupport.onShaderWorkFailed("bar-lens", error)
                        }
                    }
                }
            }
        // 边缘高光：不使用 Highlight.Default 的满强度（自带 50% 白），只取其中一部分（文档 §6 D12）
        val barHighlight: (() -> Highlight?)? = remember(blurEnabled, highlightAlpha) {
            if (blurEnabled) {
                val highlight = if (highlightAlpha > 0f) {
                    Highlight.Default.copy(alpha = highlightAlpha)
                } else {
                    null
                }
                // 注意这是常量（0.18 / 0.42），只有调用方传入 0 时才会走到 null 分支；
                // 压力相关的那几处才是真正的逐帧判断（Backdrop 只在入参为 null 时早退）
                val provider: () -> Highlight? = {
                    if (highlightAlpha <= 0f || !GlassShaderSupport.highlightAllowed) {
                        null
                    } else {
                        highlight
                    }
                }
                provider
            } else {
                null
            }
        }
        // 栏体内阴影：浅色档给非零 alpha，深色档为 0（不产生多余离屏层开销）
        val barInnerRadius = spec.barInnerShadowRadiusLight
        val barInnerAlpha = if (isDark) 0f else spec.barInnerShadowAlphaLight
        val barInnerShadow: (() -> InnerShadow?)? = remember(barInnerRadius, barInnerAlpha) {
            if (barInnerAlpha <= 0f) {
                null
            } else {
                val innerShadow = InnerShadow(radius = barInnerRadius, alpha = barInnerAlpha)
                val provider: () -> InnerShadow? = { innerShadow }
                provider
            }
        }
        val barShadow: () -> Shadow = remember {
            val shadow = Shadow.Default.copy(alpha = 0.8f)
            val provider: () -> Shadow = { shadow }
            provider
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
                .then(barSizeModifier)
                .then(panelShiftModifier)
                // 注意：这里**不再**放任何 pointerInput。
                // 物理指针由 D 层 Overlay 独占（方案 §单一物理输入层）；此前这里的「吞掉全部事件」
                // 节点会把 MOVE 全部消费掉，导致 D 层识别器一移动就判定被接管而中止，
                // 表现为「按下能赴按、但无法拖动跟手」。
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = NavCapsuleShape,
                    effects = barEffects,
                    highlight = barHighlight,
                    // 阴影与经典档（`LiquidGlassNavigationBar` 的 `Shadow.Default.copy(alpha = 0.8f)`，
                    // 等效约 8% 黑）**完全一致**，两档观感统一（§6 D26）。更强的投影方案
                    // 「浅色 10% / 深色 20% 黑、24dp 模糊、下移 4dp」（强 2.5 倍）仍不采用（§6 D11）。
                    shadow = barShadow,
                    // 浅色档：贴着上下边缘的柔和内暗边，形成「玻璃厚度」的观感
                    innerShadow = barInnerShadow,
                    layerBlock = barPressLayer,
                    onDrawSurface = barSurface,
                )
                .then(edgeStroke)
                .then(pressGlow?.modifier ?: Modifier)
                .height(spec.barHeight)
                .padding(spec.barInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标不录入材质采样源。
        }

        // ---- B 材质采样：不包含图标，仅保留透镜原有的背景材料 ----
        // 与 A 层同样做轻微模糊 + 遮罩，但不做色散；图标不进入这里的背景采样。
        // B 的折射量也不随按压变化，采样边距因此固定，离屏层不再逐帧改尺寸（旧 D13 的成本已消）。
        val copyEffects: BackdropEffectScope.() -> Unit =
            remember(blurEnabled, refractionEnabled, spec, barBlurRadius, barColorFilter) {
                {
                    if (blurEnabled) {
                        colorFilter(barColorFilter)
                        blur(barBlurRadius.toPx())
                    }
                    if (refractionEnabled && GlassShaderSupport.allowShaderWork) {
                        // 采样副本：只保留轻微厚度折射，**不做色散**（显式传 false，不跟随
                        // spec.barLensChromatic——那是给 A 层可见栏体的彩虹带用的）
                        try {
                            lens(
                                refractionHeight = barLensHeightPx,
                                refractionAmount = barLensAmountPx,
                                depthEffect = false,
                                chromaticAberration = false,
                            )
                        } catch (error: Exception) {
                            GlassShaderSupport.onShaderWorkFailed("copy-lens", error)
                        }
                    }
                }
            }
        // 采样副本的高光按压力插值（静止也保留一点，保证 C 层静止折射时有合理底衬）
        val copyHighlight: (() -> Highlight?)? = remember(blurEnabled, spec, controller) {
            if (blurEnabled) {
                {
                    if (!GlassShaderSupport.highlightAllowed) {
                        null
                    } else {
                        val p = controller.pressure.coerceIn(0f, 1f)
                        Highlight.Default.copy(
                            alpha = lerp(spec.lensHighlightAlphaRest, spec.lensHighlightAlphaPressed, p),
                        )
                    }
                }
            } else {
                null
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { }
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .then(panelShiftModifier)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = NavCapsuleShape,
                    effects = copyEffects,
                    highlight = copyHighlight,
                    shadow = null,
                    onDrawSurface = barSurface,
                )
                .height(spec.lensHeight)
                .padding(horizontal = spec.barInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) { }

        // ---- C 移动透镜：原有材质折射与体积变化，不再采样另一套图标 ----
        if (tabWidthPx > 0f) {
            // 静止端点 ↔ 按压端点插值：未按压也有真实折射与轻虹彩（方案 §静止与按压光学分离）。
            // 命名参数显式写清 depthEffect / chromaticAberration——旧代码第三个位置参数
            // 写 `true` 实际打开的是 depthEffect 而非色散（Backdrop 1.0.2 签名）。
            val lensEffects: BackdropEffectScope.() -> Unit = remember(refractionEnabled, spec, controller) {
                {
                    if (refractionEnabled && GlassShaderSupport.allowShaderWork) {
                        val p = controller.pressure.coerceIn(0f, 1f)
                        try {
                            lens(
                                refractionHeight = lerp(
                                    restRefractionHeight.toPx(),
                                    pressedRefractionHeight.toPx(),
                                    p,
                                ),
                                refractionAmount = lerp(
                                    restRefractionAmount.toPx(),
                                    pressedRefractionAmount.toPx(),
                                    p,
                                ),
                                depthEffect = false,
                                // 滑块压到图标时给一圈彩边（用户要求的「覆盖图标时的彩边」）；
                                // 与 A 层栏体上下边缘的彩虹带分工不同，各自只做一次
                                chromaticAberration = spec.movingLensChromatic,
                            )
                        } catch (error: Exception) {
                            GlassShaderSupport.onShaderWorkFailed("lens-lens", error)
                        }
                    }
                }
            }
            // 静止也保留低强度高光；返回 null 的条件是「折射能力不可用」或「本机高光不可用/已熔断」
            val lensHighlight: (() -> Highlight?)? = remember(refractionEnabled, spec, controller) {
                if (refractionEnabled) {
                    {
                        if (!GlassShaderSupport.highlightAllowed) {
                            null
                        } else {
                            val p = controller.pressure.coerceIn(0f, 1f)
                            Highlight.Default.copy(
                                alpha = lerp(spec.lensHighlightAlphaRest, spec.lensHighlightAlphaPressed, p),
                            )
                        }
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
                            radius = lerp(
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
            // 透镜图层：**只做手指按压放大**，不做任何速度拉伸
            // （用户要求：无论点击哪个图标，形变效果都要一致，与 PR81 相同）
            val lensLayer: GraphicsLayerScope.() -> Unit = remember(controller) {
                {
                    scaleX = controller.scaleX
                    scaleY = controller.scaleY
                }
            }
            val lensCover: DrawScope.() -> Unit = remember(refractionEnabled, colors, spec, controller) {
                val staticCover = colors.staticLensCover.copy(alpha = spec.staticLensCoverAlpha)
                val drawCover: DrawScope.() -> Unit = {
                    if (refractionEnabled) {
                        val progress = controller.pressure.coerceIn(0f, 1f)
                        drawRect(
                            color = staticCover,
                            alpha = 1f - progress,
                        )
                        drawRect(colors.pressedLensCover.copy(alpha = spec.pressedLensCoverAlpha * progress))
                    } else {
                        // 降级档（API < 33 无折射）：用主题色半透明胶囊保持选中可见性
                        drawRect(colors.lensFallbackTint)
                    }
                }
                drawCover
            }
            val lensPositionLayer: GraphicsLayerScope.() -> Unit = remember(controller, density, spec) {
                {
                    val shift = controller.index * currentTabWidthPx
                    translationX = if (currentIsLtr) shift + panelShift else -shift + panelShift
                }
            }
            // 外层：整槽宽度的**手势热区**（宽屏拉伸后单槽很宽，热区保持整槽才好按）；
            // 内层：视觉透镜，宽度按 spec.lensMaxWidth 封顶并居中，避免变成巨大胶囊
            Box(
                Modifier
                    .padding(horizontal = spec.barInnerPadding)
                    .graphicsLayer(lensPositionLayer)
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
                            shape = NavCapsuleShape,
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

        // 唯一图标行：着色边界用输出坐标，折射采样用同一份图标轮廓。
        // shader 创建失败只在本组件回到普通选中着色，不改其他底栏共用的能力标记。
        val iconShader = remember(refractionEnabled) {
            if (refractionEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    RuntimeShader(LensIconShader)
                } catch (error: Exception) {
                    Log.w("GlassLens", "Icon refraction unavailable for this navigation bar", error)
                    null
                }
            } else {
                null
            }
        }
        val iconLayer: GraphicsLayerScope.() -> Unit = remember(
            iconShader, controller, density, spec, colors, pressDeltaPx, pressDeltaMax,
            paddingPx, tabs, lensMaxWidthPx,
        ) {
            val effectCache = iconShader?.let { shader ->
                LensIconEffectCache(
                    shader = shader,
                    lensHeightPx = with(density) { spec.lensHeight.roundToPx().toFloat() },
                    paddingPx = paddingPx,
                    tabs = tabs.toFloat(),
                    seamWidthPx = with(density) { 1.5.dp.toPx() },
                    dispersion = if (spec.movingLensChromatic) 1f else 0f,
                    baseTint = colors.unselectedIcon.toArgb(),
                    lensTint = colors.lensContentTint.toArgb(),
                    fringeSurface = colors.surfaceTint.toArgb(),
                )
            }
            val iconScaleOnPress = spec.iconScaleOnPress
                .safeMotionValue(1f, 3f, GlassBottomBarSpec.Default.iconScaleOnPress)
            val refractionHeightPx = with(density) { pressedRefractionHeight.toPx() }
            val refractionAmountPx = with(density) { pressedRefractionAmount.toPx() }
            val layer: GraphicsLayerScope.() -> Unit = {
                if (effectCache != null && currentTabWidthPx > 0f && size.width > 0f) {
                    val pressure = controller.pressure.coerceIn(0f, 1f)
                    val barScale = lerp(
                        1f,
                        1f + (pressDeltaPx / size.width).fastCoerceIn(0f, pressDeltaMax),
                        controller.pressure,
                    )
                    val fromStart = paddingPx + (controller.index + 0.5f) * currentTabWidthPx
                    val lensCenterX = if (currentIsLtr) fromStart else size.width - fromStart
                    renderEffect = effectCache.effect(
                        centerX = size.width / 2f + (lensCenterX - size.width / 2f) / barScale,
                        centerY = size.height / 2f,
                        lensWidth = minOf(currentTabWidthPx, lensMaxWidthPx).toDp().roundToPx().toFloat(),
                        scaleX = controller.scaleX / barScale,
                        scaleY = controller.scaleY / barScale,
                        tabWidth = currentTabWidthPx,
                        iconScale = lerp(1f, iconScaleOnPress, pressure) / barScale,
                        refractionHeight = refractionHeightPx * pressure,
                        refractionAmount = refractionAmountPx * pressure,
                        forceRefresh = controller.isAnimating ||
                            controller.state == LensInteractionState.PressJump ||
                            controller.state == LensInteractionState.PressedTracking ||
                            sessionGrabbed || panelRecenterJob?.isActive == true,
                    )
                } else {
                    renderEffect = null
                }
            }
            layer
        }
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .then(panelShiftModifier)
                .graphicsLayer(barPressLayer)
                .graphicsLayer(iconLayer)
                .height(spec.barHeight)
                .padding(spec.barInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val onTabClick = remember(index, requestSelection) {
                    {
                        currentPressTick()
                        requestSelection(index)
                    }
                }
                VisibleTab(
                    item = item,
                    selected = index == selectedIndex,
                    tint = when {
                        iconShader != null && tabWidthPx > 0f -> Color.White // 单一轮廓源。
                        index == selectedIndex -> colors.selectedIcon
                        else -> colors.unselectedIcon
                    },
                    iconSize = spec.iconSize,
                    onClick = onTabClick,
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
    tint: Color,
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
            tint = tint,
            iconSize = iconSize,
        )
    }
}

@Composable
private fun TabIcon(
    item: LiquidGlassNavItem,
    tint: Color,
    iconSize: Dp,
) {
    val iconModifier = remember(iconSize) { Modifier.size(iconSize) }
    val tintFilter = remember(tint) { ColorFilter.tint(tint) }
    Image(
        painter = painterResource(item.iconRes),
        contentDescription = item.label,
        modifier = iconModifier,
        contentScale = ContentScale.Fit,
        colorFilter = tintFilter,
    )
}

/**
 * RuntimeShader effect 可能保存创建时的 uniform 快照：动态阶段保持逐次提交与重建。
 * 仅在交互和动画均已闲置、全部 uniform 相同的帧之间复用；静态参数由 remember 的 key 管理。
 */
private class LensIconEffectCache(
    private val shader: RuntimeShader,
    private val lensHeightPx: Float,
    private val paddingPx: Float,
    private val tabs: Float,
    private val seamWidthPx: Float,
    private val dispersion: Float,
    private val baseTint: Int,
    private val lensTint: Int,
    private val fringeSurface: Int,
) {
    private var lastCenterX = Float.NaN
    private var lastCenterY = Float.NaN
    private var lastLensWidth = Float.NaN
    private var lastScaleX = Float.NaN
    private var lastScaleY = Float.NaN
    private var lastTabWidth = Float.NaN
    private var lastIconScale = Float.NaN
    private var lastRefractionHeight = Float.NaN
    private var lastRefractionAmount = Float.NaN
    private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null

    fun effect(
        centerX: Float,
        centerY: Float,
        lensWidth: Float,
        scaleX: Float,
        scaleY: Float,
        tabWidth: Float,
        iconScale: Float,
        refractionHeight: Float,
        refractionAmount: Float,
        forceRefresh: Boolean,
    ): androidx.compose.ui.graphics.RenderEffect {
        val first = cachedEffect == null
        val force = first || forceRefresh
        var changed = force
        if (first) {
            shader.setFloatUniform("seamWidth", seamWidthPx)
            shader.setFloatUniform("dispersion", dispersion)
            shader.setColorUniform("baseTint", baseTint)
            shader.setColorUniform("lensTint", lensTint)
            shader.setColorUniform("fringeSurface", fringeSurface)
        }
        if (force || centerX.hasChangedFrom(lastCenterX) ||
            centerY.hasChangedFrom(lastCenterY)
        ) {
            shader.setFloatUniform("lensCenter", centerX, centerY)
            lastCenterX = centerX
            lastCenterY = centerY
            changed = true
        }
        if (force || lensWidth.hasChangedFrom(lastLensWidth)) {
            shader.setFloatUniform("lensSize", lensWidth, lensHeightPx)
            lastLensWidth = lensWidth
            changed = true
        }
        if (force || scaleX.hasChangedFrom(lastScaleX) ||
            scaleY.hasChangedFrom(lastScaleY)
        ) {
            shader.setFloatUniform("lensScale", scaleX, scaleY)
            lastScaleX = scaleX
            lastScaleY = scaleY
            changed = true
        }
        if (force || tabWidth.hasChangedFrom(lastTabWidth)) {
            shader.setFloatUniform("iconGrid", paddingPx, tabWidth, tabs)
            lastTabWidth = tabWidth
            changed = true
        }
        if (force || iconScale.hasChangedFrom(lastIconScale)) {
            shader.setFloatUniform("iconScale", iconScale)
            lastIconScale = iconScale
            changed = true
        }
        if (force || refractionHeight.hasChangedFrom(lastRefractionHeight)) {
            shader.setFloatUniform("refractionHeight", refractionHeight)
            lastRefractionHeight = refractionHeight
            changed = true
        }
        if (force || refractionAmount.hasChangedFrom(lastRefractionAmount)) {
            shader.setFloatUniform("refractionAmount", refractionAmount)
            lastRefractionAmount = refractionAmount
            changed = true
        }
        if (changed) {
            cachedEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
        return checkNotNull(cachedEffect)
    }

    private fun Float.hasChangedFrom(previous: Float): Boolean =
        toRawBits() != previous.toRawBits()
}

/** 仅供此透镜底栏使用，不改变公共 lens 效果或其他导航样式。 */
private const val LensIconShader = """
uniform shader content;
uniform float2 lensCenter;
uniform float2 lensSize;
uniform float2 lensScale;
uniform float3 iconGrid;
uniform float iconScale;
uniform float seamWidth;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float dispersion;
layout(color) uniform half4 baseTint;
layout(color) uniform half4 lensTint;
layout(color) uniform half4 fringeSurface;

half lensAlpha(float2 coord, float2 shift, float seam) {
    float2 source = coord + shift * lensScale;
    float slot = clamp(floor((source.x - iconGrid.x) / iconGrid.y), 0.0, iconGrid.z - 1.0);
    float2 iconCenter = float2(iconGrid.x + (slot + 0.5) * iconGrid.y, lensCenter.y);
    source = iconCenter + (source - iconCenter) / iconScale;
    // 只过渡同一轮廓的采样坐标，不叠加原形与放大后的另一套图标。
    return content.eval(mix(coord, source, seam)).a;
}

half4 main(float2 coord) {
    float2 halfSize = lensSize * 0.5;
    float radius = min(halfSize.x, halfSize.y);
    float2 p = (coord - lensCenter) / lensScale;
    float2 q = abs(p) - halfSize + radius;
    float2 outside = max(q, float2(0.0));
    float outsideLength = length(outside);
    float sd = outsideLength + min(max(q.x, q.y), 0.0) - radius;
    if (sd >= 0.0) {
        half alpha = content.eval(coord).a * baseTint.a;
        return half4(baseTint.rgb * alpha, alpha);
    }
    float2 normal = outsideLength > 0.0001
        ? outside / outsideLength * sign(p)
        : (q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y)));
    float height = min(max(refractionHeight, 0.001), radius);
    float z = clamp(1.0 + sd / height, 0.0, 1.0);
    // 保留 Backdrop 透镜原有的 circleMap 曲线、折射强度与七波长色散。
    float amount = max(refractionAmount, 0.0) * (1.0 - sqrt(max(1.0 - z * z, 0.0)));
    float2 shift = -normal * amount;
    float2 fringe = shift * dispersion * p.x * p.y / max(halfSize.x * halfSize.y, 0.001);
    float seam = smoothstep(0.0, max(seamWidth, 0.001), -sd);
    if (dispersion <= 0.0) {
        half alpha = lensAlpha(coord, shift, seam) * lensTint.a;
        return half4(lensTint.rgb * alpha, alpha);
    }
    half redSample = lensAlpha(coord, shift + fringe, seam);
    half orange = lensAlpha(coord, shift + fringe * (2.0 / 3.0), seam);
    half yellow = lensAlpha(coord, shift + fringe * (1.0 / 3.0), seam);
    half greenSample = lensAlpha(coord, shift, seam);
    half cyan = lensAlpha(coord, shift - fringe * (1.0 / 3.0), seam);
    half blueSample = lensAlpha(coord, shift - fringe * (2.0 / 3.0), seam);
    half purple = lensAlpha(coord, shift - fringe, seam);
    half red = (redSample + orange + yellow) / 3.5 + purple / 7.0;
    half green = orange / 7.0 + (yellow + greenSample + cyan) / 3.5;
    half blue = (cyan + blueSample + purple) / 3.0;
    half3 mask = half3(red, green, blue);
    half coverage = max(red, max(green, blue));
    // 向材质底色柔化彩边，保留原七波长的色相，避免透明层上的深色硬边。
    half softness = 0.5 * (coverage - min(red, min(green, blue))) / max(coverage, 0.001);
    half3 optical = mix(lensTint.rgb * mask, fringeSurface.rgb * coverage, softness);
    // 同一轮廓内从主题色平滑过渡到彩边，不改变折射坐标与放大强度。
    half chroma = half(1.0 - smoothstep(0.0, height, -sd));
    half3 rgb = mix(lensTint.rgb * coverage, optical, chroma);
    return half4(rgb * lensTint.a, coverage * lensTint.a);
}
"""

/**
 * 构建「亮度 / 对比度 / 饱和度」色彩矩阵滤镜（独立实现）。
 *
 * 饱和度用标准亮度权重组合，对比度以中灰为轴缩放，亮度为线性偏移；
 * 结果一次性构造成 [android.graphics.ColorMatrixColorFilter]，由调用方 remember 复用。
 */
private fun buildBarColorFilter(brightness: Float, contrast: Float, saturation: Float): ColorFilter {
    val b = brightness.safeMotionValue(-1f, 1f, 0f)
    val c = contrast.safeMotionValue(0f, 4f, 1f)
    val s = saturation.safeMotionValue(0f, 4f, 1f)
    val lumR = 0.213f
    val lumG = 0.715f
    val lumB = 0.072f
    val sr = (1f - s) * lumR
    val sg = (1f - s) * lumG
    val sb = (1f - s) * lumB
    // 对比度以 0.5 为轴，亮度为平移；ColorMatrix 的偏移列按 0..255 计
    val offset = ((1f - c) * 0.5f + b) * 255f
    val values = floatArrayOf(
        (sr + s) * c, sg * c, sb * c, 0f, offset,
        sr * c, (sg + s) * c, sb * c, 0f, offset,
        sr * c, sg * c, (sb + s) * c, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    )
    return ColorFilter.colorMatrix(ColorMatrix(values))
}

