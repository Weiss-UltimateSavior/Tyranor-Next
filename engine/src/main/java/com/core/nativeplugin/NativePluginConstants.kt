package com.core.nativeplugin

import com.core.engine.EnginePrefs

/**
 * Constants shared by the launcher UI and the engine process for native engine plugins.
 *
 * The values in this object are machine-readable protocol data. User-visible labels must
 * stay in app string resources.
 */
object NativePluginConstants {
    const val ENGINE_KIRIKIROID2 = "kirikiroid2"
    const val ENGINE_ONS = "ons"
    const val ENGINE_ARTEMIS = "artemis"
    const val ABI_ARM64 = "arm64-v8a"
    const val KIRIKIROID2_BRIDGE_ABI = 1
    const val ONS_BRIDGE_ABI = 1
    const val ARTEMIS_BRIDGE_ABI = 1
    const val META_KIRIKIROID2_EXPECTED_ZIP_SHA256 = "rinne.kirikiroid2.zip.sha256"
    const val META_ONS_EXPECTED_ZIP_SHA256 = "rinne.ons.zip.sha256"
    const val META_ARTEMIS_EXPECTED_ZIP_SHA256 = "rinne.artemis.zip.sha256"
    const val PREFS_NAME = EnginePrefs.APP_PREFS

    const val LIB_SDL2 = "libSDL2.so"
    const val LIB_FFMPEG = "libffmpeg.so"
    const val LIB_GAME_139 = "libgame.so"
    const val LIB_GAME_134 = "libgame134.so"
    const val LIB_GAME_126 = "libgame126.so"

    const val LIB_ARTEMIS = "libartemis.so"
    const val LIB_ARTEMIS_COMPATIBLE = "libartemis-compatible.so"
    const val LIB_ARTEMIS_COMPATIBLE_V2 = "libartemis-compatible-v2.so"
    const val LIB_ARTEMIS_V4 = "libartemis-v4.so"
    const val LIB_ARTEMIS_V5 = "libartemis-v5.so"
    /** 官方拆解 Rev.3294（含 E-mote）运行库。 */
    const val LIB_ARTEMIS_V6 = "libartemis-v6.so"
    /** 自研 clean-room 兼容内核（artemis-compat 仓库构建产物）。 */
    const val LIB_ARTEMIS_CLEAN = "libartemis-clean.so"
    /** artemis_loader 消费的库名（无 lib 前缀/扩展名），与 artemis_loader.cpp 白名单一致。 */
    const val ARTEMIS_CLEAN_ENGINE_LIB_NAME = "artemis-clean"

    const val LIB_SDL2_IMAGE = "libSDL2_image.so"
    const val LIB_SDL2_MIXER = "libSDL2_mixer.so"
    const val LIB_SDL2_TTF = "libSDL2_ttf.so"
    const val LIB_BZ2 = "libbz2.so"
    const val LIB_JPEG = "libjpeg.so"
    const val LIB_LUA = "liblua.so"
    const val LIB_ONSYURI = "libonsyuri.so"

    val KIRIKIROID2_REQUIRED_LIBS: List<String> = listOf(
        LIB_SDL2,
        LIB_FFMPEG,
        LIB_GAME_139,
        LIB_GAME_134,
        LIB_GAME_126,
    )

    /** ONS(Yuri runtime) 外置插件必备 so，加载顺序即此处顺序（依赖先于依赖方）。 */
    val ONS_REQUIRED_LIBS: List<String> = listOf(
        LIB_SDL2,
        LIB_LUA,
        LIB_JPEG,
        LIB_BZ2,
        LIB_SDL2_IMAGE,
        LIB_SDL2_MIXER,
        LIB_SDL2_TTF,
        LIB_ONSYURI,
    )

    /**
     * 用户选择的引擎版本目录名（如 "v0.7.7"）。
     * 只由 App 侧写入（`EngineSettingsStore.setOnsEngineVersion`），引擎进程**只读**：
     * 引擎不再回写该键，避免跨进程 SharedPreferences 的陈旧值覆盖用户选择。
     */
    const val KEY_ONS_ENGINE_VERSION = "engine_version"

    /**
     * ONS 插件 arm64-v8a 根目录下的基础引擎版本目录名。
     * 该目录同时是找不到子版本时的回退目标，
     * 老插件 zip 里只有这一个目录，必须保持可用。
     */
    const val ONS_BASE_VERSION = "v0.7.6"
    /** 随插件附带的可选新引擎版本目录名（位于 arm64-v8a 下）。 */
    const val ONS_VERSION_0_7_7 = "v0.7.7"
    /** 加载顺序即回退顺序，第一个是当前默认版本。 */
    val ONS_AVAILABLE_VERSIONS: List<String> = listOf(ONS_VERSION_0_7_7, ONS_BASE_VERSION)
    /**
     * libONSPatch.so 经验证只与该版本匹配。
     * 它靠硬编码偏移改写 libonsyuri.so 的指令——不依赖符号名，
     * 版本不符时会把指令写到错误位置，后果是运行中随机崩溃且难以排查。
     * 因此只在版本完全一致时才加载，其他版本一律跳过。
     */
    const val ONS_PATCH_COMPATIBLE_VERSION = ONS_BASE_VERSION

    /** Artemis 外置插件必备 so：多套 revision 运行库 + 自研内核，均只依赖系统库，互不依赖。 */
    val ARTEMIS_REQUIRED_LIBS: List<String> = listOf(
        LIB_ARTEMIS,
        LIB_ARTEMIS_COMPATIBLE,
        LIB_ARTEMIS_COMPATIBLE_V2,
        LIB_ARTEMIS_V4,
        LIB_ARTEMIS_V5,
        LIB_ARTEMIS_V6,
        LIB_ARTEMIS_CLEAN,
    )
}
