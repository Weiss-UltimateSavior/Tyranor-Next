package com.tyranor.next.ui.common

import android.view.Window
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassPanel
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.LocalAppDialogContent
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.motion.Bind
import com.tyranor.next.ui.common.motion.DialogSinkStrength
import com.tyranor.next.ui.common.motion.LocalSheetBackdrop
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.passThroughTouches
import com.tyranor.next.ui.common.motion.restoreInteraction
import com.tyranor.next.ui.common.motion.DialogWindowScrim
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.notice.NoticeHost
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.rememberEntranceState
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.animateContentSizeSpring
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import android.view.WindowManager

/** 统一弹窗最大宽度：与游戏操作抽屉（ModalBottomSheet 的 SheetMaxWidth）一致，避免横屏/大屏拉伸过宽。 */
internal val AppDialogMaxWidth = 640.dp

/** 弹窗正文的水平内边距：超框内容（[AppAlertDialog] 的 topOverhang）按同一值对齐。 */
internal val AppDialogContentHorizontalPadding = 18.dp

/** 弹窗正文的垂直内边距：弹窗标题与正文共用。 */
internal val AppDialogContentVerticalPadding = 16.dp

/**
 * Miuix 风格弹窗：小屏底部弹入 + 窗口变暗 + 标题居中。
 * 面板和圆角跟随动态主题，动画节奏复刻 miuix OverlayDialog。
 * dismissEnabled 在退场前阻止关闭；已接受的退场始终交付 onDismissRequest。
 */
@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    buttonArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp, Alignment.End),
    // Opt in only for plain text bodies; wrapping a self-scrolling list would pass infinite height constraints.
    textScrollEnabled: Boolean = false,
    dismissEnabled: Boolean = true,
    /**
     * 位于卡片上沿外侧、不参与卡片自身测量的内容（如超出面板顶部的游戏封面）。
     * 内容从卡片顶部上方 [topOverhangHeight] 处开始绘制；水平方向与正文内边距对齐。
     * 卡片会整体下移 [topOverhangHeight]，为超框内容留出空间。
     */
    topOverhang: (@Composable () -> Unit)? = null,
    topOverhangHeight: Dp = 0.dp,
    /** 是否展示底部按钮行；纯条目式弹窗可从遮罩/返回键关闭，不显示额外按钮。 */
    showButtonRow: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 键盘可见时 IME 区域已包含底部导航栏，底部避让必须二选一（见下方 imePadding/navigationBarsPadding）
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // 卡片滑入行程 = 卡片高度 + 底部留白与导航栏（见 graphicsLayer）
    val dialogTravelPx = with(density) { 96.dp.toPx() }
    // 「普通」动效档沿用原版：以整屏高度为行程
    val windowHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val enhanced = MotionMode.enhanced
    // 弹窗所在的平台窗口：关闭开始时立即变为不可触摸，退场期间点击直接落到下层页面
    val dialogWindow = remember { arrayOfNulls<Window>(1) }
    val entrance = rememberEntranceState(respectGate = false)
    // 正文区域高度上限随屏幕自适应：固定 420dp 会在长列表（如 Artemis 全版本）末条截断
    val textMaxHeight = (configuration.screenHeightDp * 0.62f).dp.coerceIn(280.dp, 560.dp)
    val textScrollState = rememberScrollState()
    val dimAlpha = remember { Animatable(0f) }
    val slideFraction = remember { Animatable(1f) }
    // 弹窗升起时背后的页面随遮罩进度下沉 / 褪色（见 LocalSheetBackdrop）
    LocalSheetBackdrop.current?.Bind(strength = { DialogSinkStrength }) { dimAlpha.value.coerceIn(0f, 1f) }
    val dismissing = remember { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentDismissEnabled by rememberUpdatedState(dismissEnabled)
    val focusManager = LocalFocusManager.current

    fun actionFailed(failure: Exception) {
        Log.e("AppAlertDialog", "Dialog exit action failed", failure)
        try {
            currentOnDismiss()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closeFailure: Exception) {
            Log.e("AppAlertDialog", "Could not close dialog after its exit action failed", closeFailure)
        }
    }

    // 取消类按钮：先播放退场再执行按钮动作（按钮通常直接把弹窗从组合里移除，来不及播放退场）。
    // 动作执行后弹窗若仍在（例如校验未通过），再弹回来，不会卡在隐藏状态。
    val exitController = remember(enhanced, focusManager) {
        DialogExitController { action ->
            if (dismissing.value) return@DialogExitController
            dismissing.value = true
            focusManager.clearFocus(force = true)
            dialogWindow[0].passThroughTouches()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runDialogExitAction(
                    animateExit = {
                        if (enhanced) {
                            launch { dimAlpha.animateTo(0f, MotionTokens.fadeOut(0.001f)) }
                            slideFraction.animateTo(1f, MotionTokens.surfaceExit(0.001f))
                        }
                    },
                    action = action,
                    afterAction = {
                        // 仍在组合中：动作没有关闭弹窗，恢复显示。
                        delay(80)
                        dialogWindow[0].restoreInteraction()
                        dismissing.value = false
                        if (enhanced) {
                            launch { dimAlpha.animateTo(1f, MotionTokens.fadeIn(0.001f)) }
                            slideFraction.animateTo(0f, DialogEnterSpec)
                        }
                    },
                    onFailure = { actionFailed(it) },
                    cleanup = {
                        try {
                            dialogWindow[0]?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        } finally {
                            dismissing.value = false
                        }
                    },
                )
            }
        }
    }

    /** 接受一次允许的关闭请求，先释放触摸并同步退场，再交付最新关闭回调。 */
    fun dismiss() {
        if (!currentDismissEnabled || dismissing.value) return
        dismissing.value = true
        focusManager.clearFocus(force = true)
        dialogWindow[0].passThroughTouches()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runDialogExitAction(
                animateExit = {
                    if (enhanced) {
                        launch { dimAlpha.animateTo(0f, MotionTokens.fadeOut(0.001f)) }
                        slideFraction.animateTo(1f, MotionTokens.surfaceExit(0.001f))
                    } else {
                        launch { dimAlpha.animateTo(0f, tween(250, easing = FastOutLinearInEasing)) }
                        slideFraction.animateTo(1f, tween(260, easing = FastOutLinearInEasing))
                    }
                },
                action = { currentOnDismiss() },
                afterAction = {},
                onFailure = { Log.e("AppAlertDialog", "Dialog dismissal failed", it) },
                cleanup = {
                    try {
                        dialogWindow[0]?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    } finally {
                        dismissing.value = false
                    }
                },
            )
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        // decorFitsSystemWindows = false：insets 统一由 Compose 处理。保留默认 true 时平台会先把
        // 弹窗窗口避让到键盘之上，Compose 仍上报完整 IME inset，imePadding 会再抬一次（离键盘过远）。
        properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = currentDismissEnabled, dismissOnClickOutside = currentDismissEnabled),
    ) {
        DialogWindowScrim(
            enhanced = enhanced,
            progress = { dimAlpha.value },
            onWindow = { dialogWindow[0] = it },
            onDismissRequest = { dismiss() },
            dismissEnabled = currentDismissEnabled,
        ) {
            // 弹窗整体（卡片 + 可选的超框内容）：底部对齐，从屏底弹入
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(when {
                        isTabletScreen() -> Modifier.safeDrawingPadding()
                        imeVisible -> Modifier.imePadding()
                        else -> Modifier.navigationBarsPadding()
                    })
                    .padding(12.dp)
                    .widthIn(max = AppDialogMaxWidth)
                    .fillMaxWidth()
                    .graphicsLayer {
                        // 以卡片自身高度（加底部留白）为行程：一开始就从屏底露头，而不是先穿过整屏；
                        // 入场弹簧带回弹，卡片会略微冲过停位再落稳；同时从底边略微放大（停稳时为 1）
                        val f = slideFraction.value
                        if (enhanced) {
                            translationY = f * (size.height + dialogTravelPx)
                            val s = 1f - DialogEnterScaleDelta * f.coerceIn(0f, 1f)
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        } else {
                            translationY = f * windowHeightPx
                        }
                    }
                    // 消费弹窗区域点击（含超框内容），避免穿透到遮罩
                    .pointerInput(Unit) { detectTapGestures { } }
                    .then(if (dismissing.value) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 为超框内容留出卡片上沿空间；padding 在 Card 背景之外，表面仍从下方开始
                        .padding(top = topOverhangHeight)
                        .glassBorder(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(
                        // 复古玻璃用高不透明度深色面板；白雾玻璃用页面底图 + 白纱的面板层
                        containerColor = when {
                            AppThemeColors.isGlass -> GlassPanel
                            AppThemeColors.isMilkGlass -> Color.Transparent
                            else -> NavWhite
                        },
                        contentColor = TextColor,
                    ),
                    shape = AppComponentShape,
                ) {
                    Box(Modifier.fillMaxWidth().clip(AppComponentShape)) {
                        if (AppThemeColors.isMilkGlass) {
                            MilkGlassPanelBackground(Modifier.matchParentSize())
                        }
                        // 仅为弹窗子组件提供取色上下文，使输入框与标签指示器和面板形成反差；
                        // 不把弹窗条目色扩展到页内组件，也不由页面调用方覆盖公共组件颜色。
                        CompositionLocalProvider(LocalAppDialogContent provides true) {
                            ProvideEntrance(entrance) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .skipEntranceOnDrag()
                                        .animateContentSizeSpring()
                                        .padding(
                                            horizontal = AppDialogContentHorizontalPadding,
                                            vertical = AppDialogContentVerticalPadding,
                                        ),
                                ) {
                                    Box(Modifier.fillMaxWidth().staggeredEntrance(0, distance = 14.dp, stepMillis = 45L), contentAlignment = Alignment.Center) {
                                        title()
                                    }
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .staggeredEntrance(1, distance = 14.dp, stepMillis = 45L)
                                            // Measure the title/actions first so long text cannot consume
                                            // their space in landscape or with a large system font.
                                            .then(if (showButtonRow || textScrollEnabled) Modifier.weight(1f, fill = false) else Modifier)
                                            .heightIn(max = textMaxHeight)
                                            .then(
                                                if (textScrollEnabled) {
                                                    Modifier.verticalScroll(textScrollState)
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .padding(top = 14.dp),
                                    ) {
                                        if (showButtonRow) text() else {
                                            CompositionLocalProvider(LocalDialogExit provides exitController) { text() }
                                        }
                                    }
                                    if (showButtonRow) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().staggeredEntrance(2, distance = 14.dp, stepMillis = 45L).padding(top = 14.dp),
                                            horizontalArrangement = buttonArrangement,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CompositionLocalProvider(LocalDialogExit provides exitController) {
                                                dismissButton?.invoke()
                                            }
                                            CompositionLocalProvider(LocalDialogExit provides if (dismissButton == null) exitController else null) {
                                                confirmButton()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (topOverhang != null) {
                    // 超框内容按自然尺寸布局；卡片通常更高，因此不会改变弹窗整体高度。
                    // 保留真实测量尺寸而不是按 0x0 上报，确保封面等内容的点击区域可命中。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(horizontal = AppDialogContentHorizontalPadding),
                    ) {
                        topOverhang()
                    }
                }
            }
            if (dismissing.value) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
            }
            // 提示条槽位：弹窗打开时提示条画在弹窗窗口里（遮罩之上），不会被遮罩盖住
            NoticeHost(Modifier.matchParentSize())
        }
    }

    // 进入动画：变暗渐入 + 底部 Q 弹弹入
    LaunchedEffect(Unit) {
        if (enhanced) {
            launch { dimAlpha.animateTo(1f, MotionTokens.fadeIn(0.001f)) }
            // Q 弹入场：卡片略冲过停位再落稳
            slideFraction.animateTo(targetValue = 0f, animationSpec = DialogEnterSpec)
        } else {
            launch { dimAlpha.animateTo(1f, tween(300, easing = LinearOutSlowInEasing)) }
            slideFraction.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f),
            )
        }
    }
}

/**
 * 弹窗的进场弹簧：响应 0.42s、阻尼 0.74（只轻轻冲过一点再落稳），与抽屉、侧栏统一。
 * 通用大面板进场（`surfaceEnter`，阻尼 0.2）弹得太狠，小弹窗来回晃好几下显得轻浮。
 */
private val DialogEnterSpec = MotionSprings.response<Float>(0.42f, 0.74f, 0.0001f)

/** 弹窗入场时从底边略微放大的幅度（停稳时缩放为 1）。 */
private const val DialogEnterScaleDelta = 0.06f

/** 弹窗按钮的「先退场再执行」入口（见 [AppAlertDialog] 的 exitController）。 */
internal class DialogExitController(val closeThen: (action: () -> Unit) -> Unit)

/** 当前按钮所在弹窗的退场控制器；为 null 时按钮直接执行动作。 */
internal val LocalDialogExit = staticCompositionLocalOf<DialogExitController?> { null }

/** Deliver an accepted action once even when exit is cancelled, then always release the exit guard. */
internal suspend fun runDialogExitAction(
    animateExit: suspend () -> Unit,
    action: () -> Unit,
    afterAction: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
    cleanup: () -> Unit,
) {
    var cancellation: CancellationException? = null
    var failure: Exception? = null
    fun record(error: Exception) {
        if (error is CancellationException) {
            if (cancellation == null) cancellation = error
            else if (cancellation !== error) cancellation!!.addSuppressed(error)
        } else {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
    }
    try {
        try {
            animateExit()
        } catch (error: Exception) {
            record(error)
        }
        // Rotation/disposal can cancel the animation after a user accepted this action.
        // Attempt it exactly once; an action failure must never cause an automatic retry.
        withContext(NonCancellable) {
            try {
                action()
            } catch (error: Exception) {
                record(error)
            }
        }
        val cancelled = cancellation
        val failed = failure
        if (cancelled != null) {
            if (failed != null) {
                cancelled.addSuppressed(failed)
                try {
                    onFailure(failed)
                } catch (closeFailure: Exception) {
                    if (closeFailure !== cancelled) cancelled.addSuppressed(closeFailure)
                }
            }
            throw cancelled
        }
        if (failed != null) onFailure(failed) else afterAction()
    } finally {
        cleanup()
    }
}
