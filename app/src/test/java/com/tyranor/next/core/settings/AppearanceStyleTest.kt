package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观风格归一化的纯 JVM 测试：两档取值、未知值回退、玻璃系判定与持久化取值稳定性。
 *
 * 这层规则决定存量用户（旧值 `glass` = 复古玻璃）升级后是否会丢风格，属于必须钉住的行为；
 * 已删除档位的历史持久化值也必须安全回退默认，不得崩溃或落到非法状态。
 */
class AppearanceStyleTest {

    @Test
    fun knownValues_areKept() {
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage("default"))
        assertEquals(AppearanceStyle.RETRO_GLASS, AppearanceStyle.fromStorage("glass"))
    }

    @Test
    fun unknownOrMissingValue_fallsBackToDefault() {
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage(null))
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage(""))
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage("advanced_glass"))
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage("glass_enhanced"))
    }

    @Test
    fun removedAdvancedStyleValue_fallsBackToDefault() {
        // 已删除的第三档遗留持久化值：升级后回默认，不崩溃、不留悬空状态
        assertEquals(AppearanceStyle.DEFAULT, AppearanceStyle.fromStorage("glass_advanced"))
    }

    @Test
    fun glassFamilyIsOnlyRetroGlass() {
        assertFalse(AppearanceStyle.DEFAULT.isGlass)
        assertTrue(AppearanceStyle.RETRO_GLASS.isGlass)
    }

    @Test
    fun storedValues_areStable() {
        // 持久化取值改了就等于一次静默的数据迁移（存量用户外观风格丢失），这里钉住
        assertEquals("default", AppearanceStyle.DEFAULT.storageValue)
        assertEquals("glass", AppearanceStyle.RETRO_GLASS.storageValue)
    }

    @Test
    fun storageValueRoundTrips() {
        AppearanceStyle.entries.forEach { style ->
            assertEquals(style, AppearanceStyle.fromStorage(style.storageValue))
        }
    }
}
