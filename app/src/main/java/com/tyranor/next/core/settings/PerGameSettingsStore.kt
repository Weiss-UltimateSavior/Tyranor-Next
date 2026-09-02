package com.tyranor.next.core.settings

import android.content.Context
import com.core.engine.EnginePrefs
import com.tyranor.next.core.game.storage.GameOverridePartitions
import com.tyranor.next.core.game.storage.GameOverridesRepository
import org.json.JSONObject

/**
 * 单游戏（应用级）引擎设置覆盖层。参考 Rinne 的 Per-game 实现：
 * 以每个游戏的稳定标识（本应用用游戏 uri）为键存一份 JSON 覆盖快照；
 * 某字段缺失 = 跟随全局默认。启动时由启动器 覆盖 ?: 全局 逐字段合并。
 *
 * 存储已迁移到 game_overrides 表（迁移方案阶段 4）：本类保留原同步 API，
 * 内部为 Repository 门面——DB 为 App 侧事实源；prefs 文件作为引擎子进程
 * （TyranoActivity/TouchPadSaveBridge 整条读改写）的同步镜像，每次写入即刷。
 * prefs 文件名契约锚点在 engine，改名只需改 EnginePrefs 一处。
 */
object PerGameSettingsStore {

    private val PREF_NAME = EnginePrefs.GAME_OVERRIDES_PREFS

    // KR 覆盖字段名
    const val F_ENGINE_VERSION = "engine_version"
    const val F_ENGINE_KERNEL = "engine_kernel"
    const val F_SCOPED_SAVE_DIR = "scoped_save_dir"
    const val F_DEFAULT_FONT = "default_font"
    const val F_FORCE_DEFAULT_FONT = "force_default_font"
    const val F_PATCH_OVERLAY_MODE = "patch_overlay_mode"
    const val F_SKIP_STARTUP_DIALOGS = "skip_startup_dialogs"
    const val F_RENDERER = "renderer"
    const val F_SOFTWARE_DRAW_THREAD = "software_draw_thread"
    const val F_SOFTWARE_COMPRESS_TEX = "software_compress_tex"
    const val F_OGL_COMPRESS_TEX = "ogl_compress_tex"
    const val F_MEM_USAGE = "mem_usage"
    const val F_OGL_MAX_TEXSIZE = "ogl_max_texsize"
    const val F_OGL_ACCURATE_RENDER = "ogl_accurate_render"
    const val F_FPS_LIMIT = "fps_limit"
    const val F_VCURSOR_SCALE = "vcursor_scale"
    const val F_MENU_HANDLER_OPA = "menu_handler_opa"
    val KR_FIELDS = listOf(
        F_RENDERER, F_SOFTWARE_DRAW_THREAD, F_SOFTWARE_COMPRESS_TEX, F_OGL_COMPRESS_TEX,
        F_MEM_USAGE, F_OGL_MAX_TEXSIZE, F_OGL_ACCURATE_RENDER, F_FPS_LIMIT,
        F_VCURSOR_SCALE, F_MENU_HANDLER_OPA,
    )

    // Artemis
    const val F_ART_VERSION = "art_engine_version"
    const val F_ART_ROTATE = "art_rotate_screen"
    const val F_ART_PATCH = "art_auto_patch"
    const val F_ART_RESOLUTION = "art_resolution"
    const val F_ART_SIDE_CUT = "art_side_cut"
    const val F_ART_SURFACE_CACHE_SIZE = "art_surface_cache_size"
    const val F_ART_FONT_CACHE_SIZE = "art_font_cache_size"
    const val F_ART_POWER_SAVING = "art_power_saving"

    // RPG Maker MV/MZ
    const val F_RPG_MAKER_MOD_ENABLED = "rpg_maker_mod_enabled"

    // Tyrano 与 RPG Maker Web 共用的存档目录开关（GameSaveManager 按此键读取）
    const val F_TY_SCOPED = "ty_scoped"

    // Ren'Py（外置模块版本选择）
    const val F_RENPY_VERSION = "renpy_engine_version"

    // ONS 子对象键
    const val ONS_KEY = "ons"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 该游戏是否存在覆盖。 */
    fun hasOverride(context: Context, gameId: String): Boolean {
        if (gameId.isBlank()) return false
        return GameOverridesRepository.loadRowBlocking(context, gameId) != null
    }

    /** 读取该游戏覆盖 JSON；无则返回空对象。 */
    fun load(context: Context, gameId: String): JSONObject {
        if (gameId.isBlank()) return JSONObject()
        val row = GameOverridesRepository.loadRowBlocking(context, gameId) ?: return JSONObject()
        return runCatching { GameOverridePartitions.assemble(row) }.getOrDefault(JSONObject())
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
        if (value == null) j.remove(key) else j.put(key, value.trim())
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
        GameOverridesRepository.clearRow(context, gameId)
        prefs(context).edit().remove(gameId).apply()
    }

    /**
     * 双写持久化：DB 异步落库（失败仅记日志），prefs 同步镜像立即刷盘——
     * 引擎子进程启动游戏时按旧契约整条读取镜像（方案阶段 4 过渡策略）。
     * 已知限制（与迁移前行为等价，见引擎 TouchPadSaveBridge 注释）：App 进程的 prefs
     * 缓存不跨进程刷新，引擎本次进程存活期间写回的 touchpad 字段对 App 不可见，
     * 此处整条镜像写会覆盖之；跨会话数据由启动时 syncFromPrefs 回灌保证不丢。
     * 彻底收口（引擎侧独立 prefs 文件）为方案后续项。
     */
    private fun persist(context: Context, gameId: String, record: JSONObject) {
        GameOverridesRepository.updateRecord(context, gameId, record)
        prefs(context).edit().putString(gameId, record.toString()).apply()
    }
}
