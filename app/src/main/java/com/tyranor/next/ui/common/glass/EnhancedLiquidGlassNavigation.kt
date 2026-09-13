package com.tyranor.next.ui.common.glass

import android.os.Build
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** 副本图标行（透镜采样内容）的按压缩放：可见行固定 1f，副本行 `1 → 1.2`。 */
private val LocalGlassTabScale = staticCompositionLocalOf { { 1f } }

/**
 * 「液态玻璃增强」底部导航栏：Legado 三层采样 + 折射透镜 + 按压拖动手感的高保真复刻。
 *
 * 移植自 legado-with-MD3
 * `app/src/main/java/io/legado/app/ui/widget/components/FloatingBottomBar.kt`
 * （commit fb01a76ebbbca41423e2c4c00080cc0861239fbd）。
 * 上游文件头部另声明：Portions of this file are derived from weishu/KernelSU
 * (https://github.com/tiann/KernelSU), Copyright (C) KernelSU contributors,
 * Licensed under GPL-3.0。该署名按分析报告附录 B 要求一并保留。
 *
 * 渲染链路（报告 §6.1 / §8.4，Modifier 顺序不可调换）：
 * ```
 * 页面内容 ──────────────────────────────→ [backdrop]（宿主录制）
 *      │
 *      ├─ A 可见栏：vibrancy → blur(8dp) → lens(24dp) → 表面色 40% → 高光/阴影 → 清晰图标
 *      │
 *      ├─ B 隐藏副本：alpha(0f) + layerBackdrop(tabsBackdrop)，内部绘制放大/染色的图标
 *      │        └──────────────────────────→ [tabsBackdrop]
 *      │
 *      └─ C 移动透镜：CombinedBackdrop(page, tabs) → lens(10dp×p, 14dp×p, depth)
 *                     + 内阴影 + 静止/按压覆盖 + 速度形变
 * ```
 *
 * 交互（报告 §6.5 / §8.6）：按住透镜**立即**响应（无长按延迟），横向拖动按槽宽换算目标索引，
 * 松手四舍五入吸附并提交；取消回原选中项（与源实现的差异已记录）。
 *
 * 使用前提：仅在用户同时打开「圆角液态玻璃导航」与「液态玻璃增强」时由宿主挂载；
 * 宿主必须把页面内容录制进 [backdrop]（`Modifier.layerBackdrop`）。
 *
 * @param backdrop 宿主录制的页面内容采样源（必须是非空的 LayerBackdrop）。
 * @param selectedIndex 当前选中项索引（受控）。
 * @param colors 主题色契约，见 [rememberGlassBottomBarColors]。
 * @param items 导航项（图标 + 无障碍标签），Tyranor 四项业务保持不变。
 * @param onItemClick 提交切页：拖动释放与点击都走这里。
 * @param onItemReselected 重新选中当前项（透镜释放回原位时回调）。
 */
@Composable
fun EnhancedLiquidGlassNavigationBar(
    backdrop: Backdrop,
    selectedIndex: Int,
    colors: GlassBottomBarColors,
    items: List<LiquidGlassNavItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemReselected: (Int) -> Unit = {},
    spec: GlassBottomBarSpec = GlassBottomBarSpec.Default,
    capabilities: GlassBottomBarCapabilities = GlassBottomBarCapabilities.current,
) {
    if (items.isEmpty()) return
    val tabsCount = items.size
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    // 能力分档（报告 §8.8）：API 31+ 实时模糊；API 33+ 折射与交互高光；更低版本实色降级。
    val isBlurEnabled = capabilities.supportsBlur
    val isRefractionEnabled = capabilities.supportsBlur && capabilities.supportsRefraction

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    // 整栏横向跟随：累计拖动距离经 EaseOut 映射后最多 ±4dp（源公式，报告 §6.4）
    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                with(density) {
                    spec.panelOffsetMax.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
    }

    // canDrag 需要读取控制器自身的最新位置，因此用持有者回填实例（与源实现一致）
    class DampedDragAnimationHolder {
        var instance: DampedDragAnimation? = null
    }
    val holder = remember { DampedDragAnimationHolder() }

    // 拖动释放已经让透镜落到目标槽位；用显式标记（而不是比较异步写入的 targetValue）判重，
    // 否则会与 animateToValue 的协程启动竞争，产生第二次按压脉冲（差异 D9）。
    // 声明在控制器之前：onDragStopped 闭包会写入它。
    var indexCommittedByDrag by remember { mutableStateOf<Int?>(null) }

    // 控制器实例按 tabsCount / 布局方向 / 密度 remember，其回调闭包会被长期持有：
    // 选中项与业务回调必须经 rememberUpdatedState 读取最新值，否则拖动释放会提交到旧索引。
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    val currentOnItemReselected by rememberUpdatedState(onItemReselected)

    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr, spec) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = spec.visibilityThreshold,
            initialScale = 1f,
            pressedScale = spec.pressedScale,
            velocityNormalizationSpan = spec.velocityNormalizationSpan,
            releaseThreshold = spec.releaseThreshold,
            canDrag = { offset ->
                val anim = holder.instance ?: return@DampedDragAnimation true
                // <= 0f：宽度不足（窗口极窄）时 tabWidthPx 可能为负，等值判断会漏放行
                if (tabWidthPx <= 0f) return@DampedDragAnimation false
                val currentValue = anim.value
                val indicatorX = currentValue * tabWidthPx
                val padding = with(density) { spec.barInnerPadding.toPx() }
                val globalTouchX = if (isLtr) {
                    padding + indicatorX + offset.x
                } else {
                    totalWidthPx - padding - tabWidthPx - indicatorX + offset.x
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                animateToValue(targetIndex.toFloat())
                indexCommittedByDrag = targetIndex
                if (targetIndex != currentSelectedIndex) {
                    currentOnItemClick(targetIndex)
                } else {
                    currentOnItemReselected(targetIndex)
                }
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDragCancelled = {
                // 正式产品语义：取消不提交，回到当前选中项（报告 §8.1 / §8.6 第 6 条，记录为差异）
                animateToValue(currentSelectedIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * (if (isLtr) 1f else -1f))
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            },
        ).also { holder.instance = it }
    }

    // 选中项变化时把透镜弹到新槽位；首次组合只记录不播放，避免启动时出现一次“按下再回弹”的假动画。
    var lastAnimatedIndex by remember(dampedDragAnimation) { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedIndex, dampedDragAnimation) {
        val previous = lastAnimatedIndex
        lastAnimatedIndex = selectedIndex
        val committedByDrag = indexCommittedByDrag == selectedIndex
        indexCommittedByDrag = null
        if (previous != null && previous != selectedIndex && !committedByDrag) {
            dampedDragAnimation.animateToValue(selectedIndex.toFloat())
        }
    }

    // 仅 API 33+ 构造 InteractiveHighlight（其构造期创建 android.graphics.RuntimeShader）；
    // 这里保留显式 SDK_INT 判断而不是只依赖能力标记，便于静态检查识别 API 门槛。
    //
    // 实例只按 animationScope remember 一次：其 gestureModifier 内部是 pointerInput(animationScope)，
    // 已启动的手势协程不会因 update() 而重置，若按 tabWidthPx / 控制器重建实例，绘制会读新实例的动画、
    // 手势却继续写旧实例，导致按压高光永久失效。位置所需的可变值统一经 rememberUpdatedState 读取。
    val currentTabWidthPx by rememberUpdatedState(tabWidthPx)
    val currentIsLtr by rememberUpdatedState(isLtr)
    val currentDragAnimation by rememberUpdatedState(dampedDragAnimation)
    val interactiveHighlight =
        if (isRefractionEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            remember(animationScope) {
                InteractiveHighlight(
                    animationScope = animationScope,
                    position = { size, _ ->
                        Offset(
                            if (currentIsLtr) {
                                (currentDragAnimation.value + 0.5f) * currentTabWidthPx + panelOffset
                            } else {
                                size.width - (currentDragAnimation.value + 0.5f) * currentTabWidthPx + panelOffset
                            },
                            size.height / 2f,
                        )
                    },
                )
            }
        } else {
            null
        }

    val tabsBackdrop = rememberLayerBackdrop()
    val containerColor = if (isBlurEnabled) {
        colors.surfaceTint.copy(alpha = spec.surfaceAlpha)
    } else {
        colors.fallbackSurface
    }

    // 栏体宽度（报告 §8.5）：N 个参考单槽（76dp）+ 左右内边距，居中收窄；
    // 窄屏/分屏放不下时按可用窗口宽度收窄，不溢出屏幕（报告 §9 窄屏风险项）。
    val configuration = LocalConfiguration.current
    val windowWidthDp = with(density) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }.takeIf { it > 0.dp } ?: configuration.screenWidthDp.dp
    val maxBarWidth = (windowWidthDp - spec.hostHorizontalPadding * 2).coerceAtLeast(0.dp)
    val naturalBarWidth = spec.naturalBarWidth(tabsCount)
    val barWidth = minOf(naturalBarWidth, maxBarWidth)

    Box(
        modifier = modifier.width(barWidth),
        contentAlignment = Alignment.CenterStart,
    ) {
        // A 可见栏：真实玻璃栏体 + 清晰图标（图标绘制在栏体之后，不被模糊）
        Row(
            Modifier
                .fillMaxWidth()
                // 声明为选择组：TalkBack 才会播报「第 n 项，共 N 项」
                .selectableGroup()
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { spec.barInnerPadding.toPx() * 2 }
                    tabWidthPx = contentWidthPx / tabsCount
                }
                .graphicsLayer { translationX = panelOffset }
                // 整条栏体吞掉落在其上的触摸（源 FloatingBottomBar.kt 同样处理）：
                // 悬浮栏的 4dp 内边距环与两端条带不属于任何导航项，若不拦截，
                // 点按 / 拖动会穿透到底下的页面内容，造成误触与误滚动（报告 §8.6 第 7 条）。
                // 用 pointerInput 而非 clickable：clickable 会发布一个无标签的可点击语义节点，
                // 让 TalkBack 在四个 Tab 之间多出一个空焦点；Main 阶段消费则保证子项先收到事件。
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
                    effects = {
                        if (isBlurEnabled) {
                            vibrancy()
                            blur(spec.blurRadius.toPx())
                            lens(spec.barLensRadius.toPx(), spec.barLensRadius.toPx())
                        }
                    },
                    // 边缘高光降到「微浅」（差异 D12）：源值 50% 白在栏体两端圆弧处过亮。
                    // 能力不足时传 null，避免逐帧录制不可见的高光层。
                    highlight = if (isBlurEnabled) {
                        { Highlight.Default.copy(alpha = spec.barHighlightAlpha) }
                    } else {
                        null
                    },
                    // 栏体不画外部投影（差异 D11）：源实现为浅色 10% / 深色 20% 黑，
                    // 是本项目原液态玻璃栏（等效 8%）的 2.5 倍，在深色渐变背景上会形成
                    // 一圈比栏体更大的暗色圆角轮廓（看起来像底栏下面还压着一层）。
                    // 文字/图标可读性由 40% 表面色 + 高光描边保证，不依赖投影分层。
                    shadow = null,
                    layerBlock = {
                        // size.width 为 0 时 1f + 16dp/0 会得到 ∞，lerp 后是 NaN → 该层被丢弃，
                        // 因此显式跳过退化尺寸。
                        if (isBlurEnabled && size.width > 0f) {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(
                                1f,
                                1f + spec.barPressScaleDelta.toPx() / size.width,
                                progress,
                            )
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(interactiveHighlight?.modifier ?: Modifier)
                .height(spec.barHeight)
                .padding(spec.barInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                GlassVisibleTab(
                    item = item,
                    selected = index == selectedIndex,
                    colors = colors,
                    iconSize = spec.iconSize,
                    onClick = { onItemClick(index) },
                )
            }
        }

        // B 隐藏副本：最终输出不可见（alpha 0），但内部内容录制进 tabsBackdrop 供透镜采样。
        // 顺序不可调换：alpha → layerBackdrop → drawBackdrop → 缩放/染色。
        CompositionLocalProvider(
            LocalGlassTabScale provides {
                if (isBlurEnabled) {
                    lerp(1f, spec.iconScaleOnPress, dampedDragAnimation.pressProgress)
                } else {
                    1f
                }
            },
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { AppNavCapsuleShape },
                        effects = {
                            if (isBlurEnabled) {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(spec.blurRadius.toPx())
                                lens(spec.barLensRadius.toPx() * progress, spec.barLensRadius.toPx() * progress)
                            }
                        },
                        // 能力不足时传 null 而不是 alpha=0 的材质：库只跳过 null，
                        // alpha=0 仍会逐帧录制一层不可见的离屏模糊层（API 31–32 按压期间）。
                        highlight = if (isBlurEnabled) {
                            { Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress) }
                        } else {
                            null
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .then(interactiveHighlight?.modifier ?: Modifier)
                    .height(spec.lensHeight)
                    .padding(horizontal = spec.barInnerPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    GlassCopyTab(item = item, colors = colors, iconSize = spec.iconSize)
                }
            }
        }

        // C 移动透镜：采样「页面 + 图标副本」的合成背景，做局部折射与体积变化
        if (tabWidthPx > 0f) {
            Box(
                Modifier
                    .padding(horizontal = spec.barInnerPadding)
                    .graphicsLayer {
                        val progressOffset = dampedDragAnimation.value * tabWidthPx
                        translationX = if (isLtr) {
                            progressOffset + panelOffset
                        } else {
                            -progressOffset + panelOffset
                        }
                    }
                    .then(interactiveHighlight?.gestureModifier ?: Modifier)
                    .then(dampedDragAnimation.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { AppNavCapsuleShape },
                        effects = {
                            if (isRefractionEnabled) {
                                val progress = dampedDragAnimation.pressProgress
                                lens(
                                    spec.lensRefractionHeight.toPx() * progress,
                                    spec.lensRefractionAmount.toPx() * progress,
                                    true,
                                )
                            }
                        },
                        // 折射不可用时传 null：避免逐帧录制不可见的离屏材质层（见副本行说明）
                        highlight = if (isRefractionEnabled) {
                            { Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress) }
                        } else {
                            null
                        },
                        shadow = if (isRefractionEnabled) {
                            { Shadow(alpha = dampedDragAnimation.pressProgress) }
                        } else {
                            null
                        },
                        innerShadow = if (isRefractionEnabled) {
                            {
                                InnerShadow(
                                    radius = spec.lensInnerShadowRadius * dampedDragAnimation.pressProgress,
                                    alpha = dampedDragAnimation.pressProgress,
                                )
                            }
                        } else {
                            null
                        },
                        layerBlock = {
                            // 缩放不依赖 shader：与源实现一致按「是否有实时模糊」分档，
                            // 使 Android 12/12L（有模糊、无折射）也保留透镜按压体积反馈。
                            if (isBlurEnabled) {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                // 速度形变带符号：左右移动不是对称的 abs(speed) 拉伸（报告 §6.4）
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            }
                        },
                        onDrawSurface = {
                            if (isRefractionEnabled) {
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    color = if (colors.isDark) {
                                        Color.White.copy(alpha = spec.staticLensCoverAlpha)
                                    } else {
                                        Color.Black.copy(alpha = spec.staticLensCoverAlpha)
                                    },
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = spec.pressedLensCoverAlpha * progress))
                            } else {
                                // 降级档（API < 33）：无折射能力，用主题色半透明胶囊保持选中可见性
                                drawRect(colors.lensFallbackTint)
                            }
                        },
                    )
                    .height(spec.lensHeight)
                    .width(with(density) { tabWidthPx.toDp() }),
            )
        }
    }
}

/**
 * 可见导航项：承担全部语义与点击（Role.Tab + selected），图标保持清晰不参与模糊。
 * 副本行与它共用同一份 [LiquidGlassNavItem] 数据，但副本只绘制、清除语义、不参与命中测试。
 */
@Composable
private fun RowScope.GlassVisibleTab(
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
        GlassTabIcon(
            item = item,
            tint = if (selected) colors.selectedIcon else colors.unselectedIcon,
            iconSize = iconSize,
        )
    }
}

/** 隐藏副本导航项：只负责被透镜采样，不声明语义、不接收点击。 */
@Composable
private fun RowScope.GlassCopyTab(
    item: LiquidGlassNavItem,
    colors: GlassBottomBarColors,
    iconSize: Dp,
) {
    val scale = LocalGlassTabScale.current
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val currentScale = scale()
                scaleX = currentScale
                scaleY = currentScale
            },
        contentAlignment = Alignment.Center,
    ) {
        GlassTabIcon(
            item = item,
            tint = colors.lensContentTint,
            iconSize = iconSize,
        )
    }
}

@Composable
private fun GlassTabIcon(
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
