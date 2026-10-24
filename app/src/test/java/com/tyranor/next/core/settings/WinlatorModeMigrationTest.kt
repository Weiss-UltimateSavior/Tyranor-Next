package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 单游戏 Winlator 运行模式：1.61 旧数据（null / "" / 值）的迁移推断。 */
class WinlatorModeMigrationTest {
    private fun build(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    /** 六个参数字段都存在且全为空串（1.61「跟随容器」的形态），可再叠加其它旧字段。 */
    private fun buildClearedParams(vararg extra: Pair<String, Any>): JSONObject = JSONObject().apply {
        put(PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER, "")
        put(PerGameSettingsStore.F_WINLATOR_DXWRAPPER, "")
        put(PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE, "")
        put(PerGameSettingsStore.F_WINLATOR_LC_ALL, "")
        put(PerGameSettingsStore.F_WINLATOR_TZ, "")
        put(PerGameSettingsStore.F_WINLATOR_BOX64_PRESET, "")
        extra.forEach { (key, value) -> put(key, value) }
    }

    private fun infer(json: JSONObject): String = PerGameSettingsStore.inferWinlatorMode(json)

    @Test
    fun explicitModeBeatsLegacyFields() {
        val json = build(
            PerGameSettingsStore.F_WINLATOR_MODE to PerGameSettingsStore.WINLATOR_MODE_GLOBAL,
            PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER to "vulkan",
        )
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_GLOBAL, infer(json))
    }

    @Test
    fun emptyRecordFollowsGlobal() {
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_GLOBAL, infer(JSONObject()))
    }

    @Test
    fun nonEmptyParameterIsCustom() {
        val json = build(PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER to "vulkan")
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CUSTOM, infer(json))
    }

    @Test
    fun containerOnlyRecordIsCustom() {
        val json = build(PerGameSettingsStore.F_WINLATOR_CONTAINER_ID to "3")
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CUSTOM, infer(json))
    }

    @Test
    fun allParametersClearedFollowsContainer() {
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CONTAINER, infer(buildClearedParams()))
    }

    @Test
    fun partiallyClearedParametersAreCustom() {
        val json = build(
            PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER to "",
            PerGameSettingsStore.F_WINLATOR_DXWRAPPER to "",
        )
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CUSTOM, infer(json))
    }

    @Test
    fun saveOnlyRecordKeepsItsOverride() {
        val json = build(PerGameSettingsStore.F_WINLATOR_SAVE to true)
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CUSTOM, infer(json))
    }

    @Test
    fun allParametersClearedWithSaveStillFollowsContainer() {
        val json = buildClearedParams(PerGameSettingsStore.F_WINLATOR_SAVE to true)
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CONTAINER, infer(json))
    }

    @Test
    fun clearedParametersWithContainerFieldStillFollowContainer() {
        // 六个参数全空是「跟随容器」的强信号：容器 ID/名称只作为容器模式的覆盖值，
        // 不能把记录判成自定义，否则 save 会继承全局值、失去 1.61 的清空语义。
        val json = buildClearedParams(PerGameSettingsStore.F_WINLATOR_CONTAINER_ID to "3")
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CONTAINER, infer(json))
    }

    @Test
    fun containerModeWithNameOnlyOverrideClearsInheritedId() {
        // 推断 + 合并的联动：六个参数全空落到 CONTAINER 后，只填名称必须清掉继承的全局 ID，
        // 否则协议里 ID 优先会让名称覆盖失效。
        val json = buildClearedParams(PerGameSettingsStore.F_WINLATOR_CONTAINER_NAME to "我的容器")
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CONTAINER, infer(json))

        val merged = EffectiveEngineSettings.mergeWinlator(
            EngineSettingsStore.Winlator(containerId = 5, containerName = "全局容器"),
            PerGameSettingsStore.toWinlatorOverride(json),
        )
        assertEquals(0, merged.containerId)
        assertEquals("我的容器", merged.containerName)
    }
}
