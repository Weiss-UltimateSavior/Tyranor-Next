package com.tyranor.next.ui.common

import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.passThroughTouches
import com.tyranor.next.ui.common.motion.syncDialogWindow
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.rememberEntranceState
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import android.view.WindowManager

/** 统一弹窗最大宽度：与游戏操作抽屉（ModalBottomSheet 的 SheetMaxWidth）一致，避免横屏/大屏拉伸过宽。 */
internal val AppDialogMaxWidth = 640.dp

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
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 卡片滑入行程 = 卡片高度 + 底部留白与导航栏（见 graphicsLayer）
    val dialogTravelPx = with(density) { 96.dp.toPx() }
    // 「普通」动效档沿用原版：以整屏高度为行程
    val windowHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val enhanced = MotionMode.enhanced
    // 弹窗所在的平台窗口：关闭开始时立即变为不可触摸，退场期间点击直接落到下层页面
    val dialogWindow = remember { arrayOfNulls<Window>(1) }
    val entrance = rememberEntranceState()
    // 正文区域高度上限随屏幕自适应：固定 420dp 会在长列表（如 Artemis 全版本）末条截断
    val textMaxHeight = (configuration.screenHeightDp * 0.62f).dp.coerceIn(280.dp, 560.dp)
    val textScrollState = rememberScrollState()
    val dimAlpha = remember { Animatable(0f) }
    val slideFraction = remember { Animatable(1f) }
    val dismissing = remember { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentDismissEnabled by rememberUpdatedState(dismissEnabled)
    val focusManager = LocalFocusManager.current

    // 取消类按钮：先播放退场再执行按钮动作（按钮通常直接把弹窗从组合里移除，来不及播放退场）。
    // 动作执行后弹窗若仍在（例如校验未通过），再弹回来，不会卡在隐藏状态。
    val exitController = remember {
        DialogExitController { action ->
            if (!enhanced) {
                action()
                return@DialogExitController
            }
            if (dismissing.value) return@DialogExitController
            dismissing.value = true
            focusManager.clearFocus(force = true)
            dialogWindow[0].passThroughTouches()
            scope.launch {
                launch { dimAlpha.animateTo(0f, MotionTokens.fadeOut(0.001f)) }
                slideFraction.animateTo(1f, MotionTokens.surfaceExit(0.001f))
                action()
                // 仍在组合中：动作没有关闭弹窗，恢复显示
                delay(80)
                dialogWindow[0]?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                dismissing.value = false
                launch { dimAlpha.animateTo(1f, MotionTokens.fadeIn(0.001f)) }
                slideFraction.animateTo(0f, MotionTokens.surfaceEnter(0.0001f))
            }
        }
    }

    /** 接受一次允许的关闭请求，先释放触摸并同步退场，再交付最新关闭回调。 */
    fun dismiss() {
        if (!currentDismissEnabled || dismissing.value) return
        dismissing.value = true
        focusManager.clearFocus(force = true)
        dialogWindow[0].passThroughTouches()
        scope.launch {
            if (enhanced) {
                // 退场：临界阻尼弹簧顺滑沉下（不回弹），遮罩同步淡出
                launch { dimAlpha.animateTo(0f, MotionTokens.fadeOut(0.001f)) }
                slideFraction.animateTo(1f, MotionTokens.surfaceExit(0.001f))
            } else {
                launch { dimAlpha.animateTo(0f, tween(250, easing = FastOutLinearInEasing)) }
                slideFraction.animateTo(1f, tween(260, easing = FastOutLinearInEasing))
            }
            currentOnDismiss()
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnBackPress = currentDismissEnabled, dismissOnClickOutside = currentDismissEnabled),
    ) {
        // 平台窗口自带的背景变暗（dim）原本由系统在窗口移除时另行淡出：卡片已经退场后还会残留一段灰色遮罩，
        // 且期间拦截点击。这里关闭窗口自带的进出场动画，并让窗口 dim 跟随同一个遮罩进度一起淡入淡出，
        // 停稳时的总暗度与原来一致。
        dialogWindow[0] = syncDialogWindow { dimAlpha.value }
        // 窗口变暗遮罩，点击关闭
        val scrimAlpha = 0.5f
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 遮罩透明度在绘制阶段读取：动画期间不逐帧重组整个弹窗
                .drawBehind { drawRect(Color.Black.copy(alpha = scrimAlpha * dimAlpha.value)) }
                .clickable(
                    enabled = currentDismissEnabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismiss() },
                ),
        ) {
            // 弹窗卡片：底部对齐，从屏底弹入
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
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
                    // 消费卡片区域点击，避免穿透到遮罩
                    .pointerInput(Unit) { detectTapGestures { } }
                    .then(if (dismissing.value) Modifier.clearAndSetSemantics {} else Modifier)
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
                        Column(Modifier.fillMaxWidth().skipEntranceOnDrag().padding(horizontal = 18.dp, vertical = 16.dp)) {
                            // 标题 / 正文 / 按钮依次轻轻升起（停稳后与原样一致）
                            Box(
                                Modifier.fillMaxWidth().staggeredEntrance(0, distance = 14.dp, stepMillis = 45L),
                                contentAlignment = Alignment.Center,
                            ) {
                                title()
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .staggeredEntrance(1, distance = 14.dp, stepMillis = 45L)
                                    // Measure the title/actions first so long text cannot consume
                                    // their space in landscape or with a large system font.
                                    .then(if (textScrollEnabled) Modifier.weight(1f, fill = false) else Modifier)
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
                                text()
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .staggeredEntrance(2, distance = 14.dp, stepMillis = 45L)
                                    .padding(top = 14.dp),
                                horizontalArrangement = buttonArrangement,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 取消位按钮（或单按钮弹窗的唯一按钮）点击时先退场再执行
                                CompositionLocalProvider(LocalDialogExit provides exitController) {
                                    dismissButton?.invoke()
                                }
                                CompositionLocalProvider(
                                    LocalDialogExit provides if (dismissButton == null) exitController else null,
                                ) {
                                    confirmButton()
                                }
                            }
                        }
                        }
                    }
                }
            }
            if (dismissing.value) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
            }
        }
    }

    // 进入动画：变暗渐入 + 底部 Q 弹弹入
    LaunchedEffect(Unit) {
        if (enhanced) {
            launch { dimAlpha.animateTo(1f, MotionTokens.fadeIn(0.001f)) }
            // Q 弹入场：卡片略冲过停位再落稳
            slideFraction.animateTo(
                targetValue = 0f,
                animationSpec = MotionTokens.surfaceEnter(0.0001f),
            )
        } else {
            launch { dimAlpha.animateTo(1f, tween(300, easing = LinearOutSlowInEasing)) }
            slideFraction.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f),
            )
        }
    }
}

/** 弹窗入场时从底边略微放大的幅度（停稳时缩放为 1）。 */
private const val DialogEnterScaleDelta = 0.06f

/** 弹窗按钮的「先退场再执行」入口（见 [AppAlertDialog] 的 exitController）。 */
internal class DialogExitController(val closeThen: (action: () -> Unit) -> Unit)

/** 当前按钮所在弹窗的退场控制器；为 null 时按钮直接执行动作。 */
internal val LocalDialogExit = staticCompositionLocalOf<DialogExitController?> { null }
