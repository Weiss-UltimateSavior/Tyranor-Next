package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Winlator 外置启动设置归一：对齐 winlator-cn `LaunchArgs` 校验规则，非法值一律回退空串（不下发）。
 */
class WinlatorSettingsTest {

    @Test
    fun graphicsDriverOnlyAcceptsFixedVulkanOpenGlCombinations() {
        assertEquals("turnip,zink", EngineSettingsStore.normalizeWinlatorGraphicsDriver(" turnip, zink "))
        assertEquals("vortek,virgl", EngineSettingsStore.normalizeWinlatorGraphicsDriver("VORTEK,VIRGL"))
        assertEquals("vortek,gladio", EngineSettingsStore.normalizeWinlatorGraphicsDriver("vortek,gladio"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorGraphicsDriver(""))
        assertEquals("", EngineSettingsStore.normalizeWinlatorGraphicsDriver("turnip"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorGraphicsDriver("zink"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorGraphicsDriver("turnip,bad"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorGraphicsDriver("auto"))
    }

    @Test
    fun dxWrapperOnlyAcceptsDxvkAndWined3d() {
        assertEquals("dxvk", EngineSettingsStore.normalizeWinlatorDxWrapper("DXVK"))
        assertEquals("wined3d", EngineSettingsStore.normalizeWinlatorDxWrapper("wined3d"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorDxWrapper("vkd3d"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorDxWrapper("auto"))
    }

    @Test
    fun box64PresetAcceptsBuiltinsAndCustom() {
        assertEquals("PERFORMANCE", EngineSettingsStore.normalizeWinlatorBox64Preset("performance"))
        assertEquals("STABILITY", EngineSettingsStore.normalizeWinlatorBox64Preset("STABILITY"))
        assertEquals("CUSTOM-3", EngineSettingsStore.normalizeWinlatorBox64Preset("custom-3"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorBox64Preset("TURBO"))
    }

    @Test
    fun screenSizeOnlyAcceptsFixedOptions() {
        assertEquals("1600x900", EngineSettingsStore.normalizeWinlatorScreenSize("1600x900"))
        assertEquals("1920x1080", EngineSettingsStore.normalizeWinlatorScreenSize(" 1920x1080 "))
        assertEquals("1280x800", EngineSettingsStore.normalizeWinlatorScreenSize("1280x800"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorScreenSize("auto"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorScreenSize("1600X900"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorScreenSize("320x160"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorScreenSize("2560x1440"))
    }

    @Test
    fun mergeWinlatorUsesOverrideWhenPresentAndGlobalOtherwise() {
        val global = EngineSettingsStore.Winlator(
            containerId = 3,
            containerName = "全局容器",
            graphicsDriver = "turnip,zink",
            screenSize = "1280x720",
            tz = "Asia/Tokyo",
            save = true,
        )

        val merged = EffectiveEngineSettings.mergeWinlator(
            global,
            WinlatorOverride(
                containerId = "7",
                screenSize = "",                       // 显式不下发
                graphicsDriver = "vortek,virgl",
                tz = "invalid tz",                     // 非法覆盖归一回空串，不继承全局
            ),
        )

        assertEquals(7, merged.containerId)
        assertEquals("全局容器", merged.containerName)
        assertEquals("vortek,virgl", merged.graphicsDriver)
        assertEquals("", merged.screenSize)
        assertEquals("", merged.tz)
        assertEquals(true, merged.save)
    }

    @Test
    fun mergeWinlatorWithoutOverrideKeepsGlobal() {
        val global = EngineSettingsStore.Winlator(containerId = 5, containerName = "C")
        assertEquals(global, EffectiveEngineSettings.mergeWinlator(global, null))
    }

    @Test
    fun perGameOverrideSnapshotMapsFlatKeys() {
        val json = org.json.JSONObject()
            .put(PerGameSettingsStore.F_WINLATOR_CONTAINER_ID, "2")
            .put(PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE, "")
            .put(PerGameSettingsStore.F_WINLATOR_SAVE, true)
        val override = PerGameSettingsStore.toWinlatorOverride(json)

        assertEquals("2", override?.containerId)
        assertEquals("", override?.screenSize)
        assertEquals(true, override?.save)
        assertEquals(null, override?.containerName)
    }

    @Test
    fun lcAllOnlyAcceptsFixedOptions() {
        assertEquals("ja_JP.UTF-8", EngineSettingsStore.normalizeWinlatorLcAll(" ja_JP.UTF-8 "))
        assertEquals("zh_CN.utf8", EngineSettingsStore.normalizeWinlatorLcAll("zh_CN.utf8"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorLcAll("ru_RU.UTF-8"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorLcAll("ja JP"))
    }

    @Test
    fun tzOnlyAcceptsFixedOptions() {
        assertEquals("Asia/Tokyo", EngineSettingsStore.normalizeWinlatorTz("Asia/Tokyo"))
        assertEquals("UTC", EngineSettingsStore.normalizeWinlatorTz("UTC"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorTz("Etc/GMT-8"))
        assertEquals("", EngineSettingsStore.normalizeWinlatorTz("Asia Tokyo"))
    }

    @Test
    fun defaultsKeepAllParametersUnset() {
        val defaults = EngineSettingsStore.Winlator()
        assertEquals(0, defaults.containerId)
        assertEquals("", defaults.containerName)
        assertEquals("", defaults.graphicsDriver)
        assertEquals("", defaults.dxwrapper)
        assertEquals("", defaults.screenSize)
        assertEquals("", defaults.lcAll)
        assertEquals("", defaults.tz)
        assertEquals("", defaults.box64Preset)
        assertEquals(false, defaults.save)
    }
}
