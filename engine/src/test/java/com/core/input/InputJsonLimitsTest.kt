package com.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入配置解析约束的回归。
 *
 * 重点锚定 [InputJsonLimits.MAX_KEYS_PER_BINDING] 与键位目录的关系：该上限是
 * **防御性**的（拦坏文件/构造文件），不是编辑约束。若它小于用户可勾选的键总数，
 * 用户在键位对话框里如实勾很多键后，落盘的数据会在下次解析时被静默截断——
 * 表现为「重启后绑定丢了几个」，且无任何提示。
 */
class InputJsonLimitsTest {

    private fun catalogKeyCount(): Int = InputKeyCatalog.groups().sumOf { it.keys.size }

    @Test
    fun keyCapExceedsSelectableKeyCount() {
        val selectable = catalogKeyCount()
        assertTrue(
            "上限（$selectable 个可选键）必须显著高于目录总数，" +
                "否则用户如实勾选会被解析侧静默截断",
            InputJsonLimits.MAX_KEYS_PER_BINDING > selectable,
        )
        // 留出余量，避免目录后续小幅增补就顶到上限
        assertTrue(
            "应为目录增补留出余量（当前上限 ${InputJsonLimits.MAX_KEYS_PER_BINDING}）",
            InputJsonLimits.MAX_KEYS_PER_BINDING >= selectable + 16,
        )
    }

    @Test
    fun fullCatalogSelectionSurvivesRoundTrip() {
        // 极端但合法的场景：把目录里所有键都绑到一个按钮上，落盘后必须原样读回
        val allKeys = InputKeyCatalog.groups().flatMap { group -> group.keys.map { it.code } }
        val profile = PadProfile.defaultProfile("p", "P").copy(
            buttons = listOf(
                PadProfile.newButtonDefaults(PadButtonGeometry.OVAL, "all", "All")
                    .copy(keys = allKeys),
            ),
        )

        val parsed = PadProfile.parse(profile.toJson())!!
        assertEquals("全量勾选不得被截断", allKeys.size, parsed.buttons.single().keys.size)
        assertEquals(allKeys, parsed.buttons.single().keys)
    }

    // ---------- schema 兼容性 ----------

    @Test
    fun higherSchemaRejectedAndEqualAccepted() {
        assertFalse(InputJsonLimits.isSupportedSchema(PadProfile.SCHEMA + 1, PadProfile.SCHEMA))
        assertTrue(InputJsonLimits.isSupportedSchema(PadProfile.SCHEMA, PadProfile.SCHEMA))
        assertTrue(InputJsonLimits.isSupportedSchema(null, PadProfile.SCHEMA))
    }

    @Test
    fun schemaBeyondIntRangeIsNotTruncatedToLowVersion() {
        // 0x1_0000_0001 若先 toInt() 截断会变成 1，被误判成旧版本放行
        val beyondInt = 0x1_0000_0001L
        assertFalse(
            "超出 Int 范围的高版本必须被拒绝，而不是截断后放行",
            InputJsonLimits.isSupportedSchema(beyondInt, PadProfile.SCHEMA),
        )
    }

    @Test
    fun nonNumericSchemaIsTreatedAsMissing() {
        // 非数值不表达「更新的版本」：按当前字段尽力解析比整体拒绝更符合预期
        assertTrue(InputJsonLimits.isSupportedSchema("2", PadProfile.SCHEMA))
    }
}
