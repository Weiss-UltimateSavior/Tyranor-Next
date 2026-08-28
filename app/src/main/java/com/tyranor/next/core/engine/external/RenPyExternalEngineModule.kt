package com.tyranor.next.core.engine.external

import android.content.Intent
import com.tyranor.next.core.engine.EngineType

/**
 * Ren'Py 外置 APK 引擎模块家族（issue #52）。
 *
 * - Ren'Py 8.5 / 7.7.1 走 JoiPlay runtime 模块协议（`cyou.joiplay.runtime.renpy.run`），
 *   两者仅包名不同，共享 [RenPyRuntimeModule] 的 intent 构造。
 * - Ren'Py 8.0.3 是独立插件（`cyou.joiplay.renpy`），Manifest 无 runtime.run action，
 *   只能通过 MAIN launcher 拉起插件主界面，由用户在插件内选择游戏（兼容方法）。
 *
 * 启动协议集中在模块文件内，UI / 扫描器不散落 package/action 字符串。
 */

/** JoiPlay Ren'Py Runtime 模块通用协议（runtime.run）。 */
abstract class RenPyRuntimeModule(
    override val id: String,
    override val displayName: String,
    override val packageName: String,
    override val installUrl: String?,
    override val defaultAlias: String,
    override val supportedAliases: Set<String>,
) : ExternalEngineModule {
    override val engine: EngineType = EngineType.RENPY
    override val action: String = "cyou.joiplay.runtime.renpy.run"

    override fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent =
        Intent(action).setPackage(packageName).apply {
            putExtra("game", buildGameJson(request))
            putExtra("settings", "{}")
            putExtra("orientation", 6)
            putExtra("rootUri", request.game.uri)
            putExtra("launchTarget", request.launchTarget)
        }

    internal fun buildGameJson(request: ExternalEngineLaunchRequest): String {
        val folder = request.gameDirectoryPath.trimEnd('/')
        val title = request.game.title.ifBlank {
            folder.substringAfterLast('/', missingDelimiterValue = "Ren'Py Game")
        }
        return buildString {
            append('{')
            appendJsonField("title", title)
            append(',')
            appendJsonField("id", Integer.toHexString(folder.hashCode()))
            append(',')
            appendJsonField("folder", folder)
            append(',')
            appendJsonField("execFile", "")
            append(',')
            appendJsonField("type", "renpy")
            append('}')
        }
    }
}

/** Ren'Py 8.5 runtime 模块（默认版本）。 */
object RenPyExternalEngineModule : RenPyRuntimeModule(
    id = "renpy85",
    displayName = "Ren'Py 8.5",
    packageName = "cyou.joiplay.runtime.renpy.v8d4d1",
    installUrl = RENPY_RELEASE_BASE + "RenPy-Plugin-8.5.apk",
    defaultAlias = "internal.renpy",
    supportedAliases = setOf("external.renpy", "internal.renpy8"),
)

/** Ren'Py 7.7.1 runtime 模块（与 8.5 同协议，仅包名不同）。 */
object RenPy77ExternalEngineModule : RenPyRuntimeModule(
    id = "renpy77",
    displayName = "Ren'Py 7.7.1",
    packageName = "cyou.joiplay.runtime.renpy.v7d7d1",
    installUrl = RENPY_RELEASE_BASE + "RenPy-Plugin-7.7.1.apk",
    defaultAlias = "internal.renpy7",
    supportedAliases = setOf("external.renpy7", "internal.renpy77"),
)

/** Ren'Py 8.0.3 独立插件（无 runtime.run，仅 MAIN launcher）。 */
object RenPy80ExternalEngineModule : ExternalEngineModule {
    override val id: String = "renpy80"
    override val engine: EngineType = EngineType.RENPY
    override val displayName: String = "Ren'Py 8.0.3"
    override val packageName: String = "cyou.joiplay.renpy"
    override val action: String = Intent.ACTION_MAIN
    override val defaultAlias: String = "internal.renpy80"
    override val supportedAliases: Set<String> = setOf("external.renpy80")
    override val requiresGameDirectoryPath: Boolean = false
    override val installUrl: String? = RENPY_RELEASE_BASE + "RenPy-Plugin-8.0.3.apk"

    override fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent =
        Intent(action).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
}

/** Ren'Py 插件 release 下载地址（托管于 Tyranor-Next Releases）。 */
private const val RENPY_RELEASE_BASE =
    "https://github.com/Weiss-UltimateSavior/Tyranor-Next/releases/download/renpy-plugins/"
