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
            krEngineVersion = EffectiveEngineSettings.resolve(
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
            krAnime4kMode = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ANIME4K_MODE),
                EngineSettingsStore.getKrAnime4kMode(app),
                EngineSettingsStore.ANIME4K_MODES,
                EngineSettingsStore.ANIME4K_OFF,
            ),
            krRenderer = EffectiveEngineSettings.resolve(
                str(PerGameSettingsStore.F_RENDERER),
                EngineSettingsStore.getKrRenderer(app),
            ),
            artVersion = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ART_VERSION),
                EngineSettingsStore.getArtEngineVersion(app),
                EngineSettingsStore.ART_VERSIONS,
                EngineSettingsStore.ART_ENGINE_AUTO,
            ),
            artRotate = EffectiveEngineSettings.resolveBool(
                bool(PerGameSettingsStore.F_ART_ROTATE),
                EngineSettingsStore.isArtRotateScreen(app),
            ),
            artAutoPatch = EffectiveEngineSettings.resolveAllowed(
                str(PerGameSettingsStore.F_ART_PATCH),
                EngineSettingsStore.getArtAutoPatch(app),
                EngineSettingsStore.ART_PATCHES,
                EngineSettingsStore.AUTO_PATCH_ASK,
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
    val krEngineVersion: String,
    val krKernel: String,
    val krScopedSaveDir: Boolean,
    val krDefaultFont: String,
    val krForceDefaultFont: Boolean,
    val krPatchOverlayMode: String,
    val krSkipStartupDialogs: Boolean,
    val krAnime4kMode: String,
    val krRenderer: String,
    val artVersion: String,
    val artRotate: Boolean,
    val artAutoPatch: String,
    val artResolution: String,
    val artSideCut: String,
    val artSurfaceCacheSize: String,
    val artFontCacheSize: String,
    val artPowerSaving: String,
    val webScopedSaveDir: Boolean,
    val rpgMakerModEnabled: Boolean,
    val rpgLegacyRenderer: Boolean,
    val rpgMvVersion: String,
    val rpgMzVersion: String,
    val renpyVersion: String,
)
