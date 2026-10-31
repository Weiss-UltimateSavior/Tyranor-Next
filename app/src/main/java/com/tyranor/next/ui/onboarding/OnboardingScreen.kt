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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.tyranor.next.R
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton

private data class CoachStep(
    @StringRes val title: Int,
    @StringRes val body: Int,
)

internal object CoachAnchor {
    const val HOME = 0
    const val GAME_ADD = 1
    const val GAME_SEARCH = 2
    const val GAME_COVER = 3
    const val GAME_SCAN = 4
    const val ENGINE = 5
    const val SETTINGS = 6

    fun pageOf(index: Int): Int = when (index) {
        HOME -> 0
        in GAME_ADD..GAME_SCAN -> 1
        ENGINE -> 2
        else -> 3
    }
}

private val coachSteps = listOf(
    CoachStep(R.string.onboarding_coach_home_title, R.string.onboarding_coach_home_body),
    CoachStep(R.string.onboarding_coach_games_title, R.string.onboarding_coach_games_body),
    CoachStep(R.string.onboarding_coach_games_search_title, R.string.onboarding_coach_games_search_body),
    CoachStep(R.string.onboarding_coach_games_cover_title, R.string.onboarding_coach_games_cover_body),
    CoachStep(R.string.onboarding_coach_games_scan_title, R.string.onboarding_coach_games_scan_body),
    CoachStep(R.string.onboarding_coach_engine_title, R.string.onboarding_coach_engine_body),
    CoachStep(R.string.onboarding_coach_settings_title, R.string.onboarding_coach_settings_body),
)

private fun coachFallbackRect(step: Int, width: Float, height: Float): Rect {
    val fractions = when (step) {
        0 -> floatArrayOf(0.04f, 0.13f, 0.96f, 0.37f)
        1 -> floatArrayOf(0.29f, 0.02f, 0.51f, 0.10f)
        2 -> floatArrayOf(0.51f, 0.02f, 0.63f, 0.10f)
        3 -> floatArrayOf(0.63f, 0.02f, 0.76f, 0.10f)
        4 -> floatArrayOf(0.76f, 0.02f, 0.95f, 0.10f)
        else -> floatArrayOf(0.04f, 0.13f, 0.96f, 0.37f)
    }
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

internal fun Modifier.coachAnchor(enabled: Boolean, onBounds: (Rect) -> Unit): Modifier =
    if (enabled) onGloballyPositioned { onBounds(it.boundsInRoot()) } else this

@Composable
fun OnboardingStorageError(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        ),
    ) {
        AppTopBar(title = stringResource(R.string.onboarding_title))
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_save_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = TextColor,
            )
            NoRippleButton(text = stringResource(R.string.onboarding_retry), onClick = onRetry)
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
        homeStyle == AppSettingsStore.HOME_STYLE_CATEGORY -> R.string.onboarding_coach_home_category_title
        else -> coachSteps[safeStep].title
    }
    val bodyRes = when {
        safeStep != CoachAnchor.HOME -> coachSteps[safeStep].body
        homeStyle == AppSettingsStore.HOME_STYLE_WEB -> R.string.onboarding_coach_home_web_body
        homeStyle == AppSettingsStore.HOME_STYLE_CATEGORY -> R.string.onboarding_coach_home_category_body
        else -> coachSteps[safeStep].body
    }
    val contentDescription = stringResource(
        R.string.onboarding_progress,
        safeStep + 1,
        coachSteps.size,
    )
    val primary = MaterialTheme.colorScheme.primary
    var tooltipBounds by remember(safeStep) { mutableStateOf<Rect?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val overlayWidthPx = with(density) { maxWidth.toPx() }
        val overlayHeightPx = with(density) { maxHeight.toPx() }
        val needsMeasuredTarget = safeStep == CoachAnchor.ENGINE || safeStep == CoachAnchor.SETTINGS
        val hasMeasuredTarget = targetBounds?.isEmpty == false
        val showTarget = !needsMeasuredTarget || hasMeasuredTarget
        val targetRect = if (showTarget) {
            targetBounds ?: coachFallbackRect(safeStep, overlayWidthPx, overlayHeightPx)
        } else {
            Rect.Zero
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
            val cornerRadius = minOf(18.dp.toPx(), targetHeight * 0.22f)
            drawRect(Color.Black.copy(alpha = 0.46f))
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
                tooltipBounds?.let { tooltip ->
                    val drawnTarget = Rect(targetLeft, targetTop, targetRight, targetBottom)
                    val connector = if (drawnTarget.bottom <= tooltip.top) {
                        Offset(tooltip.center.x.coerceIn(drawnTarget.left, drawnTarget.right), tooltip.top) to
                            Offset(drawnTarget.center.x, drawnTarget.bottom)
                    } else if (drawnTarget.top >= tooltip.bottom) {
                        Offset(tooltip.center.x.coerceIn(drawnTarget.left, drawnTarget.right), tooltip.bottom) to
                            Offset(drawnTarget.center.x, drawnTarget.top)
                    } else {
                        Offset(tooltip.left, tooltip.center.y) to Offset(drawnTarget.right, drawnTarget.center.y)
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
                .shadow(10.dp, AppComponentShape)
                .verticalScroll(rememberScrollState())
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    if (tooltipBounds != bounds) tooltipBounds = bounds
                }
                .background(NavWhite, AppComponentShape)
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
                    color = TextColor,
                )
            }
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = TextColor,
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
                                    if (index == safeStep) primary else TextColor.copy(alpha = 0.24f),
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
