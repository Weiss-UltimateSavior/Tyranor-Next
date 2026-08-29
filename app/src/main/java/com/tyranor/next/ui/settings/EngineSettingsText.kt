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
    EngineSettingsStore.ART_ENGINE_V1 to "V1（Artroid+ 1.0）",
    EngineSettingsStore.ART_ENGINE_V2 to "V2（Artroid+ 2.0）",
    EngineSettingsStore.ART_ENGINE_V3 to "V3（Artroid+ 3.0）",
    EngineSettingsStore.ART_ENGINE_V4 to "V4（Tyn 1.0）",
)

@Composable
internal fun renpyVersionOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.RENPY_AUTO to stringResource(R.string.common_auto),
    EngineSettingsStore.RENPY_85 to "8.5",
    EngineSettingsStore.RENPY_77 to "7.7.1",
)

@Composable
internal fun artPatchOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.AUTO_PATCH_ASK to stringResource(R.string.engine_option_auto_patch_ask),
    EngineSettingsStore.AUTO_PATCH_AUTO to stringResource(R.string.common_auto),
    EngineSettingsStore.AUTO_PATCH_OFF to stringResource(R.string.engine_option_off),
)

@Composable
internal fun krSelectOptionsMap(): Map<String, String> = krSelectOptions().toMap()

@Composable
internal fun krKernelOptionsMap(): Map<String, String> = krKernelOptions().toMap()

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
internal fun renpyVersionOptionsMap(): Map<String, String> = renpyVersionOptions().toMap()

@Composable
internal fun artPatchOptionsMap(): Map<String, String> = artPatchOptions().toMap()

@Composable
internal fun rpgMvVersionOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.RPG_MV_V0 to stringResource(R.string.engine_option_rpg_mv_v0),
    EngineSettingsStore.RPG_MV_V1 to stringResource(R.string.engine_option_rpg_mv_v1),
)

@Composable
internal fun rpgMzVersionOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.RPG_MZ_V0 to stringResource(R.string.engine_option_rpg_mz_v0),
    EngineSettingsStore.RPG_MZ_V1 to stringResource(R.string.engine_option_rpg_mz_v1),
)

@Composable
internal fun rpgMvVersionOptionsMap(): Map<String, String> = rpgMvVersionOptions().toMap()

@Composable
internal fun rpgMzVersionOptionsMap(): Map<String, String> = rpgMzVersionOptions().toMap()
