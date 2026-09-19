package com.tyranor.next.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.settings.EngineSettingsStore

@Composable
internal fun engineSettingsKindTitle(kind: EngineSettingsKind): String = stringResource(kind.titleRes)

@Composable
internal fun krSelectOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.KR_AUTO to stringResource(R.string.common_auto),
    EngineSettingsStore.KR_139 to "1.3.9",
    EngineSettingsStore.KR_134 to "1.3.4",
    EngineSettingsStore.KR_126 to "1.2.6",
)

@Composable
internal fun krKernelOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.KR_AUTO to stringResource(R.string.common_auto),
    EngineSettingsStore.KERNEL_KIRIKIRI2 to stringResource(R.string.engine_option_kirikiri2),
    EngineSettingsStore.KERNEL_KRKRSDL3 to "krkrsdl3",
)

@Composable
internal fun krPatchOverlayOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.KR_PATCH_OVERLAY_AUTO to stringResource(R.string.common_auto),
    EngineSettingsStore.KR_PATCH_OVERLAY_FORCE to stringResource(R.string.engine_option_force_enable),
    EngineSettingsStore.KR_PATCH_OVERLAY_OFF to stringResource(R.string.engine_option_off),
)

@Composable
internal fun krAnime4kOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.ANIME4K_OFF to stringResource(R.string.engine_option_off),
    EngineSettingsStore.ANIME4K_S to stringResource(R.string.engine_option_anime4k_fast),
    EngineSettingsStore.ANIME4K_M to stringResource(R.string.engine_option_anime4k_balanced),
    EngineSettingsStore.ANIME4K_L to stringResource(R.string.engine_option_anime4k_high),
    EngineSettingsStore.ANIME4K_SOFT_S to stringResource(R.string.engine_option_anime4k_soft_fast),
    EngineSettingsStore.ANIME4K_SOFT_M to stringResource(R.string.engine_option_anime4k_soft_balanced),
    EngineSettingsStore.ANIME4K_SOFT_L to stringResource(R.string.engine_option_anime4k_soft_high),
    EngineSettingsStore.ANIME4K_DEBLUR to stringResource(R.string.engine_option_anime4k_deblur),
)

@Composable
internal fun krRendererOptions(): List<Pair<String, String>> = listOf(
    "default" to stringResource(R.string.engine_option_engine_default),
    EngineSettingsStore.RENDERER_SOFTWARE to stringResource(R.string.engine_option_software_renderer),
    EngineSettingsStore.RENDERER_OPENGL to "OpenGL",
)

@Composable
internal fun krSdl3RendererOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.RENDERER_OPENGL to stringResource(R.string.engine_option_opengl_default),
    EngineSettingsStore.RENDERER_SOFTWARE to stringResource(R.string.engine_option_software_renderer),
)

@Composable
internal fun krThreadOptions(): List<Pair<String, String>> =
    listOf("0" to stringResource(R.string.common_auto)) +
        (1..8).map { it.toString() to stringResource(R.string.engine_option_threads, it) }

@Composable
internal fun krSoftwareCompressOptions(): List<Pair<String, String>> = listOf(
    "" to stringResource(R.string.engine_option_engine_default),
    "none" to stringResource(R.string.engine_option_none),
    "halfline" to stringResource(R.string.engine_option_half_line),
    "lz4" to "LZ4",
    "lz4+tlg5" to "LZ4+TLG5",
)

@Composable
internal fun krOglCompressOptions(): List<Pair<String, String>> = listOf(
    "" to stringResource(R.string.engine_option_engine_default),
    "none" to stringResource(R.string.engine_option_none),
    "half" to stringResource(R.string.engine_option_half_precision),
    "etc2" to "ETC2",
    "pvrtc" to "PVRTC",
)

@Composable
internal fun krMemOptions(): List<Pair<String, String>> = listOf(
    "" to stringResource(R.string.engine_option_engine_default),
    EngineSettingsStore.MEM_USAGE_UNLIMITED to stringResource(R.string.engine_option_unlimited),
    EngineSettingsStore.MEM_USAGE_HIGH to stringResource(R.string.engine_option_high),
    EngineSettingsStore.MEM_USAGE_MEDIUM to stringResource(R.string.engine_option_medium),
    EngineSettingsStore.MEM_USAGE_LOW to stringResource(R.string.engine_option_low),
)

@Composable
internal fun krTexSizeOptions(): List<Pair<String, String>> =
    listOf("0" to stringResource(R.string.common_auto)) +
        listOf(1024, 2048, 4096, 8192, 16384).map { it.toString() to it.toString() }

@Composable
internal fun krFpsOptions(): List<Pair<String, String>> =
    listOf("" to stringResource(R.string.engine_option_engine_default)) +
        listOf(60, 45, 30, 15).map { it.toString() to it.toString() }

internal fun onsSharpnessOptions(): List<Pair<String, String>> =
    listOf("1" to "1.0", "2" to "2.0", "3" to "3.0", "4" to "4.0", "5" to "5.0")

internal fun onsEncodingOptions(): List<Pair<String, String>> =
    listOf("gbk" to "GBK", "sjis" to "Shift-JIS", "utf8" to "UTF-8")

@Composable
internal fun artVersionOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.ART_ENGINE_AUTO to stringResource(R.string.common_auto),
    EngineSettingsStore.ART_ENGINE_V2 to "v1（Tyranor/Rev.2762）",
    EngineSettingsStore.ART_ENGINE_V1 to "v2（Tyranor/Rev.3049/emote）",
    EngineSettingsStore.ART_ENGINE_V3 to "v3（Tyranor/Rev.3201/emote）",
    EngineSettingsStore.ART_ENGINE_V4 to "V1（TyranorNext/Rev.2958）",
    EngineSettingsStore.ART_ENGINE_V5 to "V3（TyranorNext/Rev.3288）",
    EngineSettingsStore.ART_ENGINE_V6 to "V4（TyranorNext/Rev.3294/emote）",
)

/** Artemis 内核选项：官方多 revision 运行库 / 自研 clean-room 兼容内核（单库无版本）。 */
@Composable
internal fun artKernelOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.ART_KERNEL_OFFICIAL to stringResource(R.string.engine_option_art_kernel_official),
    EngineSettingsStore.ART_KERNEL_CLEAN to stringResource(R.string.engine_option_art_kernel_clean),
)

@Composable
internal fun renpyVersionOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.RENPY_AUTO to stringResource(R.string.common_auto),
    EngineSettingsStore.RENPY_85 to "8.5",
    EngineSettingsStore.RENPY_77 to "7.7.1",
)

/** Siglus 游戏语言（引擎 GET_LANGUAGE 返回值；auto 保持引擎默认 JP）。 */
@Composable
internal fun siglusLanguageOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.SIGLUS_LANGUAGE_AUTO to stringResource(R.string.engine_settings_siglus_language_auto),
    EngineSettingsStore.SIGLUS_LANGUAGE_JP to stringResource(R.string.engine_settings_siglus_language_value_jp),
    EngineSettingsStore.SIGLUS_LANGUAGE_EN to stringResource(R.string.engine_settings_siglus_language_value_en),
    EngineSettingsStore.SIGLUS_LANGUAGE_ZH to stringResource(R.string.engine_settings_siglus_language_value_zh),
    EngineSettingsStore.SIGLUS_LANGUAGE_ZHTW to stringResource(R.string.engine_settings_siglus_language_value_zhtw),
    EngineSettingsStore.SIGLUS_LANGUAGE_DE to stringResource(R.string.engine_settings_siglus_language_value_de),
    EngineSettingsStore.SIGLUS_LANGUAGE_ES to stringResource(R.string.engine_settings_siglus_language_value_es),
    EngineSettingsStore.SIGLUS_LANGUAGE_FR to stringResource(R.string.engine_settings_siglus_language_value_fr),
    EngineSettingsStore.SIGLUS_LANGUAGE_ID to stringResource(R.string.engine_settings_siglus_language_value_id),
)

/** FVP 文本编码（创建引擎时固定；改动后需重启本局）。 */
@Composable
internal fun fvpNlsOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.FVP_NLS_SJIS to stringResource(R.string.engine_settings_fvp_nls_sjis),
    EngineSettingsStore.FVP_NLS_GBK to stringResource(R.string.engine_settings_fvp_nls_gbk),
    EngineSettingsStore.FVP_NLS_UTF8 to stringResource(R.string.engine_settings_fvp_nls_utf8),
)

@Composable
internal fun artPatchOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.AUTO_PATCH_ASK to stringResource(R.string.engine_option_auto_patch_ask),
    EngineSettingsStore.AUTO_PATCH_AUTO to stringResource(R.string.common_auto),
    EngineSettingsStore.AUTO_PATCH_OFF to stringResource(R.string.engine_option_off),
)

@Composable
internal fun artResolutionOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.ART_RESOLUTION_DEFAULT to stringResource(R.string.engine_option_engine_default),
    EngineSettingsStore.ART_RESOLUTION_1920_1080 to "1920 × 1080",
    EngineSettingsStore.ART_RESOLUTION_1280_720 to "1280 × 720",
    EngineSettingsStore.ART_RESOLUTION_960_540 to "960 × 540",
)

@Composable
internal fun artToggleOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.ART_TOGGLE_DEFAULT to stringResource(R.string.engine_option_engine_default),
    EngineSettingsStore.ART_TOGGLE_OFF to stringResource(R.string.common_disabled),
    EngineSettingsStore.ART_TOGGLE_ON to stringResource(R.string.common_enabled),
)

@Composable
internal fun artSurfaceCacheOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.ART_CACHE_DEFAULT to stringResource(R.string.engine_option_engine_default),
    EngineSettingsStore.ART_SURFACE_CACHE_64MB to "64 MB",
    EngineSettingsStore.ART_SURFACE_CACHE_128MB to "128 MB",
    EngineSettingsStore.ART_SURFACE_CACHE_256MB to "256 MB",
)

@Composable
internal fun artFontCacheOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.ART_CACHE_DEFAULT to stringResource(R.string.engine_option_engine_default),
    EngineSettingsStore.ART_FONT_CACHE_8MB to "8 MB",
    EngineSettingsStore.ART_FONT_CACHE_16MB to "16 MB",
    EngineSettingsStore.ART_FONT_CACHE_32MB to "32 MB",
    EngineSettingsStore.ART_FONT_CACHE_64MB to "64 MB",
)

@Composable
internal fun krSelectOptionsMap(): Map<String, String> = krSelectOptions().toMap()

@Composable
internal fun krKernelOptionsMap(): Map<String, String> = krKernelOptions().toMap()

@Composable
internal fun krPatchOverlayOptionsMap(): Map<String, String> = krPatchOverlayOptions().toMap()

@Composable
internal fun krAnime4kOptionsMap(): Map<String, String> = krAnime4kOptions().toMap()

@Composable
internal fun krRendererOptionsMap(): Map<String, String> =
    krRendererOptions().filterNot { it.first == "default" }.toMap()

@Composable
internal fun krThreadOptionsMap(): Map<String, String> = krThreadOptions().toMap()

@Composable
internal fun krSoftwareCompressOptionsMap(): Map<String, String> =
    krSoftwareCompressOptions().filterNot { it.first.isEmpty() }.toMap()

@Composable
internal fun krOglCompressOptionsMap(): Map<String, String> =
    krOglCompressOptions().filterNot { it.first.isEmpty() }.toMap()

@Composable
internal fun krMemOptionsMap(): Map<String, String> =
    krMemOptions().filterNot { it.first.isEmpty() }.toMap()

@Composable
internal fun krTexSizeOptionsMap(): Map<String, String> = krTexSizeOptions().toMap()

@Composable
internal fun krFpsOptionsMap(): Map<String, String> =
    krFpsOptions().filterNot { it.first.isEmpty() }.toMap()

internal fun onsEncodingOptionsMap(): Map<String, String> = onsEncodingOptions().toMap()

@Composable
internal fun artVersionOptionsMap(): Map<String, String> = artVersionOptions().toMap()

@Composable
internal fun artKernelOptionsMap(): Map<String, String> = artKernelOptions().toMap()

@Composable
internal fun renpyVersionOptionsMap(): Map<String, String> = renpyVersionOptions().toMap()

@Composable
internal fun siglusLanguageOptionsMap(): Map<String, String> = siglusLanguageOptions().toMap()

@Composable
internal fun fvpNlsOptionsMap(): Map<String, String> = fvpNlsOptions().toMap()

// ───────────────────────── Winlator 外置启动 ─────────────────────────

/** 空串统一下发语义：不下发该参数，跟随 Winlator 容器/快捷方式配置。 */
@Composable
internal fun winlatorGraphicsDriverOptions(): List<Pair<String, String>> =
    listOf("" to stringResource(R.string.engine_settings_winlator_follow_container)) +
        EngineSettingsStore.WINLATOR_GRAPHICS_DRIVERS.map { it to winlatorGraphicsDriverLabel(it) }

/** 图形驱动组合标签：Vulkan 驱动 + OpenGL 驱动（对齐 Winlator 容器设置两项下拉，专名保留原文）。 */
internal fun winlatorGraphicsDriverLabel(driver: String): String = when (driver) {
    "turnip,zink" -> "Turnip + Zink"
    "turnip,virgl" -> "Turnip + VirGL"
    "turnip,gladio" -> "Turnip + Gladio"
    "vortek,zink" -> "Vortek + Zink"
    "vortek,virgl" -> "Vortek + VirGL"
    "vortek,gladio" -> "Vortek + Gladio"
    else -> driver
}

@Composable
internal fun winlatorLcAllOptions(): List<Pair<String, String>> =
    listOf("" to stringResource(R.string.engine_settings_winlator_follow_container)) +
        EngineSettingsStore.WINLATOR_LOCALES.map { it to it }

@Composable
internal fun winlatorTimezoneOptions(): List<Pair<String, String>> =
    listOf("" to stringResource(R.string.engine_settings_winlator_follow_container)) +
        EngineSettingsStore.WINLATOR_TIMEZONES.map { it to it }

@Composable
internal fun winlatorDxWrapperOptions(): List<Pair<String, String>> =
    EngineSettingsStore.WINLATOR_DXWRAPPERS.map { v ->
        v to v.ifBlank { stringResource(R.string.engine_settings_winlator_follow_container) }
    }

@Composable
internal fun winlatorBox64PresetOptions(): List<Pair<String, String>> =
    EngineSettingsStore.WINLATOR_BOX64_PRESETS.map { v ->
        v to v.ifBlank { stringResource(R.string.engine_settings_winlator_follow_container) }
    }

/** 分辨率固定档位（无自定义输入）：`宽x高 (宽高比)`，宽度按 3 位数字分档便于扫读。 */
@Composable
internal fun winlatorScreenSizeOptions(): List<Pair<String, String>> =
    listOf("" to stringResource(R.string.engine_settings_winlator_follow_container)) +
        EngineSettingsStore.WINLATOR_SCREEN_SIZES.map { it to winlatorScreenSizeLabel(it) }

internal fun winlatorScreenSizeLabel(size: String): String = when (size) {
    "640x360" -> "640x360 (16:9)"
    "640x480" -> "640x480 (4:3)"
    "800x600" -> "800x600 (4:3)"
    "854x480" -> "854x480 (16:9)"
    "960x544" -> "960x544 (16:9)"
    "1024x768" -> "1024x768 (4:3)"
    "1280x720" -> "1280x720 (16:9)"
    "1280x800" -> "1280x800 (16:10)"
    "1280x1024" -> "1280x1024 (5:4)"
    "1366x768" -> "1366x768 (16:9)"
    "1440x900" -> "1440x900 (16:10)"
    "1600x900" -> "1600x900 (16:9)"
    "1920x1080" -> "1920x1080 (16:9)"
    else -> size
}

@Composable
internal fun winlatorGraphicsDriverOptionsMap(): Map<String, String> =
    winlatorGraphicsDriverOptions().toMap()

@Composable
internal fun winlatorDxWrapperOptionsMap(): Map<String, String> = winlatorDxWrapperOptions().toMap()

@Composable
internal fun winlatorBox64PresetOptionsMap(): Map<String, String> =
    winlatorBox64PresetOptions().toMap()

@Composable
internal fun winlatorScreenSizeOptionsMap(): Map<String, String> = winlatorScreenSizeOptions().toMap()

@Composable
internal fun winlatorLcAllOptionsMap(): Map<String, String> = winlatorLcAllOptions().toMap()

@Composable
internal fun winlatorTimezoneOptionsMap(): Map<String, String> = winlatorTimezoneOptions().toMap()

@Composable
internal fun artPatchOptionsMap(): Map<String, String> = artPatchOptions().toMap()

@Composable
internal fun rpgMvVersionOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.RPG_MV_V0 to stringResource(R.string.engine_option_rpg_mv_v0),
    EngineSettingsStore.RPG_MV_V1 to stringResource(R.string.engine_option_rpg_mv_v1),
    EngineSettingsStore.RPG_MV_V2 to stringResource(R.string.engine_option_rpg_mv_v2),
)

@Composable
internal fun rpgMzVersionOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.RPG_MZ_V0 to stringResource(R.string.engine_option_rpg_mz_v0),
    // RPG_MZ v1 为预留占位（TyranoActivity 对 MZ v1 仅回退 v0，不构建 overlay），本期不在选项中暴露以免误导
    EngineSettingsStore.RPG_MZ_V2 to stringResource(R.string.engine_option_rpg_mz_v2),
)

@Composable
internal fun rpgMvVersionOptionsMap(): Map<String, String> = rpgMvVersionOptions().toMap()

@Composable
internal fun rpgMzVersionOptionsMap(): Map<String, String> = rpgMzVersionOptions().toMap()

@Composable
internal fun artResolutionOptionsMap(): Map<String, String> = artResolutionOptions().toMap()

@Composable
internal fun artToggleOptionsMap(): Map<String, String> = artToggleOptions().toMap()

@Composable
internal fun artSurfaceCacheOptionsMap(): Map<String, String> = artSurfaceCacheOptions().toMap()

@Composable
internal fun artFontCacheOptionsMap(): Map<String, String> = artFontCacheOptions().toMap()

// ───────────────────────── RPG Maker RGSS 外置模块 ─────────────────────────

/** 窗口尺寸全为尺寸字面量，无需本地化。 */
@Composable
internal fun rpgWindowSizeOptions(): List<Pair<String, String>> =
    EngineSettingsStore.RPG_WINDOW_SIZES.map { it to it }

/** 加速倍率 1..9（显示为 Nx，数值本身无语言差异）。 */
@Composable
internal fun rpgSpeedUpOptions(): List<Pair<String, String>> =
    EngineSettingsStore.RPG_SPEED_UPS.map { it to "${it}x" }

/** 字体缩放档位（0.25..2.00）。 */
@Composable
internal fun rpgFontScaleOptions(): List<Pair<String, String>> =
    EngineSettingsStore.RPG_FONT_SCALES.map { it to it }

/** 竖屏对齐（插件 MKXPConfiguration 的 verticalScreenAlign 取值）。 */
@Composable
internal fun rpgVerticalAlignOptions(): List<Pair<String, String>> = listOf(
    "top" to stringResource(R.string.engine_settings_rpgm_valign_top),
    "top-center" to stringResource(R.string.engine_settings_rpgm_valign_top_center),
    "center" to stringResource(R.string.engine_settings_rpgm_valign_center),
)

@Composable
internal fun rpgWindowSizeOptionsMap(): Map<String, String> = rpgWindowSizeOptions().toMap()

@Composable
internal fun rpgSpeedUpOptionsMap(): Map<String, String> = rpgSpeedUpOptions().toMap()

@Composable
internal fun rpgFontScaleOptionsMap(): Map<String, String> = rpgFontScaleOptions().toMap()

@Composable
internal fun rpgVerticalAlignOptionsMap(): Map<String, String> = rpgVerticalAlignOptions().toMap()
