package com.tyranor.next.ui.common

import java.util.Locale

/**
 * 字节数的人类可读形式（全 App 唯一实现）。
 *
 * 统一为**二进制单位**（1024 进制，标签 B / KiB / MiB / GiB / TiB）：磁盘与文件大小按
 * 1024 划分是平台惯例（`df`、文件管理器皆如此），此前散落的三处实现除数本就都是 1024，
 * 只有标签（KB/MB/GB 与 MiB/GiB 混用）与精度不一致，这里一并收敛。
 *
 * 精度：保留一位小数并去掉尾随 `.0`——`256 MiB` 而不是 `256.0 MiB`，同时避免
 * `%.0f` 把 `1.5 GiB` 四舍五入成 `2 GiB` 这种量级失真。
 */
object ByteFormats {

    private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

    /** 格式化字节数；负数与 0 原样按 B 输出。 */
    fun formatBinarySize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < UNITS.size - 1) {
            value /= 1024
            unit++
        }
        var rounded = Math.round(value * 10) / 10.0
        // 舍入可能跨到下一个量级（如 1023.99 KiB → 1024.0 KiB）：进位而不是显示 1024
        if (rounded >= 1024 && unit < UNITS.size - 1) {
            rounded /= 1024
            unit++
        }
        val text = if (rounded % 1.0 == 0.0) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
        return "$text ${UNITS[unit]}"
    }
}
