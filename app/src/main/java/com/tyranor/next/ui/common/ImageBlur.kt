package com.tyranor.next.ui.common

/**
 * 纯 ARGB 像素的分离式盒式模糊（3 轮近似高斯）。
 *
 * 用于 Android 12 以下没有 `RenderEffect` 时的封面模糊兜底：只对小尺寸缩略图
 * （约 40px 宽）做卷积，开销可忽略；算法本身不依赖 Android，便于 JVM 单测。
 *
 * @param pixels 非预乘 ARGB_8888 像素数组（长度 = width * height）
 * @param radius 盒式窗口半径（像素）；<=0 时不处理
 * @param passes 迭代轮数，3 轮已足够接近高斯
 */
internal fun boxBlurArgb(
    pixels: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    passes: Int = 3,
) {
    if (radius <= 0 || width <= 0 || height <= 0) return
    require(pixels.size >= width * height) { "pixels size ${pixels.size} < ${width * height}" }
    val temp = IntArray(width * height)
    repeat(passes) {
        boxBlurHorizontal(pixels, temp, width, height, radius)
        boxBlurVertical(temp, pixels, width, height, radius)
    }
}

private fun boxBlurHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val window = radius * 2 + 1
    val half = window / 2
    for (y in 0 until height) {
        val row = y * width
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val c = src[row + i.coerceIn(0, width - 1)]
            a += (c ushr 24) and 0xFF
            r += (c ushr 16) and 0xFF
            g += (c ushr 8) and 0xFF
            b += c and 0xFF
        }
        for (x in 0 until width) {
            dst[row + x] = ((a + half) / window shl 24) or
                ((r + half) / window shl 16) or
                ((g + half) / window shl 8) or
                ((b + half) / window)
            val out = src[row + (x - radius).coerceIn(0, width - 1)]
            val into = src[row + (x + radius + 1).coerceIn(0, width - 1)]
            a += ((into ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
            r += ((into ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
            g += ((into ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
            b += (into and 0xFF) - (out and 0xFF)
        }
    }
}

private fun boxBlurVertical(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val window = radius * 2 + 1
    val half = window / 2
    for (x in 0 until width) {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val c = src[i.coerceIn(0, height - 1) * width + x]
            a += (c ushr 24) and 0xFF
            r += (c ushr 16) and 0xFF
            g += (c ushr 8) and 0xFF
            b += c and 0xFF
        }
        for (y in 0 until height) {
            dst[y * width + x] = ((a + half) / window shl 24) or
                ((r + half) / window shl 16) or
                ((g + half) / window shl 8) or
                ((b + half) / window)
            val out = src[(y - radius).coerceIn(0, height - 1) * width + x]
            val into = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            a += ((into ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
            r += ((into ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
            g += ((into ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
            b += (into and 0xFF) - (out and 0xFF)
        }
    }
}
