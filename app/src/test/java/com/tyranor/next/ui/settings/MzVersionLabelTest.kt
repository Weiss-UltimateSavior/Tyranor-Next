package com.tyranor.next.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MZ 版本显示名与 MZ 专有设置行的文案契约（三语言同步）。
 *
 * `engine_option_rpg_mz_v2` 的**存储值**是 `"v2"`（历史遗留，`TynMZ-0.1` 只是显示名）：
 * 升级到 `TynMZ-0.2` 只改文案、不动值，因此不需要设置迁移，也不影响
 * `PerGameSettingsStore.F_RPG_MZ_VERSION`。
 */
class MzVersionLabelTest {

    /**
     * 单测工作目录可能是模块目录（`app/`）或仓库根，两种都要能定位到资源。
     */
    private fun strings(locale: String): String {
        // 资源文件名恒为 strings.xml，只有目录区分语言
        val name = "strings.xml"
        val candidates = listOf(
            File("src/main/res/$locale/$name"),
            File("app/src/main/res/$locale/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到 $locale/$name，已尝试：" + candidates.joinToString { it.absolutePath },
            file != null,
        )
        return file!!.readText()
    }

    @Test
    fun mzVersionLabelIsTynMz02InEveryLocale() {
        for (locale in listOf("values", "values-en", "values-ja")) {
            val xml = strings(locale)
            val match = Regex("""<string name="engine_option_rpg_mz_v2">([^<]*)</string>""").find(xml)
            assertTrue("$locale 必须有 engine_option_rpg_mz_v2", match != null)
            assertEquals("$locale 的 MZ 版本显示名", "TynMZ-0.2", match!!.groupValues[1])
        }
    }

    @Test
    fun mzOnlyRowsExistInEveryLocale() {
        val required = listOf(
            "engine_settings_rpg_effekseer_mode",
            "engine_settings_rpg_effekseer_mode_auto",
            "engine_settings_rpg_effekseer_mode_on",
            "engine_settings_rpg_effekseer_mode_off",
            "engine_settings_rpg_audio_streaming",
        )
        for (locale in listOf("values", "values-en", "values-ja")) {
            val xml = strings(locale)
            for (name in required) {
                assertTrue("$locale 缺少 $name", xml.contains("<string name=\"$name\">"))
            }
        }
    }

    /** 版本**值**不动：改名被误做成改值时，已存的用户设置会全部失效。 */
    @Test
    fun mzVersionValueStaysV2() {
        assertEquals("v2", com.tyranor.next.core.settings.EngineSettingsStore.RPG_MZ_V2)
        assertEquals("v0", com.tyranor.next.core.settings.EngineSettingsStore.RPG_MZ_V0)
    }
}