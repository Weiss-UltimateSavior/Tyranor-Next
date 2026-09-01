package com.tyranor.next.ui.game

import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 像素坐标下的正方形裁切预览参数；不依赖 Android/Compose，便于单元测试。 */
data class CropImageMetrics(
    val viewportSize: Float,
    val imageWidth: Float,
    val imageHeight: Float,
) {
    val isValid: Boolean
        get() = viewportSize.isFinite() && viewportSize > 0f &&
            imageWidth.isFinite() && imageWidth > 0f &&
            imageHeight.isFinite() && imageHeight > 0f

    /** 使图片至少覆盖整个裁切框的最小缩放值。 */
    val minimumScale: Float
        get() = if (isValid) max(viewportSize / imageWidth, viewportSize / imageHeight) else 1f

    /** 限制放大倍率，避免手势把超大图片放到不可操作的范围。 */
    val maximumScale: Float
        get() = (minimumScale * 6f).coerceAtLeast(minimumScale)
}

data class CropTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

data class CropSourceRect(
    val left: Int,
    val top: Int,
    val size: Int,
)

data class CropRenderRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** 返回初始的“居中并覆盖裁切框”状态。 */
fun initialCropTransform(metrics: CropImageMetrics): CropTransform =
    constrainCropTransform(CropTransform(scale = metrics.minimumScale), metrics)

/** 将缩放与位移限制在图片覆盖裁切框的合法范围内。 */
fun constrainCropTransform(
    transform: CropTransform,
    metrics: CropImageMetrics,
): CropTransform {
    if (!metrics.isValid) return CropTransform()

    val minScale = metrics.minimumScale
    val safeScale = transform.scale.takeIf { it.isFinite() && it > 0f } ?: minScale
    val scale = safeScale.coerceIn(minScale, metrics.maximumScale)
    val maxOffsetX = ((metrics.imageWidth * scale) - metrics.viewportSize).coerceAtLeast(0f) / 2f
    val maxOffsetY = ((metrics.imageHeight * scale) - metrics.viewportSize).coerceAtLeast(0f) / 2f
    val offsetX = transform.offsetX.takeIf { it.isFinite() }?.coerceIn(-maxOffsetX, maxOffsetX) ?: 0f
    val offsetY = transform.offsetY.takeIf { it.isFinite() }?.coerceIn(-maxOffsetY, maxOffsetY) ?: 0f
    return CropTransform(scale = scale, offsetX = offsetX, offsetY = offsetY)
}

/**
 * 应用一次实时手势：单指时 [zoom] 为 1，双指时围绕 [centroidX]/[centroidY] 缩放。
 * 缩放锚点保持在手指下方，随后叠加平移并重新约束边界。
 */
fun applyCropGesture(
    transform: CropTransform,
    metrics: CropImageMetrics,
    centroidX: Float,
    centroidY: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
): CropTransform {
    if (!metrics.isValid) return CropTransform()
    val current = constrainCropTransform(transform, metrics)
    val safeZoom = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val nextScale = (current.scale * safeZoom).coerceIn(metrics.minimumScale, metrics.maximumScale)
    val scaleRatio = nextScale / current.scale
    val viewportCenter = metrics.viewportSize / 2f
    val safeCentroidX = centroidX.takeIf { it.isFinite() } ?: viewportCenter
    val safeCentroidY = centroidY.takeIf { it.isFinite() } ?: viewportCenter
    val safePanX = panX.takeIf { it.isFinite() } ?: 0f
    val safePanY = panY.takeIf { it.isFinite() } ?: 0f

    // 让缩放前后同一个图片点继续落在手指重心处。
    val anchoredOffsetX = current.offsetX +
        (safeCentroidX - viewportCenter - current.offsetX) * (1f - scaleRatio)
    val anchoredOffsetY = current.offsetY +
        (safeCentroidY - viewportCenter - current.offsetY) * (1f - scaleRatio)
    return constrainCropTransform(
        CropTransform(
            scale = nextScale,
            offsetX = anchoredOffsetX + safePanX,
            offsetY = anchoredOffsetY + safePanY,
        ),
        metrics,
    )
}

/** Returns the destination rectangle used by the preview renderer. */
fun cropRenderRect(
    transform: CropTransform,
    metrics: CropImageMetrics,
): CropRenderRect? {
    if (!metrics.isValid) return null
    val bounded = constrainCropTransform(transform, metrics)
    val width = (metrics.imageWidth * bounded.scale).coerceAtLeast(metrics.viewportSize)
    val height = (metrics.imageHeight * bounded.scale).coerceAtLeast(metrics.viewportSize)
    return CropRenderRect(
        left = (metrics.viewportSize - width) / 2f + bounded.offsetX,
        top = (metrics.viewportSize - height) / 2f + bounded.offsetY,
        width = width,
        height = height,
    )
}

/** 将预览状态反算为原图中的正方形区域，供后台生成快捷方式图标。 */
fun cropSourceRect(
    transform: CropTransform,
    metrics: CropImageMetrics,
): CropSourceRect? {
    if (!metrics.isValid) return null
    val bounded = constrainCropTransform(transform, metrics)
    val sourceWidth = metrics.imageWidth.roundToInt().coerceAtLeast(1)
    val sourceHeight = metrics.imageHeight.roundToInt().coerceAtLeast(1)
    val minSide = min(sourceWidth, sourceHeight)
    val side = floor(metrics.viewportSize / bounded.scale)
        .roundToInt()
        .coerceIn(1, minSide)
    val centerX = sourceWidth / 2f - bounded.offsetX / bounded.scale
    val centerY = sourceHeight / 2f - bounded.offsetY / bounded.scale
    val left = (centerX - side / 2f).roundToInt().coerceIn(0, sourceWidth - side)
    val top = (centerY - side / 2f).roundToInt().coerceIn(0, sourceHeight - side)
    return CropSourceRect(left = left, top = top, size = side)
}
