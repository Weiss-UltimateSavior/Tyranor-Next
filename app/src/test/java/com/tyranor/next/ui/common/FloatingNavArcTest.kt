package com.tyranor.next.ui.common

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 悬浮按钮导航的弧形展开坐标：四个导航项在「正上方 → 正左方」的 1/4 圆弧上均分，
 * progress 0→1 从圆心滑到弧上。这里钉住坐标方向与角度分布，防止后续调参把弧线画反。
 */
class FloatingNavArcTest {

    private val tolerance = 0.01f

    @Test
    fun progressZero_allItemsAtCenter() {
        for (index in 0 until 4) {
            assertEquals(
                Offset.Zero,
                floatingNavArcOffset(index, count = 4, radiusPx = 100f, progress = 0f),
            )
        }
    }

    @Test
    fun firstItem_expandsUpward() {
        val offset = floatingNavArcOffset(index = 0, count = 4, radiusPx = 100f, progress = 1f)
        // 角度 101.25°：偏左上，纵向为主
        assertEquals(-19.51f, offset.x, tolerance)
        assertEquals(-98.08f, offset.y, tolerance)
    }

    @Test
    fun lastItem_expandsLeftward() {
        val offset = floatingNavArcOffset(index = 3, count = 4, radiusPx = 100f, progress = 1f)
        // 角度 168.75°：偏左上，横向为主
        assertEquals(-98.08f, offset.x, tolerance)
        assertEquals(-19.51f, offset.y, tolerance)
    }

    @Test
    fun allItems_stayInUpperLeftQuadrant() {
        for (index in 0 until 4) {
            val offset = floatingNavArcOffset(index, count = 4, radiusPx = 100f, progress = 1f)
            assertEquals(true, offset.x < 0f)
            assertEquals(true, offset.y < 0f)
        }
    }

    @Test
    fun progressScalesDistanceLinearly() {
        val half = floatingNavArcOffset(index = 1, count = 4, radiusPx = 100f, progress = 0.5f)
        val full = floatingNavArcOffset(index = 1, count = 4, radiusPx = 100f, progress = 1f)
        assertEquals(full.x / 2f, half.x, tolerance)
        assertEquals(full.y / 2f, half.y, tolerance)
    }

    @Test
    fun invalidInputs_areSafe() {
        assertEquals(Offset.Zero, floatingNavArcOffset(0, count = 0, radiusPx = 100f, progress = 1f))
        // 越界索引夹取到端点，不抛异常
        val clamped = floatingNavArcOffset(index = 99, count = 4, radiusPx = 100f, progress = 1f)
        val last = floatingNavArcOffset(index = 3, count = 4, radiusPx = 100f, progress = 1f)
        assertEquals(last, clamped)
    }

    @Test
    fun startAngle_followsAnchorQuadrant() {
        // 右下 → 上→左；右上 → 左→下；左下 → 右→上；左上 → 下→右
        assertEquals(90f, floatingNavArcStartAngle(900f, 1800f, containerWidth = 1000, containerHeight = 2000))
        assertEquals(180f, floatingNavArcStartAngle(900f, 200f, containerWidth = 1000, containerHeight = 2000))
        assertEquals(0f, floatingNavArcStartAngle(100f, 1800f, containerWidth = 1000, containerHeight = 2000))
        assertEquals(-90f, floatingNavArcStartAngle(100f, 200f, containerWidth = 1000, containerHeight = 2000))
    }

    @Test
    fun leftTopAnchor_fansDownRight() {
        val offset = floatingNavArcOffset(
            index = 0,
            count = 4,
            radiusPx = 100f,
            progress = 1f,
            startAngleDeg = -90f,
        )
        // 角度 -78.75°：偏右下，横向为主
        assertEquals(19.51f, offset.x, tolerance)
        assertEquals(98.08f, offset.y, tolerance)
    }

    @Test
    fun rightTopAnchor_lastItemFansDownward() {
        val offset = floatingNavArcOffset(
            index = 3,
            count = 4,
            radiusPx = 100f,
            progress = 1f,
            startAngleDeg = 180f,
        )
        // 角度 258.75°：偏左下，纵向为主
        assertEquals(-19.51f, offset.x, tolerance)
        assertEquals(98.08f, offset.y, tolerance)
    }
}
