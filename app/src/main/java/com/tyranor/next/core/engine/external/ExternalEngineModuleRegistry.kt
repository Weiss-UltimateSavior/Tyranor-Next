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

    /** 按 Ren'Py 版本（EngineSettingsStore 的 RENPY_* 常量）查找模块；auto/未知返回 null，由调用方回退默认。 */
    fun moduleForRenpyVersion(version: String?): ExternalEngineModule? =
        when (version?.trim()?.lowercase(Locale.ROOT)) {
            EngineSettingsStore.RENPY_85 -> RenPyExternalEngineModule
            EngineSettingsStore.RENPY_803 -> RenPy80ExternalEngineModule
            EngineSettingsStore.RENPY_77 -> RenPy77ExternalEngineModule
            else -> null
        }

    fun moduleForAlias(alias: String?): ExternalEngineModule? =
        modules.firstOrNull { it.supportsAlias(alias) }

    fun isExternalEngine(engine: EngineType): Boolean = moduleForEngine(engine) != null
}
