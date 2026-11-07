package com.tyranor.next.ui.common.motion

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

// ---------------------------------------------------------------------------------------------
// 模糊
// ---------------------------------------------------------------------------------------------

private val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 超过约 0.6 个 1080p 屏幕面积的内容不做运动模糊。 */
private const val LARGE_BLUR_AREA_PX = 1_200_000f

/**
 * 运动模糊：按 [radius]（px，在图层块里读取，可直接 `dp.toPx()`；不重组）给图层加高斯模糊。
 * 半径 < 0.5px 时不挂 RenderEffect；Android 12 以下没有模糊，只保留其他通道。
 */
fun Modifier.motionBlur(radius: GraphicsLayerScope.() -> Float): Modifier = if (!MotionMode.enhanced) this else graphicsLayer {
    applyMotionBlur(radius)
}

/**
 * 与 [motionBlur] 相同的模糊设置，供 [motionBlur] 与 [motionBlurAlpha] 共用（在图层块内调用）。
 */
private fun GraphicsLayerScope.applyMotionBlur(radius: GraphicsLayerScope.() -> Float) {
    // 大面积内容（整页列表、网格等）不做模糊：整屏高斯模糊在中低端 GPU 上会掉帧，只保留淡入淡出与缩放
    val large = size.width * size.height > LARGE_BLUR_AREA_PX
    val r = if (large) 0f else radius()
    renderEffect = if (blurSupported && r >= 0.5f) BlurEffect(r, r, TileMode.Decal) else null
}

/**
 * 同一图层上同时设置透明度与运动模糊，等价于 `graphicsLayer { alpha = ... }.motionBlur { ... }`，但只建一层图层。
 * 透明度作用在模糊之后的结果上，与原来外层 alpha 包住内层 blur 的顺序一致。
 */
fun Modifier.motionBlurAlpha(alpha: () -> Float, radius: GraphicsLayerScope.() -> Float): Modifier {
    // 与 motionBlur 相同：在构建修饰符时读取动效档
    val blurOn = MotionMode.enhanced
    return graphicsLayer {
        this.alpha = alpha()
        if (blurOn) applyMotionBlur(radius)
    }
}

// ---------------------------------------------------------------------------------------------
// 弹一下
// ---------------------------------------------------------------------------------------------

/**
 * 图标「弹一下」：[trigger] 每变成一个新的、非 null / 非 false 的值，内容先快速压扁再带回弹弹回。
 * 第一次组合不弹。用在点按后的图标、刚被选中的标签图标、切换状态的星标等。
 *
 * @param depth 压下去的程度（0.2 = 缩到 0.8）
 */
fun Modifier.bounceOnChange(trigger: Any?, depth: Float = 0.2f): Modifier =
    if (MotionMode.enhanced) this then BounceElement(trigger, depth) else this

private class BounceElement(val trigger: Any?, val depth: Float) : ModifierNodeElement<BounceNode>() {
    override fun create(): BounceNode = BounceNode(trigger, depth)

    override fun update(node: BounceNode) {
        node.depth = depth
        node.onTrigger(trigger)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "bounceOnChange"
        properties["trigger"] = trigger
    }

    /** 触发值与深度共同决定修饰符身份，参数未变时保留节点及当前动画。 */
    override fun equals(other: Any?): Boolean =
        other is BounceElement && other.trigger == trigger && other.depth == depth

    override fun hashCode(): Int = trigger.hashCode() * 31 + depth.hashCode()
}

private class BounceNode(private var last: Any?, var depth: Float) : Modifier.Node(), LayoutModifierNode {
    private val scale = Animatable(1f)
    private var job: Job? = null

    /** 节点复用时先恢复原始缩放，防止旧列表项的半途形变泄漏到新项。 */
    override fun onAttach() {
        // 被懒列表回收时节点协程会取消；复用后以原始尺寸显示，不继承旧元素的半途形变。
        if (scale.value != 1f) {
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { scale.snapTo(1f) }
        }
    }

    override fun onDetach() {
        job = null
    }

    /** 只响应新且有效的触发值；取消旧任务后从当前缩放继续播放反馈。 */
    fun onTrigger(trigger: Any?) {
        if (trigger == last) return
        last = trigger
        if (trigger == null || trigger == false || !isAttached) return
        job?.cancel()
        job = coroutineScope.launch {
            scale.animateTo(1f - depth, tween(70))
            scale.animateTo(1f, MotionTokens.release(0.0005f))
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val s = scale.value
                scaleX = s
                scaleY = s
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 模糊替换 / 模糊显隐
// ---------------------------------------------------------------------------------------------

/** 内容替换、显隐默认的模糊半径。 */
val ContentBlurRadius: Dp = 8.dp

/** 将显隐进度映射到图层模糊半径，完全可见时移除模糊效果。 */
@Composable
private fun AnimatedVisibilityScope.blurLayer(blur: Dp): Modifier {
    val visible by transition.animateFloat(
        transitionSpec = { MotionTokens.fadeIn(0.001f) },
        label = "contentBlur",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
    return Modifier.motionBlur {
        val v = visible.coerceIn(0f, 1f)
        if (v > 0.98f) 0f else (1f - v) * blur.toPx()
    }
}

/**
 * 通用模糊替换：旧内容变糊、缩小、淡出，新内容从糊里清晰起来；占用尺寸用带回弹的弹簧过渡。
 * 第一次组合没有动画，静止时与直接显示 [content] 相同。
 *
 * 调用方把原来作用在内容上的「布局」修饰符（weight / align / padding 等）挪到 [modifier] 上。
 */
@Composable
fun <S> BlurCrossfade(
    targetState: S,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    blur: Dp = ContentBlurRadius,
    initialScale: Float = 0.92f,
    label: String = "BlurCrossfade",
    contentKey: (S) -> Any? = { it },
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val enhanced = MotionMode.enhanced
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (!enhanced) {
                // 「普通」动效档：瞬时替换（与原版直接切换一致）
                ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
            } else {
                (
                    fadeIn(MotionTokens.fadeIn()) +
                        scaleIn(MotionTokens.content(), initialScale = initialScale)
                    ) togetherWith (
                    fadeOut(MotionTokens.fadeOut()) +
                        scaleOut(MotionTokens.contentExit(), targetScale = initialScale)
                    ) using SizeTransform(clip = false) { _, _ ->
                    MotionTokens.content(IntSize(1, 1))
                }
            }
        },
        contentAlignment = contentAlignment,
        label = label,
        contentKey = contentKey,
    ) { state ->
        Box(blurLayer(blur), propagateMinConstraints = true) {
            content(state)
        }
    }
}

/**
 * 模糊显隐（小块内容：角标、提示文字、行内图标）：从更小、更糊、透明里长出来，消失时原路退回。
 */
@Composable
fun BlurVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    blur: Dp = 10.dp,
    initialScale: Float = 0.8f,
    label: String = "BlurVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val enhanced = MotionMode.enhanced
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (!enhanced) EnterTransition.None else fadeIn(MotionTokens.fadeIn()) +
            scaleIn(MotionTokens.pop(), initialScale = initialScale),
        exit = if (!enhanced) ExitTransition.None else fadeOut(MotionTokens.fadeOut()) +
            scaleOut(MotionTokens.contentExit(), targetScale = initialScale),
        label = label,
    ) {
        Box(blurLayer(blur), propagateMinConstraints = true) {
            content()
        }
    }
}

/**
 * 记住最近一次非 null 的值：给退场动画用——内容的数据源变成 null 时，收起过程中仍显示最后的内容，
 * 而不是收起一个已经空掉的框。
 */
@Composable
fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return value ?: holder[0] as T?
}

/** 竖向展开 / 收起的进出场（列表行、设置项、提示块）：高度弹簧展开 + 淡入 + 轻微下落 + 模糊。 */
fun expandBlurEnter(): EnterTransition =
    expandVertically(MotionTokens.layout(IntSize(1, 1)), expandFrom = Alignment.Top) +
        fadeIn(MotionTokens.fadeIn()) +
        slideInVertically(MotionTokens.layout(IntOffset(1, 1))) { -it / 6 }

/** 与竖向展开配对：向顶部收回高度，同时淡出并轻微上移。 */
fun shrinkBlurExit(): ExitTransition =
    shrinkVertically(MotionTokens.layoutExit(IntSize(1, 1)), shrinkTowards = Alignment.Top) +
        fadeOut(MotionTokens.fadeOut()) +
        slideOutVertically(MotionTokens.layoutExit(IntOffset(1, 1))) { -it / 6 }

/**
 * 竖向展开显隐：替代 `if (visible) { Row(...) }`，出现时高度从 0 弹簧展开、内容从模糊里清晰，
 * 下方内容被平滑推开；消失时原路收回。
 */
@Composable
fun ExpandVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    blur: Dp = 6.dp,
    label: String = "ExpandVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (MotionMode.enhanced) expandBlurEnter() else EnterTransition.None,
        exit = if (MotionMode.enhanced) shrinkBlurExit() else ExitTransition.None,
        label = label,
    ) {
        Box(blurLayer(blur), propagateMinConstraints = true) {
            content()
        }
    }
}

/** 横向展开显隐（行尾按钮、标签内小图标）。 */
@Composable
fun ExpandHorizontallyVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    label: String = "ExpandHorizontallyVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (!MotionMode.enhanced) EnterTransition.None else expandHorizontally(MotionTokens.content(IntSize(1, 1))) +
            fadeIn(MotionTokens.fadeIn()) +
            scaleIn(MotionTokens.pop(), initialScale = 0.6f),
        exit = if (!MotionMode.enhanced) ExitTransition.None else shrinkHorizontally(MotionTokens.layoutExit(IntSize(1, 1))) +
            fadeOut(MotionTokens.fadeOut()) +
            scaleOut(MotionTokens.contentExit(), targetScale = 0.6f),
        label = label,
    ) {
        Box(blurLayer(6.dp), propagateMinConstraints = true) {
            content()
        }
    }
}

/** 尺寸变化用带一点回弹的弹簧过渡（替代瞬间跳变）。 */
fun Modifier.animateContentSizeSpring(): Modifier =
    if (MotionMode.enhanced) animateContentSize(animationSpec = MotionTokens.layout(IntSize(1, 1))) else this

// ---------------------------------------------------------------------------------------------
// 数字滚动
// ---------------------------------------------------------------------------------------------

/**
 * 数字计数器文字：按位从右往左对齐，只有变了的位像滚轮一样转过去（变大新数字从下面上来、变小从上面下来），
 * 滚动中带一点运动模糊；非数字字符模糊替换。
 *
 * 静止时绘制的就是一个与原来完全相同的 [Text]（同样的约束、字距与基线）；只在数值变化后的滚动期间，
 * 才把逐位滚轮叠在它的位置上并隐藏原文字，滚完换回原文字。只用于短的数字串（数量、序号、百分比），
 * 用户正在拖动、逐帧变化的读数（滑条旁的值）不要用。
 */
@Composable
fun RollingNumberText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    val previous = remember { PreviousText(text) }
    val direction = remember(text) {
        val old = previous.value
        previous.value = text
        rollDirection(old, text)
    }
    val enhanced = MotionMode.enhanced
    // 文字变化的同一帧就隐藏新值、显示滚轮层，避免先闪出最终文字。
    var rolling by remember { mutableStateOf(false) }
    var settledText by remember { mutableStateOf(text) }
    val rollingNow = enhanced && (rolling || text != settledText)
    val charTransitions = ArrayList<Transition<Char>>(text.length)
    val contentColor = LocalContentColor.current
    val resolvedColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> contentColor
    }
    // propagateMinConstraints：原文字收到的约束与替换前完全一致；滚轮层按原文字的尺寸叠放，不参与测量
    Box(modifier, propagateMinConstraints = true) {
        Text(
            text = text,
            modifier = Modifier.graphicsLayer { alpha = if (rollingNow) 0f else 1f },
            color = color,
            style = style,
        )
        Row(
            modifier = Modifier
                .matchParentSize()
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .clearAndSetSemantics { }
                .graphicsLayer { alpha = if (rollingNow) 1f else 0f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val length = text.length
            // 以「距离右端的位置」作为 key：个位永远是同一个滚轮
            for (i in 0 until length) {
                val fromRight = length - 1 - i
                key(fromRight) {
                    charTransitions += rollingChar(text[i], direction, resolvedColor, style)
                }
            }
        }
        LaunchedEffect(text, enhanced, charTransitions) {
            if (enhanced) {
                if (text != settledText) rolling = true
                // 等每一位的真实动画（含模糊与尺寸）完成，系统放慢动画或途中改值也不会提前切走。
                snapshotFlow {
                    charTransitions.withIndex().all { (index, transition) ->
                        transition.targetState == text[index] &&
                            transition.currentState == text[index] && !transition.isRunning
                    }
                }.first { it }
            }
            rolling = false
            settledText = text
        }
    }
}

/** 数字按方向逐位滚动，其他字符淡入缩放替换；返回转场供外层等待真实结束。 */
@Composable
private fun rollingChar(ch: Char, direction: Int, color: Color, style: TextStyle): Transition<Char> {
    // AnimatedContent(targetState) 内部也创建同样的 Transition；显式保留它仅用于观察完成状态。
    val transition = updateTransition(targetState = ch, label = "rollingDigit")
    transition.AnimatedContent(
        transitionSpec = {
            val digitChange = initialState.isDigit() && targetState.isDigit()
            if (digitChange) {
                val dir = if (direction >= 0) 1 else -1
                (
                    slideInVertically(MotionTokens.layout(IntOffset(1, 1))) { h -> h * dir } +
                        fadeIn(MotionTokens.fadeIn())
                    ) togetherWith (
                    slideOutVertically(MotionTokens.layout(IntOffset(1, 1))) { h -> -h * dir } +
                        fadeOut(MotionTokens.fadeOut())
                    ) using SizeTransform(clip = false) { _, _ ->
                    MotionTokens.content(IntSize(1, 1))
                }
            } else {
                (fadeIn(MotionTokens.fadeIn()) + scaleIn(MotionTokens.content(), 0.85f)) togetherWith
                    (fadeOut(MotionTokens.fadeOut()) + scaleOut(MotionTokens.contentExit(), 0.85f)) using
                    SizeTransform(clip = false) { _, _ -> MotionTokens.content(IntSize(1, 1)) }
            }
        },
    ) { c ->
        Text(
            text = c.toString(),
            modifier = blurLayer(4.dp),
            color = color,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
    return transition
}

private class PreviousText(var value: String)

/** 新旧文字里的数字比大小：变大 = 1（往上滚），变小 = -1，相等或无法比较 = 1。 */
private fun rollDirection(old: String, new: String): Int {
    val a = old.filter { it.isDigit() }.trimStart('0')
    val b = new.filter { it.isDigit() }.trimStart('0')
    if (a.isEmpty() && b.isEmpty()) return 1
    return when {
        a.length != b.length -> if (b.length > a.length) 1 else -1
        else -> if (b >= a) 1 else -1
    }
}
