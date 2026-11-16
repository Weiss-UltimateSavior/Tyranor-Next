package com.tyranor.next.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import com.tyranor.next.R
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.CoachAnchor
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.rememberEntranceState
import com.tyranor.next.ui.common.motion.staggeredEntrance

private data class CoachStep(
    @StringRes val title: Int,
    @StringRes val body: Int,
)

private val coachSteps = listOf(
    CoachStep(R.string.onboarding_coach_home_title, R.string.onboarding_coach_home_body),
    CoachStep(R.string.onboarding_coach_games_folder_title, R.string.onboarding_coach_games_folder_body),
    CoachStep(R.string.onboarding_coach_games_title, R.string.onboarding_coach_games_body),
    CoachStep(R.string.onboarding_coach_games_search_title, R.string.onboarding_coach_games_search_body),
    CoachStep(R.string.onboarding_coach_games_cover_title, R.string.onboarding_coach_games_cover_body),
    CoachStep(R.string.onboarding_coach_games_scan_title, R.string.onboarding_coach_games_scan_body),
    CoachStep(R.string.onboarding_coach_engine_title, R.string.onboarding_coach_engine_body),
    CoachStep(R.string.onboarding_coach_settings_title, R.string.onboarding_coach_settings_body),
)

private fun coachFallbackRect(width: Float, height: Float): Rect {
    val fractions = floatArrayOf(0.04f, 0.13f, 0.96f, 0.37f)
    return Rect(
        left = width * fractions[0],
        top = height * fractions[1],
        right = width * fractions[2],
        bottom = height * fractions[3],
    )
}

private fun Modifier.consumeCoachTouches(step: Int): Modifier = pointerInput(step) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * 主界面上的首次使用教练层：背景仍是真实 MainNavigation，提示通过聚焦框、连接线和气泡呈现。
 * [step] 只保存在 UI 内存态，完成标记仍由 AppSettingsStore 持久化。
 */
@Composable
fun OnboardingCoachOverlay(
    step: Int,
    homeStyle: String = AppSettingsStore.DEFAULT_HOME_STYLE,
    saving: Boolean,
    saveFailed: Boolean,
    targetBounds: Rect?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val safeStep = step.coerceIn(coachSteps.indices)
    val titleRes = when {
        safeStep != CoachAnchor.HOME -> coachSteps[safeStep].title
        homeStyle == AppSettingsStore.HOME_STYLE_WEB -> R.string.onboarding_coach_home_web_title
        else -> coachSteps[safeStep].title
    }
    val bodyRes = when {
        safeStep != CoachAnchor.HOME -> coachSteps[safeStep].body
        homeStyle == AppSettingsStore.HOME_STYLE_WEB -> R.string.onboarding_coach_home_web_body
        else -> coachSteps[safeStep].body
    }
    val contentDescription = stringResource(
        R.string.onboarding_progress,
        safeStep + 1,
        coachSteps.size,
    )
    val primary = MaterialTheme.colorScheme.primary
    val coachTextColor = if (AppThemeColors.isMilkGlass) TextColor else PageTextColor
    val onboardingPaneTitle = stringResource(R.string.onboarding_title)
    var tooltipBounds by remember(safeStep) { mutableStateOf<Rect?>(null) }
    var overlayBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    val spotlight = rememberCoachSpotlight()
    val displayedBodyRes = coachBodyForStep(bodyRes)
    val cardPlacement = rememberCoachCardPlacement(safeStep)
    val isLastStep = safeStep == coachSteps.lastIndex
    // 引导层自己的入场节拍：首次出现时提示卡片与其中各行依次弹入
    ProvideEntrance(rememberEntranceState()) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics {
                // 将引导暴露为独立的 TalkBack 遍历组，保留真实高亮控件的语义，不再清空整棵主界面语义树。
                dialog()
                paneTitle = onboardingPaneTitle
                isTraversalGroup = true
            }
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                if (overlayBoundsInRoot != bounds) overlayBoundsInRoot = bounds
            },
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val overlayWidthPx = with(density) { maxWidth.toPx() }
        val overlayHeightPx = with(density) { maxHeight.toPx() }
        // 目标控件和提示气泡上报的是 root 坐标；横屏平板的引导层可能带有
        // 系统栏/侧栏偏移，因此绘制前统一转换到引导层自己的局部坐标。
        val overlayOrigin = overlayBoundsInRoot?.let { Offset(it.left, it.top) } ?: Offset.Zero
        // 游戏页四个顶部按钮在横屏平板重排时不能使用比例回退矩形；
        // 等待真实控件上报后再绘制，避免高亮落在工具栏中间的错误位置。
        val needsMeasuredTarget = safeStep != CoachAnchor.HOME
        val hasMeasuredTarget = targetBounds?.isEmpty == false
        val showTarget = !needsMeasuredTarget || hasMeasuredTarget
        val targetRect = when {
            !showTarget -> Rect.Zero
            targetBounds == null -> coachFallbackRect(overlayWidthPx, overlayHeightPx)
            else -> Rect(
                left = targetBounds.left - overlayOrigin.x,
                top = targetBounds.top - overlayOrigin.y,
                right = targetBounds.right - overlayOrigin.x,
                bottom = targetBounds.bottom - overlayOrigin.y,
            )
        }
        // 文件夹按钮四周留出呼吸空间，避免高亮描边紧贴按钮边缘。
        val targetPadding = if (safeStep == CoachAnchor.GAME_FOLDER && hasMeasuredTarget) {
            with(density) { 8.dp.toPx() }
        } else {
            0f
        }
        val targetLeft = (targetRect.left - targetPadding).coerceIn(0f, overlayWidthPx)
        val targetTop = (targetRect.top - targetPadding).coerceIn(0f, overlayHeightPx)
        val targetRight = (targetRect.right + targetPadding).coerceIn(targetLeft, overlayWidthPx)
        val targetBottom = (targetRect.bottom + targetPadding).coerceIn(targetTop, overlayHeightPx)
        val targetLeftDp = with(density) { targetLeft.toDp() }
        val targetTopDp = with(density) { targetTop.toDp() }
        val targetRightDp = with(density) { targetRight.toDp() }
        val targetBottomDp = with(density) { targetBottom.toDp() }
        val targetHeightDp = with(density) { (targetBottom - targetTop).toDp() }
        val safeTop = WindowInsets.safeDrawing.getTop(density)
        val safeBottom = WindowInsets.safeDrawing.getBottom(density)
        val tooltipPlacement = if (safeStep == CoachAnchor.GAME_FOLDER && showTarget) {
            Modifier.layout { measurable, constraints ->
                val gap = 20.dp.roundToPx()
                val edge = 16.dp.roundToPx()
                val upperEdge = (safeTop + edge).coerceAtMost(constraints.maxHeight)
                val lowerEdge = (constraints.maxHeight - safeBottom - edge).coerceAtLeast(upperEdge)
                val belowStart = (targetBottom.roundToInt() + gap).coerceIn(upperEdge, lowerEdge)
                val aboveEnd = (targetTop.roundToInt() - gap).coerceIn(upperEdge, lowerEdge)
                val belowSpace = lowerEdge - belowStart
                val aboveSpace = aboveEnd - upperEdge
                val card = measurable.measure(
                    constraints.copy(minHeight = 0, maxHeight = maxOf(aboveSpace, belowSpace).coerceAtLeast(1)),
                )
                // 优先紧贴按钮下方；放不下时转到上方，卡片仍可在可用空间内滚动。
                val cardTop = if (card.height <= belowSpace || belowSpace >= aboveSpace) {
                    belowStart
                } else {
                    aboveEnd - card.height
                }
                layout(card.width, constraints.maxHeight) { card.placeRelative(0, cardTop) }
            }
        } else {
            cardPlacement.position(Modifier, maxHeight, overlayHeightPx)
        }

        spotlight.Draw(
            safeStep, Rect(targetLeft, targetTop, targetRight, targetBottom), showTarget,
            primary, overlayOrigin, tooltipBounds = { tooltipBounds },
        )

        // Canvas 仍负责绘制整屏遮罩，但不参与命中测试。外围四块拦截区阻止误触，
        // 高亮矩形中间保持为空，事件才能继续传递到真实控件。
        if (!showTarget) {
            Box(Modifier.fillMaxSize().consumeCoachTouches(safeStep))
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(targetTopDp)
                    .consumeCoachTouches(safeStep),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset(y = targetBottomDp)
                    .height((maxHeight - targetBottomDp).coerceAtLeast(0.dp))
                    .consumeCoachTouches(safeStep),
            )
            Box(
                Modifier
                    .offset(y = targetTopDp)
                    .width(targetLeftDp)
                    .height(targetHeightDp)
                    .consumeCoachTouches(safeStep),
            )
            Box(
                Modifier
                    .offset(x = targetRightDp, y = targetTopDp)
                    .width((maxWidth - targetRightDp).coerceAtLeast(0.dp))
                    .height(targetHeightDp)
                    .consumeCoachTouches(safeStep),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .then(tooltipPlacement)
                // 连接线锚点取入场图层之外的最终布局矩形，避免沿用隐藏态坐标。
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    if (tooltipBounds != bounds) tooltipBounds = bounds
                }
                // 首次出现：卡片整体从下方弹入（比卡内各行行程更长，主次分明）
                .staggeredEntrance(0, distance = 28.dp)
                // 换步落点反馈：卡片轻轻压一下再回弹
                .bounceOnChange(safeStep, depth = 0.05f)
                .then(if (AppThemeColors.isMilkGlass) Modifier else Modifier.shadow(10.dp, AppComponentShape))
                .verticalScroll(rememberScrollState())
                .background(NavWhite, AppComponentShape)
                .glassBorder()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.staggeredEntrance(1, distance = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 标题前的主题色圆点：换步时弹一下，提示「这是新的一步」
                Box(Modifier.bounceOnChange(safeStep, depth = 0.35f).size(9.dp).background(primary, CircleShape))
                // 标题模糊替换（内容永不瞬间替换）；卡片高度随新内容弹簧过渡
                BlurCrossfade(targetState = titleRes, label = "coachTitle") { res ->
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = coachTextColor,
                    )
                }
            }
            // 正文晚标题一拍模糊替换（错峰）
            BlurCrossfade(
                targetState = displayedBodyRes,
                modifier = Modifier.staggeredEntrance(2, distance = 12.dp),
                label = "coachBody",
            ) { res ->
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    color = coachTextColor,
                )
            }
            // 保存失败提示：展开推开下方内容，重试时原路收回
            ExpandVisibility(visible = saveFailed, label = "coachSaveFailed") {
                Text(
                    stringResource(R.string.onboarding_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                modifier = Modifier.staggeredEntrance(3, distance = 12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics {
                        this.contentDescription = contentDescription
                        liveRegion = LiveRegionMode.Polite
                    },
                ) {
                    coachSteps.indices.forEach { index ->
                        CoachProgressDot(index, index == safeStep, primary, coachTextColor.copy(alpha = 0.24f))
                    }
                }
                Spacer(Modifier.weight(1f))
                NoRippleTextButton(
                    text = stringResource(R.string.onboarding_skip),
                    modifier = Modifier.heightIn(min = 40.dp),
                    enabled = !saving,
                    onClick = onSkip,
                )
            }
            NoRippleButton(
                text = stringResource(
                    if (safeStep == coachSteps.lastIndex) R.string.onboarding_start else R.string.onboarding_next,
                ),
                modifier = Modifier
                    .coachNextAction(isLastStep, saving, saveFailed)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                enabled = !saving,
                onClick = onNext,
            )
        }
    }
    }
}
