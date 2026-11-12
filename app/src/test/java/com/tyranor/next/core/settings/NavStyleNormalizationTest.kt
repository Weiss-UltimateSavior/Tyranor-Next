package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导航样式归一化的纯 JVM 测试：四态取值、缺省/未知值回退、透镜档的版本门槛，以及默认外观的
 * 默认样式（液态玻璃 · 经典）。这层规则决定旧版本与从新版备份恢复的数据会不会落到降级画面。
 */
class NavStyleNormalizationTest {

    private val classicMd = AppSettingsStore.NAV_STYLE_CLASSIC_MD
    private val glass = AppSettingsStore.NAV_STYLE_LIQUID_GLASS
    private val enhanced = AppSettingsStore.NAV_STYLE_LIQUID_GLASS_ENHANCED
    private val floating = AppSettingsStore.NAV_STYLE_FLOATING_BUTTON
    private val auto = AppSettingsStore.NAV_STYLE_AUTO

    @Test
    fun knownValues_areKeptWhenSupported() {
        assertEquals(classicMd, AppSettingsStore.normalizeNavStyle(classicMd, enhancedSupported = true))
        assertEquals(glass, AppSettingsStore.normalizeNavStyle(glass, enhancedSupported = true))
        assertEquals(enhanced, AppSettingsStore.normalizeNavStyle(enhanced, enhancedSupported = true))
        assertEquals(floating, AppSettingsStore.normalizeNavStyle(floating, enhancedSupported = true))
    }

    @Test
    fun classicMd_hasNoVersionGate() {
        // 显式选过经典 MD（旧版本的「默认」）的用户在任何版本上都保持原样
        assertEquals(classicMd, AppSettingsStore.normalizeNavStyle(classicMd, enhancedSupported = false))
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
    fun unknownOrMissingValue_remainsAutomaticOnAllVersions() {
        for (supported in listOf(true, false)) {
            for (stored in listOf(null, "", "liquid", auto)) {
                assertEquals(auto, AppSettingsStore.normalizeNavStyle(stored, supported))
            }
        }
    }

    @Test
    fun automaticStyle_usesClassicLiquidGlassForDefaultAppearance() {
        // 默认外观的默认导航栏为图示的磨砂玻璃（液态玻璃 · 经典）；透镜需显式选择
        for (supported in listOf(true, false)) {
            assertEquals(glass, AppSettingsStore.resolveNavStyle(auto, supported, glassAppearance = false))
            assertEquals(classicMd, AppSettingsStore.resolveNavStyle(auto, supported, glassAppearance = true))
        }
    }

    @Test
    fun appearanceChanges_doNotOverwriteExplicitChoice() {
        for (supported in listOf(true, false)) {
            for (style in listOf(classicMd, glass, enhanced, floating)) {
                val expected = AppSettingsStore.normalizeNavStyle(style, supported)
                for (glassAppearance in listOf(false, true, false)) {
                    assertEquals(expected, AppSettingsStore.resolveNavStyle(style, supported, glassAppearance))
                }
            }
        }
    }

    @Test
    fun automaticStyle_followsAppearanceChangesWithoutBeingPinned() {
        for (supported in listOf(true, false)) {
            val defaultAppearanceStyle = glass
            assertEquals(defaultAppearanceStyle, AppSettingsStore.resolveNavStyle(auto, supported, glassAppearance = false))
            assertEquals(classicMd, AppSettingsStore.resolveNavStyle(auto, supported, glassAppearance = true))
            assertEquals(defaultAppearanceStyle, AppSettingsStore.resolveNavStyle(auto, supported, glassAppearance = false))
        }
    }

    @Test
    fun storedValues_areStable() {
        // 持久化取值改了就等于一次静默的数据迁移，这里钉住（经典 MD 沿用历史值 "default"）
        assertEquals("default", classicMd)
        assertEquals("liquid_glass", glass)
        assertEquals("liquid_glass_enhanced", enhanced)
        assertEquals("floating_button", floating)
    }
}
