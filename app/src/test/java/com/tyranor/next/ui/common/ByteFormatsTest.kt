package com.tyranor.next.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字节格式化回归：全 App 三处实现（归档页 / RTP 提示 / 更新进度）统一到本类后，
 * 单位标签与精度必须一致。
 *
 * 关键锚定：
 *  - 单位是**二进制**（KiB/MiB/GiB）而不是 KB/MB/GB——除数本来就是 1024，
 *    旧标签只是写错；
 *  - 精度保留一位并去掉尾随 `.0`：既不让 `256 MiB` 显示成 `256.0 MiB`，
 *    也不把 `1.5 GiB` 舍成 `2 GiB`（旧 RTP 实现的 `%.0f` 会）。
 */
class ByteFormatsTest {

    @Test
    fun belowOneKiBShowsBytes() {
        assertEquals("0 B", ByteFormats.formatBinarySize(0))
        assertEquals("1 B", ByteFormats.formatBinarySize(1))
        assertEquals("1023 B", ByteFormats.formatBinarySize(1023))
    }

    @Test
    fun usesBinaryUnits() {
        assertEquals("1 KiB", ByteFormats.formatBinarySize(1024))
        assertEquals("1 MiB", ByteFormats.formatBinarySize(1024L * 1024))
        assertEquals("1 GiB", ByteFormats.formatBinarySize(1024L * 1024 * 1024))
        assertEquals("1 TiB", ByteFormats.formatBinarySize(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun dropsTrailingZeroDecimal() {
        // 整数倍不得输出 .0
        assertEquals("256 MiB", ByteFormats.formatBinarySize(256L * 1024 * 1024))
        assertEquals("4 GiB", ByteFormats.formatBinarySize(4L * 1024 * 1024 * 1024))
    }

    @Test
    fun keepsOneDecimalWhenMeaningful() {
        // 1.5 GiB 不得被舍成 2 GiB（旧 %.0f 实现的问题）
        assertEquals("1.5 GiB", ByteFormats.formatBinarySize((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1.5 MiB", ByteFormats.formatBinarySize((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.5 KiB", ByteFormats.formatBinarySize((2.5 * 1024).toLong()))
    }

    @Test
    fun roundingCarriesToNextUnitInsteadOfShowing1024() {
        // 1023.99 KiB 舍入到一位会得到 1024.0：必须进位成 1 MiB，而不是显示 "1024 KiB"
        val justBelowMib = (1024.0 * 1023.99).toLong()
        assertEquals("1 MiB", ByteFormats.formatBinarySize(justBelowMib))
    }

    @Test
    fun negativeValuesDegradeToBytes() {
        // 调用方（如尚未获取大小的下载进度）可能传 -1：直接按 B 输出，不参与量级换算
        assertEquals("-1 B", ByteFormats.formatBinarySize(-1))
    }
}
