package com.tyranor.next.core.settings

/**
 * KR 渲染/内存偏好字段的单一来源（P0-3）：应用级 prefs 键、单游戏覆盖字段、引擎 XML 键
 * 三者的映射只在此定义。新增一个 KR 渲染偏好 = 在此加一行。
 *
 * - [EngineSettingsStore.KR_RENDER_PREF_KEYS] 与 [PerGameSettingsStore.KR_FIELDS] 由本表派生，
 *   保证两侧字段清单与顺序永远一致；
 * - `krkr_engine_prefs` JSON 的组装（[EngineSettingsStore.buildKrEnginePrefsJson]）直接遍历本表。
 */
data class KrRenderPref(
    /** 应用级 SharedPreferences 键（`tyranor_prefs`）。 */
    val globalKey: String,
    /** 单游戏覆盖 JSON 字段名（`tyranor_game_overrides`）。 */
    val overrideField: String,
    /** 写入引擎 XML（Kirikiroid2Preference.xml 等）的 Item 键。 */
    val engineKey: String,
)

object KrRenderPrefs {
    val ALL: List<KrRenderPref> = listOf(
        KrRenderPref(
            EngineSettingsStore.KEY_KR_RENDERER,
            PerGameSettingsStore.F_RENDERER,
            EngineSettingsStore.KEY_KR_RENDERER,
        ),
        KrRenderPref(
            EngineSettingsStore.KEY_KR_SOFTWARE_DRAW_THREAD,
            PerGameSettingsStore.F_SOFTWARE_DRAW_THREAD,
            EngineSettingsStore.KEY_KR_SOFTWARE_DRAW_THREAD,
        ),
        KrRenderPref(
            EngineSettingsStore.KEY_KR_SOFTWARE_COMPRESS_TEX,
            PerGameSettingsStore.F_SOFTWARE_COMPRESS_TEX,
            EngineSettingsStore.KEY_KR_SOFTWARE_COMPRESS_TEX,
        ),
        KrRenderPref(
            EngineSettingsStore.KEY_KR_OGL_COMPRESS_TEX,
            PerGameSettingsStore.F_OGL_COMPRESS_TEX,
            EngineSettingsStore.KEY_KR_OGL_COMPRESS_TEX,
        ),
        KrRenderPref(
            EngineSettingsStore.KEY_KR_MEM_USAGE,
            PerGameSettingsStore.F_MEM_USAGE,
            EngineSettingsStore.KEY_KR_MEM_USAGE,
        ),
        KrRenderPref(
            EngineSettingsStore.KEY_KR_OGL_MAX_TEXSIZE,
            PerGameSettingsStore.F_OGL_MAX_TEXSIZE,
            EngineSettingsStore.KEY_KR_OGL_MAX_TEXSIZE,
        ),
        KrRenderPref(
            EngineSettingsStore.KEY_KR_OGL_ACCURATE_RENDER,
            PerGameSettingsStore.F_OGL_ACCURATE_RENDER,
            EngineSettingsStore.KEY_KR_OGL_ACCURATE_RENDER,
        ),
        KrRenderPref(
            EngineSettingsStore.KEY_KR_FPS_LIMIT,
            PerGameSettingsStore.F_FPS_LIMIT,
            EngineSettingsStore.KEY_KR_FPS_LIMIT,
        ),
        KrRenderPref(
            EngineSettingsStore.KEY_KR_VCURSOR_SCALE,
            PerGameSettingsStore.F_VCURSOR_SCALE,
            EngineSettingsStore.ENGINE_VCURSOR_SCALE,
        ),
        KrRenderPref(
            EngineSettingsStore.KEY_KR_MENU_HANDLER_OPA,
            PerGameSettingsStore.F_MENU_HANDLER_OPA,
            EngineSettingsStore.ENGINE_MENU_HANDLER_OPA,
        ),
    )

    val BY_GLOBAL_KEY: Map<String, KrRenderPref> = ALL.associateBy { it.globalKey }
}
