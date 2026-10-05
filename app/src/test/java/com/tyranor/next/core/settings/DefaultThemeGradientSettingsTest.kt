package com.tyranor.next.core.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认主题渐变默认开启（应用设置可关闭，回退纯色页面背景）。
 */
class DefaultThemeGradientSettingsTest {

    @Test
    fun gradientDefaultsToOn() {
        assertTrue(AppSettingsStore.DEFAULT_DEFAULT_THEME_GRADIENT)
    }
}
