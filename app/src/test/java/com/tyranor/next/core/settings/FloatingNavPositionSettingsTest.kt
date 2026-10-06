package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 悬浮按钮位置归一化：解析 "x,y"、非法值回退右下角、越界钳制。
 * 磁盘值一旦发布即为数据契约，用测试钉住解析行为。
 */
class FloatingNavPositionSettingsTest {

    private val default = AppSettingsStore.DEFAULT_FLOATING_NAV_POSITION

    @Test
    fun missingOrInvalid_fallsBackToBottomRight() {
        assertEquals(default, AppSettingsStore.normalizeFloatingNavPosition(null))
        assertEquals(default, AppSettingsStore.normalizeFloatingNavPosition(""))
        assertEquals(default, AppSettingsStore.normalizeFloatingNavPosition("abc"))
        assertEquals(default, AppSettingsStore.normalizeFloatingNavPosition("0.5"))
        assertEquals(default, AppSettingsStore.normalizeFloatingNavPosition("NaN,0.5"))
        assertEquals(default, AppSettingsStore.normalizeFloatingNavPosition("0.5,Infinity"))
    }

    @Test
    fun validValues_areParsed() {
        assertEquals(0.25f to 0.75f, AppSettingsStore.normalizeFloatingNavPosition("0.25,0.75"))
        assertEquals(0f to 0f, AppSettingsStore.normalizeFloatingNavPosition("0,0"))
    }

    @Test
    fun outOfRange_isClamped() {
        assertEquals(0f to 1f, AppSettingsStore.normalizeFloatingNavPosition("-1,2"))
    }

    @Test
    fun default_isBottomRight() {
        assertEquals(1f, default.first, 0f)
        assertEquals(1f, default.second, 0f)
    }
}
