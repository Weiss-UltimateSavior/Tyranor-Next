package com.tyranor.next.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import com.tyranor.next.R
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.CoachOverlayScrim
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.CoachAnchor
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton

private data class CoachStep(
    @StringRes val title: Int,
    @StringRes val body: Int,
)

private val coachSteps = listOf(
    CoachStep(R.string.onboarding_coach_home_title, R.string.onboarding_coach_home_body),
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
        val targetLeft = targetRect.left.coerceIn(0f, overlayWidthPx)
        val targetTop = targetRect.top.coerceIn(0f, overlayHeightPx)
        val targetRight = targetRect.right.coerceIn(targetLeft, overlayWidthPx)
        val targetBottom = targetRect.bottom.coerceIn(targetTop, overlayHeightPx)
        val targetWidth = targetRight - targetLeft
        val targetHeight = targetBottom - targetTop
        val targetLeftDp = with(density) { targetLeft.toDp() }
        val targetTopDp = with(density) { targetTop.toDp() }
        val targetRightDp = with(density) { targetRight.toDp() }
        val targetBottomDp = with(density) { targetBottom.toDp() }
        val targetHeightDp = with(density) { (targetBottom - targetTop).toDp() }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 让被讲解的真实控件保持清晰，避免整屏灰蒙蒙。
                    compositingStrategy = CompositingStrategy.Offscreen
                }
        ) {
            // 优先使用真实控件上报的 root 坐标；首帧尚未测量时才使用保守回退值。
            val cornerRadius = minOf(AppComponentCornerRadius.toPx(), targetHeight * 0.22f)
            drawRect(CoachOverlayScrim)
            if (showTarget) {
                val targetSize = Size(targetWidth, targetHeight)
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(targetLeft, targetTop),
                    size = targetSize,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
                    blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    color = primary.copy(alpha = 0.86f),
                    topLeft = Offset(targetLeft, targetTop),
                    size = targetSize,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                )
                tooltipBounds?.let {
                    val tooltip = Rect(
                        left = it.left - overlayOrigin.x,
                        top = it.top - overlayOrigin.y,
                        right = it.right - overlayOrigin.x,
                        bottom = it.bottom - overlayOrigin.y,
                    )
                    val drawnTarget = Rect(targetLeft, targetTop, targetRight, targetBottom)
                    val horizontalOverlap = drawnTarget.left <= tooltip.right &&
                        drawnTarget.right >= tooltip.left
                    val connector = when {
                        // 目标在提示卡片上方：两端分别落在提示卡片上边和目标下边，
                        // 每个端点都限制在自身矩形内，避免连线悬空在卡片外。
                        drawnTarget.bottom <= tooltip.top -> {
                            if (horizontalOverlap) {
                                Offset(tooltip.center.x, tooltip.top) to
                                    Offset(drawnTarget.center.x, drawnTarget.bottom)
                            } else {
                                Offset(
                                    drawnTarget.center.x.coerceIn(tooltip.left, tooltip.right),
                                    tooltip.top,
                                ) to Offset(
                                    tooltip.center.x.coerceIn(drawnTarget.left, drawnTarget.right),
                                    drawnTarget.bottom,
                                )
                            }
                        }
                        // 目标在提示卡片下方。
                        drawnTarget.top >= tooltip.bottom -> {
                            if (horizontalOverlap) {
                                Offset(tooltip.center.x, tooltip.bottom) to
                                    Offset(drawnTarget.center.x, drawnTarget.top)
                            } else {
                                Offset(
                                    drawnTarget.center.x.coerceIn(tooltip.left, tooltip.right),
                                    tooltip.bottom,
                                ) to Offset(
                                    tooltip.center.x.coerceIn(drawnTarget.left, drawnTarget.right),
                                    drawnTarget.top,
                                )
                            }
                        }
                        // 两者高度相交时，按左右关系连接相邻边缘。
                        drawnTarget.left >= tooltip.right -> {
                            Offset(tooltip.right, tooltip.center.y) to
                                Offset(drawnTarget.left, drawnTarget.center.y)
                        }
                        drawnTarget.right <= tooltip.left -> {
                            Offset(tooltip.left, tooltip.center.y) to
                                Offset(drawnTarget.right, drawnTarget.center.y)
                        }
                        else -> {
                            Offset(tooltip.center.x, tooltip.center.y) to
                                Offset(drawnTarget.center.x, drawnTarget.center.y)
                        }
                    }
                    drawLine(
                        color = primary.copy(alpha = 0.72f),
                        start = connector.first,
                        end = connector.second,
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

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
                .padding(top = maxHeight * if (safeStep == 0) 0.40f else 0.54f)
                .then(if (AppThemeColors.isMilkGlass) Modifier else Modifier.shadow(10.dp, AppComponentShape))
                .verticalScroll(rememberScrollState())
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    if (tooltipBounds != bounds) tooltipBounds = bounds
                }
                .background(NavWhite, AppComponentShape)
                .glassBorder()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(9.dp).background(primary, CircleShape))
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = coachTextColor,
                )
            }
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = coachTextColor,
            )
            if (saveFailed) {
                Text(
                    stringResource(R.string.onboarding_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        Box(
                            Modifier
                                .size(if (index == safeStep) 16.dp else 7.dp)
                                .background(
                                    if (index == safeStep) primary else coachTextColor.copy(alpha = 0.24f),
                                    CircleShape,
                                ),
                        )
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                enabled = !saving,
                onClick = onNext,
            )
        }
    }
}
