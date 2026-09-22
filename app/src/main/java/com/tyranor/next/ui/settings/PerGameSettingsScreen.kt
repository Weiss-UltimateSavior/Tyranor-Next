package com.tyranor.next.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.EmulatorLaunchStyle
import com.tyranor.next.core.engine.external.ExternalEmulatorRegistry
import com.tyranor.next.core.engine.external.RpgMakerRuntimeEnvironment
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.core.settings.RenPyOverride
import com.tyranor.next.core.settings.RpgMakerOverride
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.TopBarIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单游戏（应用级）引擎设置页。每项基于「覆盖 ?: 全局」，可单独切回“跟随全局”。
 * 顶部右侧保存图标提交覆盖；左上返回。
 */
@Composable
fun PerGameSettingsScreen(game: ScanGame) {
    val ctx = LocalContext.current
    val gid = game.uri

    // 覆盖值（null=跟随全局）
    var krVersion by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ENGINE_VERSION)) }
    var krKernel by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ENGINE_KERNEL)) }
    var krScoped by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_SCOPED_SAVE_DIR)) }
    var krSkipStartupDialogs by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_SKIP_STARTUP_DIALOGS)) }
    var krPatchOverlayMode by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_PATCH_OVERLAY_MODE)) }
    var krAnime4kMode by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ANIME4K_MODE)) }
    var krFont by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_DEFAULT_FONT)) }
    var krForceFont by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_FORCE_DEFAULT_FONT)) }
    val krRender = PerGameSettingsStore.KR_FIELDS.associateWith { field ->
        remember(gid, field) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, field)) }
    }

    var artKernel by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_KERNEL)) }
    var artVersion by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_VERSION)) }
    var artRotate by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_ART_ROTATE)) }
    var artPatch by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_PATCH)) }
    var artResolution by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_RESOLUTION)) }
    var artSideCut by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_SIDE_CUT)) }
    var artSurfaceCache by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_SURFACE_CACHE_SIZE)) }
    var artFontCache by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_FONT_CACHE_SIZE)) }
    var artPowerSaving by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_POWER_SAVING)) }
    var renpyVersion by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_RENPY_VERSION)) }
    var siglusLanguage by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_SIGLUS_LANGUAGE)) }
    var fvpNls by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_FVP_NLS)) }
    var fvpSystemFont by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_FVP_SYSTEM_FONT)) }
    var fvpTextHidpi by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_FVP_TEXT_HIDPI)) }
    var ppssppVersion by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_PPSSPP_VERSION)) }
    var fvpFont by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_FVP_FONT)) }
    var winlatorContainerId by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_CONTAINER_ID)) }
    var winlatorContainerName by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_CONTAINER_NAME)) }
    var winlatorGraphicsDriver by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER)) }
    var winlatorDxwrapper by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_DXWRAPPER)) }
    var winlatorScreenSize by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE)) }
    var winlatorLcAll by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_LC_ALL)) }
    var winlatorTz by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_TZ)) }
    var winlatorBox64Preset by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_BOX64_PRESET)) }
    var winlatorSave by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_WINLATOR_SAVE)) }
    var artPatchRunning by remember(gid) { mutableStateOf(false) }
    var artPatchResult by remember(gid) { mutableStateOf<EngineLauncher.ArtemisManualPatchResult?>(null) }
    var renpyOverride by remember(gid) {
        mutableStateOf(PerGameSettingsStore.toRenPyOverride(PerGameSettingsStore.load(ctx, gid)))
    }
    val renpy = renpyOverride ?: RenPyOverride()

    val onsOverride = remember { mutableStateOf(PerGameSettingsStore.loadOnsOverride(ctx, gid) ?: JSONObject()) }
    var onsScoped by remember { mutableStateOf(onsBool(onsOverride.value, "scopedsavedir")) }
    var onsStretch by remember { mutableStateOf(onsBool(onsOverride.value, "strechfull")) }
    var onsCutout by remember { mutableStateOf(onsBool(onsOverride.value, "ignorecutout")) }
    var onsNoVideo by remember { mutableStateOf(onsBool(onsOverride.value, "disablevideo")) }
    var onsSharp by remember { mutableStateOf(onsBool(onsOverride.value, "sharpness")) }
    var onsSharpVal by remember { mutableStateOf(onsStr(onsOverride.value, "sharpness_value", "2")) }
    var onsEnc by remember { mutableStateOf(onsStr(onsOverride.value, "encoding", "gbk")) }

    var tyExternal by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, "ty_external")) }
    var tyScoped by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, "ty_scoped")) }
    var rpgMakerMod by remember {
        mutableStateOf(
            PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED),
        )
    }
    var rpgLegacyRenderer by remember {
        mutableStateOf(
            PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_RPG_LEGACY_RENDERER),
        )
    }
    var rpgSaveInterop by remember {
        mutableStateOf(
            PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_RPG_SAVE_INTEROP),
        )
    }
    var rpgMvVersion by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_RPG_MV_VERSION)) }
    var rpgMzVersion by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_RPG_MZ_VERSION)) }
    var rpgmOverride by remember(gid) {
        mutableStateOf(PerGameSettingsStore.toRpgMakerOverride(PerGameSettingsStore.load(ctx, gid)))
    }
    val rpgm = rpgmOverride ?: RpgMakerOverride()

    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val p = FontImport.importToPrivate(ctx, uri)
            if (p != null) krFont = p
        }
    }
    val fvpFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val p = FontImport.importToPrivate(ctx, uri)
            if (p != null) {
                fvpFont = p
            } else {
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(R.string.engine_settings_fvp_font_import_failed),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val scope = rememberCoroutineScope()
    // RPGM 外置插件读不到 App 私有目录，自定义字体必须落共享存储（见 RpgMakerRuntimeEnvironment）
    val rpgFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { RpgMakerRuntimeEnvironment.importCustomFont(ctx, uri) }
            if (path != null) rpgmOverride = rpgm.copy(customFont = path)
            val msg = if (path != null) {
                R.string.engine_settings_rpgm_custom_font_imported
            } else {
                R.string.engine_settings_rpgm_custom_font_import_failed
            }
            android.widget.Toast.makeText(ctx, ctx.getString(msg), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val globalKrVersion = EngineSettingsStore.getKrEngineVersion(ctx)
    val globalKrKernel = EngineSettingsStore.getKrKernel(ctx)
    val globalKrScoped = EngineSettingsStore.isKrScopedSaveDir(ctx)
    val globalKrSkipStartupDialogs = EngineSettingsStore.isKrSkipStartupDialogs(ctx)
    val globalKrPatchOverlayMode = EngineSettingsStore.getKrPatchOverlayMode(ctx)
    val globalKrAnime4kMode = EngineSettingsStore.getKrAnime4kMode(ctx)
    val globalKrFont = EngineSettingsStore.getKrDefaultFont(ctx)
    val globalForce = EngineSettingsStore.isKrForceDefaultFont(ctx)
    val configuredGlobalRenderer = EngineSettingsStore.getKrRenderer(ctx)
    val globalOns = remember { EngineSettingsStore.loadOns(ctx) }
    val globalArtKernel = EngineSettingsStore.getArtKernel(ctx)
    val globalArtVersion = EngineSettingsStore.getArtEngineVersion(ctx)
    val globalArtRotate = EngineSettingsStore.isArtRotateScreen(ctx)
    val globalArtPatch = EngineSettingsStore.getArtAutoPatch(ctx)
    val globalArtResolution = EngineSettingsStore.getArtResolution(ctx)
    val globalArtSideCut = EngineSettingsStore.getArtSideCut(ctx)
    val globalArtSurfaceCache = EngineSettingsStore.getArtSurfaceCacheSize(ctx)
    val globalArtFontCache = EngineSettingsStore.getArtFontCacheSize(ctx)
    val globalArtPowerSaving = EngineSettingsStore.getArtPowerSaving(ctx)
    val globalTyExternal = EngineSettingsStore.isTyranoExternalNetwork(ctx)
    val globalTyScoped = EngineSettingsStore.isTyranoScopedSaveDir(ctx)
    val globalRpgMakerMod = EngineSettingsStore.isRpgMakerModEnabled(ctx)
    val globalRpgLegacyRenderer = EngineSettingsStore.isRpgLegacyRenderer(ctx)
    val globalRpgSaveInterop = EngineSettingsStore.isRpgSaveInterop(ctx)
    val globalRpgMvVersion = EngineSettingsStore.getRpgMvEngineVersion(ctx)
    val globalRpgMzVersion = EngineSettingsStore.getRpgMzEngineVersion(ctx)
    val globalRenpyVersion = EngineSettingsStore.getRenpyVersion(ctx)
    val globalSiglusLanguage = EngineSettingsStore.getSiglusLanguage(ctx)
    val globalFvpNls = EngineSettingsStore.getFvpNls(ctx)
    val globalFvpSystemFont = EngineSettingsStore.isFvpSystemFont(ctx)
    val globalFvpTextHidpi = EngineSettingsStore.isFvpTextHidpi(ctx)
    val globalFvpFont = EngineSettingsStore.getFvpFont(ctx)
    val globalWinlator = remember { EngineSettingsStore.loadWinlator(ctx) }
    val globalPpssppVersion = EngineSettingsStore.getPpssppVersion(ctx)
    val ppssppVersionMap = ppssppVersionOptionsMap()
    val winlatorDriverMap = winlatorGraphicsDriverOptionsMap()
    val winlatorDxWrapperMap = winlatorDxWrapperOptionsMap()
    val winlatorScreenSizeMap = winlatorScreenSizeOptionsMap()
    val winlatorLcAllMap = winlatorLcAllOptionsMap()
    val winlatorTimezoneMap = winlatorTimezoneOptionsMap()
    val winlatorBox64PresetMap = winlatorBox64PresetOptionsMap()
    val globalRenpy = remember { EngineSettingsStore.loadRenPy(ctx) }
    val globalRpg = remember { EngineSettingsStore.loadRpgMaker(ctx) }
    val rpgWindowMap = rpgWindowSizeOptionsMap()
    val rpgSpeedUpMap = rpgSpeedUpOptionsMap()
    val rpgFontScaleMap = rpgFontScaleOptionsMap()
    val rpgVerticalAlignMap = rpgVerticalAlignOptionsMap()
    val krVersionMap = krSelectOptionsMap()
    val krKernelMap = krKernelOptionsMap()
    val krPatchOverlayMap = krPatchOverlayOptionsMap()
    val krRendererMap = krRendererOptionsMap()
    val krThreadMap = krThreadOptionsMap()
    val krSwCompressMap = krSoftwareCompressOptionsMap()
    val krOglCompressMap = krOglCompressOptionsMap()
    val krMemMap = krMemOptionsMap()
    val krTexsizeMap = krTexSizeOptionsMap()
    val krFpsMap = krFpsOptionsMap()
    val onsEncodingMap = onsEncodingOptionsMap()
    val artKernelMap = artKernelOptionsMap()
    val artVersionMap = artVersionOptionsMap()
    val renpyVersionMap = renpyVersionOptionsMap()
    val siglusLanguageMap = siglusLanguageOptionsMap()
    val fvpNlsMap = fvpNlsOptionsMap()
    val artPatchMap = artPatchOptionsMap()
    val artResolutionMap = artResolutionOptionsMap()
    val artToggleMap = artToggleOptionsMap()
    val artSurfaceCacheMap = artSurfaceCacheOptionsMap()
    val artFontCacheMap = artFontCacheOptionsMap()
    val engineDefault = stringResource(R.string.engine_option_engine_default)
    val builtinFont = stringResource(R.string.engine_settings_builtin_font)
    val auto = stringResource(R.string.common_auto)
    val perGameSettingsSavedMessage = stringResource(R.string.engine_settings_per_game_saved)

    val isSdl3 = (krKernel ?: globalKrKernel) == EngineSettingsStore.KERNEL_KRKRSDL3
    val globalRenderer = configuredGlobalRenderer.ifEmpty {
        if (isSdl3) EngineSettingsStore.RENDERER_OPENGL else ""
    }
    val effVersion = krVersion ?: globalKrVersion
    val krIs134126 = effVersion == EngineSettingsStore.KR_134 || effVersion == EngineSettingsStore.KR_126

    // 渲染相关全局值（跟随全局时展示用）
    val globalAccurate = EngineSettingsStore.getKrOglAccurateRender(ctx) == "1"
    val globalMem = EngineSettingsStore.getKrMemUsage(ctx)
    val globalDrawThread = EngineSettingsStore.getKrSoftwareDrawThread(ctx)
    val globalSwCompress = EngineSettingsStore.getKrSoftwareCompressTex(ctx)
    val globalOglCompress = EngineSettingsStore.getKrOglCompressTex(ctx)
    val globalTexsize = EngineSettingsStore.getKrOglMaxTexsize(ctx)
    val globalFps = EngineSettingsStore.getKrFpsLimit(ctx)
    val globalVCursorScale = EngineSettingsStore.getKrVCursorScale(ctx)
    val globalMenuOpa = EngineSettingsStore.getKrMenuHandlerOpa(ctx)
    val effRenderer = krRender[PerGameSettingsStore.F_RENDERER]!!.value ?: globalRenderer

    fun save() {
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ENGINE_VERSION, krVersion)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ENGINE_KERNEL, krKernel)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_SCOPED_SAVE_DIR, krScoped)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_SKIP_STARTUP_DIALOGS, krSkipStartupDialogs)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_PATCH_OVERLAY_MODE, krPatchOverlayMode)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ANIME4K_MODE, krAnime4kMode?.takeIf { it in EngineSettingsStore.ANIME4K_MODES })
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_DEFAULT_FONT, krFont)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_FORCE_DEFAULT_FONT, krForceFont)
        krRender.forEach { (field, st) ->
            val v = if (field == PerGameSettingsStore.F_VCURSOR_SCALE) {
                val raw = st.value
                when {
                    raw == null -> null
                    raw.isEmpty() -> ""
                    else -> EngineSettingsStore.normalizeVcursorScale(raw)
                }
            } else st.value
            PerGameSettingsStore.setStr(ctx, gid, field, v)
        }
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_KERNEL, artKernel)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_VERSION, artVersion)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_ART_ROTATE, artRotate)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_PATCH, artPatch)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_RESOLUTION, artResolution)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_SIDE_CUT, artSideCut)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_SURFACE_CACHE_SIZE, artSurfaceCache)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_FONT_CACHE_SIZE, artFontCache)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_POWER_SAVING, artPowerSaving)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RENPY_VERSION, renpyVersion)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_SIGLUS_LANGUAGE, siglusLanguage)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_FVP_NLS, fvpNls)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_FVP_SYSTEM_FONT, fvpSystemFont)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_FVP_TEXT_HIDPI, fvpTextHidpi)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_PPSSPP_VERSION, ppssppVersion)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_FVP_FONT, fvpFont)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_CONTAINER_ID, winlatorContainerId)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_CONTAINER_NAME, winlatorContainerName)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER, winlatorGraphicsDriver)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_DXWRAPPER, winlatorDxwrapper)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE, winlatorScreenSize)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_LC_ALL, winlatorLcAll)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_TZ, winlatorTz)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_BOX64_PRESET, winlatorBox64Preset)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_WINLATOR_SAVE, winlatorSave)
        val onsObj = JSONObject()
        putIfNotNull(onsObj, "scopedsavedir", onsScoped)
        putIfNotNull(onsObj, "strechfull", onsStretch)
        putIfNotNull(onsObj, "ignorecutout", onsCutout)
        putIfNotNull(onsObj, "disablevideo", onsNoVideo)
        putIfNotNull(onsObj, "sharpness", onsSharp)
        putIfNotNull(onsObj, "sharpness_value", onsSharpVal)
        putIfNotNull(onsObj, "encoding", onsEnc)
        PerGameSettingsStore.setOnsOverride(ctx, gid, onsObj)
        PerGameSettingsStore.setBool(ctx, gid, "ty_external", tyExternal)
        PerGameSettingsStore.setBool(ctx, gid, "ty_scoped", tyScoped)
        PerGameSettingsStore.setBool(
            ctx,
            gid,
            PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED,
            rpgMakerMod,
        )
        PerGameSettingsStore.setBool(
            ctx,
            gid,
            PerGameSettingsStore.F_RPG_LEGACY_RENDERER,
            rpgLegacyRenderer,
        )
        PerGameSettingsStore.setBool(
            ctx,
            gid,
            PerGameSettingsStore.F_RPG_SAVE_INTEROP,
            rpgSaveInterop,
        )
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RPG_MV_VERSION, rpgMvVersion)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RPG_MZ_VERSION, rpgMzVersion)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_USE_RUBY18, rpgm.useRuby18)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_SMOOTH_SCALING, rpgm.smoothScaling)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_VSYNC, rpgm.vsync)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_FRAME_SKIP, rpgm.frameSkip)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_SOLID_FONTS, rpgm.solidFonts)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_PATH_CACHE, rpgm.pathCache)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_PREBUILT_PATH_CACHE, rpgm.prebuiltPathCache)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_FAST_PATH_ENUM, rpgm.fastPathEnum)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_COPY_TEXT, rpgm.copyText)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_CHEATS, rpgm.cheats)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_USE_CJK_FONT, rpgm.useCJKFont)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_ENABLE_POSTLOAD_SCRIPTS, rpgm.enablePostloadScripts)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RPG_DEBUG, rpgm.debug)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RPG_CUSTOM_FONT, rpgm.customFont)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RPG_VERTICAL_SCREEN_ALIGN, rpgm.verticalScreenAlign)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RPG_WINDOW_SIZE, rpgm.windowSize)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RPG_SPEED_UP, rpgm.speedUp)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RPG_FONT_SCALE, rpgm.fontScale)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RENPY_CHEATS, renpy.cheats)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RENPY_HW_VIDEO, renpy.hwVideo)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RENPY_AUTOSAVE, renpy.autosave)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RENPY_PHONE_SMALL_VARIANT, renpy.phoneSmallVariant)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RENPY_VSYNC, renpy.vsync)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RENPY_LESS_MEMORY, renpy.lessMemory)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RENPY_LESS_UPDATES, renpy.lessUpdates)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RENPY_DONT_USE_GL2, renpy.dontUseGl2)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_RENPY_RECOMPILE, renpy.recompile)
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = game.title,
                    background = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                    trailing = {
                        TopBarIcon(painterResource(R.drawable.ic_save), stringResource(R.string.common_save), MiuixTheme.colorScheme.primary) {
                            save()
                            android.widget.Toast.makeText(ctx, perGameSettingsSavedMessage, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                // 顶栏透明：列表整体垫在顶栏下方（持久 padding），避免滚动时内容穿过顶栏
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (game.engine) {
	                    EngineType.KIRIKIRI -> {
                        item {
                            SectionCard("KRKR") {
                                OverrideSwitch(stringResource(R.string.engine_settings_scoped_save_dir), globalKrScoped, krScoped) { krScoped = it }
                                OverrideSwitch(
                                    stringResource(R.string.engine_settings_skip_startup_dialogs),
                                    globalKrSkipStartupDialogs,
                                    krSkipStartupDialogs,
                                ) { krSkipStartupDialogs = it }
                                OverrideChoice(stringResource(R.string.engine_settings_engine_version), krVersionMap, globalKrVersion, krVersion) { krVersion = it }
                                OverrideChoice(stringResource(R.string.engine_settings_engine_kernel), krKernelMap, globalKrKernel, krKernel) { krKernel = it }
                                if (!isSdl3) {
                                    OverrideChoice(stringResource(R.string.engine_settings_krkr_patch_overlay), krPatchOverlayMap, globalKrPatchOverlayMode, krPatchOverlayMode) { krPatchOverlayMode = it }
                                }
                            }
                        }
                        item {
                            SectionCard(stringResource(R.string.engine_settings_render)) {
                                if (!isSdl3) {
                                    OverrideSwitch(stringResource(R.string.engine_settings_opengl_accurate_render), globalAccurate, krRender[PerGameSettingsStore.F_OGL_ACCURATE_RENDER]!!.value == "1") { b ->
                                        krRender[PerGameSettingsStore.F_OGL_ACCURATE_RENDER]!!.value = when (b) { null -> ""; true -> "1"; false -> "0" }
                                    }
                                    OverrideChoice(stringResource(R.string.engine_settings_memory_usage), krMemMap, globalMem, krRender[PerGameSettingsStore.F_MEM_USAGE]!!.value, emptyLabel = engineDefault) {
                                        krRender[PerGameSettingsStore.F_MEM_USAGE]!!.value = it
                                    }
                                    // Anime4K 后处理仅 kirikiri2 内核路径支持（GLSurfaceView 注入）
                                    OverrideChoice(
                                        stringResource(R.string.engine_settings_anime4k),
                                        krAnime4kOptionsMap(),
                                        globalKrAnime4kMode,
                                        krAnime4kMode,
                                    ) { krAnime4kMode = it }
                                }
                                OverrideChoice(stringResource(R.string.engine_settings_renderer), krRendererMap, globalRenderer, krRender[PerGameSettingsStore.F_RENDERER]!!.value, emptyLabel = engineDefault) {
                                    krRender[PerGameSettingsStore.F_RENDERER]!!.value = it
                                }
                                if (!isSdl3) {
                                    if (effRenderer == "" || effRenderer == EngineSettingsStore.RENDERER_SOFTWARE) {
                                        OverrideChoice(stringResource(R.string.engine_settings_software_draw_threads), krThreadMap, globalDrawThread, krRender[PerGameSettingsStore.F_SOFTWARE_DRAW_THREAD]!!.value, emptyLabel = auto) {
                                            krRender[PerGameSettingsStore.F_SOFTWARE_DRAW_THREAD]!!.value = it
                                        }
                                        OverrideChoice(stringResource(R.string.engine_settings_software_texture_compression), krSwCompressMap, globalSwCompress, krRender[PerGameSettingsStore.F_SOFTWARE_COMPRESS_TEX]!!.value, emptyLabel = engineDefault) {
                                            krRender[PerGameSettingsStore.F_SOFTWARE_COMPRESS_TEX]!!.value = it
                                        }
                                    }
                                    if (!krIs134126) {
                                        OverrideChoice(stringResource(R.string.engine_settings_fps_limit), krFpsMap, globalFps, krRender[PerGameSettingsStore.F_FPS_LIMIT]!!.value, emptyLabel = engineDefault) {
                                            krRender[PerGameSettingsStore.F_FPS_LIMIT]!!.value = it
                                        }
                                    }
                                    if (effRenderer == "" || effRenderer == EngineSettingsStore.RENDERER_OPENGL) {
                                        OverrideChoice(stringResource(R.string.engine_settings_opengl_texture_compression), krOglCompressMap, globalOglCompress, krRender[PerGameSettingsStore.F_OGL_COMPRESS_TEX]!!.value, emptyLabel = engineDefault) {
                                            krRender[PerGameSettingsStore.F_OGL_COMPRESS_TEX]!!.value = it
                                        }
                                        OverrideChoice(stringResource(R.string.engine_settings_max_texture_size), krTexsizeMap, globalTexsize, krRender[PerGameSettingsStore.F_OGL_MAX_TEXSIZE]!!.value, emptyLabel = auto) {
                                            krRender[PerGameSettingsStore.F_OGL_MAX_TEXSIZE]!!.value = it
                                        }
                                    }
                                }
                            }
                        }
                        if (!isSdl3) {
                            item {
                                SectionCard(stringResource(R.string.engine_settings_operation)) {
                                    // 仅 kirikiri2 内核生效（0.01..1.50，两位小数，0.50 即 Ty 0.5）
                                    OverrideChoice(
                                        stringResource(R.string.engine_settings_vcursor_scale),
                                        krkrVcursorOptions().toMap(),
                                        globalVCursorScale,
                                        krRender[PerGameSettingsStore.F_VCURSOR_SCALE]!!.value?.let { raw ->
                                            if (raw.isEmpty()) "" else EngineSettingsStore.normalizeVcursorScale(raw)
                                        },
                                        emptyLabel = stringResource(R.string.engine_option_engine_default),
                                    ) { krRender[PerGameSettingsStore.F_VCURSOR_SCALE]!!.value = it }
                                    OverrideChoice(
                                        stringResource(R.string.engine_settings_menu_handler_opa),
                                        krkrPercentOptions().toMap(),
                                        globalMenuOpa,
                                        krRender[PerGameSettingsStore.F_MENU_HANDLER_OPA]!!.value,
                                        emptyLabel = stringResource(R.string.engine_option_engine_default),
                                    ) { krRender[PerGameSettingsStore.F_MENU_HANDLER_OPA]!!.value = it }
                                }
                            }
                        }
                        if (!isSdl3) {
                            item {
                                SectionCard(stringResource(R.string.engine_settings_font)) {
                                    // 「跟随全局」必须删除覆盖键（null），存 "" 会被引擎当作
                                    // 显式内置字体覆盖，导致全局字体设置对该游戏永久失效
                                    FontPreference(
                                        label = stringResource(R.string.engine_settings_default_font),
                                        value = krFont?.ifEmpty { stringResource(R.string.engine_settings_builtin_font) }
                                            ?: stringResource(
                                                R.string.engine_settings_follow_global_font,
                                                globalKrFont.ifEmpty { stringResource(R.string.engine_settings_builtin_font) },
                                            ),
                                        followLabel = stringResource(R.string.engine_settings_follow_global),
                                        onFollow = { krFont = null },
                                        onPick = { fontLauncher.launch("*/*") },
                                        valueInSummary = true,
                                    )
                                    if (effVersion != EngineSettingsStore.KR_126) {
                                        OverrideSwitch(stringResource(R.string.engine_settings_force_default_font_short), globalForce, krForceFont) { krForceFont = it }
                                    }
                                }
                            }
                        }
                    }
                    EngineType.ONS -> item {
                        SectionCard("ONS") {
                            OverrideSwitch(stringResource(R.string.engine_settings_scoped_save_dir), globalOns.scopedSaveDir, onsScoped) { onsScoped = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_fullscreen_stretch), globalOns.stretchFull, onsStretch) { onsStretch = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_ignore_cutout), globalOns.ignoreCutout, onsCutout) { onsCutout = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_disable_video), globalOns.disableVideo, onsNoVideo) { onsNoVideo = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_sharpness), globalOns.sharpness, onsSharp) { onsSharp = it }
                            OverrideChoice(stringResource(R.string.engine_settings_text_encoding), onsEncodingMap, globalOns.encoding.decode(), onsEnc) { onsEnc = it }
                        }
                    }
                    EngineType.ARTEMIS -> {
                        item {
                            SectionCard("Artemis") {
                                val effectiveArtKernel = artKernel ?: globalArtKernel
                                OverrideChoice(stringResource(R.string.engine_settings_engine_kernel), artKernelMap, globalArtKernel, artKernel) { artKernel = it }
                                OverrideSwitch(stringResource(R.string.engine_settings_rotate_screen), globalArtRotate, artRotate) { artRotate = it }
                                if (effectiveArtKernel == EngineSettingsStore.ART_KERNEL_OFFICIAL) {
                                    OverrideChoice(stringResource(R.string.engine_settings_engine_version), artVersionMap, globalArtVersion, artVersion) { artVersion = it }
                                    OverrideChoice(stringResource(R.string.engine_settings_auto_patch), artPatchMap, globalArtPatch, artPatch) { artPatch = it }
                                    OverrideChoice(stringResource(R.string.engine_settings_artemis_resolution), artResolutionMap, globalArtResolution, artResolution) { artResolution = it }
                                    OverrideChoice(stringResource(R.string.engine_settings_artemis_side_cut), artToggleMap, globalArtSideCut, artSideCut) { artSideCut = it }
                                    OverrideChoice(stringResource(R.string.engine_settings_artemis_surface_cache), artSurfaceCacheMap, globalArtSurfaceCache, artSurfaceCache) { artSurfaceCache = it }
                                    OverrideChoice(stringResource(R.string.engine_settings_artemis_font_cache), artFontCacheMap, globalArtFontCache, artFontCache) { artFontCache = it }
                                    OverrideChoice(stringResource(R.string.engine_settings_artemis_power_saving), artToggleMap, globalArtPowerSaving, artPowerSaving) { artPowerSaving = it }
                                } else {
                                    // 自研内核直接读游戏包内配置，官方专属项不适用
                                    Text(
                                        stringResource(R.string.engine_settings_artemis_clean_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                        }
                    }
                    // 官方内核专属的手动补丁动作：与启动前自动补丁同源实现
                    if ((artKernel ?: globalArtKernel) == EngineSettingsStore.ART_KERNEL_OFFICIAL) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val patchRunningSummary = stringResource(R.string.engine_settings_artemis_patch_running)
                                AppNavItem(
                                    title = stringResource(R.string.engine_settings_artemis_add_base_patch),
                                    summary = if (artPatchRunning) patchRunningSummary else stringResource(R.string.engine_settings_artemis_add_base_patch_summary),
                                    leadingIcon = R.drawable.ic_artemis_patch,
                                    containerColor = NavWhite,
                                    verticalPadding = 17.dp,
                                    onClick = if (artPatchRunning) null else {
                                        {
                                            artPatchRunning = true
                                            scope.launch {
                                                artPatchResult = EngineLauncher.applyArtemisBasePatchManually(ctx, game)
                                                artPatchRunning = false
                                            }
                                        }
                                    },
                                )
                                AppNavItem(
                                    title = stringResource(R.string.engine_settings_artemis_add_windows_env_patch),
                                    summary = if (artPatchRunning) patchRunningSummary else stringResource(R.string.engine_settings_artemis_add_windows_env_patch_summary),
                                    leadingIcon = R.drawable.ic_artemis_patch,
                                    containerColor = NavWhite,
                                    verticalPadding = 17.dp,
                                    onClick = if (artPatchRunning) null else {
                                        {
                                            artPatchRunning = true
                                            scope.launch {
                                                artPatchResult = EngineLauncher.applyArtemisWindowsEnvPatchManually(ctx, game)
                                                artPatchRunning = false
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    }
                    EngineType.RENPY -> item {
                        SectionCard("Ren'Py") {
                            OverrideChoice(stringResource(R.string.engine_settings_engine_version), renpyVersionMap, globalRenpyVersion, renpyVersion) { renpyVersion = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_renpy_hw_video), globalRenpy.hwVideo, renpy.hwVideo) {
                                renpyOverride = renpy.copy(hwVideo = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_renpy_vsync), globalRenpy.vsync, renpy.vsync) {
                                renpyOverride = renpy.copy(vsync = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_renpy_less_memory), globalRenpy.lessMemory, renpy.lessMemory) {
                                renpyOverride = renpy.copy(lessMemory = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_renpy_dont_use_gl2), !globalRenpy.dontUseGl2, renpy.dontUseGl2?.let { !it }) {
                                renpyOverride = renpy.copy(dontUseGl2 = it?.let { v -> !v })
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_renpy_phone_small_variant), globalRenpy.phoneSmallVariant, renpy.phoneSmallVariant) {
                                renpyOverride = renpy.copy(phoneSmallVariant = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_renpy_autosave), globalRenpy.autosave, renpy.autosave) {
                                renpyOverride = renpy.copy(autosave = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_renpy_less_updates), globalRenpy.lessUpdates, renpy.lessUpdates) {
                                renpyOverride = renpy.copy(lessUpdates = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_renpy_recompile), globalRenpy.recompile, renpy.recompile) {
                                renpyOverride = renpy.copy(recompile = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_renpy_cheats), globalRenpy.cheats, renpy.cheats) {
                                renpyOverride = renpy.copy(cheats = it)
                            }
                            Text(
                                stringResource(R.string.engine_settings_renpy_module_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    EngineType.SIGLUS -> item {
                        SectionCard("Siglus") {
                            OverrideChoice(stringResource(R.string.engine_settings_siglus_language_title), siglusLanguageMap, globalSiglusLanguage, siglusLanguage) { siglusLanguage = it }
                            Text(
                                stringResource(R.string.engine_settings_siglus_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    EngineType.FVP -> item {
                        SectionCard("FVP") {
                            OverrideChoice(stringResource(R.string.engine_settings_fvp_nls_title), fvpNlsMap, globalFvpNls, fvpNls) { fvpNls = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_fvp_system_font_title), globalFvpSystemFont, fvpSystemFont) { fvpSystemFont = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_fvp_text_hidpi_title), globalFvpTextHidpi, fvpTextHidpi) { fvpTextHidpi = it }
                            OverrideFontPreference(
                                label = stringResource(R.string.engine_settings_fvp_font_title),
                                globalValue = globalFvpFont,
                                override = fvpFont,
                                followGameDefaultLabel = stringResource(R.string.engine_settings_fvp_font_follow),
                                onSet = { fvpFont = it },
                                onPick = { fvpFontLauncher.launch("*/*") },
                            )
                            Text(
                                stringResource(R.string.engine_settings_fvp_font_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            Text(
                                stringResource(R.string.engine_settings_fvp_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    EngineType.RPGMAKER -> item {
                        SectionCard("RPG Maker RGSS") {
                            Text(
                                stringResource(R.string.engine_settings_rpgmaker_module_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_use_ruby18), globalRpg.useRuby18, rpgm.useRuby18) {
                                rpgmOverride = rpgm.copy(useRuby18 = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_smooth_scaling), globalRpg.smoothScaling, rpgm.smoothScaling) {
                                rpgmOverride = rpgm.copy(smoothScaling = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_vsync), globalRpg.vsync, rpgm.vsync) {
                                rpgmOverride = rpgm.copy(vsync = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_frame_skip), globalRpg.frameSkip, rpgm.frameSkip) {
                                rpgmOverride = rpgm.copy(frameSkip = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_solid_fonts), globalRpg.solidFonts, rpgm.solidFonts) {
                                rpgmOverride = rpgm.copy(solidFonts = it)
                            }
                            OverrideChoice(
                                stringResource(R.string.engine_settings_rpgm_window_size),
                                rpgWindowMap,
                                globalRpg.windowSize,
                                rpgm.windowSize,
                            ) { rpgmOverride = rpgm.copy(windowSize = it) }
                            OverrideChoice(
                                stringResource(R.string.engine_settings_rpgm_speed_up),
                                rpgSpeedUpMap,
                                globalRpg.speedUp,
                                rpgm.speedUp,
                            ) { rpgmOverride = rpgm.copy(speedUp = it) }
                            OverrideChoice(
                                stringResource(R.string.engine_settings_rpgm_font_scale),
                                rpgFontScaleMap,
                                globalRpg.fontScale,
                                rpgm.fontScale,
                            ) { rpgmOverride = rpgm.copy(fontScale = it) }
                            OverrideChoice(
                                stringResource(R.string.engine_settings_rpgm_vertical_align),
                                rpgVerticalAlignMap,
                                globalRpg.verticalScreenAlign,
                                rpgm.verticalScreenAlign,
                            ) { rpgmOverride = rpgm.copy(verticalScreenAlign = it) }
                            // 「跟随全局」必须删除覆盖键（null）；显式空串表示覆盖为默认字体
                            FontPreference(
                                label = stringResource(R.string.engine_settings_rpgm_custom_font),
                                value = rpgm.customFont?.let { path ->
                                    if (path.isBlank()) {
                                        stringResource(R.string.engine_settings_rpgm_custom_font_default)
                                    } else {
                                        RpgMakerRuntimeEnvironment.customFontFileName(path)
                                    }
                                } ?: stringResource(
                                    R.string.engine_settings_follow_global_font,
                                    globalRpg.customFont.takeIf { it.isNotBlank() }
                                        ?.let { RpgMakerRuntimeEnvironment.customFontFileName(it) }
                                        ?: stringResource(R.string.engine_settings_rpgm_custom_font_default),
                                ),
                                followLabel = stringResource(R.string.engine_settings_follow_global),
                                onFollow = { rpgmOverride = rpgm.copy(customFont = null) },
                                onPick = { rpgFontLauncher.launch("*/*") },
                                valueInSummary = true,
                            )
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_path_cache), globalRpg.pathCache, rpgm.pathCache) {
                                rpgmOverride = rpgm.copy(pathCache = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_prebuilt_path_cache), globalRpg.prebuiltPathCache, rpgm.prebuiltPathCache) {
                                rpgmOverride = rpgm.copy(prebuiltPathCache = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_fast_path_enum), globalRpg.fastPathEnum, rpgm.fastPathEnum) {
                                rpgmOverride = rpgm.copy(fastPathEnum = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_copy_text), globalRpg.copyText, rpgm.copyText) {
                                rpgmOverride = rpgm.copy(copyText = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_cheats), globalRpg.cheats, rpgm.cheats) {
                                rpgmOverride = rpgm.copy(cheats = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_use_cjk_font), globalRpg.useCJKFont, rpgm.useCJKFont) {
                                rpgmOverride = rpgm.copy(useCJKFont = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_postload_scripts), globalRpg.enablePostloadScripts, rpgm.enablePostloadScripts) {
                                rpgmOverride = rpgm.copy(enablePostloadScripts = it)
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_rpgm_debug), globalRpg.debug, rpgm.debug) {
                                rpgmOverride = rpgm.copy(debug = it)
                            }
                        }
                    }
                    EngineType.RPG_MV, EngineType.RPG_MZ -> item {
                        val isMv = game.engine == EngineType.RPG_MV
                        val versionMap = if (isMv) rpgMvVersionOptionsMap() else rpgMzVersionOptionsMap()
                        val globalVersion = if (isMv) globalRpgMvVersion else globalRpgMzVersion
                        val overrideVersion = if (isMv) rpgMvVersion else rpgMzVersion
                        SectionCard(game.engine.displayName) {
                            OverrideChoice(stringResource(R.string.engine_settings_engine_version), versionMap, globalVersion, overrideVersion) { v ->
                                if (isMv) rpgMvVersion = v else rpgMzVersion = v
                            }
                            OverrideSwitch(stringResource(R.string.engine_settings_external_network), globalTyExternal, tyExternal) { tyExternal = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_scoped_save_dir), globalTyScoped, tyScoped) { tyScoped = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_game_modifier), globalRpgMakerMod, rpgMakerMod) { rpgMakerMod = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_legacy_renderer), globalRpgLegacyRenderer, rpgLegacyRenderer) { rpgLegacyRenderer = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_save_interop), globalRpgSaveInterop, rpgSaveInterop) { rpgSaveInterop = it }
                        }
                    }
                    EngineType.TYRANO,
                    EngineType.VN,
                    EngineType.WEB_OTHER,
                    EngineType.UNKNOWN -> item {
                        SectionCard(
                            if (game.engine == EngineType.UNKNOWN) {
                                stringResource(R.string.engine_name_web)
                            } else {
                                game.engine.displayName
                            },
                        ) {
                            OverrideSwitch(stringResource(R.string.engine_settings_external_network), globalTyExternal, tyExternal) { tyExternal = it }
                            if (game.engine !in setOf(EngineType.VN, EngineType.WEB_OTHER)) {
                                OverrideSwitch(stringResource(R.string.engine_settings_scoped_save_dir), globalTyScoped, tyScoped) { tyScoped = it }
                            }
                        }
                    }
                    EngineType.PSP -> item {
                        SectionCard(game.engine.displayName) {
                            OverrideChoice(
                                stringResource(R.string.engine_settings_ppsspp_version_title),
                                ppssppVersionMap,
                                globalPpssppVersion,
                                ppssppVersion,
                            ) { ppssppVersion = it }
                            Text(
                                stringResource(R.string.engine_settings_ppsspp_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    EngineType.NINTENDO_SWITCH -> item {
                        SectionCard(game.engine.displayName) {
                            Text(
                                stringResource(R.string.engine_settings_external_emulator_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    EngineType.YURIS -> item {
                        SectionCard(game.engine.displayName) {
                            Text(
                                stringResource(R.string.engine_settings_yuris_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    EngineType.PC, EngineType.CATSYSTEM2 -> item {
                        SectionCard(game.engine.displayName) {
                            Text(
                                stringResource(R.string.engine_settings_pc_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                if (game.engine in WINLATOR_LAUNCH_ENGINES) item {
                    SectionCard("Winlator") {
                        OverrideText(
                            label = stringResource(R.string.engine_settings_winlator_container_id_title),
                            globalValue = if (globalWinlator.containerId > 0) globalWinlator.containerId.toString() else "",
                            override = winlatorContainerId,
                            hint = stringResource(R.string.engine_settings_winlator_container_id_summary),
                            sanitize = { it.filter { ch -> ch.isDigit() }.take(6) },
                        ) { winlatorContainerId = it }
                        OverrideText(
                            label = stringResource(R.string.engine_settings_winlator_container_name_title),
                            globalValue = globalWinlator.containerName,
                            override = winlatorContainerName,
                            hint = stringResource(R.string.engine_settings_winlator_container_name_summary),
                        ) { winlatorContainerName = it }
                        OverrideChoice(
                            stringResource(R.string.engine_settings_winlator_graphics_driver_title),
                            winlatorDriverMap,
                            globalWinlator.graphicsDriver,
                            winlatorGraphicsDriver,
                        ) { winlatorGraphicsDriver = it }
                        OverrideChoice(
                            stringResource(R.string.engine_settings_winlator_dxwrapper_title),
                            winlatorDxWrapperMap,
                            globalWinlator.dxwrapper,
                            winlatorDxwrapper,
                        ) { winlatorDxwrapper = it }
                        OverrideChoice(
                            stringResource(R.string.engine_settings_winlator_screen_size_title),
                            winlatorScreenSizeMap,
                            globalWinlator.screenSize,
                            winlatorScreenSize,
                        ) { winlatorScreenSize = it }
                        OverrideChoice(
                            stringResource(R.string.engine_settings_winlator_lc_all_title),
                            winlatorLcAllMap,
                            globalWinlator.lcAll,
                            winlatorLcAll,
                        ) { winlatorLcAll = it }
                        OverrideChoice(
                            stringResource(R.string.engine_settings_winlator_tz_title),
                            winlatorTimezoneMap,
                            globalWinlator.tz,
                            winlatorTz,
                        ) { winlatorTz = it }
                        OverrideChoice(
                            stringResource(R.string.engine_settings_winlator_box64_preset_title),
                            winlatorBox64PresetMap,
                            globalWinlator.box64Preset,
                            winlatorBox64Preset,
                        ) { winlatorBox64Preset = it }
                        OverrideSwitch(
                            stringResource(R.string.engine_settings_winlator_save_title),
                            globalWinlator.save,
                            winlatorSave,
                        ) { winlatorSave = it }
                        Text(
                            stringResource(R.string.engine_settings_winlator_per_game_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                item { Box(Modifier.fillMaxWidth().navigationBarsPadding().height(12.dp)) }
            }
        }
    }

    artPatchResult?.let { result ->
        val message = when (result) {
            EngineLauncher.ArtemisManualPatchResult.SUCCESS ->
                stringResource(R.string.engine_settings_artemis_patch_success)
            EngineLauncher.ArtemisManualPatchResult.FAILED ->
                stringResource(R.string.engine_settings_artemis_patch_failed)
            EngineLauncher.ArtemisManualPatchResult.GAME_DIR_UNRESOLVED ->
                stringResource(R.string.engine_settings_artemis_patch_dir_unresolved)
            EngineLauncher.ArtemisManualPatchResult.PERMISSION_REQUIRED ->
                stringResource(R.string.engine_settings_artemis_patch_permission_required)
        }
        AppAlertDialog(
            onDismissRequest = { artPatchResult = null },
            title = {
                Text(
                    stringResource(R.string.engine_settings_artemis_patch_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { artPatchResult = null }) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }
}

// ───────────────────────── 覆盖行组件 ─────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** 覆盖版下拉行：Miuix OverlayDropdownPreference，选项首位为“跟随全局”。 */
@Composable
private fun OverrideChoice(
    label: String,
    options: Map<String, String>,
    global: String,
    override: String?,
    emptyLabel: String = stringResource(R.string.engine_settings_builtin_font),
    onSet: (String?) -> Unit,
) {
    val following = override == null
    val effValue = override ?: global
    val keys = options.keys.toList()
    val labels = listOf(stringResource(R.string.engine_settings_follow_global_with_value, labelOf(effValue, options, emptyLabel))) + keys.map { options[it] ?: it }
    val index = if (following) 0 else (keys.indexOf(override).takeIf { it >= 0 } ?: -1) + 1
    OverlayDropdownPreference(
        title = label,
        items = labels,
        selectedIndex = index,
        onSelectedIndexChange = { i -> onSet(if (i == 0) null else keys[i - 1]) },
    )
}

/** 覆盖版开关行：Miuix OverlayDropdownPreference，三态（跟随全局 / 开 / 关）。 */
@Composable
private fun OverrideSwitch(label: String, global: Boolean, override: Boolean?, onSet: (Boolean?) -> Unit) {
    val onText = stringResource(R.string.common_enabled)
    val offText = stringResource(R.string.common_disabled)
    val labels = listOf(stringResource(R.string.engine_settings_follow_global_bool, if (global) onText else offText), onText, offText)
    val index = when { override == null -> 0; override -> 1; else -> 2 }
    OverlayDropdownPreference(
        title = label,
        items = labels,
        selectedIndex = index,
        onSelectedIndexChange = { i -> onSet(if (i == 0) null else i == 1) },
    )
}

/**
 * 覆盖版字体行（FVP）：三态——跟随全局（展示全局值）/ 跟随游戏默认 / 选择字体文件。
 * 覆盖值语义：null=跟随全局，""=跟随游戏默认，路径=自定义（App 私有字体）。
 */
@Composable
private fun OverrideFontPreference(
    label: String,
    globalValue: String,
    override: String?,
    followGameDefaultLabel: String,
    onSet: (String?) -> Unit,
    onPick: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val globalLabel = globalValue.substringAfterLast('/').ifBlank { followGameDefaultLabel }
    val summary = when {
        override == null -> stringResource(R.string.engine_settings_follow_global_with_value, globalLabel)
        override.isEmpty() -> followGameDefaultLabel
        else -> override.substringAfterLast('/')
    }
    ArrowPreference(
        title = label,
        summary = summary,
        onClick = { open = true },
    )
    if (open) {
        AppAlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppNavItem(
                        title = stringResource(R.string.engine_settings_follow_global_with_value, globalLabel),
                        leadingIcon = R.drawable.ic_font_bookmark,
                        containerColor = DialogItemSurface,
                    ) {
                        onSet(null)
                        open = false
                    }
                    AppNavItem(
                        title = followGameDefaultLabel,
                        leadingIcon = R.drawable.ic_font_bookmark,
                        containerColor = DialogItemSurface,
                    ) {
                        onSet("")
                        open = false
                    }
                    AppNavItem(
                        title = stringResource(R.string.engine_settings_select_font_file),
                        leadingIcon = R.drawable.ic_font_bookmark,
                        containerColor = DialogItemSurface,
                    ) {
                        open = false
                        onPick()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** 经外置 Winlator 启动的引擎（YU-RIS / CatSystem2 / 手动添加的 PC）：展示 Winlator 覆盖卡片。 */
private val WINLATOR_LAUNCH_ENGINES: Set<EngineType> = EngineType.entries
    .filter { ExternalEmulatorRegistry.forEngine(it)?.launchStyle == EmulatorLaunchStyle.WINLATOR_EXTERNAL }
    .toSet()

/** 覆盖版文本行：弹统一输入框；确定=写入覆盖（空串=显式不下发该参数），「跟随全局」=移除覆盖。 */
@Composable
private fun OverrideText(
    label: String,
    globalValue: String,
    override: String?,
    hint: String,
    sanitize: ((String) -> String)? = null,
    onSet: (String?) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val emptyLabel = stringResource(R.string.engine_settings_winlator_follow_container)
    val summary = if (override == null) {
        stringResource(R.string.engine_settings_follow_global_with_value, globalValue.ifBlank { emptyLabel })
    } else {
        override.ifBlank { emptyLabel }
    }
    ArrowPreference(
        title = label,
        summary = summary,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        var text by remember(override) { mutableStateOf(override.orEmpty()) }
        val normalized = text.trim()
        AppAlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    AppSearchField(
                        query = text,
                        onQueryChange = { text = sanitize?.invoke(it) ?: it },
                        onSearch = { onSet(normalized); showDialog = false },
                        leadingIcon = painterResource(R.drawable.ic_sheet_rename),
                        iconContentDescription = label,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSet(normalized); showDialog = false }) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onSet(null); showDialog = false }) {
                        Text(stringResource(R.string.engine_settings_follow_global))
                    }
                    TextButton(onClick = { showDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            },
        )
    }
}

private fun labelOf(v: String, map: Map<String, String>, emptyLabel: String): String = map[v] ?: v.ifEmpty { emptyLabel }

private fun onsBool(o: JSONObject, key: String): Boolean? = if (o.has(key)) o.optBoolean(key) else null
private fun onsStr(o: JSONObject, key: String, def: String): String? = if (o.has(key)) o.optString(key, def) else null
private fun putIfNotNull(o: JSONObject, key: String, v: Boolean?) { if (v != null) o.put(key, v) else o.remove(key) }
private fun putIfNotNull(o: JSONObject, key: String, v: String?) { if (v != null) o.put(key, v) else o.remove(key) }
private fun String.decode(): String = if (this == "sjis") "sjis" else if (this == "utf8") "utf8" else "gbk"
