package com.tyranor.next.core.game.storage

import org.json.JSONObject

/**
 * 单游戏覆盖记录在「prefs 整条 JSON blob」与「DB 分区列」之间的双向映射（迁移方案 4.4）。
 *
 * 引擎契约（TyranoActivity.TouchPadSaveBridge）：引擎子进程对 prefs blob 整条读改写，
 * 仅触碰 touchpad 两键；键名在此以字面量锚定（与 PerGameSettingsStore 的 F_* 常量、
 * 引擎侧常量三处对应，GameOverridePartitionsTest 约束一致），避免 storage 反向依赖
 * settings 形成包环。未识别的顶层键兜底归入 tyrano 分区，保证组装不丢字段。
 */
internal object GameOverridePartitions {

    /** 引擎侧 TyranoActivity.PER_GAME_TOUCH_PAD_KEY / ..._PRESETS_KEY 的契约镜像。 */
    const val TOUCH_PAD_CONFIG_KEY = "touch_pad_config"
    const val TOUCH_PAD_PRESETS_KEY = "touch_pad_presets"

    const val KEY_ENGINE_VERSION = "engine_version"
    const val KEY_ENGINE_KERNEL = "engine_kernel"
    const val KEY_SCOPED_SAVE_DIR = "scoped_save_dir"
    const val KEY_DEFAULT_FONT = "default_font"
    const val KEY_FORCE_DEFAULT_FONT = "force_default_font"
    const val KEY_PATCH_OVERLAY_MODE = "patch_overlay_mode"
    const val KEY_SKIP_STARTUP_DIALOGS = "skip_startup_dialogs"
    const val KEY_RENDERER = "renderer"
    const val KEY_SOFTWARE_DRAW_THREAD = "software_draw_thread"
    const val KEY_SOFTWARE_COMPRESS_TEX = "software_compress_tex"
    const val KEY_OGL_COMPRESS_TEX = "ogl_compress_tex"
    const val KEY_MEM_USAGE = "mem_usage"
    const val KEY_OGL_MAX_TEXSIZE = "ogl_max_texsize"
    const val KEY_OGL_ACCURATE_RENDER = "ogl_accurate_render"
    const val KEY_FPS_LIMIT = "fps_limit"
    const val KEY_VCURSOR_SCALE = "vcursor_scale"
    const val KEY_MENU_HANDLER_OPA = "menu_handler_opa"
    const val KEY_ART_VERSION = "art_engine_version"
    const val KEY_ART_ROTATE = "art_rotate_screen"
    const val KEY_ART_PATCH = "art_auto_patch"
    const val KEY_RPG_MAKER_MOD_ENABLED = "rpg_maker_mod_enabled"
    const val KEY_TY_SCOPED = "ty_scoped"
    const val KEY_RENPY_VERSION = "renpy_engine_version"
    const val ONS_OBJECT_KEY = "ons"

    val KR_KEYS: Set<String> = setOf(
        KEY_ENGINE_VERSION, KEY_ENGINE_KERNEL, KEY_SCOPED_SAVE_DIR, KEY_DEFAULT_FONT,
        KEY_FORCE_DEFAULT_FONT, KEY_PATCH_OVERLAY_MODE, KEY_RENDERER, KEY_SOFTWARE_DRAW_THREAD,
        KEY_SOFTWARE_COMPRESS_TEX, KEY_OGL_COMPRESS_TEX, KEY_MEM_USAGE, KEY_OGL_MAX_TEXSIZE,
        KEY_OGL_ACCURATE_RENDER, KEY_FPS_LIMIT, KEY_VCURSOR_SCALE, KEY_MENU_HANDLER_OPA,
        KEY_SKIP_STARTUP_DIALOGS,
    )
    val ARTEMIS_KEYS: Set<String> = setOf(KEY_ART_VERSION, KEY_ART_ROTATE, KEY_ART_PATCH)
    val TYRANO_KEYS: Set<String> = setOf(KEY_TY_SCOPED, KEY_RPG_MAKER_MOD_ENABLED)
    val RENPY_KEYS: Set<String> = setOf(KEY_RENPY_VERSION)
    val TOUCHPAD_KEYS: Set<String> = setOf(TOUCH_PAD_CONFIG_KEY, TOUCH_PAD_PRESETS_KEY)

    /** 整条 blob → 分区行；updatedAt 由调用方给出。 */
    fun split(gameUri: String, blob: JSONObject, updatedAt: Long): GameOverrideEntity {
        val kr = JSONObject()
        val artemis = JSONObject()
        val ons = JSONObject()
        val tyrano = JSONObject()
        val renpy = JSONObject()
        val touchpad = JSONObject()
        for (key in blob.keys()) {
            val value = blob.opt(key) ?: continue
            when {
                key == ONS_OBJECT_KEY -> ons.put(key, value)
                key in TOUCHPAD_KEYS -> touchpad.put(key, value)
                key in KR_KEYS -> kr.put(key, value)
                key in ARTEMIS_KEYS -> artemis.put(key, value)
                key in RENPY_KEYS -> renpy.put(key, value)
                // 未识别键（含未来引擎新增字段）兜底归入 tyrano 分区，保证整条组装不丢字段
                else -> tyrano.put(key, value)
            }
        }
        return GameOverrideEntity(
            gameUri = gameUri,
            krJson = kr.takeIfNotEmpty(),
            artemisJson = artemis.takeIfNotEmpty(),
            onsJson = ons.takeIfNotEmpty(),
            tyranoJson = tyrano.takeIfNotEmpty(),
            renpyJson = renpy.takeIfNotEmpty(),
            touchpadJson = touchpad.takeIfNotEmpty(),
            updatedAt = updatedAt,
        )
    }

    /** 分区行 → 整条 blob（引擎 prefs 镜像的组装来源）；各分区按键扁平并回。 */
    fun assemble(row: GameOverrideEntity): JSONObject {
        val blob = JSONObject()
        for (partition in listOf(row.krJson, row.artemisJson, row.tyranoJson, row.renpyJson, row.onsJson, row.touchpadJson)) {
            mergeFlat(blob, partition)
        }
        return blob
    }

    private fun mergeFlat(blob: JSONObject, json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val partition = JSONObject(json)
            for (key in partition.keys()) blob.put(key, partition.opt(key))
        }
    }

    private fun JSONObject.takeIfNotEmpty(): String? = if (length() == 0) null else toString()
}
