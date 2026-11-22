package com.tyranor.next.ui.game

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 封面解码的字节预算（ARGB_8888）：极端宽高比（如 60000×600 的横幅）只按档位采样会解码
 * ~144MB；采样循环同时受该预算约束，避免每次回到视口都申请-释放一次超大位图。
 */
internal const val CoverDecodeBudgetBytes = 32L * 1024 * 1024

/**
 * 封面解码档位：按消费方的最大显示宽度分档。
 *
 * `BitmapFactory.inSampleSize` 只能取 2 的幂，单靠采样无法精确命中目标尺寸（例如 1000×1333
 * 在 384×512 阈值下会解到 500×666）。这里先按档位采样，再按消费方盒子的宽高比裁剪；
 * 超过目标尺寸时精确缩放，小图保留原生尺寸不放大。封面流（1080p 竖屏卡片约 648px 宽）
 * 使用更大档位，避免复用 384 宽缩略图放大发虚。
 *
 * 档位与目标宽高比（0.05 一档分桶）共同参与 Bitmap 缓存 key：同一 URI 在网格、瀑布流、
 * 首页最近打开与封面流可能以不同清晰度 / 形状驻留，LruCache 仍按真实字节数统一计费与逐出。
 */
internal enum class CoverDecodeTier(val maxWidthPx: Int, val maxHeightPx: Int) {
    /** 多列网格（每行 2–10 列）、封面搜索预览与首页快捷启动卡的模糊底图（卡片最大边约 180–384px）。 */
    GRID(384, 512),

    /** 首页最近打开（手机约 500px 宽）等中等卡片；长按操作弹窗、网格与搜索预览共用 GRID。 */
    CARD(512, 683),

    /** 封面流（1080p 竖屏卡片约 648px 宽）。 */
    FLOW(768, 1024),

    /** 大屏 / 平板封面流。 */
    FLOW_LARGE(1024, 1365),
    ;

    /** 档位自身的目标宽高比（3:4）；未指定消费方比例时使用。 */
    val defaultAspect: Float get() = maxWidthPx.toFloat() / maxHeightPx

    /** 缓存 key：档位 + 0.05 分桶的宽高比 + 原 uri。 */
    fun cacheKey(uri: String, targetAspect: Float? = null): String {
        val aspect = quantizedCoverAspect(targetAspect ?: defaultAspect)
        return "${name.lowercase()}:a${aspectKey(aspect)}:$uri"
    }

    companion object {
        /** 按卡片宽度取档位：档位数量有限，缓存 key 不会随窗口宽度无限膨胀。 */
        fun forWidth(widthPx: Int): CoverDecodeTier = when {
            widthPx <= GRID.maxWidthPx -> GRID
            widthPx <= CARD.maxWidthPx -> CARD
            widthPx <= FLOW.maxWidthPx -> FLOW
            else -> FLOW_LARGE
        }

        /** 取解码目标能覆盖显示盒的最小档，超出上限时使用最大档。 */
        fun forDisplay(widthPx: Int, aspect: Float): CoverDecodeTier {
            val displayAspect = if (aspect.isFinite() && aspect > 0f) aspect else GRID.defaultAspect
            val heightPx = (widthPx / displayAspect).roundToInt()
            return entries.firstOrNull { tier ->
                val (targetWidth, targetHeight) = coverTargetSize(tier, displayAspect)
                targetWidth >= widthPx && targetHeight >= heightPx
            } ?: FLOW_LARGE
        }
    }
}

/**
 * 目标宽高比按 0.05 一档量化：瀑布流按 uri 哈希取 0.60–0.80，缓存 key 不能直接用浮点数
 * （0.6000001 与 0.6 会生成不同 key），量化后同一消费方稳定命中同一份位图。
 */
internal fun quantizedCoverAspect(aspect: Float): Float {
    if (!aspect.isFinite() || aspect <= 0f) return 0.75f
    val bucket = (aspect * 20f).roundToInt().coerceAtLeast(1)
    return bucket / 20f
}

/** 居中裁剪矩形（源图像素坐标）。 */
internal data class CoverCropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * 把源图居中裁剪到 [targetAspect]；宽高非法时返回 null。
 * 纯函数，供 JVM 单测覆盖裁剪几何（不加载 Android Bitmap）。
 */
internal fun coverCropRect(srcWidth: Int, srcHeight: Int, targetAspect: Float): CoverCropRect? {
    if (srcWidth <= 0 || srcHeight <= 0) return null
    val aspect = quantizedCoverAspect(targetAspect)
    val sourceAspect = srcWidth.toFloat() / srcHeight
    if (abs(sourceAspect - aspect) <= 0.01f) {
        return CoverCropRect(0, 0, srcWidth, srcHeight)
    }
    val cropWidth = if (sourceAspect > aspect) {
        (srcHeight * aspect).roundToInt().coerceIn(1, srcWidth)
    } else {
        srcWidth
    }
    val cropHeight = if (sourceAspect > aspect) {
        srcHeight
    } else {
        (srcWidth / aspect).roundToInt().coerceIn(1, srcHeight)
    }
    return CoverCropRect(
        left = (srcWidth - cropWidth) / 2,
        top = (srcHeight - cropHeight) / 2,
        width = cropWidth,
        height = cropHeight,
    )
}

/**
 * 目标位图的尺寸上限：把 [targetAspect] 的盒子适配进档位盒（宽高都不超过档位上限）。
 * 1:1 的首页最近打开卡在 CARD 档下得到 512×512；0.6 的瀑布流卡在 GRID 档下得到 307×512。
 */
internal fun coverTargetSize(tier: CoverDecodeTier, targetAspect: Float): Pair<Int, Int> {
    val aspect = quantizedCoverAspect(targetAspect)
    return if (aspect >= tier.defaultAspect) {
        val width = tier.maxWidthPx
        width to (width / aspect).roundToInt().coerceAtLeast(1)
    } else {
        val height = tier.maxHeightPx
        (height * aspect).roundToInt().coerceAtLeast(1) to height
    }
}

/**
 * 采样档位：先保证解码结果不小于档位目标（不把图缩到比目标还小），再保证解码字节数
 * ≤ [maxBytes]。两者冲突时字节预算优先（极端横幅宁可略微放大，也不申请 100MB+ 位图）。
 * 纯函数，便于 JVM 单测。
 */
internal fun coverSampleSize(
    width: Int,
    height: Int,
    tier: CoverDecodeTier,
    maxBytes: Long = CoverDecodeBudgetBytes,
): Int {
    if (width <= 0 || height <= 0 || maxBytes <= 0) return 1
    // 1) 不主动缩到档位目标以下
    var targetSample = 1
    while (targetSample < MAX_COVER_SAMPLE) {
        val next = targetSample * 2
        val w = width / next
        val h = height / next
        if (w < tier.maxWidthPx || h < tier.maxHeightPx) break
        targetSample = next
    }
    // 2) 字节预算：必要时继续翻倍，哪怕已经小于目标尺寸
    var sample = targetSample
    while (sample < MAX_COVER_SAMPLE) {
        val w = width.toLong() / sample
        val h = height.toLong() / sample
        if (w <= 0 || h <= 0) break
        if (w * h * BYTES_PER_PIXEL <= maxBytes) break
        sample *= 2
    }
    return sample
}

private const val BYTES_PER_PIXEL = 4L

/** 采样上限：2^20 已足以把任意 Android 可解码图片压到远小于目标。 */
private const val MAX_COVER_SAMPLE = 1 shl 20

/** 缓存 key 里的比例字符串：用整数百分位生成，避免 Locale / 浮点格式差异。 */
private fun aspectKey(aspect: Float): String {
    val hundredths = (aspect * 100f).roundToInt().coerceAtLeast(1)
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
