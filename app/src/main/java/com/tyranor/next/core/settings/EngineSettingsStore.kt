package com.tyranor.next.core.settings

import android.content.Context
import com.core.engine.EnginePrefs
import org.json.JSONObject

/**
 * 引擎设置存储层。键名与 RinneMobile 保持一致：
 * - KRKR / Artemis / Tyrano 的全局设置存 tyranor_prefs（原 yukihub_prefs，引擎进程读取同一 prefs）
 * - ONS 设置存 onsyuri 的 gameargs JSON（OnsSettings.load 读取同文件）
 *
 * 设置值经 launcher 在启动时以 Intent extra 注入引擎（KR 走 krkr_engine_prefs 等，
 * 见 EngineLauncher），ONS 则由引擎进程直接读 prefs。
 */
object EngineSettingsStore {

    // 与 CorePreferences 一致的键名（Kr 引擎）
    const val KEY_KR_ENGINE_VERSION = "kr_engine_version"
    const val KEY_KR_ENGINE_KERNEL = "kr_engine_kernel"
    const val KEY_KR_DEFAULT_FONT = "kr_default_font"
    const val KEY_KR_FORCE_DEFAULT_FONT = "kr_force_default_font"
    const val KEY_KR_RENDERER = "kr_renderer"
    const val KEY_KR_SOFTWARE_DRAW_THREAD = "kr_software_draw_thread"
    const val KEY_KR_SOFTWARE_COMPRESS_TEX = "kr_software_compress_tex"
    const val KEY_KR_OGL_COMPRESS_TEX = "kr_ogl_compress_tex"
    const val KEY_KR_MEM_USAGE = "kr_mem_usage"
    const val KEY_KR_OGL_MAX_TEXSIZE = "kr_ogl_max_texsize"
    const val KEY_KR_OGL_ACCURATE_RENDER = "kr_ogl_accurate_render"
    const val KEY_KR_FPS_LIMIT = "kr_fps_limit"
    const val KEY_KR_VCURSOR_SCALE = "kr_vcursor_scale"
    const val KEY_KR_MENU_HANDLER_OPA = "kr_menu_handler_opa"
    const val KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir"
    const val KEY_KR_PATCH_OVERLAY_MODE = "kr_patch_overlay_mode"
    const val KEY_KR_SKIP_STARTUP_DIALOGS = "kr_skip_startup_dialogs"
    const val KEY_KR_ANIME4K_MODE = "kr_anime4k_mode"

    // Artemis 应用级默认
    const val KEY_ARTEMIS_ENGINE_VERSION = "artemis_engine_version"
    const val KEY_ARTEMIS_ROTATE_SCREEN = "artemis_rotate_screen"
    const val KEY_ARTEMIS_AUTO_PATCH = "artemis_auto_patch"
    const val KEY_ARTEMIS_RESOLUTION = "artemis_resolution"
    const val KEY_ARTEMIS_SIDE_CUT = "artemis_side_cut"
    const val KEY_ARTEMIS_SURFACE_CACHE_SIZE = "artemis_surface_cache_size"
    const val KEY_ARTEMIS_FONT_CACHE_SIZE = "artemis_font_cache_size"
    const val KEY_ARTEMIS_POWER_SAVING = "artemis_power_saving"

    // Ren'Py 应用级默认（外置模块版本选择）
    const val KEY_RENPY_ENGINE_VERSION = "renpy_engine_version"

    // Ren'Py 外置模块（settings extra 的 renpy 节；cheats 发 app 节，键名与 RenPyConfigurationParser 一致）
    const val KEY_RENPY_CHEATS = "renpy_cheats"
    const val KEY_RENPY_HW_VIDEO = "renpy_hw_video"
    const val KEY_RENPY_AUTOSAVE = "renpy_autosave"
    const val KEY_RENPY_PHONE_SMALL_VARIANT = "renpy_phonesmallvariant"
    const val KEY_RENPY_VSYNC = "renpy_vsync"
    const val KEY_RENPY_LESS_MEMORY = "renpy_less_memory"
    const val KEY_RENPY_LESS_UPDATES = "renpy_less_updates"
    const val KEY_RENPY_DONT_USE_GL2 = "renpy_dont_use_gl2"
    const val KEY_RENPY_RECOMPILE = "renpy_recompile"

    // Tyrano 与 RPG Maker Web 共用同一套 WebView 宿主设置；启动链路按同一键读取。
    const val KEY_TYRANO_EXTERNAL_NETWORK = "tyrano_external_network"
    const val KEY_TYRANO_SCOPED_SAVE_DIR = "tyrano_scoped_save_dir"
    const val KEY_RPG_MAKER_MOD_ENABLED = "rpg_maker_mod_enabled"
    const val KEY_RPG_LEGACY_RENDERER = "rpg_legacy_renderer"
    const val KEY_RPG_SAVE_INTEROP = "rpg_save_interop"
    const val KEY_RPG_MV_ENGINE_VERSION = "rpg_mv_engine_version"
    const val KEY_RPG_MZ_ENGINE_VERSION = "rpg_mz_engine_version"

    // RPG Maker RGSS 外置模块（settings extra 的 rpg 节，键名与 RPGM 插件 MKXPConfigurationParser 一致）
    const val KEY_RPG_USE_RUBY18 = "rpg_use_ruby18"
    const val KEY_RPG_DEBUG = "rpg_debug"
    const val KEY_RPG_SMOOTH_SCALING = "rpg_smooth_scaling"
    const val KEY_RPG_VSYNC = "rpg_vsync"
    const val KEY_RPG_FRAME_SKIP = "rpg_frame_skip"
    const val KEY_RPG_SOLID_FONTS = "rpg_solid_fonts"
    const val KEY_RPG_PATH_CACHE = "rpg_path_cache"
    const val KEY_RPG_PREBUILT_PATH_CACHE = "rpg_prebuilt_path_cache"
    const val KEY_RPG_FAST_PATH_ENUM = "rpg_fast_path_enum"
    const val KEY_RPG_COPY_TEXT = "rpg_copy_text"
    const val KEY_RPG_CHEATS = "rpg_cheats"
    const val KEY_RPG_USE_CJK_FONT = "rpg_use_cjk_font"
    const val KEY_RPG_ENABLE_POSTLOAD_SCRIPTS = "rpg_enable_postload_scripts"
    const val KEY_RPG_CUSTOM_FONT = "rpg_custom_font"
    const val KEY_RPG_VERTICAL_SCREEN_ALIGN = "rpg_vertical_screen_align"
    const val KEY_RPG_WINDOW_SIZE = "rpg_window_size"
    const val KEY_RPG_SPEED_UP = "rpg_speed_up"
    const val KEY_RPG_FONT_SCALE = "rpg_font_scale"

    // 取值常量
    const val KR_AUTO = "auto"
    const val KR_139 = "1.3.9"
    const val KR_134 = "1.3.4"
    const val KR_126 = "1.2.6"
    const val KERNEL_KIRIKIRI2 = "kirikiri2"
    const val KERNEL_KRKRSDL3 = "krkrsdl3"
    const val KR_PATCH_OVERLAY_AUTO = "auto"
    const val KR_PATCH_OVERLAY_FORCE = "force"
    const val KR_PATCH_OVERLAY_OFF = "off"

    // Anime4K 画面超分模式（仅 kirikiri2 内核路径生效；取值与引擎侧 Anime4kRuntime 一致）
    const val ANIME4K_OFF = "off"
    const val ANIME4K_S = "s"
    const val ANIME4K_M = "m"
    const val ANIME4K_L = "l"
    const val ANIME4K_SOFT_S = "soft_s"
    const val ANIME4K_SOFT_M = "soft_m"
    const val ANIME4K_SOFT_L = "soft_l"
    const val ANIME4K_DEBLUR = "deblur"
    /** Anime4K 模式全量白名单，供单游戏覆盖值校验（非法持久化值回退全局）。 */
    val ANIME4K_MODES = setOf(
        ANIME4K_OFF, ANIME4K_S, ANIME4K_M, ANIME4K_L,
        ANIME4K_SOFT_S, ANIME4K_SOFT_M, ANIME4K_SOFT_L, ANIME4K_DEBLUR,
    )

    const val RENDERER_SOFTWARE = "software"
    const val RENDERER_OPENGL = "opengl"

    // 写入引擎 XML（Kirikiroid2Preference.xml/GlobalPreference.xml）的 Item 键名，
    // 与 libgame.so 内 IndividualConfigManager 读取的键一致，键名不可改。
    const val ENGINE_VCURSOR_SCALE = "vcursor_scale"
    const val ENGINE_MENU_HANDLER_OPA = "menu_handler_opa"
    // 虚拟鼠标缩放比（0.01..1.50，引擎默认 1.00；0.50 与原版 Ty 一致，两位小数）
    val KR_VCURSOR_SCALE_MIN = 0.01f
    val KR_VCURSOR_SCALE_MAX = 1.50f
    val KR_VCURSOR_SCALES: Set<String> = (1..150).map { String.format(java.util.Locale.US, "%.2f", it / 100.0) }.toSet()
    // 菜单按钮不透明度（1..100，引擎默认 100；滚动条 1..100 全可选）
    val KR_MENU_HANDLER_OPA_RANGE = 1..100
    val KR_MENU_HANDLER_OPAS: Set<String> = KR_MENU_HANDLER_OPA_RANGE.map { it.toString() }.toSet()
    const val MEM_USAGE_UNLIMITED = "unlimited"
    const val MEM_USAGE_HIGH = "high"
    const val MEM_USAGE_MEDIUM = "medium"
    const val MEM_USAGE_LOW = "low"

    const val ART_ENGINE_AUTO = "auto"
    const val ART_ENGINE_V1 = "1"
    const val ART_ENGINE_V2 = "2"
    const val ART_ENGINE_V3 = "3"
    const val ART_ENGINE_V4 = "4"
    const val ART_ENGINE_V5 = "5"
    const val AUTO_PATCH_ASK = "ask"
    const val AUTO_PATCH_AUTO = "auto"
    const val AUTO_PATCH_OFF = "off"
    /** Artemis 引擎版本全量白名单，供单游戏覆盖值校验（非法持久化值回退全局）。 */
    val ART_VERSIONS = setOf(ART_ENGINE_AUTO, ART_ENGINE_V1, ART_ENGINE_V2, ART_ENGINE_V3, ART_ENGINE_V4, ART_ENGINE_V5)
    /** Artemis 补丁策略全量白名单，供单游戏覆盖值校验（非法持久化值回退全局）。 */
    val ART_PATCHES = setOf(AUTO_PATCH_ASK, AUTO_PATCH_AUTO, AUTO_PATCH_OFF)
    const val ART_RESOLUTION_DEFAULT = ""
    const val ART_RESOLUTION_1920_1080 = "1920x1080"
    const val ART_RESOLUTION_1280_720 = "1280x720"
    const val ART_RESOLUTION_960_540 = "960x540"
    const val ART_TOGGLE_DEFAULT = ""
    const val ART_TOGGLE_OFF = "0"
    const val ART_TOGGLE_ON = "1"
    const val ART_CACHE_DEFAULT = ""
    const val ART_SURFACE_CACHE_64MB = "67108864"
    const val ART_SURFACE_CACHE_128MB = "134217728"
    const val ART_SURFACE_CACHE_256MB = "268435456"
    const val ART_FONT_CACHE_8MB = "8388608"
    const val ART_FONT_CACHE_16MB = "16777216"
    const val ART_FONT_CACHE_32MB = "33554432"
    const val ART_FONT_CACHE_64MB = "67108864"
    val ART_RESOLUTIONS = setOf(
        ART_RESOLUTION_DEFAULT,
        ART_RESOLUTION_1920_1080,
        ART_RESOLUTION_1280_720,
        ART_RESOLUTION_960_540,
    )
    val ART_TOGGLES = setOf(ART_TOGGLE_DEFAULT, ART_TOGGLE_OFF, ART_TOGGLE_ON)
    val ART_SURFACE_CACHES = setOf(
        ART_CACHE_DEFAULT,
        ART_SURFACE_CACHE_64MB,
        ART_SURFACE_CACHE_128MB,
        ART_SURFACE_CACHE_256MB,
    )
    val ART_FONT_CACHES = setOf(
        ART_CACHE_DEFAULT,
        ART_FONT_CACHE_8MB,
        ART_FONT_CACHE_16MB,
        ART_FONT_CACHE_32MB,
        ART_FONT_CACHE_64MB,
    )

    const val RPG_MV_V0 = "v0"
    const val RPG_MZ_V0 = "v0"
    const val RPG_MV_V1 = "v1"
    const val RPG_MZ_V1 = "v1"
    const val RPG_MV_V2 = "v2"
    const val RPG_MZ_V2 = "v2"
    // 与 PerGameSettingsStore.F_RPG_* 同名，分属不同 prefs 文件（yukihub_prefs vs tyranor_game_overrides）
    // Ren'Py 版本取值常量
    const val RENPY_AUTO = "auto"
    const val RENPY_85 = "8.5"
    const val RENPY_77 = "7.7.1"

    // RPG Maker RGSS 外置模块取值域（对齐 JoiPlay utilities/f.java；verticalAlign 对齐插件默认）
    const val RPG_WINDOW_SIZE_DEFAULT = "640x480"
    const val RPG_SPEED_UP_DEFAULT = "1"
    const val RPG_FONT_SCALE_DEFAULT = "0.75"
    const val RPG_VERTICAL_ALIGN_DEFAULT = "top-center"
    val RPG_WINDOW_SIZES: Set<String> = linkedSetOf(
        "512x384", "512x768", "544x416", "640x480", "800x600",
        "1024x768", "1280x720", "1280x960", "1920x1080",
    )
    val RPG_SPEED_UPS: Set<String> = (1..9).map { it.toString() }.toSet()
    val RPG_FONT_SCALES: Set<String> =
        setOf("0.25", "0.50", "0.75", "1.00", "1.25", "1.50", "1.75", "2.00")
    val RPG_VERTICAL_ALIGNS: Set<String> = setOf("top", "top-center", "center")

    /** KR 渲染/内存偏好字段清单（由 [KrRenderPrefs] 派生，保证与单游戏覆盖字段一一对应）。 */
    val KR_RENDER_PREF_KEYS: List<String> = KrRenderPrefs.ALL.map { it.globalKey }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)

    private fun onsPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("onsyuri", Context.MODE_PRIVATE)

    // ---------- KRKR ----------
    fun getKrEngineVersion(c: Context): String =
        normalizeKr(prefs(c).getString(KEY_KR_ENGINE_VERSION, KR_AUTO))
    fun setKrEngineVersion(c: Context, v: String) =
        prefs(c).edit().putString(KEY_KR_ENGINE_VERSION, normalizeKr(v)).apply()

    fun getKrKernel(c: Context): String {
        val v = prefs(c).getString(KEY_KR_ENGINE_KERNEL, KR_AUTO)
        return when (v) { KERNEL_KIRIKIRI2, KERNEL_KRKRSDL3 -> v; else -> KR_AUTO }
    }
    fun setKrKernel(c: Context, v: String) = prefs(c).edit().putString(KEY_KR_ENGINE_KERNEL, v).apply()

    fun isKrScopedSaveDir(c: Context): Boolean =
        prefs(c).getBoolean(KEY_KR_SCOPED_SAVE_DIR, true)
    fun setKrScopedSaveDir(c: Context, b: Boolean) =
        prefs(c).edit().putBoolean(KEY_KR_SCOPED_SAVE_DIR, b).apply()

    fun getKrPatchOverlayMode(c: Context): String =
        normalizeKrPatchOverlayMode(prefs(c).getString(KEY_KR_PATCH_OVERLAY_MODE, KR_PATCH_OVERLAY_AUTO))
    fun setKrPatchOverlayMode(c: Context, v: String) =
        prefs(c).edit().putString(KEY_KR_PATCH_OVERLAY_MODE, normalizeKrPatchOverlayMode(v)).apply()

    fun getKrAnime4kMode(c: Context): String {
        val v = prefs(c).getString(KEY_KR_ANIME4K_MODE, null)
        return if (v != null && v in ANIME4K_MODES) v else ANIME4K_OFF
    }
    fun setKrAnime4kMode(c: Context, v: String) =
        prefs(c).edit().putString(KEY_KR_ANIME4K_MODE, if (v in ANIME4K_MODES) v else ANIME4K_OFF).apply()

    /** Automatically confirm one-button KRKR information dialogs during the first 30 seconds. */
    fun isKrSkipStartupDialogs(c: Context): Boolean =
        prefs(c).getBoolean(KEY_KR_SKIP_STARTUP_DIALOGS, false)

    fun setKrSkipStartupDialogs(c: Context, enabled: Boolean) =
        prefs(c).edit().putBoolean(KEY_KR_SKIP_STARTUP_DIALOGS, enabled).apply()

    fun getKrDefaultFont(c: Context): String = prefs(c).getString(KEY_KR_DEFAULT_FONT, "").orEmpty()
    fun setKrDefaultFont(c: Context, p: String) = prefs(c).edit().putString(KEY_KR_DEFAULT_FONT, p.trim()).apply()

    fun isKrForceDefaultFont(c: Context): Boolean = prefs(c).getBoolean(KEY_KR_FORCE_DEFAULT_FONT, false)
    fun setKrForceDefaultFont(c: Context, b: Boolean) = prefs(c).edit().putBoolean(KEY_KR_FORCE_DEFAULT_FONT, b).apply()

    private fun krPref(c: Context, key: String): String = prefs(c).getString(key, null).orEmpty()
    private fun setKrPref(c: Context, key: String, v: String?) = prefs(c).edit().putString(key, v?.trim().orEmpty()).apply()

    fun getKrRenderer(c: Context): String = if (krPref(c, KEY_KR_RENDERER) in setOf(RENDERER_SOFTWARE, RENDERER_OPENGL)) krPref(c, KEY_KR_RENDERER) else ""
    fun setKrRenderer(c: Context, v: String) = setKrPref(c, KEY_KR_RENDERER, v)
    fun getKrSoftwareDrawThread(c: Context): String { val n = krPref(c, KEY_KR_SOFTWARE_DRAW_THREAD).toIntOrNull() ?: return ""; return if (n in 0..8) n.toString() else "" }
    fun setKrSoftwareDrawThread(c: Context, v: String) = setKrPref(c, KEY_KR_SOFTWARE_DRAW_THREAD, v)
    fun getKrSoftwareCompressTex(c: Context): String { val v = krPref(c, KEY_KR_SOFTWARE_COMPRESS_TEX); return if (v in setOf("none", "halfline", "lz4", "lz4+tlg5")) v else "" }
    fun setKrSoftwareCompressTex(c: Context, v: String) = setKrPref(c, KEY_KR_SOFTWARE_COMPRESS_TEX, v)
    fun getKrOglCompressTex(c: Context): String { val v = krPref(c, KEY_KR_OGL_COMPRESS_TEX); return if (v in setOf("none", "half", "etc2", "pvrtc")) v else "" }
    fun setKrOglCompressTex(c: Context, v: String) = setKrPref(c, KEY_KR_OGL_COMPRESS_TEX, v)
    fun getKrMemUsage(c: Context): String { val v = krPref(c, KEY_KR_MEM_USAGE); return if (v in setOf(MEM_USAGE_UNLIMITED, MEM_USAGE_HIGH, MEM_USAGE_MEDIUM, MEM_USAGE_LOW)) v else "" }
    fun setKrMemUsage(c: Context, v: String) = setKrPref(c, KEY_KR_MEM_USAGE, v)
    fun getKrOglMaxTexsize(c: Context): String { val n = krPref(c, KEY_KR_OGL_MAX_TEXSIZE).toIntOrNull() ?: return ""; return if (n == 0 || n in 1024..16384) n.toString() else "" }
    fun setKrOglMaxTexsize(c: Context, v: String) = setKrPref(c, KEY_KR_OGL_MAX_TEXSIZE, v)
    fun getKrOglAccurateRender(c: Context): String = when (krPref(c, KEY_KR_OGL_ACCURATE_RENDER)) { "1", "true" -> "1"; "0", "false" -> "0"; else -> "" }
    fun setKrOglAccurateRender(c: Context, v: String) = setKrPref(c, KEY_KR_OGL_ACCURATE_RENDER, v)
    fun getKrFpsLimit(c: Context): String { val v = krPref(c, KEY_KR_FPS_LIMIT); return if (v in setOf("60", "45", "30", "15")) v else "" }
    fun setKrFpsLimit(c: Context, v: String) = setKrPref(c, KEY_KR_FPS_LIMIT, v)
    fun getKrVCursorScale(c: Context): String {
        val v = krPref(c, KEY_KR_VCURSOR_SCALE).trim()
        if (v.isEmpty()) return ""
        // 兼容旧版整型百分比 1..150（如 "50" → "0.50"）
        normalizeVcursorScale(v)?.let { return it }
        return ""
    }
    fun setKrVCursorScale(c: Context, v: String) {
        val t = v.trim()
        if (t.isEmpty()) { setKrPref(c, KEY_KR_VCURSOR_SCALE, ""); return }
        normalizeVcursorScale(t)?.let { setKrPref(c, KEY_KR_VCURSOR_SCALE, it) }
    }

    fun normalizeVcursorScale(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t in KR_VCURSOR_SCALES) return t
        // 旧整型百分比兼容： "50" → "0.50"
        t.toIntOrNull()?.let { iv ->
            if (iv in 1..150) return String.format(java.util.Locale.US, "%.2f", iv / 100.0)
        }
        // 单小数位等宽容： "0.5" → "0.50"，"1" → "1.00"
        val f = t.toFloatOrNull() ?: return null
        if (f < KR_VCURSOR_SCALE_MIN - 1e-6 || f > KR_VCURSOR_SCALE_MAX + 1e-6) return null
        // 四舍五入到两位小数
        val rounded = Math.round(f * 100) / 100.0
        val formatted = String.format(java.util.Locale.US, "%.2f", rounded)
        return if (formatted in KR_VCURSOR_SCALES) formatted else null
    }

    /** 供 PerGameSettingsScreen 等对旧整型/单小数位做展示归一，空串保持空 */
    fun normalizeVcursorScaleForDisplay(raw: String?): String? {
        if (raw == null) return null
        val t = raw.trim()
        if (t.isEmpty()) return null
        return normalizeVcursorScale(t) ?: t
    }
    fun getKrMenuHandlerOpa(c: Context): String { val v = krPref(c, KEY_KR_MENU_HANDLER_OPA).trim(); return if (v in KR_MENU_HANDLER_OPAS) v else "" }
    fun setKrMenuHandlerOpa(c: Context, v: String) { val t = v.trim(); if (t.isEmpty() || t in KR_MENU_HANDLER_OPAS) setKrPref(c, KEY_KR_MENU_HANDLER_OPA, t) }

    /** 组装 krkr_engine_prefs JSON：{<引擎键>:{v, s}}。overrideGetter 返回某偏好的单游戏覆盖（null=跟随全局）。 */
    fun buildKrEnginePrefsJson(c: Context, overrideGetter: (KrRenderPref) -> String? = { null }): String {
        val json = JSONObject()
        KrRenderPrefs.ALL.forEach { pref ->
            val key = pref.globalKey
            val rawOverride = overrideGetter(pref)
            val override = rawOverride?.trim()?.takeIf { it.isNotEmpty() || rawOverride == "" }
            // 仅保留合法值或显式空串（引擎默认），非法值按跟随全局处理；vcursor 支持旧整型兼容
            val sanitizedOverride = when (key) {
                KEY_KR_VCURSOR_SCALE -> override?.let {
                    if (it.isEmpty()) it else normalizeVcursorScale(it)
                }
                KEY_KR_MENU_HANDLER_OPA -> override?.let { if (it.isEmpty() || it in KR_MENU_HANDLER_OPAS) it else null }
                else -> override
            }
            val globalRaw = prefs(c).getString(key, null).orEmpty().trim()
            val normalizedGlobal = normalizeVcursorScale(globalRaw)
            val sanitizedGlobal = when (key) {
                KEY_KR_VCURSOR_SCALE -> when {
                    globalRaw.isEmpty() -> ""
                    normalizedGlobal != null -> normalizedGlobal
                    else -> ""
                }
                KEY_KR_MENU_HANDLER_OPA -> if (globalRaw in KR_MENU_HANDLER_OPAS) globalRaw else ""
                else -> prefs(c).getString(key, null).orEmpty()
            }
            val rawValue = sanitizedOverride ?: sanitizedGlobal
            // 虚拟鼠标：prefs 存 "0.01".."1.50"（两位小数），引擎需浮点字符串，去尾零与 Ty 一致
            val value = when (key) {
                KEY_KR_VCURSOR_SCALE -> if (rawValue.isEmpty()) "" else {
                    // 已是两位小数格式，去尾零后与原版 Ty 的 "0.5" 等价
                    rawValue.trimEnd('0').trimEnd('.').ifEmpty { rawValue }
                }
                else -> rawValue
            }
            json.put(pref.engineKey, JSONObject().put("v", value).put("s", if (sanitizedOverride != null) "game" else "global"))
        }
        return json.toString()
    }

    private fun normalizeKr(v: String?): String = when (v?.trim()?.lowercase()) {
        KR_139 -> KR_139
        KR_134 -> KR_134
        KR_126 -> KR_126
        else -> KR_AUTO
    }

    fun normalizeKrPatchOverlayMode(v: String?): String = when (v?.trim()?.lowercase()) {
        KR_PATCH_OVERLAY_FORCE -> KR_PATCH_OVERLAY_FORCE
        KR_PATCH_OVERLAY_OFF -> KR_PATCH_OVERLAY_OFF
        else -> KR_PATCH_OVERLAY_AUTO
    }

    // ---------- ONS（存 onsyuri/gameargs JSON，引擎进程 OnsSettings.load 直接读） ----------
    data class Ons(
        var scopedSaveDir: Boolean = true,
        var stretchFull: Boolean = false,
        var ignoreCutout: Boolean = true,
        var disableVideo: Boolean = false,
        var sharpness: Boolean = false,
        var sharpnessValue: String = "2",
        var encoding: String = "gbk",
    ) {
        fun toJson(): String =
            JSONObject()
                .put("scopedsavedir", scopedSaveDir)
                .put("strechfull", stretchFull)
                .put("ignorecutout", ignoreCutout)
                .put("disablevideo", disableVideo)
                .put("sharpness", sharpness)
                .put("sharpness_value", sharpnessValue)
                .put("encoding", normalizeEncoding(encoding))
                .toString()
    }

    fun loadOns(c: Context): Ons {
        val o = Ons()
        try {
            val json = onsPrefs(c).getString("gameargs", null) ?: return o
            val j = JSONObject(json)
            o.scopedSaveDir = j.optBoolean("scopedsavedir", o.scopedSaveDir)
            o.stretchFull = j.optBoolean("strechfull", o.stretchFull)
            o.ignoreCutout = j.optBoolean("ignorecutout", o.ignoreCutout)
            o.disableVideo = j.optBoolean("disablevideo", o.disableVideo)
            o.sharpness = j.optBoolean("sharpness", o.sharpness)
            o.sharpnessValue = j.optString("sharpness_value", o.sharpnessValue)
            o.encoding = normalizeEncoding(j.optString("encoding", o.encoding))
        } catch (t: Throwable) {
            // 解析失败用默认值
        }
        return o
    }

    fun saveOns(c: Context, o: Ons) = onsPrefs(c).edit().putString("gameargs", o.toJson()).apply()

    fun normalizeEncoding(v: String): String = when (v.trim().lowercase()) {
        "utf8", "utf-8" -> "utf8"
        "sjis", "shift-jis", "shift_jis" -> "sjis"
        else -> "gbk"
    }

    // ---------- Artemis ----------
    fun getArtEngineVersion(c: Context): String {
        val v = prefs(c).getString(KEY_ARTEMIS_ENGINE_VERSION, ART_ENGINE_AUTO)
        return if (
            v == ART_ENGINE_V1 ||
            v == ART_ENGINE_V2 ||
            v == ART_ENGINE_V3 ||
            v == ART_ENGINE_V4 ||
            v == ART_ENGINE_V5
        ) v else ART_ENGINE_AUTO
    }
    fun setArtEngineVersion(c: Context, v: String) = prefs(c).edit().putString(KEY_ARTEMIS_ENGINE_VERSION, v).apply()
    fun isArtRotateScreen(c: Context): Boolean = prefs(c).getBoolean(KEY_ARTEMIS_ROTATE_SCREEN, false)
    fun setArtRotateScreen(c: Context, b: Boolean) = prefs(c).edit().putBoolean(KEY_ARTEMIS_ROTATE_SCREEN, b).apply()
    fun getArtAutoPatch(c: Context): String {
        val v = prefs(c).getString(KEY_ARTEMIS_AUTO_PATCH, AUTO_PATCH_ASK)
        return if (v == AUTO_PATCH_AUTO || v == AUTO_PATCH_OFF) v else AUTO_PATCH_ASK
    }
    fun setArtAutoPatch(c: Context, v: String) = prefs(c).edit().putString(KEY_ARTEMIS_AUTO_PATCH, v).apply()
    fun getArtResolution(c: Context): String = artStringPref(c, KEY_ARTEMIS_RESOLUTION, ART_RESOLUTIONS)
    fun setArtResolution(c: Context, v: String) = setArtStringPref(c, KEY_ARTEMIS_RESOLUTION, v, ART_RESOLUTIONS)
    fun getArtSideCut(c: Context): String = artStringPref(c, KEY_ARTEMIS_SIDE_CUT, ART_TOGGLES)
    fun setArtSideCut(c: Context, v: String) = setArtStringPref(c, KEY_ARTEMIS_SIDE_CUT, v, ART_TOGGLES)
    fun getArtSurfaceCacheSize(c: Context): String = artStringPref(c, KEY_ARTEMIS_SURFACE_CACHE_SIZE, ART_SURFACE_CACHES)
    fun setArtSurfaceCacheSize(c: Context, v: String) = setArtStringPref(c, KEY_ARTEMIS_SURFACE_CACHE_SIZE, v, ART_SURFACE_CACHES)
    fun getArtFontCacheSize(c: Context): String = artStringPref(c, KEY_ARTEMIS_FONT_CACHE_SIZE, ART_FONT_CACHES)
    fun setArtFontCacheSize(c: Context, v: String) = setArtStringPref(c, KEY_ARTEMIS_FONT_CACHE_SIZE, v, ART_FONT_CACHES)
    fun getArtPowerSaving(c: Context): String = artStringPref(c, KEY_ARTEMIS_POWER_SAVING, ART_TOGGLES)
    fun setArtPowerSaving(c: Context, v: String) = setArtStringPref(c, KEY_ARTEMIS_POWER_SAVING, v, ART_TOGGLES)

    private fun artStringPref(c: Context, key: String, allowed: Set<String>): String {
        val value = prefs(c).getString(key, ART_CACHE_DEFAULT).orEmpty().trim()
        return if (value in allowed) value else ART_CACHE_DEFAULT
    }

    private fun setArtStringPref(c: Context, key: String, value: String, allowed: Set<String>) {
        val normalized = value.trim().takeIf { it in allowed } ?: ART_CACHE_DEFAULT
        prefs(c).edit().putString(key, normalized).apply()
    }

    // ---------- Ren'Py ----------
    fun getRenpyVersion(c: Context): String {
        val v = prefs(c).getString(KEY_RENPY_ENGINE_VERSION, RENPY_AUTO)
        return when (v) {
            RENPY_85, RENPY_77 -> v
            else -> RENPY_AUTO
        }
    }
    fun setRenpyVersion(c: Context, v: String) = prefs(c).edit().putString(KEY_RENPY_ENGINE_VERSION, v).apply()

    // ---------- Tyrano ----------
    fun isTyranoExternalNetwork(c: Context): Boolean = prefs(c).getBoolean(KEY_TYRANO_EXTERNAL_NETWORK, false)
    fun setTyranoExternalNetwork(c: Context, b: Boolean) = prefs(c).edit().putBoolean(KEY_TYRANO_EXTERNAL_NETWORK, b).apply()
    fun isTyranoScopedSaveDir(c: Context): Boolean = prefs(c).getBoolean(KEY_TYRANO_SCOPED_SAVE_DIR, true)
    fun setTyranoScopedSaveDir(c: Context, b: Boolean) = prefs(c).edit().putBoolean(KEY_TYRANO_SCOPED_SAVE_DIR, b).apply()
    fun isRpgMakerModEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_RPG_MAKER_MOD_ENABLED, true)
    fun setRpgMakerModEnabled(c: Context, b: Boolean) = prefs(c).edit().putBoolean(KEY_RPG_MAKER_MOD_ENABLED, b).apply()
    fun isRpgLegacyRenderer(c: Context): Boolean = prefs(c).getBoolean(KEY_RPG_LEGACY_RENDERER, false)
    fun setRpgLegacyRenderer(c: Context, b: Boolean) = prefs(c).edit().putBoolean(KEY_RPG_LEGACY_RENDERER, b).apply()
    fun isRpgSaveInterop(c: Context): Boolean = prefs(c).getBoolean(KEY_RPG_SAVE_INTEROP, false)
    fun setRpgSaveInterop(c: Context, b: Boolean) = prefs(c).edit().putBoolean(KEY_RPG_SAVE_INTEROP, b).apply()

    // ---------- RPG Maker MV / MZ ----------
    fun getRpgMvEngineVersion(c: Context): String =
        normalizeRpgMv(prefs(c).getString(KEY_RPG_MV_ENGINE_VERSION, RPG_MV_V0))
    fun setRpgMvEngineVersion(c: Context, v: String) =
        prefs(c).edit().putString(KEY_RPG_MV_ENGINE_VERSION, normalizeRpgMv(v)).apply()
    fun getRpgMzEngineVersion(c: Context): String =
        normalizeRpgMz(prefs(c).getString(KEY_RPG_MZ_ENGINE_VERSION, RPG_MZ_V0))
    fun setRpgMzEngineVersion(c: Context, v: String) =
        prefs(c).edit().putString(KEY_RPG_MZ_ENGINE_VERSION, normalizeRpgMz(v)).apply()

    private fun normalizeRpgMv(v: String?): String = when (v?.trim()?.lowercase()) {
        RPG_MV_V1 -> RPG_MV_V1
        RPG_MV_V2 -> RPG_MV_V2
        RPG_MV_V0 -> RPG_MV_V0
        else -> RPG_MV_V0
    }

    private fun normalizeRpgMz(v: String?): String = when (v?.trim()?.lowercase()) {
        RPG_MZ_V1 -> RPG_MZ_V1
        RPG_MZ_V2 -> RPG_MZ_V2
        RPG_MZ_V0 -> RPG_MZ_V0
        else -> RPG_MZ_V0
    }

    // ---------- RPG Maker RGSS 外置模块 ----------

    /**
     * RGSS/mkxp 外置模块的 rpg 配置节。默认值对齐 JoiPlay `SettingsFactory.loadDefault`：
     * useRuby18/smoothScaling/prebuiltPathCache/cheats 默认开，其余默认关。
     * 仅保留 RPGM 插件 `MKXPConfigurationParser` 实际解析的键。
     */
    data class RpgMaker(
        var useRuby18: Boolean = true,
        var debug: Boolean = false,
        var smoothScaling: Boolean = true,
        var vsync: Boolean = false,
        var frameSkip: Boolean = false,
        var solidFonts: Boolean = false,
        var pathCache: Boolean = false,
        var prebuiltPathCache: Boolean = true,
        var fastPathEnum: Boolean = true,
        var copyText: Boolean = false,
        var cheats: Boolean = true,
        var useCJKFont: Boolean = false,
        var enablePostloadScripts: Boolean = false,
        var customFont: String = "",
        var verticalScreenAlign: String = RPG_VERTICAL_ALIGN_DEFAULT,
        var windowSize: String = RPG_WINDOW_SIZE_DEFAULT,
        var speedUp: String = RPG_SPEED_UP_DEFAULT,
        var fontScale: String = RPG_FONT_SCALE_DEFAULT,
    )

    private val RPG_MAKER_BOOL_KEYS = listOf(
        KEY_RPG_USE_RUBY18, KEY_RPG_DEBUG, KEY_RPG_SMOOTH_SCALING,
        KEY_RPG_VSYNC, KEY_RPG_FRAME_SKIP, KEY_RPG_SOLID_FONTS, KEY_RPG_PATH_CACHE,
        KEY_RPG_PREBUILT_PATH_CACHE, KEY_RPG_FAST_PATH_ENUM, KEY_RPG_COPY_TEXT,
        KEY_RPG_CHEATS, KEY_RPG_USE_CJK_FONT, KEY_RPG_ENABLE_POSTLOAD_SCRIPTS,
    )
    private val RPG_MAKER_STRING_KEYS = listOf(
        KEY_RPG_CUSTOM_FONT, KEY_RPG_VERTICAL_SCREEN_ALIGN,
        KEY_RPG_WINDOW_SIZE, KEY_RPG_SPEED_UP, KEY_RPG_FONT_SCALE,
    )

    fun loadRpgMaker(c: Context): RpgMaker {
        val p = prefs(c)
        val d = RpgMaker()
        return RpgMaker(
            useRuby18 = p.getBoolean(KEY_RPG_USE_RUBY18, d.useRuby18),
            debug = p.getBoolean(KEY_RPG_DEBUG, d.debug),
            smoothScaling = p.getBoolean(KEY_RPG_SMOOTH_SCALING, d.smoothScaling),
            vsync = p.getBoolean(KEY_RPG_VSYNC, d.vsync),
            frameSkip = p.getBoolean(KEY_RPG_FRAME_SKIP, d.frameSkip),
            solidFonts = p.getBoolean(KEY_RPG_SOLID_FONTS, d.solidFonts),
            pathCache = p.getBoolean(KEY_RPG_PATH_CACHE, d.pathCache),
            prebuiltPathCache = p.getBoolean(KEY_RPG_PREBUILT_PATH_CACHE, d.prebuiltPathCache),
            fastPathEnum = p.getBoolean(KEY_RPG_FAST_PATH_ENUM, d.fastPathEnum),
            copyText = p.getBoolean(KEY_RPG_COPY_TEXT, d.copyText),
            cheats = p.getBoolean(KEY_RPG_CHEATS, d.cheats),
            useCJKFont = p.getBoolean(KEY_RPG_USE_CJK_FONT, d.useCJKFont),
            enablePostloadScripts = p.getBoolean(KEY_RPG_ENABLE_POSTLOAD_SCRIPTS, d.enablePostloadScripts),
            customFont = p.getString(KEY_RPG_CUSTOM_FONT, d.customFont).orEmpty(),
            verticalScreenAlign = normalizeRpgVerticalAlign(p.getString(KEY_RPG_VERTICAL_SCREEN_ALIGN, d.verticalScreenAlign)),
            windowSize = normalizeRpgWindowSize(p.getString(KEY_RPG_WINDOW_SIZE, d.windowSize)),
            speedUp = normalizeRpgSpeedUp(p.getString(KEY_RPG_SPEED_UP, d.speedUp)),
            fontScale = normalizeRpgFontScale(p.getString(KEY_RPG_FONT_SCALE, d.fontScale)),
        )
    }

    fun saveRpgMaker(c: Context, r: RpgMaker) {
        prefs(c).edit().apply {
            putBoolean(KEY_RPG_USE_RUBY18, r.useRuby18)
            putBoolean(KEY_RPG_DEBUG, r.debug)
            putBoolean(KEY_RPG_SMOOTH_SCALING, r.smoothScaling)
            putBoolean(KEY_RPG_VSYNC, r.vsync)
            putBoolean(KEY_RPG_FRAME_SKIP, r.frameSkip)
            putBoolean(KEY_RPG_SOLID_FONTS, r.solidFonts)
            putBoolean(KEY_RPG_PATH_CACHE, r.pathCache)
            putBoolean(KEY_RPG_PREBUILT_PATH_CACHE, r.prebuiltPathCache)
            putBoolean(KEY_RPG_FAST_PATH_ENUM, r.fastPathEnum)
            putBoolean(KEY_RPG_COPY_TEXT, r.copyText)
            putBoolean(KEY_RPG_CHEATS, r.cheats)
            putBoolean(KEY_RPG_USE_CJK_FONT, r.useCJKFont)
            putBoolean(KEY_RPG_ENABLE_POSTLOAD_SCRIPTS, r.enablePostloadScripts)
            putString(KEY_RPG_CUSTOM_FONT, r.customFont.trim())
            putString(KEY_RPG_VERTICAL_SCREEN_ALIGN, normalizeRpgVerticalAlign(r.verticalScreenAlign))
            putString(KEY_RPG_WINDOW_SIZE, normalizeRpgWindowSize(r.windowSize))
            putString(KEY_RPG_SPEED_UP, normalizeRpgSpeedUp(r.speedUp))
            putString(KEY_RPG_FONT_SCALE, normalizeRpgFontScale(r.fontScale))
        }.apply()
    }

    /** 仅重置 rpg 节，其余引擎设置不动（对齐 JoiPlay resetSettingsButton 的作用域）。 */
    fun resetRpgMaker(c: Context) {
        val editor = prefs(c).edit()
        RPG_MAKER_BOOL_KEYS.forEach(editor::remove)
        RPG_MAKER_STRING_KEYS.forEach(editor::remove)
        editor.apply()
    }

    fun normalizeRpgWindowSize(v: String?): String {
        val t = v?.trim().orEmpty()
        return if (t in RPG_WINDOW_SIZES) t else RPG_WINDOW_SIZE_DEFAULT
    }

    fun normalizeRpgSpeedUp(v: String?): String {
        val t = v?.trim().orEmpty()
        return if (t in RPG_SPEED_UPS) t else RPG_SPEED_UP_DEFAULT
    }

    fun normalizeRpgFontScale(v: String?): String {
        val t = v?.trim().orEmpty()
        return if (t in RPG_FONT_SCALES) t else RPG_FONT_SCALE_DEFAULT
    }

    fun normalizeRpgVerticalAlign(v: String?): String {
        val t = v?.trim().orEmpty()
        return if (t in RPG_VERTICAL_ALIGNS) t else RPG_VERTICAL_ALIGN_DEFAULT
    }

    // ---------- Ren'Py 外置模块 ----------

    /**
     * Ren'Py 外置模块的配置节。默认值对齐 JoiPlay `SettingsFactory.loadDefault` 与插件
     * `RenPyConfiguration`：hw_video/cheats 默认开，其余默认关。
     */
    data class RenPy(
        var cheats: Boolean = true,
        var hwVideo: Boolean = true,
        var autosave: Boolean = false,
        var phoneSmallVariant: Boolean = false,
        var vsync: Boolean = false,
        var lessMemory: Boolean = false,
        var lessUpdates: Boolean = false,
        var dontUseGl2: Boolean = false,
        var recompile: Boolean = false,
    )

    private val RENPY_BOOL_KEYS = listOf(
        KEY_RENPY_CHEATS, KEY_RENPY_HW_VIDEO, KEY_RENPY_AUTOSAVE,
        KEY_RENPY_PHONE_SMALL_VARIANT, KEY_RENPY_VSYNC, KEY_RENPY_LESS_MEMORY,
        KEY_RENPY_LESS_UPDATES, KEY_RENPY_DONT_USE_GL2, KEY_RENPY_RECOMPILE,
    )

    fun loadRenPy(c: Context): RenPy {
        val p = prefs(c)
        val d = RenPy()
        return RenPy(
            cheats = p.getBoolean(KEY_RENPY_CHEATS, d.cheats),
            hwVideo = p.getBoolean(KEY_RENPY_HW_VIDEO, d.hwVideo),
            autosave = p.getBoolean(KEY_RENPY_AUTOSAVE, d.autosave),
            phoneSmallVariant = p.getBoolean(KEY_RENPY_PHONE_SMALL_VARIANT, d.phoneSmallVariant),
            vsync = p.getBoolean(KEY_RENPY_VSYNC, d.vsync),
            lessMemory = p.getBoolean(KEY_RENPY_LESS_MEMORY, d.lessMemory),
            lessUpdates = p.getBoolean(KEY_RENPY_LESS_UPDATES, d.lessUpdates),
            dontUseGl2 = p.getBoolean(KEY_RENPY_DONT_USE_GL2, d.dontUseGl2),
            recompile = p.getBoolean(KEY_RENPY_RECOMPILE, d.recompile),
        )
    }

    fun saveRenPy(c: Context, r: RenPy) {
        prefs(c).edit().apply {
            putBoolean(KEY_RENPY_CHEATS, r.cheats)
            putBoolean(KEY_RENPY_HW_VIDEO, r.hwVideo)
            putBoolean(KEY_RENPY_AUTOSAVE, r.autosave)
            putBoolean(KEY_RENPY_PHONE_SMALL_VARIANT, r.phoneSmallVariant)
            putBoolean(KEY_RENPY_VSYNC, r.vsync)
            putBoolean(KEY_RENPY_LESS_MEMORY, r.lessMemory)
            putBoolean(KEY_RENPY_LESS_UPDATES, r.lessUpdates)
            putBoolean(KEY_RENPY_DONT_USE_GL2, r.dontUseGl2)
            putBoolean(KEY_RENPY_RECOMPILE, r.recompile)
        }.apply()
    }

    /** 仅重置 renpy 节，版本键（renpy_engine_version）保留。 */
    fun resetRenPy(c: Context) {
        val editor = prefs(c).edit()
        RENPY_BOOL_KEYS.forEach(editor::remove)
        editor.apply()
    }
}
