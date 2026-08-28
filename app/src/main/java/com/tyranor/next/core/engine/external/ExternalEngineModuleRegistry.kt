package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import java.util.Locale

/** 外置 APK 引擎模块单一注册表。 */
object ExternalEngineModuleRegistry {
    val modules: List<ExternalEngineModule> = listOf(
        RenPyExternalEngineModule,
        RenPy80ExternalEngineModule,
        RenPy77ExternalEngineModule,
        RpgMakerExternalEngineModule,
    )

    fun moduleForEngine(engine: EngineType): ExternalEngineModule? =
        modules.firstOrNull { it.engine == engine }

    /** 解析某引擎实际生效的外置模块：Ren'Py 按版本（null/auto/未知 → 默认 8.5），其余引擎返回其唯一模块。 */
    fun resolveModule(engine: EngineType, renpyVersion: String? = null): ExternalEngineModule? {
        if (engine == EngineType.RENPY) {
            return moduleForRenpyVersion(renpyVersion) ?: moduleForEngine(engine)
        }
        return moduleForEngine(engine)
    }

    /** 按 Ren'Py 版本（EngineSettingsStore 的 RENPY_* 常量）查找模块；auto/未知返回 null，由调用方回退默认。 */
    fun moduleForRenpyVersion(version: String?): ExternalEngineModule? =
        when (version?.trim()?.lowercase(Locale.ROOT)) {
            EngineSettingsStore.RENPY_85 -> RenPyExternalEngineModule
            EngineSettingsStore.RENPY_803 -> RenPy80ExternalEngineModule
            EngineSettingsStore.RENPY_77 -> RenPy77ExternalEngineModule
            else -> null
        }

    /**
     * 按内部别名查找模块。仅用于兼容历史扫描数据与测试；当前启动不走此路径——
     * Ren'Py 按单游戏版本设置选模块，RPG Maker 直接读 ScanGame.externalModuleAlias。
     */
    fun moduleForAlias(alias: String?): ExternalEngineModule? =
        modules.firstOrNull { it.supportsAlias(alias) }

    fun isExternalEngine(engine: EngineType): Boolean = moduleForEngine(engine) != null
}
