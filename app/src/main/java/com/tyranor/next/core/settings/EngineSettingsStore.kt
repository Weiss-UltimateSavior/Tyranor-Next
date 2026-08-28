package com.tyranor.next.core.settings

import android.content.Context
import org.json.JSONObject

/**
 * 引擎设置存储层。键名与 RinneMobile 保持一致：
 * - KRKR / Artemis / Tyrano 的全局设置存 yukihub_prefs（引擎进程读取同一 prefs）
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
    const val KEY_KR_SCOPED_SAVE_DIR = "kr_scoped_save_dir"

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

    const val RENDERER_SOFTWARE = "software"
    const val RENDERER_OPENGL = "opengl"
    const val MEM_USAGE_UNLIMITED = "unlimited"
    const val MEM_USAGE_HIGH = "high"
    const val MEM_USAGE_MEDIUM = "medium"
    const val MEM_USAGE_LOW = "low"

    const val ART_ENGINE_AUTO = "auto"
    const val ART_ENGINE_V1 = "1"
    const val ART_ENGINE_V2 = "2"
    const val ART_ENGINE_V3 = "3"
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
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("yukihub_prefs", Context.MODE_PRIVATE)

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

    /** 组装 krkr_engine_prefs JSON：{<引擎键>:{v, s}}。overrideGetter 返回某键的单游戏覆盖（null=跟随全局）。 */
    fun buildKrEnginePrefsJson(c: Context, overrideGetter: (String) -> String? = { null }): String {
        val json = JSONObject()
        KR_RENDER_PREF_KEYS.forEach { key ->
            val override = overrideGetter(key)
            val value = override ?: prefs(c).getString(key, null).orEmpty()
            json.put(key, JSONObject().put("v", value).put("s", if (override != null) "game" else "global"))
        }
        return json.toString()
    }

    private fun normalizeKr(v: String?): String = when (v?.trim()?.lowercase()) {
        KR_139 -> KR_139
        KR_134 -> KR_134
        KR_126 -> KR_126
        else -> KR_AUTO
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
        return if (v == ART_ENGINE_V1 || v == ART_ENGINE_V2 || v == ART_ENGINE_V3) v else ART_ENGINE_AUTO
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
