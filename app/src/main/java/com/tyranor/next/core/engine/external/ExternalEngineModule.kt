package com.tyranor.next.core.engine.external

import android.content.Intent
import com.tyranor.next.core.engine.EngineType
import java.util.Locale

/** 外置 APK 引擎模块的静态协议描述。 */
interface ExternalEngineModule {
    val id: String
    val engine: EngineType
    val displayName: String
    val packageName: String
    val action: String
    val defaultAlias: String
    val supportedAliases: Set<String>
    val installUrl: String?

    fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent

    fun supportsAlias(alias: String?): Boolean {
        val normalized = alias?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty()) return false
        return normalized == packageName.lowercase(Locale.ROOT) ||
            normalized == defaultAlias.lowercase(Locale.ROOT) ||
            supportedAliases.any { it.equals(normalized, ignoreCase = true) }
    }
}
