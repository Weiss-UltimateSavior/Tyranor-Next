package com.tyranor.next.ui.common.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.tyranor.next.ui.common.boxBlurArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 高级玻璃的封面拼贴模糊底图（复刻参考图的「被模糊的壁纸」）。
 *
 * 做法：取游戏库最多 [MosaicTileCount] 张封面（不足则循环复用）拼成 2×3 马赛克小图，
 * 再对小图做多轮盒式模糊（复用 [boxBlurArgb]，全 API 一致、无 RenderEffect），
 * 由根背景按 cover 缩放铺满。小图模糊后放大天然平滑，成本一次性、无逐帧开销。
 *
 * 封面均为本地缓存文件（`file://`）或用户自选（`content://`），经 `ContentResolver` 解码；
 * 解码失败/无封面的瓦片保留底色，整张失败（无封面库）时返回 null，背景退化为主题色软色斑。
 */
private const val MosaicTileCount = 6
private const val MosaicColumns = 2
private const val MosaicRows = 3
private const val MosaicWidth = 288
private const val MosaicHeight = 432
private const val AmbientBlurRadius = 10
private const val MosaicBaseColor = 0xFF101216.toInt()

/** 生成/复用封面拼贴模糊底图；无可用封面时返回 null。 */
internal fun buildAmbientBackdrop(context: Context, coverUris: List<String>): ImageBitmap? {
    val uris = coverUris.filter { it.isNotBlank() }.distinct().take(MosaicTileCount)
    if (uris.isEmpty()) return null
    val cacheKey = uris.joinToString("|")
    AmbientBackdropCache.get(cacheKey)?.let { return it }

    val mosaic = Bitmap.createBitmap(MosaicWidth, MosaicHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mosaic)
    canvas.drawColor(MosaicBaseColor)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val tileWidth = MosaicWidth / MosaicColumns
    val tileHeight = MosaicHeight / MosaicRows
    var decodedCount = 0
    repeat(MosaicTileCount) { index ->
        val bitmap = decodeCover(context, uris[index % uris.size], min(tileWidth, tileHeight))
            ?: return@repeat
        decodedCount += 1
        val column = index % MosaicColumns
        val row = index / MosaicColumns
        val dst = Rect(
            column * tileWidth,
            row * tileHeight,
            column * tileWidth + tileWidth,
            row * tileHeight + tileHeight,
        )
        canvas.drawBitmap(bitmap, centerCropSrc(bitmap, tileWidth, tileHeight), dst, paint)
        bitmap.recycle()
    }
    // 一张都没解出来时返回 null，让背景走主题色软色斑兜底，而不是一张纯底色图
    if (decodedCount == 0) {
        mosaic.recycle()
        return null
    }

    val pixels = IntArray(MosaicWidth * MosaicHeight)
    mosaic.getPixels(pixels, 0, MosaicWidth, 0, 0, MosaicWidth, MosaicHeight)
    boxBlurArgb(pixels, MosaicWidth, MosaicHeight, radius = AmbientBlurRadius, passes = 3)
    mosaic.setPixels(pixels, 0, MosaicWidth, 0, 0, MosaicWidth, MosaicHeight)

    val image = mosaic.asImageBitmap()
    AmbientBackdropCache.put(cacheKey, image)
    return image
}

/** 离开高级玻璃时释放缓存（避免常驻一张位图）。 */
internal fun clearAmbientBackdropCache() {
    AmbientBackdropCache.clear()
}

/**
 * 组合层入口：按封面集合生成底图（后台线程），[enabled] 为 false 时不生成并返回 null。
 * 结果经 `AppThemeColors.setAmbientBackdrop` 写入全局快照供根背景绘制。
 */
@Composable
internal fun rememberAmbientBackdrop(
    coverUris: List<String>,
    enabled: Boolean,
): State<ImageBitmap?> {
    val context = LocalContext.current
    val uris = remember(coverUris) {
        coverUris.filter { it.isNotBlank() }.distinct().take(MosaicTileCount)
    }
    return produceState<ImageBitmap?>(initialValue = null, uris, enabled) {
        value = if (!enabled) {
            null
        } else {
            withContext(Dispatchers.Default) {
                buildAmbientBackdrop(context.applicationContext, uris)
            }
        }
    }
}

/** 单条缓存：封面集合未变时不重复解码/模糊（进程内保留一张底图）。 */
private object AmbientBackdropCache {
    private var key: String? = null
    private var image: ImageBitmap? = null

    @Synchronized
    fun get(requestKey: String): ImageBitmap? = if (key == requestKey) image else null

    @Synchronized
    fun put(requestKey: String, value: ImageBitmap) {
        key = requestKey
        image = value
    }

    @Synchronized
    fun clear() {
        key = null
        image = null
    }
}

private fun decodeCover(context: Context, uriText: String, tileSize: Int): Bitmap? = runCatching {
    val uri = Uri.parse(uriText)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= tileSize && bounds.outHeight / (sampleSize * 2) >= tileSize) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

/** 居中等比裁切源矩形，保证瓦片不变形。 */
private fun centerCropSrc(bitmap: Bitmap, width: Int, height: Int): Rect {
    val srcAspect = bitmap.width.toFloat() / bitmap.height
    val dstAspect = width.toFloat() / height
    return if (srcAspect > dstAspect) {
        val cropWidth = (bitmap.height * dstAspect).toInt().coerceAtLeast(1)
        val left = (bitmap.width - cropWidth) / 2
        Rect(left, 0, left + cropWidth, bitmap.height)
    } else {
        val cropHeight = (bitmap.width / dstAspect).toInt().coerceAtLeast(1)
        val top = (bitmap.height - cropHeight) / 2
        Rect(0, top, bitmap.width, top + cropHeight)
    }
}
