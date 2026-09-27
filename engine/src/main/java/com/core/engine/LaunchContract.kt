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
    /** Web 壳本地 HTTP 服务端口（Tyrano / WebOther / VN）：固定端口保证 localStorage/IndexedDB 存档跨启动可见。 */
    const val WEB_SHELL_PORT = "webShellPort"
    /** Web 壳默认固定端口（与 Tyranor 原版 webGamePort 默认值一致，便于沿用既有存档域）。 */
    const val WEB_SHELL_PORT_DEFAULT = 23333
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

    // ---------- Siglus ----------

    /** 游戏语言：引擎 GET_LANGUAGE 返回值（auto 时不传，保持引擎默认 JP）。 */
    const val SIGLUS_LANGUAGE = "siglus_language"

    /** 游戏根路径哈希（与 app 侧 `Integer.toHexString(path.hashCode())` 一致），标题回写定位用。 */
    const val SIGLUS_PATH_HASH = "siglus_path_hash"

    // ---------- FVP ----------

    /** 文本编码：`sjis`（默认）| `gbk` | `utf8`，创建引擎时固定。 */
    const val FVP_NLS = "fvp_nls"

    /** 系统 CJK 字体回退开关（bool，缺省 true）。 */
    const val FVP_SYSTEM_FONT = "fvp_system_font"

    /** 文本高分辨率渲染开关（bool，缺省 true）。 */
    const val FVP_TEXT_HIDPI = "fvp_text_hidpi"

    /** 自定义字体路径（App 私有目录绝对路径；不传表示跟随游戏默认/不强制）。 */
    const val FVP_FONT_PATH = "fvp_font_path"

    // ---------- framebuffer 引擎（RealLive / AVG32 / UK2） ----------

    /** 引擎 id：`reallive` | `avg32` | `uk2`（不传时引擎库自行探测）。 */
    const val GAMES_ENGINE = "games_engine"

    /** 文本编码：`auto` | `sjis` | `gbk` | `big5` | `utf8` | `korean`（不传使用引擎默认）。 */
    const val GAMES_NLS = "games_nls"

    /** 游戏标题（加载层/窗口标题兜底用）。 */
    const val GAMES_TITLE = "games_title"

    // ---------- launchMode 固定取值 ----------

    const val LAUNCH_MODE_KRKRSDL3 = "internal.krkrsdl3"
    const val LAUNCH_MODE_KIRIKIROID2 = "internal.kirikiroid2"
    const val LAUNCH_MODE_ONS = "internal.ons"
    const val LAUNCH_MODE_ARTEMIS = "internal.artemis"
    const val LAUNCH_MODE_SIGLUS = "internal.siglus"
    const val LAUNCH_MODE_FVP = "internal.fvp"
    const val LAUNCH_MODE_FRAMEBUFFER = "internal.framebuffer"
}
