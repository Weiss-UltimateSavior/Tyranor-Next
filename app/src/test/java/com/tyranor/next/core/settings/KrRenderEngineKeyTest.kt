package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * krkr_engine_prefs 的引擎键名回归锚（issue #109）：
 * 写入引擎 XML 的 Item 键必须是 Kirikiroid2 内核（PreferenceConfig.h 中
 * tTVPPreferenceInfo* 构造参数第二项）读取的存储键。曾误用 App 侧 kr_* 前缀键，
 * 内核不识别，渲染器等 8 项设置被静默忽略（preference_* 只是 locale 文案 id）。
 */
class KrRenderEngineKeyTest {

    @Test
    fun `engine keys match Kirikiroid2 kernel preference keys`() {
        // 预期侧用内核真名字面量（Kirikiroid2 PreferenceConfig.h tTVPPreferenceInfo 第二参），
        // 防止 ENGINE_* 常量与映射被同步改错时测试失效。
        val expected = mapOf(
            EngineSettingsStore.KEY_KR_RENDERER to "renderer",
            EngineSettingsStore.KEY_KR_SOFTWARE_DRAW_THREAD to "software_draw_thread",
            EngineSettingsStore.KEY_KR_SOFTWARE_COMPRESS_TEX to "software_compress_tex",
            EngineSettingsStore.KEY_KR_OGL_COMPRESS_TEX to "ogl_compress_tex",
            EngineSettingsStore.KEY_KR_MEM_USAGE to "memusage",
            EngineSettingsStore.KEY_KR_OGL_MAX_TEXSIZE to "ogl_max_texsize",
            EngineSettingsStore.KEY_KR_OGL_ACCURATE_RENDER to "ogl_accurate_render",
            EngineSettingsStore.KEY_KR_FPS_LIMIT to "fps_limit",
            EngineSettingsStore.KEY_KR_VCURSOR_SCALE to "vcursor_scale",
            EngineSettingsStore.KEY_KR_MENU_HANDLER_OPA to "menu_handler_opa",
        )
        assertEquals(expected, KrRenderPrefs.ALL.associate { it.globalKey to it.engineKey })
    }

    @Test
    fun `engine keys are unique never kr_ prefixed and never blank`() {
        val engineKeys = KrRenderPrefs.ALL.map { it.engineKey }
        assertEquals(engineKeys.size, engineKeys.toSet().size)
        assertTrue(
            "引擎键不得携带 App 侧 kr_ 前缀：$engineKeys",
            engineKeys.none { it.startsWith("kr_") },
        )
        assertTrue(
            "引擎键不得为空串（空键会导致 JSON Item 丢失）：$engineKeys",
            engineKeys.all { it.isNotBlank() },
        )
    }
}
