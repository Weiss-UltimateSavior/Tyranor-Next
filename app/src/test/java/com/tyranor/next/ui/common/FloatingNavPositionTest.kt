package com.tyranor.next.ui.common

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 悬浮按钮位置换算（纯函数）：归一化 0..1 ↔ 像素，以及边界钳制。
 * 默认 (1,1) 表示右下角，且四周保留统一边距；容器过小时安全退化不产生负坐标。
 */
class FloatingNavPositionTest {

    private val tolerance = 0.01f

    @Test
    fun defaultPosition_isBottomRightWithMargin() {
        val position = floatingNavPositionPx(
            normalized = 1f to 1f,
            containerWidth = 1000,
            containerHeight = 2000,
            buttonSizePx = 100f,
            marginPx = 16f,
        )
        // 右/下边缘各留 16px：1000 - 16 - 100 = 884；2000 - 16 - 100 = 1884
        assertEquals(884f, position.x, tolerance)
        assertEquals(1884f, position.y, tolerance)
    }

    @Test
    fun zeroPosition_isTopLeftMargin() {
        val position = floatingNavPositionPx(
            normalized = 0f to 0f,
            containerWidth = 1000,
            containerHeight = 2000,
            buttonSizePx = 100f,
            marginPx = 16f,
        )
        assertEquals(16f, position.x, tolerance)
        assertEquals(16f, position.y, tolerance)
    }

    @Test
    fun clamp_keepsInsideMargins() {
        val position = clampFloatingNavPosition(
            position = Offset(-50f, 5000f),
            containerWidth = 1000,
            containerHeight = 2000,
            buttonSizePx = 100f,
            marginPx = 16f,
        )
        assertEquals(16f, position.x, tolerance)
        assertEquals(1884f, position.y, tolerance)
    }

    @Test
    fun normalizedRoundTrip_restoresPosition() {
        val original = Offset(300f, 700f)
        val normalized = floatingNavNormalizedPosition(
            position = original,
            containerWidth = 1000,
            containerHeight = 2000,
            buttonSizePx = 100f,
            marginPx = 16f,
        )
        val restored = floatingNavPositionPx(
            normalized = normalized,
            containerWidth = 1000,
            containerHeight = 2000,
            buttonSizePx = 100f,
            marginPx = 16f,
        )
        assertEquals(original.x, restored.x, tolerance)
        assertEquals(original.y, restored.y, tolerance)
    }

    @Test
    fun tinyContainer_degradesSafely() {
        val position = floatingNavPositionPx(
            normalized = 1f to 1f,
            containerWidth = 50,
            containerHeight = 50,
            buttonSizePx = 100f,
            marginPx = 16f,
        )
        assertEquals(16f, position.x, tolerance)
        assertEquals(16f, position.y, tolerance)
    }
}
