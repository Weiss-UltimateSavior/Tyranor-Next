package com.tyranor.next.ui.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tyranor.next.R
import com.tyranor.next.theme.NavWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor

private const val CROP_SOURCE_MAX_DIMENSION_PX = 2048

/** 自定义桌面快捷方式图标的正方形裁切器。 */
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
            decodeCropBitmap(context.applicationContext, imageUri)
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
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

                    when (val state = loadState) {
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
                            bitmap = state.bitmap,
                            metrics = metrics,
                            transform = transform,
                            onViewportSizeChanged = { viewportSize = min(it.width, it.height).toFloat() },
                            onTransform = { transform = it },
                        )
                    }

                    if (cropFailed) {
                        Text(
                            text = stringResource(R.string.game_desktop_shortcut_crop_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

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
                        TextButton(
                            onClick = {
                                val currentBitmap = bitmap ?: return@TextButton
                                if (!metrics.isValid || confirming) return@TextButton
                                confirming = true
                                cropFailed = false
                                val current = transform
                                scope.launch {
                                    val croppedUri = withContext(Dispatchers.IO) {
                                        writeCroppedShortcutIcon(context.applicationContext, currentBitmap, current, metrics)
                                    }
                                    confirming = false
                                    if (croppedUri == null) {
                                        cropFailed = true
                                    } else {
                                        onConfirm(croppedUri)
                                    }
                                }
                            },
                            enabled = bitmap != null && metrics.isValid && !confirming,
                        ) {
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
            }
        }
    }
}

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

private sealed interface CropImageLoadState {
    data object Loading : CropImageLoadState
    data object Failed : CropImageLoadState
    data class Ready(val bitmap: Bitmap) : CropImageLoadState
}

private fun decodeCropBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openCropInputStream(context, uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    val maxDimension = max(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (maxDimension / sampleSize > CROP_SOURCE_MAX_DIMENSION_PX && sampleSize < (1 shl 30)) {
        sampleSize = sampleSize shl 1
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
    val decoded = openCropInputStream(context, uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: return@runCatching null
    applyExifOrientation(context, uri, decoded)
}.getOrNull()

private fun openCropInputStream(context: Context, uri: Uri): java.io.InputStream? {
    val resolverStream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    if (resolverStream != null) return resolverStream
    val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { File(path).inputStream() }.getOrNull()
}

private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        openCropInputStream(context, uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

private fun writeCroppedShortcutIcon(
    context: Context,
    bitmap: Bitmap,
    transform: CropTransform,
    metrics: CropImageMetrics,
): Uri? {
    val sourceRect = cropSourceRect(transform, metrics) ?: return null
    var outputFile: File? = null
    return try {
        val cropped = Bitmap.createBitmap(
            bitmap,
            sourceRect.left,
            sourceRect.top,
            sourceRect.size,
            sourceRect.size,
        )
        outputFile = File.createTempFile("shortcut_crop_", ".png", context.cacheDir)
        FileOutputStream(outputFile).use { output ->
            check(cropped.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        if (cropped !== bitmap) cropped.recycle()
        Uri.fromFile(outputFile)
    } catch (_: Throwable) {
        outputFile?.delete()
        null
    }
}
