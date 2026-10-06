package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首页样式归一：web / category 精确接受，其余（含空/未知/大小写差异）回退原生。
 */
class HomeStyleNormalizationTest {

    @Test
    fun webIsAccepted() {
        assertEquals(AppSettingsStore.HOME_STYLE_WEB, AppSettingsStore.normalizeHomeStyle("web"))
    }

    @Test
    fun categoryIsAccepted() {
        assertEquals(
            AppSettingsStore.HOME_STYLE_CATEGORY,
            AppSettingsStore.normalizeHomeStyle("category"),
        )
    }

    @Test
    fun nativeIsAcceptedAndIsTheFallback() {
        assertEquals(AppSettingsStore.HOME_STYLE_NATIVE, AppSettingsStore.normalizeHomeStyle("native"))
        assertEquals(AppSettingsStore.HOME_STYLE_NATIVE, AppSettingsStore.normalizeHomeStyle(null))
        assertEquals(AppSettingsStore.HOME_STYLE_NATIVE, AppSettingsStore.normalizeHomeStyle(""))
        assertEquals(AppSettingsStore.HOME_STYLE_NATIVE, AppSettingsStore.normalizeHomeStyle("unknown"))
        assertEquals(AppSettingsStore.HOME_STYLE_NATIVE, AppSettingsStore.normalizeHomeStyle("WEB"))
        assertEquals(AppSettingsStore.HOME_STYLE_NATIVE, AppSettingsStore.DEFAULT_HOME_STYLE)
    }
}
