package com.tyranor.next.core.settings

import android.content.Context
import com.core.engine.EnginePrefs
import org.json.JSONObject

/**
 * 单游戏（应用级）引擎设置覆盖层。参考 Rinne 的 Per-game 实现：
 * 以每个游戏的稳定标识（本应用用游戏 uri）为键，在独立 prefs 文件里存一份 JSON 覆盖快照；
 * 某字段缺失 = 跟随全局默认。启动时由启动器 覆盖 ?: 全局 逐字段合并。
 */
object PerGameSettingsStore {

    // prefs 文件名契约锚点在 engine（引擎 TyranoActivity/TouchPadSaveBridge 直读写同一文件），
    // 改名只需改 EnginePrefs 一处。
    private val PREF_NAME = EnginePrefs.GAME_OVERRIDES_PREFS

    // KR 覆盖字段名
    const val F_ENGINE_VERSION = "engine_version"
    const val F_ENGINE_KERNEL = "engine_kernel"
    const val F_SCOPED_SAVE_DIR = "scoped_save_dir"
    const val F_DEFAULT_FONT = "default_font"
    const val F_FORCE_DEFAULT_FONT = "force_default_font"
    const val F_RENDERER = "renderer"
    const val F_SOFTWARE_DRAW_THREAD = "software_draw_thread"
    const val F_SOFTWARE_COMPRESS_TEX = "software_compress_tex"
    const val F_OGL_COMPRESS_TEX = "ogl_compress_tex"
    const val F_MEM_USAGE = "mem_usage"
    const val F_OGL_MAX_TEXSIZE = "ogl_max_texsize"
    const val F_OGL_ACCURATE_RENDER = "ogl_accurate_render"
    const val F_FPS_LIMIT = "fps_limit"
    val KR_FIELDS = listOf(
        F_RENDERER, F_SOFTWARE_DRAW_THREAD, F_SOFTWARE_COMPRESS_TEX, F_OGL_COMPRESS_TEX,
        F_MEM_USAGE, F_OGL_MAX_TEXSIZE, F_OGL_ACCURATE_RENDER, F_FPS_LIMIT,
    )

    // Artemis
    const val F_ART_VERSION = "art_engine_version"
    const val F_ART_ROTATE = "art_rotate_screen"
    const val F_ART_PATCH = "art_auto_patch"

    // RPG Maker MV/MZ
    const val F_RPG_MAKER_MOD_ENABLED = "rpg_maker_mod_enabled"

    // Ren'Py（外置模块版本选择）
    const val F_RENPY_VERSION = "renpy_engine_version"

    // ONS 子对象键
    const val ONS_KEY = "ons"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 该游戏是否存在覆盖。 */
    fun hasOverride(context: Context, gameId: String): Boolean {
        if (gameId.isBlank()) return false
        return prefs(context).contains(gameId)
    }

    /** 读取该游戏覆盖 JSON；无则返回空对象。 */
    fun load(context: Context, gameId: String): JSONObject = try {
        val raw = prefs(context).getString(gameId, null)
        if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Throwable) {
        JSONObject()
    }

    /** 字符串字段覆盖值；null=未覆盖（跟随全局），""=覆盖为空串（如内置字体）。 */
    fun getStr(context: Context, gameId: String, key: String): String? {
        val j = load(context, gameId)
        return if (j.has(key)) j.optString(key) else null
    }

    /** 布尔字段覆盖值；null=未覆盖（跟随全局）。 */
    fun getBool(context: Context, gameId: String, key: String): Boolean? {
        val j = load(context, gameId)
        return if (j.has(key)) j.optBoolean(key) else null
    }

    /** 设置字符串覆盖；value=null 表示移除该覆盖（跟随全局）。 */
    fun setStr(context: Context, gameId: String, key: String, value: String?) {
        if (gameId.isBlank()) return
        val j = load(context, gameId)
        if (value == null) j.remove(key) else j.put(key, value)
        persist(context, gameId, j)
    }

    /** 设置布尔覆盖；value=null 表示移除该覆盖（跟随全局）。 */
    fun setBool(context: Context, gameId: String, key: String, value: Boolean?) {
        if (gameId.isBlank()) return
        val j = load(context, gameId)
        if (value == null) j.remove(key) else j.put(key, value)
        persist(context, gameId, j)
    }

    /** 读取 ONS 覆盖子对象（缺失字段=跟随全局）。 */
    fun loadOnsOverride(context: Context, gameId: String): JSONObject? {
        val j = load(context, gameId)
        return if (j.has(ONS_KEY)) j.optJSONObject(ONS_KEY) else null
    }

    /** 保存 ONS 覆盖子对象。 */
    fun setOnsOverride(context: Context, gameId: String, ons: JSONObject) {
        if (gameId.isBlank()) return
        val j = load(context, gameId)
        j.put(ONS_KEY, ons)
        persist(context, gameId, j)
    }

    /** 清除某游戏全部覆盖，回退到全局默认。 */
    fun clear(context: Context, gameId: String) {
        if (gameId.isBlank()) return
        prefs(context).edit().remove(gameId).apply()
    }

    private fun persist(context: Context, gameId: String, json: JSONObject) {
        prefs(context).edit().putString(gameId, json.toString()).apply()
    }
}
