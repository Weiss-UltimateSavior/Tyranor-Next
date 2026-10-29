package com.tyranor.next.core.settings

import android.content.Context
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame

/**
 * 三级设置解析器（P0-3）：读取应用级全局值与单游戏覆盖，交给 [EffectiveEngineSettings]
 * 纯函数层合并，产出启动期一次性的 [ResolvedEngineSettings]。
 *
 * 启动链只消费解析结果，避免在 EngineLauncher / GameSaveManager 内散落 `?:` 逐字段合并；
 * 新增设置项时只需在 [ResolvedEngineSettings] 与 [resolve] 里各加一处。
 */
object EngineSettingsResolver {

    /**
     * 纯函数：全局 Winlator 设置 + 逐游戏覆盖 JSON → 生效设置。
     *
     * 三种模式的行为：
     * - 跟随全局：直接返回全局（等价于 `mergeWinlator(global, null)`）；
     * - 跟随容器：以 [WinlatorOverride.containerBaseline] 为覆盖，清空全部参数并 `save=false`，
     *   容器 ID / 名称先继承全局再由逐游戏值覆盖；
     * - 自定义：按覆盖值合并。
     *
     * 抽成纯函数是为了让 CONTAINER 的 forced-empty 组装与 `save=false` 有直接的 JVM 断言，
     * 而不是只能通过 UI 或启动链间接验证。
     */
    internal fun resolveWinlatorSettings(
        global: EngineSettingsStore.Winlator,
        overrideJson: org.json.JSONObject?,
    ): EngineSettingsStore.Winlator {
        val override = PerGameSettingsStore.toWinlatorOverride(overrideJson)
        return when (PerGameSettingsStore.inferWinlatorMode(overrideJson ?: org.json.JSONObject())) {
            PerGameSettingsStore.WINLATOR_MODE_CONTAINER ->
                EffectiveEngineSettings.mergeWinlator(
                    global,
                    WinlatorOverride.containerBaseline(override?.containerId, override?.containerName),
                )

            PerGameSettingsStore.WINLATOR_MODE_CUSTOM ->
                EffectiveEngineSettings.mergeWinlator(global, override)

            else -> EffectiveEngineSettings.mergeWinlator(global, null)
        }
    }


    /** 解析单个游戏的生效引擎设置；[gameDir] 用于可移动存储的内核回退判定，未知传 null。 */
    fun resolve(context: Context, game: ScanGame, gameDir: String?): ResolvedEngineSettings {
        val app = context.applicationContext
        val override = PerGameSettingsStore.load(app, game.uri)

        fun str(key: String): String? = if (override.has(key)) override.optString(key) else null
        fun bool(key: String): Boolean? = if (override.has(key)) override.optBoolean(key) else null

        val removableStorage = gameDir?.let { GamePathUtils.isRemovableStoragePath(it) } == true
        val onsGlobal = EngineSettingsStore.loadOns(app)
        val onsOverride = PerGameSettingsStore.toOnsOverride(override.optJSONObject(PerGameSettingsStore.ONS_KEY))

        return ResolvedEngineSettings(
            ons = EffectiveEngineSettings.mergeOns(onsGlobal, onsOverride),
            krGraphics = GraphicsSettingsCodec.resolve(
                EngineSettingsStore.getGraphicsSettings(app, GraphicsFamily.KR),
                GraphicsSettingsCodec.readOverride(override, GraphicsFamily.KR),
            ),
            artGraphics = GraphicsSettingsCodec.resolve(
                EngineSettingsStore.getGraphicsSettings(app, GraphicsFamily.ARTEMIS),
                GraphicsSettingsCodec.readOverride(override, GraphicsFamily.ARTEMIS),
            ),
            siglusGraphics = GraphicsSettingsCodec.resolve(
                EngineSettingsStore.getGraphicsSettings(app, GraphicsFamily.SIGLUS),
                GraphicsSettingsCodec.readOverride(override, GraphicsFamily.SIGLUS),
            ),
            krEngineVersion = EffectiveEngineSettings.resolveKrVersion(
                str(PerGameSettingsStore.F_ENGINE_VERSION),
                EngineSettingsStore.getKrEngineVersion(app),
            ),
            krKernel = EffectiveEngineSettings.resolveKrKernel(
                str(PerGameSettingsStore.F_ENGINE_KERNEL),
                EngineSettingsStore.getKrKernel(app),
                removableStorage,
            ),
            krScopedSaveDir = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_SCOPED_SAVE_DIR),
                EngineSettingsStore.isKrScopedSaveDir(app),
            ),
            krDefaultFont = EffectiveEngineSettings.resolve(
                str(PerGameSettingsStore.F_DEFAULT_FONT),
                EngineSettingsStore.getKrDefaultFont(app),
            ),
            krForceDefaultFont = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_FORCE_DEFAULT_FONT),
                EngineSettingsStore.isKrForceDefaultFont(app),
            ),
            krPatchOverlayMode = EngineSettingsStore.normalizeKrPatchOverlayMode(
                EffectiveEngineSettings.resolve(
                    str(PerGameSettingsStore.F_PATCH_OVERLAY_MODE),
                    EngineSettingsStore.getKrPatchOverlayMode(app),
                ),
            ),
            krSkipStartupDialogs = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_SKIP_STARTUP_DIALOGS),
                EngineSettingsStore.isKrSkipStartupDialogs(app),
            ),
            krRenderer = EffectiveEngineSettings.resolve(
                str(PerGameSettingsStore.F_RENDERER),
                EngineSettingsStore.getKrRenderer(app),
            ),
            artVersion = EffectiveEngineSettings.resolveArtVersion(
                str(PerGameSettingsStore.F_ART_VERSION), EngineSettingsStore.getArtEngineVersion(app),
            ),
            artKernel = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ART_KERNEL),
                EngineSettingsStore.getArtKernel(app),
                EngineSettingsStore.ART_KERNELS,
                EngineSettingsStore.ART_KERNEL_OFFICIAL,
            ),
            artRotate = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_ART_ROTATE),
                EngineSettingsStore.isArtRotateScreen(app),
            ),
            artAutoPatch = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ART_PATCH),
                EngineSettingsStore.getArtAutoPatch(app),
                EngineSettingsStore.ART_PATCHES,
                EngineSettingsStore.ART_PATCH_DEFAULT,
            ),
            artResolution = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ART_RESOLUTION),
                EngineSettingsStore.getArtResolution(app),
                EngineSettingsStore.ART_RESOLUTIONS,
                EngineSettingsStore.ART_CACHE_DEFAULT,
            ),
            artSideCut = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ART_SIDE_CUT),
                EngineSettingsStore.getArtSideCut(app),
                EngineSettingsStore.ART_TOGGLES,
                EngineSettingsStore.ART_TOGGLE_DEFAULT,
            ),
            artSurfaceCacheSize = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ART_SURFACE_CACHE_SIZE),
                EngineSettingsStore.getArtSurfaceCacheSize(app),
                EngineSettingsStore.ART_SURFACE_CACHES,
                EngineSettingsStore.ART_CACHE_DEFAULT,
            ),
            artFontCacheSize = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ART_FONT_CACHE_SIZE),
                EngineSettingsStore.getArtFontCacheSize(app),
                EngineSettingsStore.ART_FONT_CACHES,
                EngineSettingsStore.ART_CACHE_DEFAULT,
            ),
            artPowerSaving = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ART_POWER_SAVING),
                EngineSettingsStore.getArtPowerSaving(app),
                EngineSettingsStore.ART_TOGGLES,
                EngineSettingsStore.ART_TOGGLE_DEFAULT,
            ),
            webShellPort = EffectiveEngineSettings.resolveWebShellPort(
                str(PerGameSettingsStore.F_WEB_SHELL_PORT),
                EngineSettingsStore.getWebShellPort(app),
            ),
            webScopedSaveDir = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_TY_SCOPED),
                EngineSettingsStore.isTyranoScopedSaveDir(app),
            ),
            rpgMakerModEnabled = EffectiveEngineSettings.resolveRpgMakerModEnabled(
                game.engine,
                bool(PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED),
                EngineSettingsStore.isRpgMakerModEnabled(app),
            ),
            rpgLegacyRenderer = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_RPG_LEGACY_RENDERER),
                EngineSettingsStore.isRpgLegacyRenderer(app),
            ),
            rpgSaveInterop = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_RPG_SAVE_INTEROP),
                EngineSettingsStore.isRpgSaveInterop(app),
            ),
            rpgMvVersion = EffectiveEngineSettings.resolveRpgVersion(
                str(PerGameSettingsStore.F_RPG_MV_VERSION),
                EngineSettingsStore.getRpgMvEngineVersion(app),
                RPG_MV_VERSIONS,
                EngineSettingsStore.RPG_MV_V0,
            ),
            rpgMzVersion = EffectiveEngineSettings.resolveRpgVersion(
                str(PerGameSettingsStore.F_RPG_MZ_VERSION),
                EngineSettingsStore.getRpgMzEngineVersion(app),
                RPG_MZ_VERSIONS,
                EngineSettingsStore.RPG_MZ_V0,
            ),
            renpyVersion = EffectiveEngineSettings.resolve(
                str(PerGameSettingsStore.F_RENPY_VERSION),
                EngineSettingsStore.getRenpyVersion(app),
            ),
            ppssppVersion = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_PPSSPP_VERSION),
                EngineSettingsStore.getPpssppVersion(app),
                EngineSettingsStore.PPSSPP_VERSIONS,
                EngineSettingsStore.PPSSPP_VERSION_STANDARD,
            ),
            siglusLanguage = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_SIGLUS_LANGUAGE),
                EngineSettingsStore.getSiglusLanguage(app),
                EngineSettingsStore.SIGLUS_LANGUAGES,
                EngineSettingsStore.SIGLUS_LANGUAGE_AUTO,
            ),
            fbNls = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_FB_NLS),
                EngineSettingsStore.getFbNls(app),
                EngineSettingsStore.FB_NLS_VALUES,
                EngineSettingsStore.FB_NLS_AUTO,
            ),
            fvpNls = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_FVP_NLS),
                EngineSettingsStore.getFvpNls(app),
                EngineSettingsStore.FVP_NLS_VALUES,
                EngineSettingsStore.FVP_NLS_SJIS,
            ),
            ethornellNls = EffectiveEngineSettings.resolveEthornellNls(
                str(PerGameSettingsStore.F_ETHORNELL_NLS),
                EngineSettingsStore.getEthornellNls(app),
            ),
            ethornellFont = EffectiveEngineSettings.resolve(
                str(PerGameSettingsStore.F_ETHORNELL_FONT),
                EngineSettingsStore.getEthornellFont(app),
            ),
            fvpSystemFont = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_FVP_SYSTEM_FONT),
                EngineSettingsStore.isFvpSystemFont(app),
            ),
            fvpTextHidpi = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_FVP_TEXT_HIDPI),
                EngineSettingsStore.isFvpTextHidpi(app),
            ),
            fvpFont = EffectiveEngineSettings.resolve(
                str(PerGameSettingsStore.F_FVP_FONT),
                EngineSettingsStore.getFvpFont(app),
            ),
            xs35ScopedSaveDir = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_XSYS35_SCOPED_SAVE_DIR),
                EngineSettingsStore.isXs35ScopedSaveDir(app),
            ),
            xs35Renderer = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_XSYS35_RENDERER),
                EngineSettingsStore.getXs35Renderer(app),
                EngineSettingsStore.XSYS35_RENDERER_VALUES,
                EngineSettingsStore.XSYS35_RENDERER_AUTO,
            ),
            xs35IntegerScale = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_XSYS35_INTEGER_SCALE),
                EngineSettingsStore.isXs35IntegerScale(app),
            ),
            xs35MuteUnfocus = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_XSYS35_MUTE_UNFOCUS),
                EngineSettingsStore.isXs35MuteUnfocus(app),
            ),
            xs4ScopedSaveDir = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_XSYS4_SCOPED_SAVE_DIR),
                EngineSettingsStore.isXs4ScopedSaveDir(app),
            ),
            xs4Encoding = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_XSYS4_ENCODING),
                EngineSettingsStore.getXs4Encoding(app),
                EngineSettingsStore.XSYS4_ENCODING_VALUES,
                EngineSettingsStore.XSYS4_ENCODING_SJIS,
            ),
            xs4FontGothicPath = EffectiveEngineSettings.resolve(
                str(PerGameSettingsStore.F_XSYS4_FONT_GOTHIC_PATH),
                EngineSettingsStore.getXs4FontGothicPath(app),
            ),
            xs4FontMinchoPath = EffectiveEngineSettings.resolve(
                str(PerGameSettingsStore.F_XSYS4_FONT_MINCHO_PATH),
                EngineSettingsStore.getXs4FontMinchoPath(app),
            ),
            xs4Joypad = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_XSYS4_JOYPAD),
                EngineSettingsStore.getXs4Joypad(app),
                EngineSettingsStore.XSYS4_JOYPAD_VALUES,
                EngineSettingsStore.XSYS4_JOYPAD_AUTO,
            ),
            xs4MsgskipDelay = EffectiveEngineSettings.resolveXs4MsgskipDelay(
                str(PerGameSettingsStore.F_XSYS4_MSGSKIP_DELAY),
                EngineSettingsStore.getXs4MsgskipDelay(app),
            ),
            rpg = EffectiveEngineSettings.mergeRpgMaker(
                EngineSettingsStore.loadRpgMaker(app),
                PerGameSettingsStore.toRpgMakerOverride(override),
            ),
            renpy = EffectiveEngineSettings.mergeRenPy(
                EngineSettingsStore.loadRenPy(app),
                PerGameSettingsStore.toRenPyOverride(override),
            ),
            ruffle = EffectiveEngineSettings.mergeRuffle(
                EngineSettingsStore.loadRuffle(app),
                PerGameSettingsStore.toRuffleOverride(override),
            ),
            winlator = run {
                val global = EngineSettingsStore.loadWinlator(app)
                // 模式推断复用本函数已加载的覆盖 JSON，避免 getWinlatorMode 再走一次阻塞读。
                resolveWinlatorSettings(global, override)
            },
        )
    }

    private val RPG_MV_VERSIONS = setOf(
        EngineSettingsStore.RPG_MV_V0,
        EngineSettingsStore.RPG_MV_V1,
        EngineSettingsStore.RPG_MV_V2,
    )
    private val RPG_MZ_VERSIONS = setOf(
        EngineSettingsStore.RPG_MZ_V0,
        EngineSettingsStore.RPG_MZ_V1,
        EngineSettingsStore.RPG_MZ_V2,
    )
}

/** 三级合并后的启动期生效设置（纯数据）。 */
data class ResolvedEngineSettings(
    val ons: EngineSettingsStore.Ons,
    val krGraphics: GraphicsSettings = GraphicsSettings.defaults(GraphicsFamily.KR),
    val artGraphics: GraphicsSettings = GraphicsSettings.defaults(GraphicsFamily.ARTEMIS),
    val siglusGraphics: GraphicsSettings = GraphicsSettings.defaults(GraphicsFamily.SIGLUS),
    val krEngineVersion: String,
    val krKernel: String,
    val krScopedSaveDir: Boolean,
    val krDefaultFont: String,
    val krForceDefaultFont: Boolean,
    val krPatchOverlayMode: String,
    val krSkipStartupDialogs: Boolean,
    val krRenderer: String,
    val artVersion: String,
    /** Artemis 内核生效值（官方多 revision / 自研 clean-room）。 */
    val artKernel: String,
    val artRotate: Boolean,
    val artAutoPatch: String,
    val artResolution: String,
    val artSideCut: String,
    val artSurfaceCacheSize: String,
    val artFontCacheSize: String,
    val artPowerSaving: String,
    /** Web 壳（Tyrano / WebOther / VN）本地 HTTP 服务端口（固定端口保证 WebView 存档域稳定）。 */
    val webShellPort: Int,
    val webScopedSaveDir: Boolean,
    val rpgMakerModEnabled: Boolean,
    val rpgLegacyRenderer: Boolean,
    /** MV/MZ 存档互通生效值（单游戏覆盖 > 全局）。合并值不区分引擎，消费方须以引擎类型门控。 */
    val rpgSaveInterop: Boolean,
    val rpgMvVersion: String,
    val rpgMzVersion: String,
    val renpyVersion: String,
    /** PPSSPP 外置模拟器生效版本（standard / gold；PSP 跳转按此选择包名）。 */
    val ppssppVersion: String,
    /** Siglus 生效语言（auto 表示不设置 SIGLUS_LANGUAGE，保持引擎默认 JP）。 */
    val siglusLanguage: String,
    /** framebuffer 引擎生效文本编码（auto/sjis/gbk/big5/utf8/korean；创建引擎时固定）。 */
    val fbNls: String,
    /** FVP 生效文本编码（sjis/gbk/utf8；创建引擎时固定，改动需重启本局）。 */
    val fvpNls: String,
    /** Graphics text only, sjis/gbk; resource names and UI locale are independent. */
    val ethornellNls: String,
    /** Empty = engine fallback; nonempty paths must pass the private-font launch preflight. */
    val ethornellFont: String,
    /** FVP 生效系统字体回退开关。 */
    val fvpSystemFont: Boolean,
    /** FVP 生效文本高分辨率渲染开关。 */
    val fvpTextHidpi: Boolean,
    /** FVP 生效自定义字体路径（空 = 跟随游戏默认；非空 = App 私有字体绝对路径，强制生效）。 */
    val fvpFont: String,
    /** System35 生效「独立存档」开关（默认开；目录 = `<外部存储>/save/<safeSaveName>`）。 */
    val xs35ScopedSaveDir: Boolean,
    /** System35 生效渲染后端（auto/opengl/software；auto 不传 `-renderer`）。 */
    val xs35Renderer: String,
    /** System35 生效整数倍缩放开关（`-integerscale`）。 */
    val xs35IntegerScale: Boolean,
    /** System35 生效失焦静音开关（`-mute_on_unfocus`）。 */
    val xs35MuteUnfocus: Boolean,
    /** System4 生效「独立存档」开关（默认开；目录 = `<外部存储>/save/<safeSaveName>`，关闭 = 游戏根 `SaveFolder`）。 */
    val xs4ScopedSaveDir: Boolean,
    /** System4 生效文本编码（sjis / gbk；映射为 `--encoding`）。 */
    val xs4Encoding: String,
    /** System4 生效自定义 gothic 字体绝对路径（空 = 用 engine 侧内置映射；非空仍需启动前校验存在）。 */
    val xs4FontGothicPath: String,
    /** System4 生效自定义 mincho 字体绝对路径（语义同 [xs4FontGothicPath]）。 */
    val xs4FontMinchoPath: String,
    /** System4 生效手柄（auto 不下发；on / off 映射为 `--joypad`）。 */
    val xs4Joypad: String,
    /** System4 生效跳字延迟毫秒（0 = 引擎默认，不下发）。 */
    val xs4MsgskipDelay: Int,
    /** RPG Maker RGSS 外置模块生效配置（三级合并结果，启动时经 settings extra 下发）。 */
    val rpg: EngineSettingsStore.RpgMaker,
    /** Ren'Py 外置模块生效配置（三级合并结果，启动时经 settings extra 下发）。 */
    val renpy: EngineSettingsStore.RenPy,
    /** Ruffle 外置模块生效配置（三级合并结果，启动时经 settings extra 下发）。 */
    val ruffle: EngineSettingsStore.Ruffle,
    /** Winlator 外置启动下发参数（三级合并结果；跳转 YU-RIS / CatSystem2 / PC 时消费）。 */
    val winlator: EngineSettingsStore.Winlator,
)
