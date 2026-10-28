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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyranor.next.R
import androidx.compose.ui.graphics.Color as ComposeColor
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.EmulatorLaunchStyle
import com.tyranor.next.core.engine.external.ExternalEmulatorRegistry
import com.tyranor.next.core.engine.external.RpgMakerRuntimeEnvironment
import com.tyranor.next.core.engine.external.WinlatorContract
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.settings.GraphicsCapabilities
import com.tyranor.next.core.settings.GraphicsFamily
import com.tyranor.next.core.settings.GraphicsOverride
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.EffectiveEngineSettings
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.core.settings.RenPyOverride
import com.tyranor.next.core.settings.RpgMakerOverride
import com.tyranor.next.core.settings.WinlatorOverride
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.DialogTextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.LocalIndication
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.game.startActivityWithPageTransition
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.TopBarIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
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
    val graphicsFamily = game.engine.graphicsFamily()
    val globalGraphics = graphicsFamily?.let { EngineSettingsStore.getGraphicsSettings(ctx, it) }
    var graphicsOverride by remember(gid, graphicsFamily) {
        mutableStateOf(graphicsFamily?.let { PerGameSettingsStore.getGraphicsOverride(ctx, gid, it) } ?: GraphicsOverride())
    }

    // 覆盖值（null=跟随全局）
    var krVersion by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ENGINE_VERSION)) }
    var krKernel by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ENGINE_KERNEL)) }
    var krScoped by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_SCOPED_SAVE_DIR)) }
    var krSkipStartupDialogs by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_SKIP_STARTUP_DIALOGS)) }
    var krPatchOverlayMode by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_PATCH_OVERLAY_MODE)) }
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
    var fbNls by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_FB_NLS)) }
    var fvpNls by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_FVP_NLS)) }
    var ethornellNls by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ETHORNELL_NLS)) }
    var ethornellFont by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ETHORNELL_FONT)) }
    var fvpSystemFont by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_FVP_SYSTEM_FONT)) }
    var fvpTextHidpi by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_FVP_TEXT_HIDPI)) }
    var ppssppVersion by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_PPSSPP_VERSION)) }
    var webShellPort by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WEB_SHELL_PORT)) }
    var fvpFont by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_FVP_FONT)) }
    var xs35Scoped by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_XSYS35_SCOPED_SAVE_DIR)) }
    var xs35Renderer by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_XSYS35_RENDERER)) }
    var xs35IntegerScale by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_XSYS35_INTEGER_SCALE)) }
    var xs35MuteUnfocus by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_XSYS35_MUTE_UNFOCUS)) }
    var xs4Scoped by remember(gid) { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_XSYS4_SCOPED_SAVE_DIR)) }
    var xs4Encoding by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_XSYS4_ENCODING)) }
    var xs4FontGothic by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_XSYS4_FONT_GOTHIC_PATH)) }
    var xs4FontMincho by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_XSYS4_FONT_MINCHO_PATH)) }
    var xs4Joypad by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_XSYS4_JOYPAD)) }
    var xs4Msgskip by remember(gid) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_XSYS4_MSGSKIP_DELAY)) }
    // System35 / System4 生效存档路径：与 GameSaveManager.resolveSaveLocation 同源（IO 读取，避免组合期磁盘/DB 阻塞）
    var saveLocation by remember(gid) { mutableStateOf<GameSaveManager.SaveLocation?>(null) }
    var winlatorContainerId by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_CONTAINER_ID)) }
    var winlatorContainerName by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_CONTAINER_NAME)) }
    var winlatorGraphicsDriver by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER)) }
    // 旧版内部预设（wined3d_gl/gdi）读入即拆成 wrapper + renderer，写回时只落新形态。
    val winlatorStoredDxWrapper = remember(gid) {
        PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_DXWRAPPER)
    }
    val winlatorLegacyRenderer = remember(gid) {
        EngineSettingsStore.winlatorLegacyRenderer(winlatorStoredDxWrapper)
    }
    var winlatorDxwrapper by remember(gid) {
        mutableStateOf(
            if (winlatorLegacyRenderer != null) {
                WinlatorContract.DXWRAPPER_WINED3D
            } else {
                winlatorStoredDxWrapper
            },
        )
    }
    var winlatorWined3dRenderer by remember(gid) {
        mutableStateOf(
            PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_WINED3D_RENDERER)
                ?: winlatorLegacyRenderer,
        )
    }
    var winlatorWinComponents by remember(gid) {
        mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_WIN_COMPONENTS))
    }
    var winlatorAudioDriver by remember(gid) {
        mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_AUDIO_DRIVER))
    }
    var winlatorEnvVars by remember(gid) {
        mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_ENV_VARS))
    }
    // 快捷方式启动：只打开 Winlator 里已存在的 .desktop，容器 ID / 名称留空时按默认推导。
    var winlatorShortcutContainerId by remember(gid) {
        mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_SHORTCUT_CONTAINER_ID))
    }
    var winlatorShortcut by remember(gid) {
        mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_SHORTCUT))
    }
    var winlatorScreenSize by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE)) }
    var winlatorLcAll by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_LC_ALL)) }
    var winlatorTz by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_TZ)) }
    var winlatorBox64Preset by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_BOX64_PRESET)) }
    var winlatorSave by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_WINLATOR_SAVE)) }
    var winlatorMode by remember { mutableStateOf(PerGameSettingsStore.getWinlatorMode(ctx, gid)) }
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
    // 输入重映射：虚拟按键 / 手柄映射（null=跟随全局）
    var inputPadEnabled by remember {
        mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_INPUT_PAD_ENABLED))
    }
    var inputGamepadEnabled by remember {
        mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_INPUT_GAMEPAD_ENABLED))
    }
    // 「跟随全局」的展示值必须按本游戏引擎取：未显式设置时 MV/MZ 默认开、其余默认关，
    // 否则这个标签会与游戏内实际是否出现按键层相反
    val inputGlobalPad = remember(gid) {
        EngineSettingsStore.isInputPadEnabled(
            ctx,
            default = InputRemapRepository.engineDefaultsPadEnabled(game.engine),
        )
    }
    val inputGlobalGamepad = remember { EngineSettingsStore.isInputGamepadEnabled(ctx) }
    // 按键方案的单游戏覆盖：旧布局迁移会把结果写成**按游戏的方案**并写入该覆盖，若这里没有
    // 入口，用户既改不回「跟随全局」，也改不了这个游戏的方案——设置页的全局选择对它无效，
    // 页面显示会与实际生效的方案相反。
    //
    // 覆盖值与方案列表**一起在 IO 读取**（第八轮复审）：
    //  - `PerGameSettingsStore` 是阻塞 DB 读（`GameOverridesRepository.loadRowBlocking`），组合期
    //    同步读取会卡首帧；
    //  - 两者必须同时就绪：覆盖已知而列表还空时，下拉只会显示「跟随全局」，误点即静默清掉覆盖
    //    （旧实现就是选择时立即落盘，用户无从察觉）。就绪前该行禁用并显示「加载中…」。
    var inputProfileOverride by remember(gid) { mutableStateOf<String?>(null) }
    var inputProfiles by remember(gid) {
        mutableStateOf(emptyList<InputRemapRepository.ProfileSummary>())
    }
    var inputProfileReady by remember(gid) { mutableStateOf(false) }
    var inputProfileLoadFailed by remember(gid) { mutableStateOf(false) }
    // 是否改过该行：只有改过才在保存时落盘（第八轮复审）。用脏标记而不是「就绪才写」：
    // 未就绪不可能脏，天然挡住「还没读出来就把既有覆盖清成跟随全局」；也不会把页面的过期快照
    // 盖到别处刚写入的值上（游戏启动时的旧布局迁移会写同一个覆盖）。
    var inputProfileDirty by remember(gid) { mutableStateOf(false) }
    val inputGlobalProfile = remember(gid) { InputRemapRepository.globalProfileId(ctx) }
    LaunchedEffect(gid) {
        // 读失败必须收敛（第八轮复审）：`inputProfileReady` 若只有成功出口，该行会永久停在
        // 「加载中…」，用户既不知情也没法改；异常本身也不该穿出组合（DB 打开/查询失败）
        try {
            val loaded = withContext(Dispatchers.IO) {
                InputRemapRepository.listProfileSummaries(ctx) to
                    InputRemapRepository.gameProfileIdOverride(ctx, gid)
            }
            inputProfiles = loaded.first
            inputProfileOverride = loaded.second
            inputProfileReady = true
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            inputProfileLoadFailed = true
        }
    }
    // System35 / System4 生效存档路径展示：resolveSaveLocation 内含游戏目录解析与三级设置合并
    // （磁盘 + DB 读），挂起在 IO 线程取结果，避免组合期阻塞
    LaunchedEffect(gid) {
        if (game.engine != EngineType.XSYS35 && game.engine != EngineType.XSYS4) return@LaunchedEffect
        saveLocation = withContext(Dispatchers.IO) {
            GameSaveManager(ctx).resolveSaveLocation(game)
        }
    }
    var rpgmOverride by remember(gid) {
        mutableStateOf(PerGameSettingsStore.toRpgMakerOverride(PerGameSettingsStore.load(ctx, gid)))
    }
    val rpgm = rpgmOverride ?: RpgMakerOverride()

    val scope = rememberCoroutineScope()
    var importingFont by remember(gid) { mutableStateOf(false) }
    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !importingFont) {
            importingFont = true
            scope.launch {
                try {
                    val p = FontImport.importToPrivate(ctx, uri)
                    if (p != null) krFont = p
                } finally { importingFont = false }
            }
        }
    }
    val fvpFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !importingFont) {
            importingFont = true
            scope.launch {
                try {
                    val p = FontImport.importToPrivate(ctx, uri)
                    if (p != null) fvpFont = p else {
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(R.string.engine_settings_fvp_font_import_failed),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                } finally { importingFont = false }
            }
        }
    }
    val ethornellFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !importingFont) {
            importingFont = true
            scope.launch {
                try {
                    val path = FontImport.importToPrivate(ctx, uri)
                    if (path != null) ethornellFont = path else android.widget.Toast.makeText(ctx, R.string.ethornell_error_font, android.widget.Toast.LENGTH_SHORT).show()
                } finally { importingFont = false }
            }
        }
    }
    val xs4GothicFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !importingFont) {
            importingFont = true
            scope.launch {
                try {
                    val path = FontImport.importToPrivate(ctx, uri)
                    if (path != null) xs4FontGothic = path else {
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(R.string.engine_settings_fvp_font_import_failed),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                } finally { importingFont = false }
            }
        }
    }
    val xs4MinchoFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !importingFont) {
            importingFont = true
            scope.launch {
                try {
                    val path = FontImport.importToPrivate(ctx, uri)
                    if (path != null) xs4FontMincho = path else {
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(R.string.engine_settings_fvp_font_import_failed),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                } finally { importingFont = false }
            }
        }
    }
    // RPGM 外置插件读不到 App 私有目录，自定义字体必须落共享存储（见 RpgMakerRuntimeEnvironment）
    val rpgFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = runInterruptible(Dispatchers.IO) { RpgMakerRuntimeEnvironment.importCustomFont(ctx, uri) }
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
    val effectiveArtKernel = EffectiveEngineSettings.resolveAllowed(
        artKernel, globalArtKernel, EngineSettingsStore.ART_KERNELS, EngineSettingsStore.ART_KERNEL_OFFICIAL,
    )
    val globalTyExternal = EngineSettingsStore.isTyranoExternalNetwork(ctx)
    val globalTyScoped = EngineSettingsStore.isTyranoScopedSaveDir(ctx)
    val globalRpgMakerMod = EngineSettingsStore.isRpgMakerModEnabled(ctx)
    val globalRpgLegacyRenderer = EngineSettingsStore.isRpgLegacyRenderer(ctx)
    val globalRpgSaveInterop = EngineSettingsStore.isRpgSaveInterop(ctx)
    val globalRpgMvVersion = EngineSettingsStore.getRpgMvEngineVersion(ctx)
    val globalRpgMzVersion = EngineSettingsStore.getRpgMzEngineVersion(ctx)
    val globalRenpyVersion = EngineSettingsStore.getRenpyVersion(ctx)
    val globalSiglusLanguage = EngineSettingsStore.getSiglusLanguage(ctx)
    val globalFbNls = EngineSettingsStore.getFbNls(ctx)
    val globalFvpNls = EngineSettingsStore.getFvpNls(ctx)
    val globalFvpSystemFont = EngineSettingsStore.isFvpSystemFont(ctx)
    val globalFvpTextHidpi = EngineSettingsStore.isFvpTextHidpi(ctx)
    val globalFvpFont = EngineSettingsStore.getFvpFont(ctx)
    val globalXs35Scoped = EngineSettingsStore.isXs35ScopedSaveDir(ctx)
    val globalXs35Renderer = EngineSettingsStore.getXs35Renderer(ctx)
    val globalXs35IntegerScale = EngineSettingsStore.isXs35IntegerScale(ctx)
    val globalXs35MuteUnfocus = EngineSettingsStore.isXs35MuteUnfocus(ctx)
    val globalXs4Scoped = EngineSettingsStore.isXs4ScopedSaveDir(ctx)
    val globalXs4Encoding = EngineSettingsStore.getXs4Encoding(ctx)
    val globalXs4FontGothic = EngineSettingsStore.getXs4FontGothicPath(ctx)
    val globalXs4FontMincho = EngineSettingsStore.getXs4FontMinchoPath(ctx)
    val globalXs4Joypad = EngineSettingsStore.getXs4Joypad(ctx)
    val globalXs4Msgskip = EngineSettingsStore.getXs4MsgskipDelay(ctx)
    // 从「Winlator 全局设置」返回时重新读取：否则自定义模式的「跟随全局」摘要会停在进入前的旧值。
    var globalWinlatorRevision by remember { mutableIntStateOf(0) }
    val globalWinlator = remember(globalWinlatorRevision) { EngineSettingsStore.loadWinlator(ctx) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) globalWinlatorRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 只覆盖容器名时 mergeWinlator 会把生效 ID 置 0，摘要必须按生效值显示，否则和实际启动不一致。
    val winlatorAutoByContainerNameLabel =
        stringResource(R.string.engine_settings_winlator_container_id_auto_by_name)
    val winlatorNameOnlyOverride = EffectiveEngineSettings.isNameOnlyContainerOverride(
        WinlatorOverride(containerId = winlatorContainerId, containerName = winlatorContainerName),
    )
    val winlatorEffectiveContainerIdLabel = when {
        winlatorNameOnlyOverride -> winlatorAutoByContainerNameLabel
        globalWinlator.containerId > 0 -> globalWinlator.containerId.toString()
        else -> ""
    }
    // 只填名称时摘要直接呈现生效值，不再加「跟随全局 ·」前缀——容器模式下没有「跟随全局」语义。
    val winlatorContainerIdSummaryOverride =
        winlatorAutoByContainerNameLabel.takeIf { winlatorNameOnlyOverride }
    val globalPpssppVersion = EngineSettingsStore.getPpssppVersion(ctx)
    val globalWebShellPort = EngineSettingsStore.getWebShellPort(ctx)
    val ppssppVersionMap = ppssppVersionOptionsMap()
    val winlatorDriverMap = winlatorGraphicsDriverOptionsMap()
    val winlatorDxWrapperMap = winlatorDxWrapperOptionsMap()
    val winlatorScreenSizeMap = winlatorScreenSizeOptionsMap()
    val winlatorLcAllMap = winlatorLcAllOptionsMap()
    val winlatorTimezoneMap = winlatorTimezoneOptionsMap()
    val winlatorBox64PresetMap = winlatorBox64PresetOptionsMap()
    val winlatorWined3dRendererMap = winlatorWined3dRendererOptionsMap()
    val winlatorWinComponentMap = winlatorWinComponentOptionsMap()
    val winlatorAudioDriverMap = winlatorAudioDriverOptionsMap()
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
    val fbNlsMap = fbNlsOptionsMap()
    val fvpNlsMap = fvpNlsOptionsMap()
    val xs35RendererMap = xsys35RendererOptionsMap()
    val xs4EncodingMap = xsys4EncodingOptionsMap()
    val xs4JoypadMap = xsys4JoypadOptionsMap()
    val xs4MsgskipMap = xsys4MsgskipOptionsMap()
    val artPatchMap = artPatchOptionsMap()
    val artResolutionMap = artResolutionOptionsMap()
    val artToggleMap = artToggleOptionsMap()
    val artSurfaceCacheMap = artSurfaceCacheOptionsMap()
    val artFontCacheMap = artFontCacheOptionsMap()
    val engineDefault = stringResource(R.string.engine_option_engine_default)
    val builtinFont = stringResource(R.string.engine_settings_builtin_font)
    val auto = stringResource(R.string.common_auto)
    val perGameSettingsSavedMessage = stringResource(R.string.engine_settings_per_game_saved)

    val effectiveKrKernel = EffectiveEngineSettings.resolveKrKernel(
        krKernel, globalKrKernel,
        GamePathUtils.safUriToPath(game.uri)?.let(GamePathUtils::isRemovableStoragePath) == true,
    )
    val isSdl3 = effectiveKrKernel == EngineSettingsStore.KERNEL_KRKRSDL3
    val globalRenderer = configuredGlobalRenderer.ifEmpty {
        if (isSdl3) EngineSettingsStore.RENDERER_OPENGL else ""
    }
    val effVersion = EffectiveEngineSettings.resolveKrVersion(krVersion, globalKrVersion)
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
        graphicsFamily?.let { PerGameSettingsStore.setGraphicsOverride(ctx, gid, it, graphicsOverride) }
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ENGINE_VERSION, krVersion)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ENGINE_KERNEL, krKernel)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_SCOPED_SAVE_DIR, krScoped)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_SKIP_STARTUP_DIALOGS, krSkipStartupDialogs)
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
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_FB_NLS, fbNls)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_FVP_NLS, fvpNls)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ETHORNELL_NLS, ethornellNls)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ETHORNELL_FONT, ethornellFont)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_FVP_SYSTEM_FONT, fvpSystemFont)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_FVP_TEXT_HIDPI, fvpTextHidpi)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_PPSSPP_VERSION, ppssppVersion)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WEB_SHELL_PORT, webShellPort)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_FVP_FONT, fvpFont)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_XSYS35_SCOPED_SAVE_DIR, xs35Scoped)
        PerGameSettingsStore.setStr(
            ctx,
            gid,
            PerGameSettingsStore.F_XSYS35_RENDERER,
            xs35Renderer?.takeIf { it in EngineSettingsStore.XSYS35_RENDERER_VALUES },
        )
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_XSYS35_INTEGER_SCALE, xs35IntegerScale)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_XSYS35_MUTE_UNFOCUS, xs35MuteUnfocus)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_XSYS4_SCOPED_SAVE_DIR, xs4Scoped)
        PerGameSettingsStore.setStr(
            ctx,
            gid,
            PerGameSettingsStore.F_XSYS4_ENCODING,
            xs4Encoding?.takeIf { it in EngineSettingsStore.XSYS4_ENCODING_VALUES },
        )
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_XSYS4_FONT_GOTHIC_PATH, xs4FontGothic)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_XSYS4_FONT_MINCHO_PATH, xs4FontMincho)
        PerGameSettingsStore.setStr(
            ctx,
            gid,
            PerGameSettingsStore.F_XSYS4_JOYPAD,
            xs4Joypad?.takeIf { it in EngineSettingsStore.XSYS4_JOYPAD_VALUES },
        )
        PerGameSettingsStore.setStr(
            ctx,
            gid,
            PerGameSettingsStore.F_XSYS4_MSGSKIP_DELAY,
            xs4Msgskip?.toIntOrNull()?.takeIf { it in EngineSettingsStore.XSYS4_MSGSKIP_DELAY_VALUES }?.toString(),
        )
        // 覆盖值按原样落盘：切换模式不删除另一种模式的已填值，切回来还能恢复。
        // 模式键先写且总是写——它才是生效依据，否则「把六个下拉改回空」会让推断翻转。
        if (game.engine in WINLATOR_LAUNCH_ENGINES) {
            PerGameSettingsStore.setWinlatorMode(ctx, gid, winlatorMode)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_CONTAINER_ID, winlatorContainerId)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_CONTAINER_NAME, winlatorContainerName)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER, winlatorGraphicsDriver)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_DXWRAPPER, winlatorDxwrapper)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_WINED3D_RENDERER, winlatorWined3dRenderer)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE, winlatorScreenSize)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_LC_ALL, winlatorLcAll)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_TZ, winlatorTz)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_BOX64_PRESET, winlatorBox64Preset)
            PerGameSettingsStore.setStr(
                ctx,
                gid,
                PerGameSettingsStore.F_WINLATOR_WIN_COMPONENTS,
                winlatorWinComponents?.let { EngineSettingsStore.normalizeWinlatorWinComponentsOverride(it) },
            )
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_AUDIO_DRIVER, winlatorAudioDriver)
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_ENV_VARS, winlatorEnvVars)
            // 快捷方式两键独立于运行模式：默认「跟随全局」时也要能填写。
            PerGameSettingsStore.setStr(
                ctx,
                gid,
                PerGameSettingsStore.F_WINLATOR_SHORTCUT_CONTAINER_ID,
                winlatorShortcutContainerId,
            )
            PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_WINLATOR_SHORTCUT, winlatorShortcut)
            PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_WINLATOR_SAVE, winlatorSave)
        }
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
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_INPUT_PAD_ENABLED, inputPadEnabled)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_INPUT_GAMEPAD_ENABLED, inputGamepadEnabled)
        // 按键方案覆盖只在用户真的改过这一行时落盘（脏标记）。无条件写会把本页的过期快照
        // 盖到别处刚写入的值上（启动迁移会写同一个覆盖），就绪前写则会把既有覆盖清成跟随全局。
        if (inputProfileDirty) {
            InputRemapRepository.setGameProfileIdOverride(ctx, gid, inputProfileOverride)
            inputProfileDirty = false
        }
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
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = game.title,
                    background = ComposeColor.Transparent,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                    trailing = {
                        TopBarIcon(painterResource(R.drawable.ic_save), stringResource(R.string.common_save), MiuixTheme.colorScheme.primary) {
                            save()
                            // 存档路径展示与实际生效设置同源：保存后按新值重解一次（磁盘 + DB 读，走 IO）
                            if (game.engine == EngineType.XSYS35 || game.engine == EngineType.XSYS4) {
                                scope.launch {
                                    saveLocation = withContext(Dispatchers.IO) {
                                        GameSaveManager(ctx).resolveSaveLocation(game)
                                    }
                                }
                            }
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
                    if (effectiveArtKernel == EngineSettingsStore.ART_KERNEL_OFFICIAL) {
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
                    EngineType.REALLIVE, EngineType.AVG32, EngineType.UK2 -> item {
                        SectionCard("RealLive / AVG32 / UK2") {
                            OverrideChoice(stringResource(R.string.engine_settings_fb_nls_title), fbNlsMap, globalFbNls, fbNls) { fbNls = it }
                            Text(
                                stringResource(R.string.engine_settings_fb_note),
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
                    EngineType.ETHORNELL -> item {
                        SectionCard("Ethornell / BGI") {
                            OverrideChoice(stringResource(R.string.ethornell_nls_title), ethornellNlsOptions().toMap(), EngineSettingsStore.getEthornellNls(ctx), ethornellNls) { ethornellNls = it }
                            OverrideFontPreference(
                                label = stringResource(R.string.ethornell_font_title),
                                globalValue = EngineSettingsStore.getEthornellFont(ctx),
                                override = ethornellFont,
                                followGameDefaultLabel = stringResource(R.string.ethornell_font_default),
                                onSet = { ethornellFont = it },
                                onPick = { if (!importingFont) ethornellFontLauncher.launch("*/*") },
                                enabled = !importingFont,
                            )
                            Text(stringResource(R.string.ethornell_settings_note), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                        }
                    }
                    EngineType.XSYS35 -> item {
                        SectionCard(stringResource(R.string.engine_settings_xsys35_title)) {
                            OverrideSwitch(stringResource(R.string.engine_settings_scoped_save_dir), globalXs35Scoped, xs35Scoped) { xs35Scoped = it }
                            OverrideChoice(stringResource(R.string.engine_settings_xsys35_renderer_title), xs35RendererMap, globalXs35Renderer, xs35Renderer) { xs35Renderer = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_xsys35_integer_scale_title), globalXs35IntegerScale, xs35IntegerScale) { xs35IntegerScale = it }
                            OverrideSwitch(stringResource(R.string.engine_settings_xsys35_mute_unfocus_title), globalXs35MuteUnfocus, xs35MuteUnfocus) { xs35MuteUnfocus = it }
                            Text(
                                stringResource(R.string.engine_settings_xsys35_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            Text(
                                stringResource(R.string.engine_settings_xsys35_uninstall_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            // 当前生效存档路径（与存档管理同源）：独立存档 = 应用外部存档目录，关闭 = 游戏目录 save/
                            saveLocation?.let { location ->
                                Text(
                                    location.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                                location.directory?.let { dir ->
                                    Text(
                                        dir.absolutePath,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                    EngineType.XSYS4 -> item {
                        SectionCard(stringResource(R.string.engine_settings_xsys4_title)) {
                            OverrideSwitch(stringResource(R.string.engine_settings_scoped_save_dir), globalXs4Scoped, xs4Scoped) { xs4Scoped = it }
                            OverrideChoice(stringResource(R.string.engine_settings_xsys4_encoding_title), xs4EncodingMap, globalXs4Encoding, xs4Encoding) { xs4Encoding = it }
                            OverrideFontPreference(
                                label = stringResource(R.string.engine_settings_xsys4_font_gothic_title),
                                globalValue = globalXs4FontGothic,
                                override = xs4FontGothic,
                                followGameDefaultLabel = stringResource(R.string.engine_settings_use_builtin_font),
                                onSet = { xs4FontGothic = it },
                                onPick = { if (!importingFont) xs4GothicFontLauncher.launch("*/*") },
                                enabled = !importingFont,
                            )
                            OverrideFontPreference(
                                label = stringResource(R.string.engine_settings_xsys4_font_mincho_title),
                                globalValue = globalXs4FontMincho,
                                override = xs4FontMincho,
                                followGameDefaultLabel = stringResource(R.string.engine_settings_use_builtin_font),
                                onSet = { xs4FontMincho = it },
                                onPick = { if (!importingFont) xs4MinchoFontLauncher.launch("*/*") },
                                enabled = !importingFont,
                            )
                            OverrideChoice(stringResource(R.string.engine_settings_xsys4_joypad_title), xs4JoypadMap, globalXs4Joypad, xs4Joypad) { xs4Joypad = it }
                            OverrideChoice(stringResource(R.string.engine_settings_xsys4_msgskip_title), xs4MsgskipMap, globalXs4Msgskip.toString(), xs4Msgskip) { xs4Msgskip = it }
                            Text(
                                stringResource(R.string.engine_settings_xsys4_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            Text(
                                stringResource(R.string.engine_settings_xsys4_uninstall_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            Text(
                                stringResource(R.string.engine_settings_xsys4_compat_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            // 当前生效存档路径（与存档管理同源）：独立存档 = 应用外部存档目录，关闭 = 游戏根 SaveFolder
                            saveLocation?.let { location ->
                                Text(
                                    location.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                                location.directory?.let { dir ->
                                    Text(
                                        dir.absolutePath,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                            }
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
                            // 输入重映射：虚拟按键 + 手柄映射（逐游戏跟随全局或单独开关）
                            OverrideSwitch(
                                stringResource(R.string.engine_settings_input_pad_title),
                                inputGlobalPad,
                                inputPadEnabled,
                            ) { inputPadEnabled = it }
                            OverrideSwitch(
                                stringResource(R.string.engine_settings_input_gamepad_title),
                                inputGlobalGamepad,
                                inputGamepadEnabled,
                            ) { inputGamepadEnabled = it }
                            InputProfileOverrideRow(
                                profiles = inputProfiles,
                                globalId = inputGlobalProfile,
                                overrideId = inputProfileOverride,
                                ready = inputProfileReady,
                                failed = inputProfileLoadFailed,
                                onSet = { id ->
                                    inputProfileOverride = id
                                    inputProfileDirty = true
                                },
                            )
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
                            // 输入重映射：Web 系共享（虚拟按键 + 手柄映射），逐游戏可跟随全局或单独开关
                            OverrideSwitch(
                                stringResource(R.string.engine_settings_input_pad_title),
                                inputGlobalPad,
                                inputPadEnabled,
                            ) { inputPadEnabled = it }
                            OverrideSwitch(
                                stringResource(R.string.engine_settings_input_gamepad_title),
                                inputGlobalGamepad,
                                inputGamepadEnabled,
                            ) { inputGamepadEnabled = it }
                            InputProfileOverrideRow(
                                profiles = inputProfiles,
                                globalId = inputGlobalProfile,
                                overrideId = inputProfileOverride,
                                ready = inputProfileReady,
                                failed = inputProfileLoadFailed,
                                onSet = { id ->
                                    inputProfileOverride = id
                                    inputProfileDirty = true
                                },
                            )
                            if (game.engine in setOf(EngineType.TYRANO, EngineType.VN, EngineType.WEB_OTHER)) {
                                OverrideText(
                                    label = stringResource(R.string.engine_settings_web_shell_port_title),
                                    globalValue = globalWebShellPort.toString(),
                                    override = webShellPort,
                                    hint = stringResource(R.string.engine_settings_web_shell_port_summary),
                                    sanitize = { it.filter { ch -> ch.isDigit() }.take(5) },
                                    allowEmpty = false,
                                    validate = { it.toIntOrNull()?.let { port -> port in 1..65535 } == true },
                                ) { webShellPort = it }
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
                    EngineType.ANDROID_APP -> item {
                        SectionCard(game.engine.displayName) {
                            Text(
                                stringResource(R.string.engine_settings_android_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                if (graphicsFamily != null && globalGraphics != null) item {
                    PerGameGraphicsSettings(
                        global = globalGraphics,
                        override = graphicsOverride,
                        capability = GraphicsCapabilities.forSettings(
                            graphicsFamily, if (graphicsFamily == GraphicsFamily.KR) effectiveKrKernel else effectiveArtKernel,
                            if (graphicsFamily == GraphicsFamily.KR) effVersion
                            else EffectiveEngineSettings.resolveArtVersion(artVersion, globalArtVersion),
                            effRenderer,
                        ),
                    ) { graphicsOverride = it }
                }

                if (game.engine in WINLATOR_LAUNCH_ENGINES) item {
                    SectionCard("Winlator") {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.engine_settings_winlator_mode_title),
                            items = listOf(
                                stringResource(R.string.engine_settings_winlator_mode_global),
                                stringResource(R.string.engine_settings_winlator_mode_container),
                                stringResource(R.string.engine_settings_winlator_mode_custom),
                            ),
                            selectedIndex = when (winlatorMode) {
                                PerGameSettingsStore.WINLATOR_MODE_CONTAINER -> 1
                                PerGameSettingsStore.WINLATOR_MODE_CUSTOM -> 2
                                else -> 0
                            },
                            onSelectedIndexChange = {
                                winlatorMode = when (it) {
                                    0 -> PerGameSettingsStore.WINLATOR_MODE_GLOBAL
                                    1 -> PerGameSettingsStore.WINLATOR_MODE_CONTAINER
                                    else -> PerGameSettingsStore.WINLATOR_MODE_CUSTOM
                                }
                            },
                        )
                        if (winlatorMode == PerGameSettingsStore.WINLATOR_MODE_GLOBAL) {
                            AppNavItem(
                                title = stringResource(R.string.engine_settings_winlator_global_entry),
                                summary = stringResource(R.string.engine_settings_winlator_global_entry_summary),
                                onClick = {
                                    startActivityWithPageTransition(
                                        ctx,
                                        EngineSettingsActivity.createIntent(ctx, EngineSettingsKind.WINLATOR),
                                    )
                                },
                            )
                        }
                    }
                }

                if (game.engine in WINLATOR_LAUNCH_ENGINES &&
                    winlatorMode == PerGameSettingsStore.WINLATOR_MODE_CONTAINER
                ) item {
                    SectionCard(stringResource(R.string.engine_settings_winlator_group_container)) {
                        WinlatorContainerRows(
                            globalContainerName = globalWinlator.containerName,
                            effectiveContainerIdLabel = winlatorEffectiveContainerIdLabel,
                            containerId = winlatorContainerId,
                            containerName = winlatorContainerName,
                            idSummaryOverride = winlatorContainerIdSummaryOverride,
                            onContainerIdChange = { winlatorContainerId = it },
                            onContainerNameChange = { winlatorContainerName = it },
                        )
                    }
                }

                if (game.engine in WINLATOR_LAUNCH_ENGINES &&
                    winlatorMode == PerGameSettingsStore.WINLATOR_MODE_CUSTOM
                ) item {
                    SectionCard(stringResource(R.string.engine_settings_winlator_group_container)) {
                        WinlatorContainerRows(
                            globalContainerName = globalWinlator.containerName,
                            effectiveContainerIdLabel = winlatorEffectiveContainerIdLabel,
                            containerId = winlatorContainerId,
                            containerName = winlatorContainerName,
                            idSummaryOverride = winlatorContainerIdSummaryOverride,
                            onContainerIdChange = { winlatorContainerId = it },
                            onContainerNameChange = { winlatorContainerName = it },
                        )
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
                        ) {
                            winlatorDxwrapper = it
                            // 切到非 WineD3D 时清掉渲染器覆盖，避免隐藏后仍残留 GDI/GL/Vulkan。
                            if ((it ?: globalWinlator.dxwrapper) != WinlatorContract.DXWRAPPER_WINED3D) {
                                winlatorWined3dRenderer = null
                            }
                        }
                        // 未覆盖（null）时运行时用全局值；显式空串仍表示「跟随容器」，不回退到全局。
                        val effectiveDxWrapper = winlatorDxwrapper ?: globalWinlator.dxwrapper
                        if (effectiveDxWrapper == WinlatorContract.DXWRAPPER_WINED3D) {
                            OverrideChoice(
                                stringResource(R.string.engine_settings_winlator_wined3d_renderer_title),
                                winlatorWined3dRendererMap,
                                globalWinlator.wined3dRenderer,
                                winlatorWined3dRenderer,
                            ) { winlatorWined3dRenderer = it }
                        }
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
                        EngineSettingsStore.WINLATOR_WIN_COMPONENT_KEYS.forEach { key ->
                            OverrideChoice(
                                label = winlatorWinComponentTitle(key),
                                options = winlatorWinComponentMap,
                                global = EngineSettingsStore.winlatorWinComponent(
                                    globalWinlator.winComponents,
                                    key,
                                ),
                                override = EngineSettingsStore.winlatorWinComponentOverride(
                                    winlatorWinComponents,
                                    key,
                                ),
                            ) { value ->
                                winlatorWinComponents = EngineSettingsStore.setWinlatorWinComponentOverride(
                                    winlatorWinComponents,
                                    key,
                                    value,
                                )
                            }
                        }
                        // 接收端按位置比较组件串：九项不齐时整组不下发，必须让用户看到这一点。
                        val effectiveWinComponents = EngineSettingsStore.mergeWinlatorWinComponents(
                            globalWinlator.winComponents,
                            winlatorWinComponents,
                        )
                        if (effectiveWinComponents.isNotBlank() &&
                            !EngineSettingsStore.isCompleteWinlatorWinComponents(effectiveWinComponents)
                        ) {
                            Text(
                                stringResource(R.string.engine_settings_winlator_win_components_incomplete),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        OverrideChoice(
                            stringResource(R.string.engine_settings_winlator_audio_driver_title),
                            winlatorAudioDriverMap,
                            globalWinlator.audioDriver,
                            winlatorAudioDriver,
                        ) { winlatorAudioDriver = it }
                        OverrideText(
                            label = stringResource(R.string.engine_settings_winlator_env_vars_title),
                            globalValue = globalWinlator.envVars,
                            override = winlatorEnvVars,
                            hint = stringResource(R.string.engine_settings_winlator_env_vars_summary),
                            validate = { EngineSettingsStore.isValidWinlatorEnvVars(it) },
                        ) { winlatorEnvVars = it }
                        OverrideSwitch(
                            stringResource(R.string.engine_settings_winlator_save_title),
                            globalWinlator.save,
                            winlatorSave,
                        ) { winlatorSave = it }
                        // 生效环境变量非空时 save 会被整体省略（接收端会替换容器变量），提示仅会话生效。
                        if ((winlatorEnvVars ?: globalWinlator.envVars).isNotBlank()) {
                            Text(
                                stringResource(R.string.engine_settings_winlator_save_suppressed_by_env),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.engine_settings_winlator_per_game_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                // 快捷方式启动与运行模式无关：默认「跟随全局」时也要能填写名称 / 容器。
                if (game.engine in WINLATOR_LAUNCH_ENGINES) item {
                    SectionCard(stringResource(R.string.engine_settings_winlator_group_shortcut)) {
                        OverrideText(
                            label = stringResource(R.string.engine_settings_winlator_shortcut_container_id_title),
                            globalValue = "",
                            override = winlatorShortcutContainerId,
                            hint = stringResource(R.string.engine_settings_winlator_shortcut_container_id_summary),
                            sanitize = { it.filter { ch -> ch.isDigit() }.take(6) },
                            nullSummary = stringResource(R.string.engine_settings_winlator_shortcut_container_default),
                            blankSummary = stringResource(R.string.engine_settings_winlator_shortcut_container_default),
                            clearLabel = stringResource(R.string.engine_settings_winlator_shortcut_container_default),
                        ) { winlatorShortcutContainerId = it }
                        OverrideText(
                            label = stringResource(R.string.engine_settings_winlator_shortcut_title),
                            globalValue = "",
                            override = winlatorShortcut,
                            hint = stringResource(R.string.engine_settings_winlator_shortcut_summary),
                            nullSummary = stringResource(R.string.engine_settings_winlator_shortcut_auto),
                            blankSummary = stringResource(R.string.engine_settings_winlator_shortcut_auto),
                            clearLabel = stringResource(R.string.engine_settings_winlator_shortcut_auto),
                        ) { winlatorShortcut = it }
                        Text(
                            stringResource(R.string.engine_settings_winlator_shortcut_note),
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
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = { artPatchResult = null },
                )
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

/**
 * 按键方案的单游戏覆盖行（首位「跟随全局」）。
 *
 * 单独包一层是因为选项值/展示名要过 [profileDisplayName]：内置默认与迁移主布局存的是
 * 语言中立占位名（Default），直接显示会把占位名暴露给用户。
 *
 * 选择**只改本地状态**，与同页其它覆盖项一致，随顶栏「保存」落盘（此前是选择即写盘，
 * 与「保存」按钮的语义相反，返回/取消也回滚不了）；是否落盘由调用方的脏标记决定。
 *
 * @param ready 方案列表与覆盖值是否已读完。未就绪时该行禁用（显示「加载中…」）——此前的空列表
 *   会让 `index` 回退到 0，把有覆盖的显示成「跟随全局」，而下拉里只有这一项，误点即清掉覆盖。
 * @param failed 读取失败（方案文件/DB 异常）：显示「加载失败」并保持禁用，不用「加载中…」冒充正常态。
 */
@Composable
private fun InputProfileOverrideRow(
    profiles: List<InputRemapRepository.ProfileSummary>,
    globalId: String,
    overrideId: String?,
    ready: Boolean,
    failed: Boolean,
    onSet: (String?) -> Unit,
) {
    OverrideChoice(
        label = stringResource(R.string.engine_settings_input_profile_title),
        options = profiles.associate { it.id to profileDisplayName(it) },
        global = globalId,
        override = overrideId,
        emptyLabel = stringResource(R.string.input_settings_profile_default),
        // 未就绪 → 只显示对应标签且**真禁用**（`OverlayDropdownPreference` 有 `enabled` 参数，
        // 同款用法见 AppSettingsActivity 的「玻璃风格下不可用」项）
        notReadyLabel = when {
            ready -> null
            failed -> stringResource(R.string.input_settings_profile_load_failed)
            else -> stringResource(R.string.common_loading)
        },
        // 覆盖引用的方案文件被删（悬空引用）时显式标注：此时 engine 实际回落到内置默认，
        // 显示成「跟随全局」会与生效值不符，用户也无从判断该覆盖为什么没效果
        missingLabel = overrideId
            ?.takeIf { id -> profiles.none { it.id == id } }
            ?.let { stringResource(R.string.input_settings_profile_missing) },
        onSet = onSet,
    )
}

/**
 * 覆盖版下拉行：Miuix OverlayDropdownPreference，选项首位为“跟随全局”。
 *
 * @param notReadyLabel 非空表示数据尚未就绪（加载中/加载失败）：只显示该标签、`enabled = false`
 *   且不响应任何选择。**此前**空选项时 `index` 会回退到 0（把有覆盖的显示成「跟随全局」），
 *   下拉里也只有这一项，误点就会把真实覆盖清成跟随全局（第八轮复审）。
 * @param missingLabel 非空且 [override] 不在 [options] 里（悬空引用，如方案文件被删）时，把它
 *   显式列成一项并选中：此时 engine 实际回落到内置默认，显示成「跟随全局」与生效值不符。
 *   选中该占位项不产生任何写入；[notReadyLabel] 非空时本参数不生效（加载态下还读不出覆盖）。
 */
@Composable
internal fun OverrideChoice(
    label: String,
    options: Map<String, String>,
    global: String,
    override: String?,
    emptyLabel: String = stringResource(R.string.engine_settings_builtin_font),
    enabled: Boolean = true,
    notReadyLabel: String? = null,
    missingLabel: String? = null,
    onSet: (String?) -> Unit,
) {
    if (notReadyLabel != null) {
        OverlayDropdownPreference(
            title = label,
            items = listOf(notReadyLabel),
            selectedIndex = 0,
            enabled = false,
            onSelectedIndexChange = { },
        )
        return
    }
    val keys = options.keys.toList()
    val dangling = override != null && override !in options
    // 悬空引用的首项取**真实的全局值**：此时 override 已不在 options 里，labelOf 查不到映射会
    // 回显原始方案 id（第八轮复审）——用户读不懂的内部标识，且它早已不是选中项
    val followValue = if (dangling) global else override ?: global
    val labels = buildList {
        add(stringResource(R.string.engine_settings_follow_global_with_value, labelOf(followValue, options, emptyLabel)))
        addAll(keys.map { options[it] ?: it })
        if (dangling && missingLabel != null) add(missingLabel)
    }
    val index = when {
        override == null -> 0
        // 占位项固定追加在末尾，用 lastIndex 而不是裸写 keys.size + 1
        dangling -> if (missingLabel != null) labels.lastIndex else 0
        else -> keys.indexOf(override) + 1
    }
    OverlayDropdownPreference(
        title = label,
        items = labels,
        selectedIndex = index,
        enabled = enabled,
        onSelectedIndexChange = { i ->
            if (i == 0) {
                onSet(null)
            } else {
                // 占位项（悬空引用）与越界下标都不写入
                keys.getOrNull(i - 1)?.let(onSet)
            }
        },
    )
}

/** 覆盖版开关行：Miuix OverlayDropdownPreference，三态（跟随全局 / 开 / 关）。 */
@Composable
internal fun OverrideSwitch(
    label: String,
    global: Boolean,
    override: Boolean?,
    enabled: Boolean = true,
    onSet: (Boolean?) -> Unit,
) {
    val onText = stringResource(R.string.common_enabled)
    val offText = stringResource(R.string.common_disabled)
    val labels = listOf(stringResource(R.string.engine_settings_follow_global_bool, if (global) onText else offText), onText, offText)
    val index = when { override == null -> 0; override -> 1; else -> 2 }
    OverlayDropdownPreference(
        title = label,
        items = labels,
        selectedIndex = index,
        enabled = enabled,
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
    enabled: Boolean = true,
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
        enabled = enabled,
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
                        indication = null,
                    ) {
                        onSet(null)
                        open = false
                    }
                    AppNavItem(
                        title = followGameDefaultLabel,
                        leadingIcon = R.drawable.ic_font_bookmark,
                        containerColor = DialogItemSurface,
                        indication = null,
                    ) {
                        onSet("")
                        open = false
                    }
                    AppNavItem(
                        title = stringResource(R.string.engine_settings_select_font_file),
                        leadingIcon = R.drawable.ic_font_bookmark,
                        containerColor = DialogItemSurface,
                        indication = null,
                    ) {
                        open = false
                        onPick()
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { open = false },
                )
            },
        )
    }
}

/** 经外置 Winlator 启动的引擎（YU-RIS / CatSystem2 / 手动添加的 PC）：展示 Winlator 覆盖卡片。 */
private val WINLATOR_LAUNCH_ENGINES: Set<EngineType> = EngineType.entries
    .filter { ExternalEmulatorRegistry.forEngine(it)?.launchStyle == EmulatorLaunchStyle.WINLATOR_EXTERNAL }
    .toSet()

/** 覆盖版文本行：弹统一输入框；确定=写入覆盖（空串=显式不下发该参数），「跟随全局」=移除覆盖。 */
/** 容器 ID / 名称两行：跟随容器与自定义卡片复用，规则只在这里维护一份。 */
@Composable
private fun WinlatorContainerRows(
    globalContainerName: String,
    effectiveContainerIdLabel: String,
    containerId: String?,
    containerName: String?,
    idSummaryOverride: String?,
    onContainerIdChange: (String?) -> Unit,
    onContainerNameChange: (String?) -> Unit,
) {
    OverrideText(
        label = stringResource(R.string.engine_settings_winlator_container_id_title),
        globalValue = effectiveContainerIdLabel,
        override = containerId,
        hint = stringResource(R.string.engine_settings_winlator_container_id_summary_per_game),
        sanitize = { it.filter { ch -> ch.isDigit() }.take(6) },
        summaryOverride = idSummaryOverride,
    ) { onContainerIdChange(it?.takeIf { value -> value.isNotBlank() }) }
    OverrideText(
        label = stringResource(R.string.engine_settings_winlator_container_name_title),
        globalValue = globalContainerName,
        override = containerName,
        hint = stringResource(R.string.engine_settings_winlator_container_name_summary_per_game),
        // 控制字符与零宽格式字符都会破坏 Winlator 端名称匹配，一并过滤。
        sanitize = {
            it.filterNot { ch -> ch.isISOControl() || ch.category == CharCategory.FORMAT }.take(64)
        },
        // 弹窗确认空文本要保留为 "：它表示「显式不下发名称」，与「跟随全局」（null）不同；
        // 否则继承了全局容器名的游戏无法单独取消名称选择。
    ) { onContainerNameChange(it) }
}

@Composable
private fun OverrideText(
    label: String,
    globalValue: String,
    override: String?,
    hint: String,
    sanitize: ((String) -> String)? = null,
    allowEmpty: Boolean = true,
    validate: (String) -> Boolean = { true },
    // 仅对本游戏生效、无全局对应项的字段（如 Winlator 快捷方式）：覆写 null / 空串摘要与清除按钮文案。
    nullSummary: String? = null,
    blankSummary: String? = null,
    clearLabel: String? = null,
    // 运行模式卡：只填容器名时直接用生效值做摘要，不再拼「跟随全局 ·」前缀。
    summaryOverride: String? = null,
    onSet: (String?) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val emptyLabel = stringResource(R.string.engine_settings_winlator_follow_container)
    val summary = summaryOverride ?: when {
        override == null ->
            nullSummary ?: stringResource(R.string.engine_settings_follow_global_with_value, globalValue.ifBlank { emptyLabel })
        override.isBlank() -> blankSummary ?: emptyLabel
        else -> override
    }
    ArrowPreference(
        title = label,
        summary = summary,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        var text by remember(override) { mutableStateOf(override.orEmpty()) }
        val normalized = text.trim()
        val canConfirm = validate(normalized) && (allowEmpty || normalized.isNotEmpty())
        AppAlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text = {
                // 弹窗点击反馈规范：输入区内禁用全部点击/按压反馈（无涟漪）
                CompositionLocalProvider(LocalIndication provides NoIndication) {
                    Column {
                        AppSearchField(
                            query = text,
                            onQueryChange = { text = sanitize?.invoke(it) ?: it },
                            onSearch = { if (canConfirm) { onSet(normalized); showDialog = false } },
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
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_save),
                    enabled = canConfirm,
                    onClick = { onSet(normalized); showDialog = false },
                )
            },
            dismissButton = {
                Row {
                    DialogTextButton(
                        text = clearLabel ?: stringResource(R.string.engine_settings_follow_global),
                        onClick = { onSet(null); showDialog = false },
                    )
                    DialogTextButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = { showDialog = false },
                    )
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
