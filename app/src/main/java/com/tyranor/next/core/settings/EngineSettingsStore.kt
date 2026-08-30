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

    // Artemis 应用级默认
    const val KEY_ARTEMIS_ENGINE_VERSION = "artemis_engine_version"
    const val KEY_ARTEMIS_ROTATE_SCREEN = "artemis_rotate_screen"
    const val KEY_ARTEMIS_AUTO_PATCH = "artemis_auto_patch"

    // Ren'Py 应用级默认（外置模块版本选择）
    const val KEY_RENPY_ENGINE_VERSION = "renpy_engine_version"

    // Tyrano 与 RPG Maker Web 共用同一套 WebView 宿主设置；启动链路按同一键读取。
    const val KEY_TYRANO_EXTERNAL_NETWORK = "tyrano_external_network"
    const val KEY_TYRANO_SCOPED_SAVE_DIR = "tyrano_scoped_save_dir"
    const val KEY_RPG_MAKER_MOD_ENABLED = "rpg_maker_mod_enabled"

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

    const val RENDERER_SOFTWARE = "software"
    const val RENDERER_OPENGL = "opengl"

    // 写入引擎 XML（Kirikiroid2Preference.xml/GlobalPreference.xml）的 Item 键名，
    // 与 libgame.so 内 IndividualConfigManager 读取的键一致，键名不可改。
    const val ENGINE_VCURSOR_SCALE = "vcursor_scale"
    const val ENGINE_MENU_HANDLER_OPA = "menu_handler_opa"
    // 供 krkr_engine_prefs JSON 写入引擎 XML 时的键名映射（prefs 键 → 引擎 Item 键）
    private val KR_ENGINE_PREF_KEY_MAP = mapOf(
        KEY_KR_VCURSOR_SCALE to ENGINE_VCURSOR_SCALE,
        KEY_KR_MENU_HANDLER_OPA to ENGINE_MENU_HANDLER_OPA,
    )
    // 虚拟鼠标缩放比（1..100，引擎默认 100；1..150 档在 150% 时已超出屏幕，收敛至 100）
    val KR_VCURSOR_SCALE_RANGE = 1..100
    val KR_VCURSOR_SCALES: Set<String> = KR_VCURSOR_SCALE_RANGE.map { it.toString() }.toSet()
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

    // Ren'Py 版本取值常量
    const val RENPY_AUTO = "auto"
    const val RENPY_85 = "8.5"
    const val RENPY_77 = "7.7.1"

    val KR_RENDER_PREF_KEYS = listOf(
        KEY_KR_RENDERER, KEY_KR_SOFTWARE_DRAW_THREAD, KEY_KR_SOFTWARE_COMPRESS_TEX,
        KEY_KR_OGL_COMPRESS_TEX, KEY_KR_MEM_USAGE, KEY_KR_OGL_MAX_TEXSIZE,
        KEY_KR_OGL_ACCURATE_RENDER, KEY_KR_FPS_LIMIT,
        KEY_KR_VCURSOR_SCALE, KEY_KR_MENU_HANDLER_OPA,
    )

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
    fun getKrVCursorScale(c: Context): String { val v = krPref(c, KEY_KR_VCURSOR_SCALE).trim(); return if (v in KR_VCURSOR_SCALES) v else "" }
    fun setKrVCursorScale(c: Context, v: String) { val t = v.trim(); if (t.isEmpty() || t in KR_VCURSOR_SCALES) setKrPref(c, KEY_KR_VCURSOR_SCALE, t) }
    fun getKrMenuHandlerOpa(c: Context): String { val v = krPref(c, KEY_KR_MENU_HANDLER_OPA).trim(); return if (v in KR_MENU_HANDLER_OPAS) v else "" }
    fun setKrMenuHandlerOpa(c: Context, v: String) { val t = v.trim(); if (t.isEmpty() || t in KR_MENU_HANDLER_OPAS) setKrPref(c, KEY_KR_MENU_HANDLER_OPA, t) }

    /** 组装 krkr_engine_prefs JSON：{<引擎键>:{v, s}}。overrideGetter 返回某键的单游戏覆盖（null=跟随全局）。 */
    fun buildKrEnginePrefsJson(c: Context, overrideGetter: (String) -> String? = { null }): String {
        val json = JSONObject()
        KR_RENDER_PREF_KEYS.forEach { key ->
            val rawOverride = overrideGetter(key)
            val override = rawOverride?.trim()?.takeIf { it.isNotEmpty() || rawOverride == "" }
            // 仅保留合法值或显式空串（引擎默认），非法值按跟随全局处理
            val sanitizedOverride = when (key) {
                KEY_KR_VCURSOR_SCALE -> override?.let { if (it.isEmpty() || it in KR_VCURSOR_SCALES) it else null }
                KEY_KR_MENU_HANDLER_OPA -> override?.let { if (it.isEmpty() || it in KR_MENU_HANDLER_OPAS) it else null }
                else -> override
            }
            val globalRaw = prefs(c).getString(key, null).orEmpty().trim()
            val sanitizedGlobal = when (key) {
                KEY_KR_VCURSOR_SCALE -> if (globalRaw in KR_VCURSOR_SCALES) globalRaw else ""
                KEY_KR_MENU_HANDLER_OPA -> if (globalRaw in KR_MENU_HANDLER_OPAS) globalRaw else ""
                else -> prefs(c).getString(key, null).orEmpty()
            }
            val rawValue = sanitizedOverride ?: sanitizedGlobal
            // 虚拟鼠标：prefs 存 1..100（百分比），引擎需 0.01..1.00 浮点字符串
            val value = when (key) {
                KEY_KR_VCURSOR_SCALE -> if (rawValue.isEmpty()) "" else {
                    val p = rawValue.toIntOrNull()
                    if (p == null || p !in KR_VCURSOR_SCALE_RANGE) rawValue
                    else String.format(java.util.Locale.US, "%.2f", p / 100.0).trimEnd('0').trimEnd('.')
                }
                else -> rawValue
            }
            val engineKey = KR_ENGINE_PREF_KEY_MAP[key] ?: key
            json.put(engineKey, JSONObject().put("v", value).put("s", if (sanitizedOverride != null) "game" else "global"))
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
}
