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
}

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
