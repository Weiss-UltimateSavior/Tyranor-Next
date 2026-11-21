package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 单游戏覆盖 JSON 键读取回归（PR #183 二轮审查非阻塞项）：JSONObject.NULL 与缺键同义
 * （=跟随全局）。optString/optBoolean 对 NULL 会产出 "null" 字符串 / false 默认值，
 * 若不先排除会把「未设置」误判成「有覆盖」，进而把错误值按 game scope 落盘引擎 XML。
 */
class EngineSettingsResolverOverrideReadTest {

    @Test
    fun `json null reads as absent override`() {
        val override = JSONObject("{\"${PerGameSettingsStore.F_DEFAULT_FONT}\": null}")
        assertNull(overrideStr(override, PerGameSettingsStore.F_DEFAULT_FONT))
        assertNull(overrideBool(override, PerGameSettingsStore.F_FORCE_DEFAULT_FONT))
    }

    @Test
    fun `missing key reads as null`() {
        val override = JSONObject("{}")
        assertNull(overrideStr(override, PerGameSettingsStore.F_DEFAULT_FONT))
        assertNull(overrideBool(override, PerGameSettingsStore.F_FORCE_DEFAULT_FONT))
    }

    @Test
    fun `present values read through including empty string`() {
        // 空串是合法覆盖（=内置字体，game scope 空 Item），不得被吞成 null
        val override = JSONObject(
            "{\"${PerGameSettingsStore.F_DEFAULT_FONT}\": \"\", \"other\": \"value\"}",
        )
        assertEquals("", overrideStr(override, PerGameSettingsStore.F_DEFAULT_FONT))
        assertEquals("value", overrideStr(override, "other"))
        val bools = JSONObject(
            "{\"${PerGameSettingsStore.F_FORCE_DEFAULT_FONT}\": true, \"off\": false}",
        )
        assertEquals(true, overrideBool(bools, PerGameSettingsStore.F_FORCE_DEFAULT_FONT))
        assertEquals(false, overrideBool(bools, "off"))
    }
}
