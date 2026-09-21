package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观风格三态归一化的纯 JVM 测试：三档取值、未知值回退、玻璃系判定与持久化取值稳定性。
 *
 * 这层规则决定存量用户（旧值 `glass` = 复古玻璃）升级后是否会丢风格，属于必须钉住的行为。
 */
class AppearanceStyleTest {

    @Test
    fun knownValues_areKept() {
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage("default"))
        assertEquals(AppearanceStyle.RETRO_GLASS, AppearanceStyle.fromStorage("glass"))
        assertEquals(AppearanceStyle.ADVANCED_GLASS, AppearanceStyle.fromStorage("glass_advanced"))
    }

    @Test
    fun unknownOrMissingValue_fallsBackToDefault() {
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage(null))
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage(""))
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage("advanced_glass"))
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage("glass_enhanced"))
    }

    @Test
    fun glassFamilyCoversRetroAndAdvanced() {
        assertFalse(AppearanceStyle.DEFAULT.isGlass)
        assertTrue(AppearanceStyle.RETRO_GLASS.isGlass)
        assertTrue(AppearanceStyle.ADVANCED_GLASS.isGlass)

        assertFalse(AppearanceStyle.RETRO_GLASS.isAdvancedGlass)
        assertTrue(AppearanceStyle.ADVANCED_GLASS.isAdvancedGlass)
    }

    @Test
    fun storedValues_areStable() {
        // 持久化取值改了就等于一次静默的数据迁移（存量用户外观风格丢失），这里钉住
        assertEquals("default", AppearanceStyle.DEFAULT.storageValue)
        assertEquals("glass", AppearanceStyle.RETRO_GLASS.storageValue)
        assertEquals("glass_advanced", AppearanceStyle.ADVANCED_GLASS.storageValue)
    }

    @Test
    fun storageValueRoundTrips() {
        AppearanceStyle.entries.forEach { style ->
            assertEquals(style, AppearanceStyle.fromStorage(style.storageValue))
        }
    }
}
