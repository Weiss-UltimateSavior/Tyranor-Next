package com.tyranor.next.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.TopBarIcon
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
    var krPatchOverlayMode by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_PATCH_OVERLAY_MODE)) }
    var krFont by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_DEFAULT_FONT)) }
    var krForceFont by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_FORCE_DEFAULT_FONT)) }
    val krRender = PerGameSettingsStore.KR_FIELDS.associateWith { field ->
        remember(gid, field) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, field)) }
    }

    var artVersion by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_VERSION)) }
    var artRotate by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_ART_ROTATE)) }
    var artPatch by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_PATCH)) }
    var artResolution by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_RESOLUTION)) }
    var artSideCut by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_SIDE_CUT)) }
    var artSurfaceCache by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_SURFACE_CACHE_SIZE)) }
    var artFontCache by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_FONT_CACHE_SIZE)) }
    var artPowerSaving by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_POWER_SAVING)) }
    var renpyVersion by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_RENPY_VERSION)) }

    val onsOverride = remember(gid) { mutableStateOf(PerGameSettingsStore.loadOnsOverride(ctx, gid) ?: JSONObject()) }
    var onsScoped by remember(gid) { mutableStateOf(onsBool(onsOverride.value, "scopedsavedir")) }
    var onsStretch by remember(gid) { mutableStateOf(onsBool(onsOverride.value, "strechfull")) }
    var onsCutout by remember(gid) { mutableStateOf(onsBool(onsOverride.value, "ignorecutout")) }
    var onsNoVideo by remember(gid) { mutableStateOf(onsBool(onsOverride.value, "disablevideo")) }
    var onsSharp by remember(gid) { mutableStateOf(onsBool(onsOverride.value, "sharpness")) }
    var onsSharpVal by remember(gid) { mutableStateOf(onsStr(onsOverride.value, "sharpness_value", "2")) }
    var onsEnc by remember(gid) { mutableStateOf(onsStr(onsOverride.value, "encoding", "gbk")) }

    var tyExternal by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, "ty_external")) }
    var tyScoped by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, "ty_scoped")) }
    var rpgMakerMod by remember(gid) {
        mutableStateOf(
            PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED),
        )
    }
    var rpgLegacyRenderer by remember(gid) {
        mutableStateOf(
            PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_RPG_LEGACY_RENDERER),
        )
    }
    var rpgMvVersion by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_RPG_MV_VERSION)) }
    var rpgMzVersion by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_RPG_MZ_VERSION)) }

    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val p = copyFontToPrivate(ctx, uri)
            if (p != null) krFont = p
        }
    }

    val globalKrVersion = EngineSettingsStore.getKrEngineVersion(ctx)
    val globalKrKernel = EngineSettingsStore.getKrKernel(ctx)
    val globalKrScoped = EngineSettingsStore.isKrScopedSaveDir(ctx)
    val globalKrPatchOverlayMode = EngineSettingsStore.getKrPatchOverlayMode(ctx)
    val globalKrFont = EngineSettingsStore.getKrDefaultFont(ctx)
    val globalForce = EngineSettingsStore.isKrForceDefaultFont(ctx)
    val configuredGlobalRenderer = EngineSettingsStore.getKrRenderer(ctx)
    val globalOns = remember { EngineSettingsStore.loadOns(ctx) }
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
    val globalRpgMvVersion = EngineSettingsStore.getRpgMvEngineVersion(ctx)
    val globalRpgMzVersion = EngineSettingsStore.getRpgMzEngineVersion(ctx)
    val globalRenpyVersion = EngineSettingsStore.getRenpyVersion(ctx)
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
    val artVersionMap = artVersionOptionsMap()
    val renpyVersionMap = renpyVersionOptionsMap()
    val artPatchMap = artPatchOptionsMap()
    val artResolutionMap = artResolutionOptionsMap()
    val artToggleMap = artToggleOptionsMap()
    val artSurfaceCacheMap = artSurfaceCacheOptionsMap()
    val artFontCacheMap = artFontCacheOptionsMap()
    val engineDefault = stringResource(R.string.engine_option_engine_default)
    val builtinFont = stringResource(R.string.engine_settings_builtin_font)
    val auto = stringResource(R.string.common_auto)

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
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_PATCH_OVERLAY_MODE, krPatchOverlayMode)
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
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_VERSION, artVersion)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_ART_ROTATE, artRotate)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_PATCH, artPatch)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_RESOLUTION, artResolution)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_SIDE_CUT, artSideCut)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_SURFACE_CACHE_SIZE, artSurfaceCache)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_FONT_CACHE_SIZE, artFontCache)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_POWER_SAVING, artPowerSaving)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RENPY_VERSION, renpyVersion)
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
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RPG_MV_VERSION, rpgMvVersion)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_RPG_MZ_VERSION, rpgMzVersion)
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                Column(modifier = Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)) {
                    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(game.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TopBarIcon(painterResource(R.drawable.ic_save), stringResource(R.string.common_save), MiuixTheme.colorScheme.primary) {
                                save()
                                android.widget.Toast.makeText(ctx, ctx.getString(R.string.engine_settings_per_game_saved), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (game.engine) {
	                    EngineType.KIRIKIRI -> {
                        item {
                            SectionCard("KRKR") {
                                OverrideSwitch(stringResource(R.string.engine_settings_scoped_save_dir), globalKrScoped, krScoped) { krScoped = it }
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
                                    OverrideFont(stringResource(R.string.engine_settings_default_font), globalKrFont, krFont, onReset = { krFont = "" }, onPick = { fontLauncher.launch("*/*") })
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
                    EngineType.ARTEMIS -> item {
                        SectionCard("Artemis") {
                            OverrideChoice(stringResource(R.string.engine_settings_engine_version), artVersionMap, globalArtVersion, artVersion) { artVersion = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_rotate_screen), globalArtRotate, artRotate) { artRotate = it }
                            OverrideChoice(stringResource(R.string.engine_settings_auto_patch), artPatchMap, globalArtPatch, artPatch) { artPatch = it }
                            OverrideChoice(stringResource(R.string.engine_settings_artemis_resolution), artResolutionMap, globalArtResolution, artResolution) { artResolution = it }
                            OverrideChoice(stringResource(R.string.engine_settings_artemis_side_cut), artToggleMap, globalArtSideCut, artSideCut) { artSideCut = it }
                            OverrideChoice(stringResource(R.string.engine_settings_artemis_surface_cache), artSurfaceCacheMap, globalArtSurfaceCache, artSurfaceCache) { artSurfaceCache = it }
                            OverrideChoice(stringResource(R.string.engine_settings_artemis_font_cache), artFontCacheMap, globalArtFontCache, artFontCache) { artFontCache = it }
                            OverrideChoice(stringResource(R.string.engine_settings_artemis_power_saving), artToggleMap, globalArtPowerSaving, artPowerSaving) { artPowerSaving = it }
                        }
                    }
                    EngineType.RENPY -> item {
                        SectionCard("Ren'Py") {
                            OverrideChoice(stringResource(R.string.engine_settings_engine_version), renpyVersionMap, globalRenpyVersion, renpyVersion) { renpyVersion = it }
                            Text(
                                stringResource(R.string.engine_settings_renpy_module_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    EngineType.RPGMAKER -> item {
                        SectionCard("RPG Maker") {
                            Text(
                                stringResource(R.string.engine_settings_rpgmaker_module_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
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
                        }
                    }
                    EngineType.TYRANO,
                    EngineType.VN,
                    EngineType.WEB_OTHER,
                    EngineType.UNKNOWN -> item {
                        SectionCard(if (game.engine == EngineType.UNKNOWN) "Web" else game.engine.displayName) {
                            OverrideSwitch(stringResource(R.string.engine_settings_external_network), globalTyExternal, tyExternal) { tyExternal = it }
                            if (game.engine !in setOf(EngineType.VN, EngineType.WEB_OTHER)) {
                                OverrideSwitch(stringResource(R.string.engine_settings_scoped_save_dir), globalTyScoped, tyScoped) { tyScoped = it }
                            }
                        }
                    }
                }

                item { Box(Modifier.fillMaxWidth().navigationBarsPadding().height(12.dp)) }
            }
        }
    }
}

// ───────────────────────── 覆盖行组件 ─────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 8.dp,
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

/** 覆盖版字体行：Miuix ArrowPreference，点击弹窗选择（跟随全局 / 选择字体文件）。 */
@Composable
private fun OverrideFont(label: String, global: String, override: String?, onReset: () -> Unit, onPick: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val following = override == null
    val builtInFont = stringResource(R.string.engine_settings_builtin_font)
    val summary = if (following) {
        stringResource(R.string.engine_settings_follow_global_font, global.ifEmpty { builtInFont })
    } else {
        override.ifEmpty { builtInFont }
    }
    ArrowPreference(title = label, summary = summary, onClick = { open = true })
    if (open) {
        AppAlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { onReset(); open = false }.padding(vertical = 8.dp)) { Text(stringResource(R.string.engine_settings_follow_global), style = MaterialTheme.typography.bodyMedium) }
                    Row(Modifier.fillMaxWidth().clickable { open = false; onPick() }.padding(vertical = 8.dp)) { Text(stringResource(R.string.engine_settings_select_font_file), style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private fun labelOf(v: String, map: Map<String, String>, emptyLabel: String): String = map[v] ?: v.ifEmpty { emptyLabel }

private fun copyFontToPrivate(ctx: android.content.Context, uri: android.net.Uri): String? = try {
    val name = (uri.lastPathSegment ?: "font.ttf").substringAfterLast('/').substringAfterLast('\\')
    val dir = java.io.File(ctx.filesDir, "fonts")
    if (!dir.isDirectory && !dir.mkdirs()) return null
    val target = java.io.File(dir, name)
    ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out -> input.copyTo(out) } } ?: return null
    target.absolutePath
} catch (t: Throwable) { null }

private fun onsBool(o: JSONObject, key: String): Boolean? = if (o.has(key)) o.optBoolean(key) else null
private fun onsStr(o: JSONObject, key: String, def: String): String? = if (o.has(key)) o.optString(key, def) else null
private fun putIfNotNull(o: JSONObject, key: String, v: Boolean?) { if (v != null) o.put(key, v) else o.remove(key) }
private fun putIfNotNull(o: JSONObject, key: String, v: String?) { if (v != null) o.put(key, v) else o.remove(key) }
private fun String.decode(): String = if (this == "sjis") "sjis" else if (this == "utf8") "utf8" else "gbk"
