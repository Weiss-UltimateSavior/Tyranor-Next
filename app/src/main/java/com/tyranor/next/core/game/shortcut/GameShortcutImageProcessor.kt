package com.tyranor.next.core.game.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

private const val CROP_SOURCE_MAX_DIMENSION_PX = 2048

/** Decodes an image for the shortcut cropper, including EXIF orientation correction. */
internal fun decodeShortcutCropBitmap(context: Context, uri: Uri): Bitmap? {
    var decoded: Bitmap? = null
    var oriented: Bitmap? = null
    var returned = false
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openShortcutCropInputStream(context, uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

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
        decoded = openShortcutCropInputStream(context, uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        oriented = applyShortcutCropExifOrientation(context, uri, decoded)
        returned = true
        oriented
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally {
        if (!returned && oriented != null && !oriented.isRecycled) oriented.recycle()
        if (decoded != null && decoded !== oriented && !decoded.isRecycled) decoded.recycle()
    }
}

/** Writes the selected square crop to a private temporary PNG for ShortcutManager. */
internal fun writeShortcutCropBitmap(
    context: Context,
    bitmap: Bitmap,
    transform: CropTransform,
    metrics: CropImageMetrics,
): Uri? {
    val sourceRect = cropSourceRect(transform, metrics) ?: return null
    var outputFile: File? = null
    var committed = false
    return try {
        val cropped = Bitmap.createBitmap(
            bitmap,
            sourceRect.left,
            sourceRect.top,
            sourceRect.size,
            sourceRect.size,
        )
        try {
            clearPreviousShortcutCropFiles(context.cacheDir)
            outputFile = File.createTempFile("shortcut_crop_", ".png", context.cacheDir)
            FileOutputStream(outputFile).use { output ->
                check(cropped.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            val resultUri = Uri.fromFile(outputFile)
            committed = true
            resultUri
        } finally {
            if (cropped !== bitmap) cropped.recycle()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally {
        if (!committed) outputFile?.delete()
    }
}

/** Deletes one generated crop file after it has been consumed by ShortcutManager. */
internal fun deleteShortcutCropBitmap(context: Context, uri: Uri?) {
    if (uri?.scheme != "file") return
    val file = uri.path?.let(::File) ?: return
    val cacheDir = runCatching { context.cacheDir.canonicalFile }.getOrNull() ?: return
    val cropFile = runCatching { file.canonicalFile }.getOrNull() ?: return
    val isGeneratedCrop = cropFile.name.startsWith("shortcut_crop_") && cropFile.name.endsWith(".png")
    val isInCache = cropFile.path.startsWith(cacheDir.path + File.separator)
    if (isGeneratedCrop && isInCache) {
        runCatching { cropFile.delete() }
    }
}

/** Opens a crop source through ContentResolver and falls back to its local file path for file/empty schemes. */
private fun openShortcutCropInputStream(context: Context, uri: Uri): InputStream? {
    val resolverStream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    if (resolverStream != null) return resolverStream
    val scheme = uri.scheme
    if (!scheme.equals("file", ignoreCase = true) && !scheme.isNullOrBlank()) return null
    val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { File(path).inputStream() }.getOrNull()
}

/** Applies the source photo's EXIF orientation without changing the crop math contract. */
private fun applyShortcutCropExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = try {
        openShortcutCropInputStream(context, uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
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
    val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (transformed !== bitmap && !bitmap.isRecycled) {
        bitmap.recycle()
    }
    return transformed
}

/** Deletes only PNGs generated by the shortcut cropper, leaving unrelated cache files intact. */
private fun clearPreviousShortcutCropFiles(cacheDir: File) {
    cacheDir.listFiles { file ->
        file.isFile && file.name.startsWith("shortcut_crop_") && file.name.endsWith(".png")
    }?.forEach { file ->
        runCatching { file.delete() }
    }
}
