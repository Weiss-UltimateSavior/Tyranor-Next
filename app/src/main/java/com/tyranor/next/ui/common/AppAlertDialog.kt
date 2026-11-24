package com.tyranor.next.ui.common

import android.view.Window
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tyranor.next.ui.common.motion.nestedPanelContentDim
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassPanel
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.LocalAppDialogContent
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.ui.common.motion.Bind
import com.tyranor.next.ui.common.motion.DialogSinkStrength
import com.tyranor.next.ui.common.motion.LocalSheetBackdrop
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.ExitTapReplay
import com.tyranor.next.ui.common.motion.PageTransitions
import com.tyranor.next.ui.common.motion.beginDialogDimExit
import com.tyranor.next.ui.common.motion.restoreInteraction
import com.tyranor.next.ui.common.motion.DialogWindowScrim
import com.tyranor.next.ui.common.motion.nestedPanelDim
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import android.view.WindowManager

/** 统一弹窗最大宽度：与游戏操作抽屉（ModalBottomSheet 的 SheetMaxWidth）一致，避免横屏/大屏拉伸过宽。 */
internal val AppDialogMaxWidth = 640.dp

/** 弹窗正文的水平内边距：超框内容（[AppAlertDialog] 的 topOverhang）按同一值对齐。 */
internal val AppDialogContentHorizontalPadding = 18.dp

/** 弹窗正文的垂直内边距：弹窗标题与正文共用。 */
internal val AppDialogContentVerticalPadding = 16.dp

/**
 * [AppAlertDialog] 的一次调用（标题 / 正文 / 按钮与关闭语义），交给 [AppDialogHost] 摆放时使用。
 * 宿主按与弹窗相同的约定提供 [LocalDialogExit] / [LocalDialogBodyExit]，并在自己的关闭动画结束后交付 [onDismissRequest]。
 */
internal class AppDialogSpec(
    val onDismissRequest: () -> Unit,
    val title: @Composable () -> Unit,
    val text: @Composable () -> Unit,
    val confirmButton: @Composable () -> Unit,
    val dismissButton: (@Composable () -> Unit)?,
    val buttonArrangement: Arrangement.Horizontal,
    val textScrollEnabled: Boolean,
    val dismissEnabled: Boolean,
    val showButtonRow: Boolean,
)

/**
 * 弹窗的替代宿主：组合树里提供了它时，[AppAlertDialog] 不另开窗口，把同一份标题 / 正文 / 按钮交给宿主摆放。
 * 目前只有「游戏盒」风格翻开的书（`ui/game/ShelfBook.kt`）提供：书里的选项弹出的二级弹窗翻到书的下一页上。
 */
internal interface AppDialogHost {
    @Composable
    fun Present(dialog: AppDialogSpec)
}

internal val LocalAppDialogHost = staticCompositionLocalOf<AppDialogHost?> { null }

/**
 * 弹窗正文里列表的高度：窗口版按 [windowMax] 封顶（窗口高度有限）；翻开的书里书页大得多，列表不设上限，
 * 有多高占多高（受页上剩下的高度约束），不留一大块空白。
 */
@Composable
internal fun Modifier.dialogListHeight(windowMax: Dp): Modifier =
    if (LocalAppDialogHost.current != null) this else heightIn(max = windowMax)

/** 同上，窗口版是固定高度 [windowHeight]；书里占满页上剩下的高度。 */
@Composable
internal fun Modifier.dialogListFixedHeight(windowHeight: Dp): Modifier =
    if (LocalAppDialogHost.current != null) fillMaxSize() else height(windowHeight)

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
    /**
     * 应用锁生效时的强制关闭路径：必须真正把弹窗从组合里移除。
     * 默认直接走 [onDismissRequest]；若业务回调里有「运行中只能重建 / 不允许关闭」之类的守卫
     * （如归档进度弹窗），必须传入不经过该守卫的收尾动作，否则独立窗口会留在锁门之上。
     */
    onLockDismiss: (() -> Unit)? = null,
) {
    // 应用锁生效时**退出组合**而不是只请求关闭：弹窗是独立窗口，层级在锁门之上；
    // 调用方若在 onDismissRequest 里有「运行中只能重建」等守卫，只发回调会让窗口留在门上方。
    // 这里直接移除 Dialog 窗口，并用 onLockDismiss（或默认回调）清理业务状态。
    val locked by AppLockController.lockedState.collectSettingAsState()
    if (locked) {
        val currentClose by rememberUpdatedState(onLockDismiss ?: onDismissRequest)
        LaunchedEffect(Unit) { currentClose() }
        return
    }
    // 有替代宿主（游戏盒里翻开的书）时交给它摆放，不另开窗口
    LocalAppDialogHost.current?.let { host ->
        host.Present(
            AppDialogSpec(
                onDismissRequest = onDismissRequest,
                title = title,
                text = text,
                confirmButton = confirmButton,
                dismissButton = dismissButton,
                buttonArrangement = buttonArrangement,
                textScrollEnabled = textScrollEnabled,
                dismissEnabled = dismissEnabled,
                showButtonRow = showButtonRow,
            ),
        )
        return
    }
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
    val pendingExitAction = remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingExitIsDismiss = remember { mutableStateOf(true) }
    val pendingExitDelivered = remember { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentDismissEnabled by rememberUpdatedState(dismissEnabled)
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Dialog 内容是独立的 Compose 根，自带一个焦点管理器；这里拿到的 focusManager 只管页面层。
    // 输入框的光标水滴柄是弹窗根里的独立 Popup，必须清弹窗根的焦点才会立刻收掉（否则面板已经下沉淡出，水滴柄还孤零零悬在原处）。
    val dialogFocus = remember { arrayOfNulls<androidx.compose.ui.focus.FocusManager>(1) }
    fun releaseInputFocus() {
        (dialogFocus[0] ?: focusManager).clearFocus(force = true)
    }
    // 退场动画与弹窗根 View：退场期间落在窗口上的单击，先中止退场动画，再在弹窗确认离开后补发给下层页面（见 ExitTapReplay）
    val exitAnimation = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    /** 退场动画：遮罩淡出与面板下沉；返回时动画已结束或被 [scrimTapDuringExit] 中止（中止不是协程取消，不会抛出）。 */
    suspend fun playExit(dimSpec: AnimationSpec<Float>, slideSpec: AnimationSpec<Float>) = coroutineScope {
        val job = launch {
            launch { dimAlpha.animateTo(0f, dimSpec) }
            slideFraction.animateTo(1f, slideSpec)
        }
        exitAnimation[0] = job
        job.join()
    }
    val dialogView = remember { arrayOfNulls<android.view.View>(1) }
    val pendingExitTap = remember { arrayOfNulls<PendingExitTap>(1) }
    val disposed = remember { BooleanArray(1) }
    val originCache = remember { IntArray(2) }
    // 关闭回调交付之后、窗口真正被移除之前还有一两帧：这段「尾巴」里的按下同样要转交给下层页面，
    // 否则恰好落在这一两帧里的点击会被正在消失的弹窗吞掉。
    val deliveredAt = remember { LongArray(1) }
    fun inTail(): Boolean =
        deliveredAt[0] != 0L && android.os.SystemClock.uptimeMillis() - deliveredAt[0] < ExitTailMillis
    fun scrimTapDuringExit(position: androidx.compose.ui.geometry.Offset) {
        if (!dismissing.value && !inTail()) return
        val view = dialogView[0] ?: return
        val activity = PageTransitions.findActivity(view.context)
        // 弹窗窗口已被移除时（按下落在窗口消失前、抬起时已不在）View 取不到屏幕坐标，改用最近一次缓存的原点
        val origin = IntArray(2)
        if (view.isAttachedToWindow) {
            view.getLocationOnScreen(origin)
            originCache[0] = origin[0]
            originCache[1] = origin[1]
        } else {
            origin[0] = originCache[0]
            origin[1] = originCache[1]
        }
        // 只中止退场动画（不取消整个退场协程）：协程随后照常交付已接受的动作，动作若没有关闭弹窗，
        // 仍走 afterAction 恢复显示；取消整个协程会跳过恢复流程，面板停在半途、遮罩不再亮回来。
        if (dismissing.value) exitAnimation[0]?.cancel()
        // 不在这里立刻回放：动作若没有关闭弹窗（校验未通过），弹窗会被恢复显示，点击就不该落到它下面的页面。
        // 先记下，弹窗真正离开组合时才发（见下方 DisposableEffect）；恢复显示则丢弃。
        if (activity != null) {
            val tap = PendingExitTap(
                activity, origin[0] + position.x, origin[1] + position.y, android.os.SystemClock.uptimeMillis(),
            )
            // 弹窗已经离开组合（手势中途被移除）：不会再有「离开」事件，直接补发；否则等离开时再发
            if (disposed[0]) ExitTapReplay.replay(tap.activity, tap.screenX, tap.screenY) else pendingExitTap[0] = tap
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            disposed[0] = true
            val tap = pendingExitTap[0]
            pendingExitTap[0] = null
            // 记下后很快（一两帧）就该离开组合；隔得太久说明弹窗其实留下了（调用方没移除它），这次点击已经过期
            if (tap != null && android.os.SystemClock.uptimeMillis() - tap.at < PendingExitTapMaxAgeMillis) {
                ExitTapReplay.replay(tap.activity, tap.screenX, tap.screenY)
            }
        }
    }

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

    fun deliverPendingExit() {
        if (pendingExitDelivered.value) return
        val action = pendingExitAction.value ?: return
        pendingExitDelivered.value = true
        pendingExitAction.value = null
        action()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // 旋转/重建在 onDestroy 之前先走 onSaveInstanceState：已接受退场（常规关闭或按钮退场）
            // 在暂停阶段提前交付回调，避免 rememberSaveable 把打开态写入 Bundle 导致复活或丢失连锁删除。
            if (event == Lifecycle.Event.ON_PAUSE && pendingExitAction.value != null && !pendingExitDelivered.value) {
                try {
                    deliverPendingExit()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (pendingExitIsDismiss.value) {
                        Log.e("AppAlertDialog", "Dialog dismissal failed on pause", failure)
                    } else {
                        actionFailed(failure)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 取消类按钮：先播放退场再执行按钮动作（按钮通常直接把弹窗从组合里移除，来不及播放退场）。
    // 动作执行后弹窗若仍在（例如校验未通过），再弹回来，不会卡在隐藏状态。
    val exitController = remember(enhanced, focusManager, lifecycleOwner) {
        DialogExitController { action ->
            if (dismissing.value) return@DialogExitController
            // 若调用本身正处于 ON_PAUSE（例如嵌套弹窗连锁关闭），直接同步交付以避免错失保存窗口
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    actionFailed(failure)
                }
                return@DialogExitController
            }
            dismissing.value = true
            pendingExitIsDismiss.value = false
            pendingExitAction.value = action
            pendingExitDelivered.value = false
            releaseInputFocus()
            dialogWindow[0].beginDialogDimExit()
            var restored = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runDialogExitAction(
                    animateExit = {
                        if (enhanced) playExit(MotionTokens.fadeOut(0.001f), MotionTokens.surfaceExit(0.001f))
                    },
                    action = { deliverPendingExit() },
                    afterAction = {
                        // 仍在组合中：动作没有关闭弹窗，恢复显示。
                        deliveredAt[0] = 0L
                        delay(80)
                        // 走到这里说明弹窗确实留下了（若被移除，协程在上面的 delay 里就被取消了）：
                        // 退场期间记下的那次点击不再转交下层页面，也不进入「退场尾段」
                        restored = true
                        pendingExitTap[0] = null
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
                            pendingExitAction.value = null
                            // 只有真正交付了关闭才进入「退场尾段」；动作没关弹窗、已恢复显示的，不能再按尾段处理遮罩按下与关闭请求
                            if (!restored) deliveredAt[0] = android.os.SystemClock.uptimeMillis()
                            dismissing.value = false
                        }
                    },
                )
            }
        }
    }

    /** 接受一次允许的关闭请求，先释放触摸并同步退场，再交付最新关闭回调。 */
    fun dismiss() {
        if (!currentDismissEnabled || dismissing.value || inTail()) return
        dismissing.value = true
        pendingExitIsDismiss.value = true
        pendingExitAction.value = { currentOnDismiss() }
        pendingExitDelivered.value = false
        releaseInputFocus()
        dialogWindow[0].beginDialogDimExit()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runDialogExitAction(
                animateExit = {
                    if (enhanced) {
                        playExit(MotionTokens.fadeOut(0.001f), MotionTokens.surfaceExit(0.001f))
                    } else {
                        playExit(tween(250, easing = FastOutLinearInEasing), tween(260, easing = FastOutLinearInEasing))
                    }
                },
                action = { deliverPendingExit() },
                afterAction = {},
                onFailure = { Log.e("AppAlertDialog", "Dialog dismissal failed", it) },
                cleanup = {
                    try {
                        dialogWindow[0]?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    } finally {
                        pendingExitAction.value = null
                        deliveredAt[0] = android.os.SystemClock.uptimeMillis()
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
        dialogFocus[0] = LocalFocusManager.current
        dialogView[0] = LocalView.current
        DialogWindowScrim(
            enhanced = enhanced,
            progress = { dimAlpha.value },
            onWindow = { dialogWindow[0] = it },
            onDismissRequest = { dismiss() },
            dismissEnabled = currentDismissEnabled,
            onScrimTap = { scrimTapDuringExit(it) },
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
                        .nestedPanelDim(window = { dialogWindow[0] }, shape = AppComponentShape)
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
                                        if (showButtonRow) {
                                            CompositionLocalProvider(LocalDialogBodyExit provides exitController) { text() }
                                        } else {
                                            CompositionLocalProvider(
                                                LocalDialogExit provides exitController,
                                                LocalDialogBodyExit provides exitController,
                                            ) { text() }
                                        }
                                    }
                                    if (showButtonRow) {
                                        val splitEnds = buttonArrangement == Arrangement.SpaceBetween && dismissButton != null
                                        val actionRowModifier = Modifier
                                            .fillMaxWidth()
                                            .staggeredEntrance(2, distance = 14.dp, stepMillis = 45L)
                                            .padding(top = 14.dp)
                                        if (splitEnds) {
                                            SplitEndsActionRow(
                                                modifier = actionRowModifier,
                                                leading = {
                                                    CompositionLocalProvider(LocalDialogExit provides exitController) {
                                                        dismissButton()
                                                    }
                                                },
                                                trailing = {
                                                    CompositionLocalProvider(LocalDialogExit provides null) {
                                                        confirmButton()
                                                    }
                                                },
                                            )
                                        } else {
                                            FlowRow(
                                                modifier = actionRowModifier,
                                                horizontalArrangement = buttonArrangement,
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                                itemVerticalAlignment = Alignment.CenterVertically,
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
                }

                if (topOverhang != null) {
                    // 超框内容按自然尺寸布局；卡片通常更高，因此不会改变弹窗整体高度。
                    // 保留真实测量尺寸而不是按 0x0 上报，确保封面等内容的点击区域可命中。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(horizontal = AppDialogContentHorizontalPadding)
                            .nestedPanelContentDim { dialogWindow[0] },
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

/** 退场期间在遮罩上记下的一次点击（屏幕坐标），等弹窗确认关闭后再转交给下层页面。 */
private class PendingExitTap(val activity: android.app.Activity, val screenX: Float, val screenY: Float, val at: Long)

/** 记下的点击超过这个时长还没等到弹窗离开组合，就视为弹窗留下了，点击作废。 */
private const val PendingExitTapMaxAgeMillis = 600L

/** 关闭回调交付后，窗口还可能多留一两帧：这段时间内的按下仍按「退场中」转交给下层页面。 */
private const val ExitTailMillis = 250L

/** 弹窗入场时从底边略微放大的幅度（停稳时缩放为 1）。 */
private const val DialogEnterScaleDelta = 0.06f

/** 弹窗按钮的「先退场再执行」入口（见 [AppAlertDialog] 的 exitController）。 */
internal class DialogExitController(val closeThen: (action: () -> Unit) -> Unit)

/** 当前按钮所在弹窗的退场控制器；为 null 时按钮直接执行动作。 */
internal val LocalDialogExit = staticCompositionLocalOf<DialogExitController?> { null }

/**
 * 两端对齐且支持窄屏/大字号自动折行的按钮行：
 * 单行可容纳时左端 [leading] 左对齐、右端 [trailing] 右对齐；
 * 超宽时整组折行右对齐，消除 `FlowRow + Spacer(weight)` 折行时产生的空行占位间距。
 */
@Composable
internal fun SplitEndsActionRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 4.dp,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                trailing()
            }
        },
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val leadingPlaceable = measurables[0].measure(childConstraints)
        val trailingPlaceable = measurables[1].measure(childConstraints)
        val hGap = horizontalSpacing.roundToPx()
        val vGap = verticalSpacing.roundToPx()
        val maxWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            leadingPlaceable.width + hGap + trailingPlaceable.width
        }
        val singleRow = leadingPlaceable.width == 0 ||
            trailingPlaceable.width == 0 ||
            leadingPlaceable.width + hGap + trailingPlaceable.width <= maxWidth
        if (singleRow) {
            val rowHeight = maxOf(leadingPlaceable.height, trailingPlaceable.height)
            layout(maxWidth, rowHeight) {
                if (leadingPlaceable.width > 0) {
                    leadingPlaceable.placeRelative(0, (rowHeight - leadingPlaceable.height) / 2)
                }
                if (trailingPlaceable.width > 0) {
                    trailingPlaceable.placeRelative(
                        (maxWidth - trailingPlaceable.width).coerceAtLeast(0),
                        (rowHeight - trailingPlaceable.height) / 2,
                    )
                }
            }
        } else {
            val totalHeight = leadingPlaceable.height + vGap + trailingPlaceable.height
            layout(maxWidth, totalHeight) {
                leadingPlaceable.placeRelative((maxWidth - leadingPlaceable.width).coerceAtLeast(0), 0)
                trailingPlaceable.placeRelative(
                    (maxWidth - trailingPlaceable.width).coerceAtLeast(0),
                    leadingPlaceable.height + vGap,
                )
            }
        }
    }
}

/**
 * 弹窗正文内「点一下就关闭弹窗」的条目所用的退场控制器（正文槽恒有，不论是否带按钮行）。
 * 与 [LocalDialogExit] 分开：正文里的 [com.tyranor.next.ui.common.DialogTextButton] 等
 * 仍按各自语义决定是否先退场，不会被连带改成先退场再执行。
 */
internal val LocalDialogBodyExit = staticCompositionLocalOf<DialogExitController?> { null }

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
