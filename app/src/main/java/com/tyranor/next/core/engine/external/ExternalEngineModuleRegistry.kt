package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import java.util.Locale

/** 外置 APK 引擎模块单一注册表。 */
object ExternalEngineModuleRegistry {
    val modules: List<ExternalEngineModule> = listOf(
        RenPyExternalEngineModule,
        RenPy77ExternalEngineModule,
        RpgMakerExternalEngineModule,
    )

    fun moduleForEngine(engine: EngineType): ExternalEngineModule? =
        modules.firstOrNull { it.engine == engine }

    /**
     * 解析某引擎实际生效的外置模块：
     * Ren'Py 显式版本优先；auto/null/未知时使用扫描探测版本；仍未知则默认 8.5。
     */
    fun resolveModule(
        engine: EngineType,
        renpyVersion: String? = null,
        detectedRenpyVersion: String? = null,
    ): ExternalEngineModule? {
        if (engine == EngineType.RENPY) {
            return resolveRenpyModule(renpyVersion, detectedRenpyVersion)
        }
        return moduleForEngine(engine)
    }

    fun resolveRenpyModule(
        configuredVersion: String? = null,
        detectedVersion: String? = null,
    ): ExternalEngineModule =
        moduleForRenpyVersion(configuredVersion)
            ?: moduleForRenpyVersion(detectedVersion)
            ?: RenPyExternalEngineModule

    /** 按 Ren'Py 版本（EngineSettingsStore 的 RENPY_* 常量）查找模块；auto/未知返回 null，由调用方回退默认。 */
    fun moduleForRenpyVersion(version: String?): ExternalEngineModule? =
        when (version?.trim()?.lowercase(Locale.ROOT)) {
            EngineSettingsStore.RENPY_85 -> RenPyExternalEngineModule
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
