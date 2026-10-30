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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.tyranor.next.R
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
    saving: Boolean,
    saveFailed: Boolean,
    targetBounds: Rect?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val safeStep = step.coerceIn(coachSteps.indices)
    val contentDescription = stringResource(
        R.string.onboarding_progress,
        safeStep + 1,
        coachSteps.size,
    )
    val primary = MaterialTheme.colorScheme.primary
    var tooltipBounds by remember(safeStep) { mutableStateOf<Rect?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                // 让被讲解的真实控件保持清晰，避免整屏灰蒙蒙。
                compositingStrategy = CompositingStrategy.Offscreen
            },
        ) {
            // 优先使用真实控件上报的 root 坐标；首帧尚未测量时才使用保守回退值。
            val fallbackBounds = when (safeStep) {
                0 -> floatArrayOf(0.04f, 0.13f, 0.96f, 0.37f)
                1 -> floatArrayOf(0.29f, 0.02f, 0.51f, 0.10f)
                2 -> floatArrayOf(0.51f, 0.02f, 0.63f, 0.10f)
                3 -> floatArrayOf(0.63f, 0.02f, 0.76f, 0.10f)
                4 -> floatArrayOf(0.76f, 0.02f, 0.95f, 0.10f)
                5 -> floatArrayOf(0.04f, 0.13f, 0.96f, 0.37f)
                6 -> floatArrayOf(0.04f, 0.13f, 0.96f, 0.37f)
                else -> floatArrayOf(0.04f, 0.13f, 0.96f, 0.37f)
            }
            val targetLeft = targetBounds?.left ?: size.width * fallbackBounds[0]
            val targetTop = targetBounds?.top ?: size.height * fallbackBounds[1]
            val targetWidth = targetBounds?.width ?: size.width * (fallbackBounds[2] - fallbackBounds[0])
            val targetHeight = targetBounds?.height ?: size.height * (fallbackBounds[3] - fallbackBounds[1])
            val cornerRadius = minOf(18.dp.toPx(), targetHeight * 0.22f)
            val targetSize = Size(targetWidth, targetHeight)
            drawRect(Color.Black.copy(alpha = 0.46f))
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
                val targetRect = Rect(targetLeft, targetTop, targetLeft + targetWidth, targetTop + targetHeight)
                val connector = if (targetRect.bottom <= tooltip.top) {
                    Offset(tooltip.center.x.coerceIn(targetRect.left, targetRect.right), tooltip.top) to
                        Offset(targetRect.center.x, targetRect.bottom)
                } else if (targetRect.top >= tooltip.bottom) {
                    Offset(tooltip.center.x.coerceIn(targetRect.left, targetRect.right), tooltip.bottom) to
                        Offset(targetRect.center.x, targetRect.top)
                } else {
                    Offset(tooltip.left, tooltip.center.y) to Offset(targetRect.right, targetRect.center.y)
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
                    stringResource(coachSteps[safeStep].title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextColor,
                )
            }
            Text(
                stringResource(coachSteps[safeStep].body),
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
