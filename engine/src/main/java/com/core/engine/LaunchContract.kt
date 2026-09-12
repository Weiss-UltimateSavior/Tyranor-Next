package com.core.engine

/**
 * App（`com.tyranor.next`）→ engine 内置引擎宿主的启动 Intent 契约常量（P1-7）。
 *
 * 内置启动链路的 extras 键与 [LAUNCH_MODE] 固定取值只在**本文件**定义；app 侧
 * `EngineLauncher` 与 engine 侧各宿主 Activity 均引用这里，禁止再写裸字符串字面量。
 * 引擎自有的功能/策略键保留在各自契约（如 [KrkrStartupDialogPolicy.EXTRA_ENABLED]、
 * `com.core.gl.Anime4kRuntime.EXTRA_MODE`）；外置 APK 模块协议见 core 侧
 * `ExternalEngineContract`；共享 prefs 键由 [EnginePrefs] 收敛。
 */
object LaunchContract {

    // ---------- 通用 ----------

    const val PATH = "path"
    const val GAME_PATH = "gamePath"
    const val PROJECT_ROOT = "projectRoot"
    const val GAME_DIR = "gamedir"
    const val ROOT_URI = "rootUri"
    const val LAUNCH_TARGET = "launchTarget"
    const val LAUNCH_MODE = "launchMode"
    const val ORIGINAL_PROJECT_ROOT = "originalProjectRoot"
    const val TYPE = "type"
    const val ORIENTATION = "orientation"
    const val FOCUS = "focus"
    const val ORIGIN_MODE = "originMode"
    const val UI_LANGUAGE_TAG = "uiLanguageTag"

    // ---------- 主题 ----------

    const val DARK_MODE = "darkMode"
    const val PRIMARY_COLOR = "primaryColor"
    const val THEME_COLOR_PRIMARY = "themeColorPrimary"
    const val THEME_COLOR_ON_PRIMARY = "themeColorOnPrimary"
    const val THEME_COLOR_CARD = "themeColorCard"
    const val THEME_COLOR_TEXT = "themeColorText"
    const val THEME_COLOR_TEXT_MUTED = "themeColorTextMuted"

    // ---------- 存档目录 ----------

    const val SCOPED_SAVE_DIR = "scopedSaveDir"
    const val SCOPED_SAVE_ROOT = "scopedSaveRoot"
    const val SCOPED_SAVE_NAME = "scopedSaveName"
    const val GAME_SAVE_ROOT = "gameSaveRoot"

    // ---------- ONS ----------

    const val GAME_ARGS = "gameargs"
    const val GAME_URI = "gameuri"
    const val IGNORE_CUTOUT = "ignorecutout"

    // ---------- KRKR ----------

    const val SAF_FILE_FALLBACK = "safFileFallback"
    const val KR_ENGINE_VERSION = "krEngineVersion"
    const val DEFAULT_FONT = "default_font"
    const val FORCE_DEFAULT_FONT = "force_default_font"
    const val KR_ENGINE_PREFS = "krkr_engine_prefs"
    const val KR_PATCH_OVERLAY_TARGET = "krPatchOverlayTarget"
    const val KR_PATCH_OVERLAY_PATH = "krPatchOverlayPath"
    const val KR_PATCH_OVERLAY_MODE = "krPatchOverlayMode"
    const val KR_STEAM_CONFIG_OVERLAY_TARGET = "krSteamConfigOverlayTarget"
    const val KR_STEAM_CONFIG_OVERLAY_PATH = "krSteamConfigOverlayPath"
    const val BASE_DOC = "baseDoc"
    const val SAF_MIRROR_ROOT = "safMirrorRoot"
    const val SAF_MIRROR_INDEX = "safMirrorIndex"
    const val SAF_MIRROR_FILES = "safMirrorFiles"

    // ---------- Artemis ----------

    const val ARTEMIS_AUTO_FALLBACK = "artemisAutoFallback"
    const val ARTEMIS_FALLBACK_STAGE = "artemisFallbackStage"
    const val ARTEMIS_CURRENT_VERSION = "artemisCurrentVersion"
    const val ARTEMIS_FALLBACK_VERSIONS = "artemisFallbackVersions"
    const val ARTEMIS_FALLBACK_INDEX = "artemisFallbackIndex"
    const val ARTEMIS_AUTO_PLAN_REASON = "artemisAutoPlanReason"
    const val ENGINE_LIB_NAME = "engineLibName"

    // ---------- RPG Maker Web ----------

    const val RPG_MAKER_MOD_ENABLED = "rpgMakerModEnabled"
    const val RPG_MAKER_MOD_GAME_ID = "rpgMakerModGameId"
    const val RPG_MAKER_VERSION = "rpgMakerVersion"
    const val RPG_LEGACY_RENDERER = "rpgLegacyRenderer"

    // ---------- launchMode 固定取值 ----------

    const val LAUNCH_MODE_KRKRSDL3 = "internal.krkrsdl3"
    const val LAUNCH_MODE_KIRIKIROID2 = "internal.kirikiroid2"
    const val LAUNCH_MODE_ONS = "internal.ons"
    const val LAUNCH_MODE_ARTEMIS = "internal.artemis"
}
