package com.tyranor.next.ui.common.motion

import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 值 / 读数 / 状态文字的「局部模糊替换」——全应用此类文字的统一做法（新增选项直接用它，或使用已内置它的公共组件：
 * `OverlayDropdownPreference` 的当前值、`ArrowPreference(value = …)`、`SliderValueHeader` 的读数）。
 *
 * - **只替换真正变化的片段**：新旧文字先去掉相同的前缀与后缀，一串数字算一个整体（「3 级」→「4 级」只有「3」→「4」在糊，「级」不动；
 *   「20 dp」→「29 dp」十位个位一起糊，「dp」不动；
 *   「液态玻璃 · 经典」→「液态玻璃 · 透镜」只有后半段在糊），原地替换，不上下滚动。
 * - **永不出现两个清晰的字叠在一起，也没有「先消失、再出现」的空档**：新旧两层共用一个丝滑的进度，同时互补地一边变模糊变淡、
 *   一边变清晰变实（合成透明度始终接近 1，叠在一起的时刻两层都是模糊的）；点选那一帧就开始动；任何时刻都只有这两层，被打断时从当前状态接着走。
 * - **全局变化不动画**：语言切换等造成整屏文字一起变时（[animateOnLocaleChange] = false），直接显示，
 *   只有被用户点选的那个控件（如语言下拉自己的当前值，传 true）才会模糊替换，避免整屏逐字模糊造成卡顿。
 * - 静止时就是一个普通的文字节点（[render] 的一次调用），外观与直接写文字完全一致；「普通」动效档与系统关闭动画时不过渡。
 *
 * 约定：[render] 每次调用只发出一个文字节点；不要对 BlurText 使用 `fillMaxWidth`（按内容宽度，对齐交给父容器）。
 */
@Composable
fun BlurTextWith(
    text: String,
    modifier: Modifier = Modifier,
    animateOnLocaleChange: Boolean = false,
    /**
     * true（默认）：只替换真正变化的片段（相同的前后缀不动）——数值 / 读数用，如「3 级」→「4 级」只有数字在糊；
     * false：整段文字一起模糊替换，即使后半截相同也整个换——选项值用，如「浅色」→「深色」、「液态玻璃 · 经典」→「液态玻璃 · 透镜」。
     */
    partial: Boolean = true,
    render: @Composable (text: String, modifier: Modifier) -> Unit,
) {
    val localeChanged = rememberLocaleJustChanged()
    val canAnimate = MotionMode.enhanced && ValueAnimator.areAnimatorsEnabled()
    val holder = remember { BlurTextHolder(text) }
    if (!canAnimate || (localeChanged && !animateOnLocaleChange)) {
        holder.from = text
        holder.to = text
        render(text, modifier)
        return
    }
    val changed = holder.to != text
    var swapMillis = SWAP_MILLIS
    var startProgress = 0f
    if (changed) {
        // 跟手：值变化得越快，过渡越短；快到手指一滑一串数字时直接跟随显示（不排队、不落后于手指）
        val now = SystemClock.uptimeMillis()
        val interval = now - holder.lastChangeAt
        holder.lastChangeAt = now
        val inFlight = holder.progress?.value ?: 1f
        holder.from = holder.to
        holder.to = text
        when {
            interval < SNAP_INTERVAL_MS -> {
                // 连续快速变化（拖滑条扫过一串数字）：直接显示最新值
                holder.from = text
                holder.progress = null
                render(text, modifier)
                return
            }
            interval < RAPID_INTERVAL_MS -> {
                swapMillis = RAPID_SWAP_MILLIS
                startProgress = carryOverProgress(inFlight)
            }
            else -> if (inFlight < 1f) startProgress = carryOverProgress(inFlight)
        }
    }
    // 文字变化时换一个新的进度（首帧仍显示旧文字，不会闪一下新文字）；首次组合与无变化时为 1
    val progress = remember(text) { Animatable(if (changed) maxOf(startProgress, START_LEAD) else 1f) }
    holder.progress = progress
    LaunchedEffect(progress) {
        if (progress.value < 1f) progress.animateTo(1f, tween(swapMillis, easing = SwapEasing))
    }
    val running by remember(progress) { derivedStateOf { progress.value < 1f } }
    if (!running) {
        render(holder.to, modifier)
        return
    }
    val from = holder.from
    val to = holder.to
    val limit = if (partial) minOf(from.length, to.length) else 0
    var prefix = 0
    while (prefix < limit && from[prefix] == to[prefix]) prefix++
    var suffix = 0
    while (suffix < limit - prefix && from[from.length - 1 - suffix] == to[to.length - 1 - suffix]) suffix++
    if (partial) {
        // 数字是一个整体（「20」→「29」十位个位一起模糊替换，只有「级」「dp」这类单位保持不动）：
        // 变化段如果落在一串数字里，就把整串数字都纳入变化段
        while (prefix > 0 && from[prefix - 1].isDigit() &&
            ((prefix < from.length && from[prefix].isDigit()) || (prefix < to.length && to[prefix].isDigit()))
        ) {
            prefix--
        }
        while (suffix > 0 && from[from.length - suffix].isDigit() &&
            ((from.length - suffix - 1 >= prefix && from[from.length - suffix - 1].isDigit()) ||
                (to.length - suffix - 1 >= prefix && to[to.length - suffix - 1].isDigit()))
        ) {
            suffix--
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (prefix > 0) render(to.substring(0, prefix), Modifier)
        SwapSegment(
            from = from.substring(prefix, from.length - suffix),
            to = to.substring(prefix, to.length - suffix),
            progress = progress,
            render = render,
        )
        if (suffix > 0) render(to.substring(to.length - suffix), Modifier)
    }
}

/** Material 文字版本：与 `Text(text, style, color, …)` 同参，值变化时局部模糊替换。 */
@Composable
fun BlurText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    animateOnLocaleChange: Boolean = false,
    partial: Boolean = true,
) {
    BlurTextWith(text, modifier, animateOnLocaleChange, partial) { shown, m ->
        Text(
            text = shown,
            modifier = m,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * 本次组合是否正好遇到应用语言变化。语言变化时整屏文字一起换，不应逐处模糊（卡顿且无意义）；
 * 供 [BlurTextWith] 与 [BlurCrossfade] 判断。语言由 `ProvideAppLocale` 经 `LocalConfiguration` 下发。
 */
@Composable
internal fun rememberLocaleJustChanged(): Boolean {
    val tag = LocalConfiguration.current.locales[0].toLanguageTag()
    val last = remember { arrayOf(tag) }
    val changed = last[0] != tag
    SideEffect { last[0] = tag }
    return changed
}

private class BlurTextHolder(var from: String) {
    var to: String = from
    var lastChangeAt: Long = 0L

    /** 当前（或刚结束的）过渡进度；被新变化打断时用来让新过渡接着当前的显示状态走，不跳变。 */
    var progress: Animatable<Float, AnimationVector1D>? = null
}

/**
 * 被打断时的接续：上一次过渡里「新文字」此刻的透明度，就是下一次过渡里「旧文字」的起始透明度——
 * 反解出对应的起始进度，从那里继续，旧文字不会突然跳回全不透明。
 */
private fun carryOverProgress(inFlight: Float): Float {
    if (inFlight >= 1f) return 0f
    val visible = newAlpha(inFlight)
    // 旧层透明度 oldAlpha(q) = visible → 二分反解 q（oldAlpha 随 q 单调递减）
    var lo = 0f
    var hi = 1f
    repeat(12) {
        val mid = (lo + hi) / 2f
        if (oldAlpha(mid) > visible) lo = mid else hi = mid
    }
    return (lo + hi) / 2f
}

// 新旧两层共用同一个进度 s（0..1，已带丝滑的缓动）：旧的一边变淡一边变糊，新的同时一边出现一边变清晰；
// 两层的透明度合成起来始终接近 1（中段 s≈0.5 时旧 0.5 / 新 0.72，合成约 0.86，且此时两层都已模糊），
// 不会出现「旧字先消失、过一会新字才冒出来」的空档，也不会有两个清晰的字叠在一起。
private fun oldAlpha(s: Float): Float = 1f - smooth(s, 0f, 1f)
private fun newAlpha(s: Float): Float = smooth(s, 0f, NEW_IN_END)
private fun oldBlur(s: Float): Float = smooth(s, 0f, OLD_BLUR_END)
private fun newBlur(s: Float): Float = 1f - smooth(s, NEW_BLUR_START, 1f)

/** 被替换的那一小段：新旧两层叠在原位，宽度随进度过渡（后面不变的文字被平滑推开 / 收拢）。 */
@Composable
private fun SwapSegment(
    from: String,
    to: String,
    progress: Animatable<Float, AnimationVector1D>,
    render: @Composable (text: String, modifier: Modifier) -> Unit,
) {
    val blurPx = with(LocalDensity.current) { SWAP_BLUR.toPx() }
    Layout(
        content = {
            // 旧：先快速变淡变糊；新：稍晚才出现、从模糊里清晰（任何时刻都不会有两个清晰的字重叠）
            render(from, Modifier.graphicsLayer { applyPhase(oldAlpha(progress.value), oldBlur(progress.value), blurPx) })
            render(to, Modifier.graphicsLayer { applyPhase(newAlpha(progress.value), newBlur(progress.value), blurPx) })
        },
    ) { measurables, constraints ->
        // 过渡期间新旧文字都按内容宽度单行测量（不受父级剩余宽度挤压而临时换行）；静止后回到普通文字节点
        val loose = Constraints(maxWidth = Constraints.Infinity, maxHeight = constraints.maxHeight)
        val a = measurables[0].measure(loose)
        val b = measurables[1].measure(loose)
        val t = smooth(progress.value, 0.05f, 0.95f)
        val w = (a.width + (b.width - a.width) * t).roundToInt()
        val h = max(a.height, b.height)
        layout(w, h) {
            a.placeRelative(0, (h - a.height) / 2)
            b.placeRelative(0, (h - b.height) / 2)
        }
    }
}

private fun GraphicsLayerScope.applyPhase(alphaValue: Float, blurFraction: Float, blurPx: Float) {
    alpha = alphaValue
    val s = 0.96f + 0.04f * alphaValue
    scaleX = s
    scaleY = s
    val r = blurPx * blurFraction
    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && r >= 0.5f) BlurEffect(r, r, TileMode.Decal) else null
}

private fun smooth(x: Float, a: Float, b: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val SWAP_MILLIS = 360

/** 距上次变化不到这么久：视为连续快速变化（拖滑条等），直接显示最新值不做过渡。 */
private const val SNAP_INTERVAL_MS = 90L

/** 距上次变化不到这么久：用更短的过渡。 */
private const val RAPID_INTERVAL_MS = 260L
private const val RAPID_SWAP_MILLIS = 200

/**
 * 过渡的缓动：起、止都柔和的对称曲线（不是「先快后慢」，也不是起步发黏的弹簧——弹簧从静止起步，
 * 点下去的头几十毫秒几乎没变化，像是没反应）。配合 [START_LEAD]，第一帧就已经动了。
 */
private val SwapEasing = CubicBezierEasing(0.3f, 0f, 0.3f, 1f)

/** 起始进度的一点超前：点选的同一帧画面就已经开始变化。 */
private const val START_LEAD = 0.05f

/** 新文字在进度 0..NEW_IN_END 内出现；旧文字的模糊在 0..OLD_BLUR_END 内到位；新文字的模糊在 NEW_BLUR_START..1 内褪去。 */
private const val NEW_IN_END = 0.8f
private const val OLD_BLUR_END = 0.85f
private const val NEW_BLUR_START = 0.15f
private val SWAP_BLUR = 5.dp
