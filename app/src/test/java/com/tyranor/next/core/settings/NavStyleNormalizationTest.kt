package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导航样式归一化的纯 JVM 测试：三态取值、未知值回退、以及「透镜档需要 Android 13+」的门槛。
 * 这层规则决定旧版本与从新版备份恢复的数据会不会落到降级画面，属于必须钉住的行为。
 */
class NavStyleNormalizationTest {

    private val default = AppSettingsStore.NAV_STYLE_DEFAULT
    private val glass = AppSettingsStore.NAV_STYLE_LIQUID_GLASS
    private val enhanced = AppSettingsStore.NAV_STYLE_LIQUID_GLASS_ENHANCED
    private val floating = AppSettingsStore.NAV_STYLE_FLOATING_BUTTON

    @Test
    fun knownValues_areKeptWhenSupported() {
        assertEquals(default, AppSettingsStore.normalizeNavStyle(default, enhancedSupported = true))
        assertEquals(glass, AppSettingsStore.normalizeNavStyle(glass, enhancedSupported = true))
        assertEquals(enhanced, AppSettingsStore.normalizeNavStyle(enhanced, enhancedSupported = true))
        assertEquals(floating, AppSettingsStore.normalizeNavStyle(floating, enhancedSupported = true))
    }

    @Test
    fun floatingButton_hasNoVersionGate() {
        // 悬浮按钮在低版本走组件内实底降级，读取时不做归一化降级
        assertEquals(floating, AppSettingsStore.normalizeNavStyle(floating, enhancedSupported = false))
    }

    @Test
    fun enhancedFallsBackToPlainGlassBelowApi33() {
        assertEquals(glass, AppSettingsStore.normalizeNavStyle(enhanced, enhancedSupported = false))
    }

    @Test
    fun unknownOrMissingValue_fallsBackToDefault() {
        assertEquals(default, AppSettingsStore.normalizeNavStyle(null, enhancedSupported = true))
        assertEquals(default, AppSettingsStore.normalizeNavStyle("", enhancedSupported = true))
        assertEquals(default, AppSettingsStore.normalizeNavStyle("liquid", enhancedSupported = true))
    }

    @Test
    fun storedValues_areStable() {
        // 持久化取值改了就等于一次静默的数据迁移，这里钉住
        assertEquals("default", default)
        assertEquals("liquid_glass", glass)
        assertEquals("liquid_glass_enhanced", enhanced)
        assertEquals("floating_button", floating)
    }
}
