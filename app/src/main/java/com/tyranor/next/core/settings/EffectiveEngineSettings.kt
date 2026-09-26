package com.tyranor.next.core.settings

import com.tyranor.next.core.engine.EngineType

/**
 * 三级设置合并的纯函数层（P0-3）：应用级全局值 + 单游戏覆盖值 → 启动期生效值。
 *
 * 覆盖语义统一为「null = 跟随全局」；本对象不依赖 Android / Context，可直接 JVM 单测。
 * Context 读取由 [EngineSettingsResolver] 完成后传入。
 */
object EffectiveEngineSettings {

    /** 通用三级合并：单游戏覆盖优先，null 跟随全局。 */
    fun <T : Any> resolve(override: T?, global: T): T = override ?: global

    /** 布尔三级合并：单游戏覆盖优先，null 跟随全局。 */
    fun resolveBool(override: Boolean?, global: Boolean): Boolean = override ?: global

    /**
     * 白名单字符串三级合并：覆盖值非法/缺失时回退全局，全局值也非法时回退 [fallback]。
     * 用于 Artemis 版本/补丁策略、RPG Maker 子版本等有取值域的设置项。
     */
    fun resolveAllowed(
        override: String?,
        global: String,
        allowed: Set<String>,
        fallback: String,
    ): String {
        val candidate = override?.trim()?.takeIf { it in allowed } ?: global.trim()
        return candidate.takeIf { it in allowed } ?: fallback
    }

    /**
     * KR 内核三级合并：可移动存储不支持 krkrsdl3，命中时强制回退 kirikiri2
     * （与 EngineLauncher/GameSaveManager 原行为一致）。
     */
    fun resolveKrKernel(override: String?, global: String, removableStorage: Boolean): String {
        val requested = resolve(override, global)
        return if (removableStorage && requested == EngineSettingsStore.KERNEL_KRKRSDL3) {
            EngineSettingsStore.KERNEL_KIRIKIRI2
        } else {
            requested
        }
    }

    /** RPG Maker 修改器开关：仅 MV/MZ 生效，单游戏覆盖优先。 */
    fun resolveRpgMakerModEnabled(
        engine: EngineType,
        override: Boolean?,
        global: Boolean,
    ): Boolean = engine in setOf(EngineType.RPG_MV, EngineType.RPG_MZ) && resolveBool(override, global)

    /**
     * RPG Maker MV/MZ 子版本三级合并：覆盖值经白名单校验（非法回退全局），
     * 全局值同样校验后回退 [fallback]。
     */
    fun resolveRpgVersion(
        override: String?,
        global: String,
        allowed: Set<String>,
        fallback: String,
    ): String {
        val normalized = override?.trim()?.lowercase()?.takeIf { it in allowed }
        if (normalized != null) return normalized
        return global.trim().lowercase().takeIf { it in allowed } ?: fallback
    }

    /**
     * ONS 全局设置 + 单游戏覆盖合并：字段级「覆盖 ?: 全局」，编码统一归一。
     */
    fun mergeOns(global: EngineSettingsStore.Ons, override: OnsOverride?): EngineSettingsStore.Ons {
        if (override == null) return global
        return global.copy(
            scopedSaveDir = resolveBool(override.scopedSaveDir, global.scopedSaveDir),
            stretchFull = resolveBool(override.stretchFull, global.stretchFull),
            ignoreCutout = resolveBool(override.ignoreCutout, global.ignoreCutout),
            disableVideo = resolveBool(override.disableVideo, global.disableVideo),
            sharpness = resolveBool(override.sharpness, global.sharpness),
            sharpnessValue = resolve(override.sharpnessValue, global.sharpnessValue),
            encoding = override.encoding?.let { EngineSettingsStore.normalizeEncoding(it) } ?: global.encoding,
        )
    }

    /**
     * RPG Maker RGSS 外置模块全局设置 + 单游戏覆盖合并：
     * 布尔字段「覆盖 ?: 全局」；字符串字段经白名单校验，非法覆盖回退全局。
     * useRuby18 不做强制改写，由 RpgMakerExternalEngineModule 按 XP 子类型兜底（默认 true）。
     */
    fun mergeRpgMaker(
        global: EngineSettingsStore.RpgMaker,
        override: RpgMakerOverride?,
    ): EngineSettingsStore.RpgMaker {
        if (override == null) return global
        return global.copy(
            useRuby18 = resolveBool(override.useRuby18, global.useRuby18),
            debug = resolveBool(override.debug, global.debug),
            smoothScaling = resolveBool(override.smoothScaling, global.smoothScaling),
            vsync = resolveBool(override.vsync, global.vsync),
            frameSkip = resolveBool(override.frameSkip, global.frameSkip),
            solidFonts = resolveBool(override.solidFonts, global.solidFonts),
            pathCache = resolveBool(override.pathCache, global.pathCache),
            prebuiltPathCache = resolveBool(override.prebuiltPathCache, global.prebuiltPathCache),
            fastPathEnum = resolveBool(override.fastPathEnum, global.fastPathEnum),
            copyText = resolveBool(override.copyText, global.copyText),
            cheats = resolveBool(override.cheats, global.cheats),
            useCJKFont = resolveBool(override.useCJKFont, global.useCJKFont),
            enablePostloadScripts = resolveBool(override.enablePostloadScripts, global.enablePostloadScripts),
            customFont = resolve(override.customFont, global.customFont),
            verticalScreenAlign = resolveAllowed(
                override.verticalScreenAlign,
                global.verticalScreenAlign,
                EngineSettingsStore.RPG_VERTICAL_ALIGNS,
                EngineSettingsStore.RPG_VERTICAL_ALIGN_DEFAULT,
            ),
            windowSize = resolveAllowed(
                override.windowSize,
                global.windowSize,
                EngineSettingsStore.RPG_WINDOW_SIZES,
                EngineSettingsStore.RPG_WINDOW_SIZE_DEFAULT,
            ),
            speedUp = resolveAllowed(
                override.speedUp,
                global.speedUp,
                EngineSettingsStore.RPG_SPEED_UPS,
                EngineSettingsStore.RPG_SPEED_UP_DEFAULT,
            ),
            fontScale = resolveAllowed(
                override.fontScale,
                global.fontScale,
                EngineSettingsStore.RPG_FONT_SCALES,
                EngineSettingsStore.RPG_FONT_SCALE_DEFAULT,
            ),
        )
    }

    /**
     * Ren'Py 外置模块全局设置 + 单游戏覆盖合并：布尔字段「覆盖 ?: 全局」。
     * 版本选择链路（renpyVersion/detectedRenpyVersion）不在此模型内。
     */
    fun mergeRenPy(
        global: EngineSettingsStore.RenPy,
        override: RenPyOverride?,
    ): EngineSettingsStore.RenPy {
        if (override == null) return global
        return global.copy(
            cheats = resolveBool(override.cheats, global.cheats),
            hwVideo = resolveBool(override.hwVideo, global.hwVideo),
            autosave = resolveBool(override.autosave, global.autosave),
            phoneSmallVariant = resolveBool(override.phoneSmallVariant, global.phoneSmallVariant),
            vsync = resolveBool(override.vsync, global.vsync),
            lessMemory = resolveBool(override.lessMemory, global.lessMemory),
            lessUpdates = resolveBool(override.lessUpdates, global.lessUpdates),
            dontUseGl2 = resolveBool(override.dontUseGl2, global.dontUseGl2),
            recompile = resolveBool(override.recompile, global.recompile),
        )
    }

    /**
     * Web 壳端口三级合并：单游戏覆盖值合法（1..65535）时优先，否则回退全局生效值。
     */
    fun resolveWebShellPort(override: String?, global: Int): Int =
        override?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: global

    /**
     * Winlator 外置启动全局设置 + 单游戏覆盖合并。
     *
     * 覆盖字段带值即生效（""=显式不下发该参数，跟随容器配置）；字符串字段仍走各自的
     * 白名单/正则归一，非法覆盖值归一回空串而不是继承全局，避免全局与覆盖混搭出非法参数。
     */
    fun mergeWinlator(
        global: EngineSettingsStore.Winlator,
        override: WinlatorOverride?,
    ): EngineSettingsStore.Winlator {
        if (override == null) return global
        return global.copy(
            containerId = override.containerId?.let {
                it.trim().filter(Char::isDigit).toIntOrNull()?.coerceAtLeast(0) ?: 0
            } ?: global.containerId,
            containerName = override.containerName?.trim() ?: global.containerName,
            graphicsDriver = override.graphicsDriver?.let {
                EngineSettingsStore.normalizeWinlatorGraphicsDriver(it)
            } ?: global.graphicsDriver,
            dxwrapper = override.dxwrapper?.let {
                EngineSettingsStore.normalizeWinlatorDxWrapper(it)
            } ?: global.dxwrapper,
            screenSize = override.screenSize?.let {
                EngineSettingsStore.normalizeWinlatorScreenSize(it)
            } ?: global.screenSize,
            lcAll = override.lcAll?.let { EngineSettingsStore.normalizeWinlatorLcAll(it) } ?: global.lcAll,
            tz = override.tz?.let { EngineSettingsStore.normalizeWinlatorTz(it) } ?: global.tz,
            box64Preset = override.box64Preset?.let {
                EngineSettingsStore.normalizeWinlatorBox64Preset(it)
            } ?: global.box64Preset,
            save = resolveBool(override.save, global.save),
        )
    }
}

/** RPG Maker RGSS 外置模块单游戏覆盖字段（null = 跟随全局）。 */
data class RpgMakerOverride(
    val useRuby18: Boolean? = null,
    val debug: Boolean? = null,
    val smoothScaling: Boolean? = null,
    val vsync: Boolean? = null,
    val frameSkip: Boolean? = null,
    val solidFonts: Boolean? = null,
    val pathCache: Boolean? = null,
    val prebuiltPathCache: Boolean? = null,
    val fastPathEnum: Boolean? = null,
    val copyText: Boolean? = null,
    val cheats: Boolean? = null,
    val useCJKFont: Boolean? = null,
    val enablePostloadScripts: Boolean? = null,
    val customFont: String? = null,
    val verticalScreenAlign: String? = null,
    val windowSize: String? = null,
    val speedUp: String? = null,
    val fontScale: String? = null,
)

/** Ren'Py 外置模块单游戏覆盖字段（null = 跟随全局）。 */
data class RenPyOverride(
    val cheats: Boolean? = null,
    val hwVideo: Boolean? = null,
    val autosave: Boolean? = null,
    val phoneSmallVariant: Boolean? = null,
    val vsync: Boolean? = null,
    val lessMemory: Boolean? = null,
    val lessUpdates: Boolean? = null,
    val dontUseGl2: Boolean? = null,
    val recompile: Boolean? = null,
)

/** ONS 单游戏覆盖字段（null = 跟随全局）。 */
data class OnsOverride(
    val scopedSaveDir: Boolean? = null,
    val stretchFull: Boolean? = null,
    val ignoreCutout: Boolean? = null,
    val disableVideo: Boolean? = null,
    val sharpness: Boolean? = null,
    val sharpnessValue: String? = null,
    val encoding: String? = null,
)

/** Winlator 外置启动单游戏覆盖字段（null = 跟随全局；非 null 的 "" = 显式不下发该参数）。 */
data class WinlatorOverride(
    val containerId: String? = null,
    val containerName: String? = null,
    val graphicsDriver: String? = null,
    val dxwrapper: String? = null,
    val screenSize: String? = null,
    val lcAll: String? = null,
    val tz: String? = null,
    val box64Preset: String? = null,
    val save: Boolean? = null,
)
