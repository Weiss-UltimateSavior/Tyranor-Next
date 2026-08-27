package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType

/** 外置 APK 引擎模块单一注册表。 */
object ExternalEngineModuleRegistry {
    val modules: List<ExternalEngineModule> = listOf(
        RenPyExternalEngineModule,
    )

    fun moduleForEngine(engine: EngineType): ExternalEngineModule? =
        modules.firstOrNull { it.engine == engine }

    fun moduleForAlias(alias: String?): ExternalEngineModule? =
        modules.firstOrNull { it.supportsAlias(alias) }

    fun isExternalEngine(engine: EngineType): Boolean = moduleForEngine(engine) != null
}
