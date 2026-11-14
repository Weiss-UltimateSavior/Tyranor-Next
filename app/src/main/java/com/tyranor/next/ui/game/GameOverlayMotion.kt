package com.tyranor.next.ui.game

import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.motion.Bind
import com.tyranor.next.ui.common.motion.DialogSinkStrength
import com.tyranor.next.ui.common.motion.dialogScrimAlpha
import com.tyranor.next.ui.common.motion.LocalSheetBackdrop
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.passThroughTouches
import com.tyranor.next.ui.common.motion.releaseDialogDim
import com.tyranor.next.ui.common.motion.syncDialogWindow
import com.tyranor.next.ui.common.motion.nestedPanelDim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class GameOverlay { CoverSearch, ShortcutCrop }

/** Both custom windows must release input and remove dim before handing control back.
 * Crop uses the panel spring for dim; search fades dim independently of panel travel.
 */
internal class GameOverlayTracks(kind: GameOverlay, enhanced: Boolean) {
    val panel = Animatable(if (enhanced) 0f else 1f)
    val dim = if (kind == GameOverlay.CoverSearch) Animatable(if (enhanced) 0f else 1f) else panel
    var window: Window? = null
    var closing = false

    /** 弹窗窗口自己的焦点管理器：Dialog 内容是独立的 Compose 根，页面层的 LocalFocusManager 管不到它里面的输入框。 */
    var focusManager: FocusManager? = null
}

internal class GameOverlayMotion(
    private val kind: GameOverlay,
    private val scope: CoroutineScope,
    private val enhanced: Boolean,
    private val tracks: GameOverlayTracks,
) {
    private val panel get() = tracks.panel
    private val dim get() = tracks.dim

    @Composable
    fun BindWindow() {
        tracks.focusManager = LocalFocusManager.current
        val current = syncDialogWindow(
            preserveWindowAnimations = !enhanced,
            scrimAlpha = if (kind == GameOverlay.CoverSearch) 0.5f else 0f,
        ) {
            if (enhanced) dim.value else 1f
        }
        tracks.window = current
        DisposableEffect(current) {
            onDispose {
                current.passThroughTouches()
                current.releaseDialogDim()
            }
        }
        if (kind == GameOverlay.CoverSearch) {
            LaunchedEffect(Unit) {
                if (enhanced) {
                    launch { dim.animateTo(1f, MotionTokens.fadeIn(0.001f)) }
                    panel.animateTo(1f, MotionTokens.surfaceEnter(0.0001f))
                }
            }
        }
    }

    fun releaseWindow() {
        tracks.window.passThroughTouches()
        tracks.window.releaseDialogDim()
    }

    /**
     * 输入框的光标水滴柄是独立的 Popup 窗口，不随面板的缩放 / 位移 / 淡出一起走；
     * 不在退场开始时清掉焦点，面板已经半透明下沉，水滴柄还孤零零停在原处。
     */
    private fun releaseInputFocus() = tracks.focusManager?.clearFocus(force = true) ?: Unit

    fun dismiss(action: () -> Unit) {
        if (kind == GameOverlay.ShortcutCrop && tracks.closing) return
        if (!enhanced) {
            releaseInputFocus()
            releaseWindow()
            action()
            return
        }
        if (tracks.closing) return
        tracks.closing = true
        releaseInputFocus()
        tracks.window.passThroughTouches()
        scope.launch {
            if (kind == GameOverlay.CoverSearch) launch { dim.animateTo(0f, MotionTokens.fadeOut(0.001f)) }
            exitPanel()
            releaseWindow()
            action()
        }
    }

    /** Run in the export job so cancellation still reaches its temporary-file cleanup. */
    suspend fun finishCropExport() {
        releaseInputFocus()
        if (enhanced) {
            tracks.closing = true
            tracks.window.passThroughTouches()
            exitPanel()
        }
        releaseWindow()
    }

    private suspend fun exitPanel() {
        val threshold = if (kind == GameOverlay.ShortcutCrop) 0.0001f else 0.001f
        panel.animateTo(0f, MotionTokens.surfaceExit(threshold))
    }

    fun panel(modifier: Modifier): Modifier {
        val motion = if (!enhanced) modifier else modifier.graphicsLayer {
            val p = panel.value
            if (kind == GameOverlay.CoverSearch) {
                val s = 0.9f + 0.1f * p
                scaleX = s
                scaleY = s
                translationY = (1f - p) * 28.dp.toPx()
                alpha = dim.value.coerceIn(0f, 1f)
            } else if (p != 1f) {
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * 48.dp.toPx()
                val s = 0.92f + 0.08f * p
                scaleX = s
                scaleY = s
            }
        }
        // Search supplies the actual fixed panel bounds here. Crop adds its dim at the Card,
        // after IME padding, so the keyboard's empty margin is never painted as a panel.
        return if (kind == GameOverlay.CoverSearch) panelDim(motion) else motion
    }

    fun panelDim(modifier: Modifier): Modifier = modifier.nestedPanelDim(
        window = { tracks.window },
        shape = AppComponentShape,
    )

    fun searchScrim(modifier: Modifier): Modifier = modifier.drawBehind {
        val alpha = tracks.window.dialogScrimAlpha()
        if (alpha > 0f) drawRect(Color.Black.copy(alpha = alpha))
    }
}

@Composable
internal fun rememberGameOverlayMotion(kind: GameOverlay): GameOverlayMotion {
    val enhanced = MotionMode.enhanced
    val scope = rememberCoroutineScope()
    val tracks = remember { GameOverlayTracks(kind, enhanced) }
    val panel = tracks.panel
    if (enhanced) LocalSheetBackdrop.current?.Bind(strength = { DialogSinkStrength }) { tracks.dim.value.coerceIn(0f, 1f) }
    if (kind == GameOverlay.ShortcutCrop) LaunchedEffect(Unit) {
        if (panel.value < 1f) panel.animateTo(1f, MotionTokens.surfaceEnter(0.0001f))
    }
    return remember(kind, scope, enhanced, tracks) { GameOverlayMotion(kind, scope, enhanced, tracks) }
}
