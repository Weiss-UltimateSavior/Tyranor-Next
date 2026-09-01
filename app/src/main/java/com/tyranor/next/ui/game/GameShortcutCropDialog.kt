package com.tyranor.next.ui.game

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import com.tyranor.next.core.game.shortcut.initialCropTransform
import com.tyranor.next.core.game.shortcut.writeShortcutCropBitmap
import com.tyranor.next.theme.NavWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor

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
    var transform by remember(imageUri) { mutableStateOf(CropTransform()) }
    var confirming by remember(imageUri) { mutableStateOf(false) }
    var cropFailed by remember(imageUri) { mutableStateOf(false) }

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
    LaunchedEffect(metrics) {
        if (metrics.isValid) transform = initialCropTransform(metrics)
    }
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
                } else {
                    val resultUri = croppedUri
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
        onDismissRequest = { if (!confirming) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !confirming,
            dismissOnClickOutside = !confirming,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            CropDialogCard(
                loadState = loadState,
                metrics = metrics,
                transform = transform,
                confirming = confirming,
                cropFailed = cropFailed,
                onViewportSizeChanged = { viewportSize = min(it.width, it.height).toFloat() },
                onTransform = { transform = it },
                onPickImage = onPickImage,
                onDismiss = onDismiss,
                onConfirm = ::confirmCrop,
            )
        }
    }
}

/** Renders the crop card while keeping the stateful dialog shell small. */
@Composable
private fun CropDialogCard(
    loadState: CropImageLoadState,
    metrics: CropImageMetrics,
    transform: CropTransform,
    confirming: Boolean,
    cropFailed: Boolean,
    onViewportSizeChanged: (androidx.compose.ui.unit.IntSize) -> Unit,
    onTransform: (CropTransform) -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).imePadding(),
        colors = CardDefaults.cardColors(containerColor = NavWhite),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.game_desktop_shortcut_crop_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.game_desktop_shortcut_crop_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CropDialogImage(
                loadState = loadState,
                metrics = metrics,
                transform = transform,
                onViewportSizeChanged = onViewportSizeChanged,
                onTransform = onTransform,
            )
            if (cropFailed) {
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
            )
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
) {
    when (loadState) {
        CropImageLoadState.Loading -> Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
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
            bitmap = loadState.bitmap,
            metrics = metrics,
            transform = transform,
            onViewportSizeChanged = onViewportSizeChanged,
            onTransform = onTransform,
        )
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPickImage, enabled = !confirming) {
            Text(
                stringResource(R.string.game_desktop_shortcut_crop_choose_image),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss, enabled = !confirming) {
            Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onConfirm, enabled = canConfirm && !confirming) {
            Text(
                text = if (confirming) {
                    stringResource(R.string.common_loading)
                } else {
                    stringResource(R.string.common_confirm)
                },
                style = MaterialTheme.typography.bodyMedium,
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
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .onSizeChanged(onViewportSizeChanged)
            .pointerInput(bitmap, metrics) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    onTransform(
                        applyCropGesture(
                            transform = currentTransform,
                            metrics = metrics,
                            centroidX = centroid.x,
                            centroidY = centroid.y,
                            panX = pan.x,
                            panY = pan.y,
                            zoom = zoom,
                        ),
                    )
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
        drawRect(
            color = Color.White.copy(alpha = 0.92f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Square),
        )
    }
}
