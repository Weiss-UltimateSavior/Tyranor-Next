package com.tyranor.next.core.engine.external

import android.content.Intent
import android.content.Context
import androidx.annotation.StringRes
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

    @get:StringRes
    val displayNameRes: Int? get() = null

    /** 是否需要解析游戏目录真实路径。独立插件（仅拉起插件主界面）可覆写为 false。 */
    val requiresGameDirectoryPath: Boolean get() = true

    /**
     * 游戏记录指向单个入口文件而非目录（如 SWF）。
     * 启动时先由 [requiresGameDirectoryPath] 的目录解析改为解析入口文件所在目录；
     * 仅 Ruffle 等文件型外置模块覆写为 true，Ren'Py / RPG Maker 保持既有目录语义。
     */
    val launchEntryIsFile: Boolean get() = false

    fun prepareForLaunch(context: Context, request: ExternalEngineLaunchRequest): ExternalEngineLaunchResult? = null

    fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent

    fun displayName(context: Context): String =
        displayNameRes?.let { context.getString(it) } ?: displayName

    fun supportsAlias(alias: String?): Boolean {
        val normalized = alias?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty()) return false
        return normalized == packageName.lowercase(Locale.ROOT) ||
            normalized == defaultAlias.lowercase(Locale.ROOT) ||
            supportedAliases.any { it.equals(normalized, ignoreCase = true) }
    }
}
