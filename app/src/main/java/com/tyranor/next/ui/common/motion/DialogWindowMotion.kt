package com.tyranor.next.ui.common.motion

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** 增强档只用窗口 dim；普通档保留主题 dim 与自绘遮罩。两者都跟随面板的遮罩进度。 */
@Composable
internal fun DialogWindowScrim(
    enhanced: Boolean,
    progress: () -> Float,
    onWindow: (Window?) -> Unit,
    onDismissRequest: () -> Unit,
    dismissEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val window = syncDialogWindow(
        baseDimOverride = if (enhanced) DIALOG_SCRIM_ALPHA else null,
        scrimAlpha = if (enhanced) 0f else DIALOG_SCRIM_ALPHA,
        dimProgress = progress,
    )
    SideEffect { onWindow(window) }
    val scrim = Modifier.drawBehind {
        val alpha = window.dialogScrimAlpha()
        if (alpha > 0f) drawRect(Color.Black.copy(alpha = alpha))
    }
    Box(
        Modifier.fillMaxSize().then(scrim).clickable(
            enabled = dismissEnabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismissRequest,
        ),
        content = content,
    )
}

private const val DIALOG_SCRIM_ALPHA = 0.5f

/**
 * 修复「弹窗已经退场，灰色遮罩却还停留一会儿、期间点不动页面」：
 * Compose `Dialog` 的平台窗口自带背景变暗（dim）与系统进出场动画，窗口移除时系统再单独淡出一次 dim，
 * 而这段时间窗口仍拦截触摸。
 *
 * 在 `Dialog { }` 内容里调用：默认关闭该窗口的系统进出场动画，并让窗口 dim 跟随 [dimProgress]（0..1，通常就是弹窗自己的
 * 遮罩进度）同步淡入淡出，停稳时的总暗度与原来一致。关闭开始时调用 [passThroughTouches]，退场期间点击直接落到下层页面。
 * 这是缺陷修复，「普通」与「增强」动效档都生效。
 * 同一 Activity 的叠层窗口保留下层仍显示的遮罩；上层开始关闭时立即将 dim 交回下层，恢复父窗口亮度。
 * 没有自绘进退场的原版弹窗可用 [preserveWindowAnimations] 保留平台效果，关闭时调用 [releaseDialogDim]。
 *
 * @return 弹窗所在的平台窗口（取不到时为 null）
 */
@Composable
fun syncDialogWindow(
    preserveWindowAnimations: Boolean = false,
    /** 非 null 时用它替代窗口主题自带的暗度（弹窗 / 侧栏的「增强」档用较浅的统一遮罩），null 则沿用窗口自带暗度。 */
    baseDimOverride: Float? = null,
    /** 本窗口自绘遮罩的完整暗度；共享协调器只让它补足下层缺少的部分，避免叠层加深。 */
    scrimAlpha: Float = 0f,
    dimProgress: () -> Float,
): Window? {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val owner = remember(window) { PageTransitions.findActivity(view.context) ?: window }
    val originalAnimations = remember(window) { window?.attributes?.windowAnimations ?: 0 }
    val themeDim = remember(window) { window?.attributes?.dimAmount ?: 0f }
    val hasThemeDim = remember(window) { (window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 }
    val baseDim = baseDimOverride ?: themeDim
    val progress = rememberUpdatedState(dimProgress)
    val dimEnabled = hasThemeDim || baseDimOverride != null
    val animations = if (preserveWindowAnimations) originalAnimations else 0
    DisposableEffect(window, owner) {
        val binding = if (window != null && owner != null) {
            DialogWindowDims.bind(owner, window, quantizedDim(baseDim, progress.value()), dimEnabled, animations,
                baseDim, quantizedDim(scrimAlpha, progress.value()), scrimAlpha)
        } else null
        onDispose { binding?.dispose() }
    }
    SideEffect {
        if (window != null) DialogWindowDims.update(window, quantizedDim(baseDim, progress.value()), dimEnabled, animations,
            baseDim, quantizedDim(scrimAlpha, progress.value()), scrimAlpha)
    }
    LaunchedEffect(window, baseDim, dimEnabled, animations, scrimAlpha) {
        if (window == null) return@LaunchedEffect
        // 暗度只有 8 位精度：弹簧尾段肉眼不可辨的微小变化不再触发一次窗口布局与 WMS 调用；两端仍精确落到 0 与 baseDim
        snapshotFlow { quantizedDim(baseDim, progress.value()) to quantizedDim(scrimAlpha, progress.value()) }.collect { (dim, scrim) ->
            DialogWindowDims.update(window, dim, dimEnabled, animations, baseDim, scrim, scrimAlpha)
        }
    }
    return window
}

private fun quantizedDim(baseDim: Float, progress: Float): Float {
    val level = (progress.coerceIn(0f, 1f) * baseDim * 255f).roundToInt()
    return if (level >= (baseDim * 255f).roundToInt()) baseDim else level / 255f
}

/** Window registration follows real attachment, while composition owns the listener lifetime. UI thread only. */
private object DialogWindowDims {
    private val bindings = IdentityHashMap<Window, Binding>()
    private val stack = DialogDimStack<Window>(
        isVisible = { it.decorView.isAttachedToWindow && it.decorView.isShown },
        applyDim = { window, dim, scrim, enabled -> bindings[window]?.applyDim(dim, scrim, enabled) },
        applyPanelDim = { window, alpha -> bindings[window]?.applyPanelDim(alpha) },
    )

    fun bind(
        owner: Any,
        window: Window,
        dim: Float,
        enabled: Boolean,
        animations: Int,
        baseDim: Float,
        scrim: Float,
        baseScrim: Float,
    ): Binding =
        Binding(owner, window, dim, enabled, animations, baseDim, scrim, baseScrim).also { it.start() }

    fun update(
        window: Window,
        dim: Float,
        enabled: Boolean,
        animations: Int,
        baseDim: Float,
        scrim: Float,
        baseScrim: Float,
    ) {
        bindings[window]?.let {
            it.dim = dim
            it.enabled = enabled
            it.animations = animations
            it.baseDim = baseDim
            it.scrim = scrim
            it.baseScrim = baseScrim
            if (it.registered) stack.update(window, dim, enabled, baseDim, scrim, baseScrim)
        }
    }

    fun release(window: Window) {
        val binding = bindings[window]
        if (binding == null) clearDim(window)
        else binding.release()
    }

    fun beginExit(window: Window) { bindings[window]?.beginExit() }

    fun restore(window: Window) { bindings[window]?.restore() }

    fun scrimAlpha(window: Window?): Float = bindings[window]?.appliedScrim ?: 0f

    fun panelDimAlpha(window: Window?): Float = bindings[window]?.appliedPanelDim ?: 0f

    class Binding(
        private val owner: Any,
        private val window: Window,
        var dim: Float,
        var enabled: Boolean,
        var animations: Int,
        var baseDim: Float,
        var scrim: Float,
        var baseScrim: Float,
    ) : View.OnAttachStateChangeListener {
        private val lifecycle = (owner as? LifecycleOwner)?.lifecycle
        private val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dispose()
        }
        private var disposed = false
        private var released = false
        var registered = false
            private set
        var appliedScrim by mutableFloatStateOf(0f)
            private set
        var appliedPanelDim by mutableFloatStateOf(0f)
            private set

        fun applyPanelDim(alpha: Float) {
            appliedPanelDim = alpha
        }

        fun start() {
            bindings[window] = this
            window.decorView.addOnAttachStateChangeListener(this)
            register()
            if (lifecycle?.currentState == Lifecycle.State.DESTROYED) dispose()
            else lifecycle?.addObserver(observer)
        }

        private fun register() {
            if (disposed || registered) return
            registered = true
            // Commit the inherited floor before this Window can replace the parent's dim in WMS.
            stack.register(owner, window, dim, enabled, baseDim, scrim, baseScrim, released)
        }

        fun beginExit() {
            if (registered && stack.beginExit(window)) released = true
        }

        fun release() {
            released = true
            if (registered) stack.release(window) else clearDim(window)
        }

        fun restore() {
            released = false
            if (registered) stack.restore(window)
        }

        override fun onViewAttachedToWindow(view: View) {
            register()
            stack.attached(window)
        }

        override fun onViewDetachedFromWindow(view: View) = unregister()

        private fun unregister() {
            if (!registered) return
            registered = false
            appliedScrim = 0f
            appliedPanelDim = 0f
            // Composition can be disposed before WMS removes its Window. Zero dim alone would
            // still override the lower Window; drop eligibility in the same attributes update.
            clearDim(window)
            stack.remove(window)
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            window.decorView.removeOnAttachStateChangeListener(this)
            lifecycle?.removeObserver(observer)
            unregister()
            bindings.remove(window)
        }

        fun applyDim(value: Float, drawnScrim: Float, nativeEnabled: Boolean) {
            appliedScrim = drawnScrim
            val attributes = window.attributes
            val flag = WindowManager.LayoutParams.FLAG_DIM_BEHIND
            val flags = if (nativeEnabled) attributes.flags or flag else attributes.flags and flag.inv()
            if (attributes.dimAmount == value && attributes.flags == flags && attributes.windowAnimations == animations) return
            attributes.dimAmount = value
            attributes.flags = flags
            attributes.windowAnimations = animations
            window.attributes = attributes
        }
    }

    private fun clearDim(window: Window) {
        val attributes = window.attributes
        val flags = attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        if (attributes.flags == flags && attributes.dimAmount == 0f) return
        attributes.flags = flags
        attributes.dimAmount = 0f
        window.attributes = attributes
    }
}

/** Drop this window's dim eligibility until an explicit reopen; lower windows retain their own masks. */
fun Window?.releaseDialogDim() {
    if (this != null) DialogWindowDims.release(this)
}

/** An accepted nested close yields the mask while allowing the surface to finish moving. */
internal fun Window?.beginDialogDimExit() {
    if (this != null) DialogWindowDims.beginExit(this)
}

/** Explicit reopening only; resource cleanup must leave a closing window released. */
internal fun Window?.restoreDialogDim() {
    if (this != null) DialogWindowDims.restore(this)
}

/** Read in the scrim's draw phase so attach/removal invalidates only that drawing. */
fun Window?.dialogScrimAlpha(): Float = DialogWindowDims.scrimAlpha(this)

/** Dim only this panel: its existing background mask keeps the same Window owner. */
internal fun Window?.dialogPanelDimAlpha(): Float = DialogWindowDims.panelDimAlpha(this)

internal fun Modifier.nestedPanelDim(
    window: () -> Window?,
    shape: Shape = RectangleShape,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawWithContent {
        drawContent()
        val alpha = window().dialogPanelDimAlpha()
        if (alpha > 0f) drawOutline(outline, Color.Black.copy(alpha = alpha))
    }
}

/** Arbitrary overhanging content keeps its own transparent outline instead of dimming its bounds. */
internal fun Modifier.nestedPanelContentDim(window: () -> Window?): Modifier = graphicsLayer {
    compositingStrategy = if (window().dialogPanelDimAlpha() > 0f) {
        CompositingStrategy.Offscreen
    } else CompositingStrategy.Auto
}.drawWithContent {
    drawContent()
    val alpha = window().dialogPanelDimAlpha()
    if (alpha > 0f) drawRect(Color.Black.copy(alpha = alpha), blendMode = BlendMode.SrcAtop)
}

/** 开始关闭时释放触摸；有下层窗口时立即交回遮罩，单窗口仍跟随原退场渐隐。 */
fun Window?.passThroughTouches() {
    if (this == null) return
    addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    beginDialogDimExit()
}

/**
 * 关闭开始时同时交还焦点：系统返回手势发给有焦点的窗口。屏幕左缘右滑既是返回手势也是唤出侧边栏的手势，
 * 窗口退场期间若还占着焦点，这次手势会被正在关闭的窗口吞掉，无法重新唤出。
 */
fun Window?.releaseFocusForExit() {
    this?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}

/** 退场途中明确重开时恢复交互和最新遮罩请求；关闭清理不可无条件调用。 */
fun Window?.restoreInteraction() {
    if (this == null) return
    clearFlags(
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    )
    restoreDialogDim()
}
