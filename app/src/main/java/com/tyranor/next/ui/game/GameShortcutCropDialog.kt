package com.tyranor.next.ui.game

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tyranor.next.R
import com.tyranor.next.core.game.shortcut.CropImageMetrics
import com.tyranor.next.core.game.shortcut.CropTransform
import com.tyranor.next.core.game.shortcut.applyCropGesture
import com.tyranor.next.core.game.shortcut.cropRenderRect
import com.tyranor.next.core.game.shortcut.deleteShortcutCropBitmap
import com.tyranor.next.core.game.shortcut.decodeShortcutCropBitmap
import com.tyranor.next.core.game.shortcut.constrainCropTransform
import com.tyranor.next.core.game.shortcut.writeShortcutCropBitmap
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassPanel
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.notice.NoticeHost
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.breathing
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor
import com.tyranor.next.ui.common.motion.DelayedProgress

/** Displays the square crop editor used to prepare a launcher shortcut icon. */
@Composable
internal fun GameShortcutCropDialog(
    imageUri: Uri,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onConfirm: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadState by remember(imageUri) { mutableStateOf<CropImageLoadState>(CropImageLoadState.Loading) }
    var viewportSize by remember(imageUri) { mutableFloatStateOf(0f) }
    // Save zoom relative to fit and pan in viewport units, never old-window pixels or the bitmap.
    var framing by rememberSaveable(imageUri, stateSaver = CropFramingSaver) { mutableStateOf(CropTransform()) }
    var confirming by remember(imageUri) { mutableStateOf(false) }
    var cropFailed by remember(imageUri) { mutableStateOf(false) }
    // 导出失败计数：每失败一次错误提示抖一下
    var cropFailCount by remember(imageUri) { mutableIntStateOf(0) }
    val overlay = rememberGameOverlayMotion(GameOverlay.ShortcutCrop)
    fun dismiss() {
        if (!confirming) overlay.dismiss(onDismiss)
    }

    LaunchedEffect(imageUri) {
        loadState = CropImageLoadState.Loading
        loadState = withContext(Dispatchers.IO) {
            decodeShortcutCropBitmap(context.applicationContext, imageUri)
                ?.let { CropImageLoadState.Ready(it) }
                ?: CropImageLoadState.Failed
        }
    }

    val bitmap = (loadState as? CropImageLoadState.Ready)?.bitmap
    val metrics = remember(bitmap, viewportSize) {
        CropImageMetrics(
            viewportSize = viewportSize,
            imageWidth = bitmap?.width?.toFloat() ?: 0f,
            imageHeight = bitmap?.height?.toFloat() ?: 0f,
        )
    }
    val transform = constrainCropTransform(
        CropTransform(framing.scale * metrics.minimumScale, framing.offsetX * viewportSize, framing.offsetY * viewportSize),
        metrics,
    )
    /** Exports the current bounded crop off the main thread and returns it to the caller. */
    fun confirmCrop() {
        val currentBitmap = bitmap ?: return
        if (!metrics.isValid || confirming) return
        confirming = true
        cropFailed = false
        val current = transform
        scope.launch {
            var croppedUri: Uri? = null
            var handedOff = false
            try {
                croppedUri = withContext(Dispatchers.IO) {
                    writeShortcutCropBitmap(context.applicationContext, currentBitmap, current, metrics)
                }
                if (croppedUri == null) {
                    cropFailed = true
                    cropFailCount++
                } else {
                    val resultUri = croppedUri
                    overlay.finishCropExport()
                    onConfirm(resultUri)
                    handedOff = true
                }
            } finally {
                confirming = false
                if (!handedOff) {
                    deleteShortcutCropBitmap(context.applicationContext, croppedUri)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !confirming,
            dismissOnClickOutside = !confirming,
        ),
    ) {
        overlay.BindWindow()
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Keep a wide landscape phone on its existing resting layout.
            val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
            val dismissFromMargin by rememberUpdatedState { dismiss() }
            if (tablet) {
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures { dismissFromMargin() }
                })
            }
            CropDialogCard(
                tablet = tablet,
                modifier = Modifier
                    .then(if (tablet) Modifier.safeDrawingPadding() else Modifier)
                    .padding(horizontal = 12.dp, vertical = 18.dp)
                    .then(overlay.panel(Modifier)),
                panelDim = overlay.panelDim(Modifier),
                loadState = loadState,
                metrics = metrics,
                transform = transform,
                confirming = confirming,
                cropFailed = cropFailed,
                cropFailCount = cropFailCount,
                onViewportSizeChanged = { viewportSize = min(it.width, it.height).toFloat() },
                onTransform = {
                    if (metrics.isValid) framing = CropTransform(
                        it.scale / metrics.minimumScale, it.offsetX / viewportSize, it.offsetY / viewportSize,
                    )
                },
                onPickImage = onPickImage,
                onDismiss = ::dismiss,
                onConfirm = ::confirmCrop,
            )
            NoticeHost(Modifier.matchParentSize())
        }
    }
}

private val CropFramingSaver = listSaver<CropTransform, Float>(
    save = { listOf(it.scale, it.offsetX, it.offsetY) },
    restore = { CropTransform(it[0], it[1], it[2]) },
)

/** Renders the crop card while keeping the stateful dialog shell small. */
@Composable
private fun CropDialogCard(
    tablet: Boolean,
    loadState: CropImageLoadState,
    metrics: CropImageMetrics,
    transform: CropTransform,
    confirming: Boolean,
    cropFailed: Boolean,
    cropFailCount: Int,
    onViewportSizeChanged: (androidx.compose.ui.unit.IntSize) -> Unit,
    onTransform: (CropTransform) -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    panelDim: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .then(if (tablet) Modifier.widthIn(max = 420.dp) else Modifier)
            .fillMaxWidth()
            .imePadding()
            .then(panelDim)
            .then(if (tablet) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier)
            .glassBorder(),
        colors = CardDefaults.cardColors(
            // 玻璃风格用高不透明度面板保证浮层内文字可读
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
            // 卡片内部编排：标题 → 说明 → 裁剪区 → 按钮依次升起（与统一弹窗同一节拍）
            ProvideEntrance {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.game_desktop_shortcut_crop_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.enterGamePanel(GameOverlay.ShortcutCrop, GamePanelPart.Title),
                )
                Text(
                    text = stringResource(R.string.game_desktop_shortcut_crop_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.enterGamePanel(GameOverlay.ShortcutCrop, GamePanelPart.Input),
                )
                CropDialogImage(
                    loadState = loadState,
                    metrics = metrics,
                    transform = transform,
                    onViewportSizeChanged = onViewportSizeChanged,
                    onTransform = onTransform,
                    // In a short tablet window measure the action row first. Give the square
                    // only the space left over; never crop its hit area or hide Confirm.
                    modifier = (if (tablet) Modifier.weight(1f, fill = false)
                        .align(Alignment.CenterHorizontally).aspectRatio(1f) else Modifier)
                        .enterGamePanel(GameOverlay.ShortcutCrop, GamePanelPart.Content),
                )
                // 导出失败提示：展开推开下方按钮、从模糊里清晰；再次失败时抖一下
                // 抖动挂在显隐容器外层：展开过程的裁切不会切掉抖动中的文字边缘
                ExpandVisibility(cropFailed, modifier = Modifier.shakeOnTrigger(cropFailCount)) {
                    Text(
                        text = stringResource(R.string.game_desktop_shortcut_crop_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                CropDialogActions(
                    confirming = confirming,
                    canConfirm = loadState is CropImageLoadState.Ready && metrics.isValid,
                    onPickImage = onPickImage,
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                    modifier = Modifier.enterGamePanel(GameOverlay.ShortcutCrop, GamePanelPart.Actions),
                )
            }
            }
        }
    }
}

/** Renders loading, failure, or interactive image content for the crop card. */
@Composable
private fun CropDialogImage(
    loadState: CropImageLoadState,
    metrics: CropImageMetrics,
    transform: CropTransform,
    onViewportSizeChanged: (androidx.compose.ui.unit.IntSize) -> Unit,
    onTransform: (CropTransform) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 加载中 / 失败 / 可编辑 之间模糊替换（按状态类别切换，换图重新加载时才触发）
    BlurCrossfade(loadState, modifier = modifier, contentKey = { it::class }) { state ->
    when (state) {
        CropImageLoadState.Loading -> Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            DelayedProgress { CircularProgressIndicator() }
        }

        CropImageLoadState.Failed -> Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.game_desktop_shortcut_crop_image_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        is CropImageLoadState.Ready -> CropPreview(
            bitmap = state.bitmap,
            metrics = metrics,
            transform = transform,
            onViewportSizeChanged = onViewportSizeChanged,
            onTransform = onTransform,
        )
    }
    }
}

/** Provides source selection, cancel, and confirm actions for the crop card. */
@Composable
private fun CropDialogActions(
    confirming: Boolean,
    canConfirm: Boolean,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DialogTextButton(
            text = stringResource(R.string.game_desktop_shortcut_crop_choose_image),
            onClick = onPickImage,
            enabled = !confirming,
        )
        Spacer(Modifier.weight(1f))
        DialogTextButton(
            text = stringResource(R.string.common_cancel),
            onClick = onDismiss,
            enabled = !confirming,
        )
        // 「确认 ↔ 处理中」模糊替换，处理中文字缓缓明灭；图片就绪、确认变为可用的一刻轻轻弹一下
        BlurCrossfade(
            targetState = confirming,
            modifier = Modifier.bounceOnChange(canConfirm, depth = 0.12f),
            contentAlignment = Alignment.Center,
        ) { busy ->
            DialogTextButton(
                text = if (busy) {
                    stringResource(R.string.common_loading)
                } else {
                    stringResource(R.string.common_confirm)
                },
                modifier = Modifier.breathing(active = busy),
                onClick = onConfirm,
                enabled = canConfirm && !confirming,
            )
        }
    }
}

private sealed interface CropImageLoadState {
    data object Loading : CropImageLoadState
    data object Failed : CropImageLoadState
    data class Ready(val bitmap: Bitmap) : CropImageLoadState
}

/** Draws the image and crop frame using the same bounded rectangle as export. */
@Composable
private fun CropPreview(
    bitmap: Bitmap,
    metrics: CropImageMetrics,
    transform: CropTransform,
    onViewportSizeChanged: (androidx.compose.ui.unit.IntSize) -> Unit,
    onTransform: (CropTransform) -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val currentTransform by rememberUpdatedState(transform)
    val renderRect = cropRenderRect(currentTransform, metrics)
    val guides = rememberCropGuides()
    Canvas(
        modifier = Modifier
            .testTag("shortcutCropPreview")
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(AppComponentShape)
            .background(Color.Black)
            .onSizeChanged(onViewportSizeChanged)
            .then(guides.observe(Modifier))
            .pointerInput(bitmap, metrics) {
                // MOVE events can arrive in a batch before the next composition. Accumulate
                // each delta here; the last rendered transform may be several events behind.
                var gestureTransform = currentTransform
                detectTransformGestures { centroid, pan, zoom, _ ->
                    gestureTransform = applyCropGesture(
                        transform = gestureTransform,
                        metrics = metrics,
                        centroidX = centroid.x,
                        centroidY = centroid.y,
                        panX = pan.x,
                        panY = pan.y,
                        zoom = zoom,
                    )
                    onTransform(gestureTransform)
                }
            },
    ) {
        val side = min(size.width, size.height)
        if (side <= 0f || renderRect == null) return@Canvas
        val destinationWidth = ceil(renderRect.width).toInt().coerceAtLeast(size.width.toInt())
        val destinationHeight = ceil(renderRect.height).toInt().coerceAtLeast(size.height.toInt())
        drawImage(
            image = imageBitmap,
            dstOffset = androidx.compose.ui.unit.IntOffset(
                floor(renderRect.left).toInt(),
                floor(renderRect.top).toInt(),
            ),
            dstSize = androidx.compose.ui.unit.IntSize(destinationWidth, destinationHeight),
        )
        with(guides) { drawThirds() }
        drawRect(
            color = Color.White.copy(alpha = 0.92f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Square),
        )
    }
}
