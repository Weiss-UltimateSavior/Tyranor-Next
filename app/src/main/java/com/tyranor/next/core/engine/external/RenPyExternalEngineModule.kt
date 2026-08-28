package com.tyranor.next.core.engine.external

import android.content.Intent
import com.tyranor.next.core.engine.EngineType

/**
 * Ren'Py 外置 APK 引擎模块家族（issue #52）。
 *
 * - Ren'Py 8.5 / 7.7.1 走 JoiPlay runtime 模块协议（`cyou.joiplay.runtime.renpy.run`），
 *   两者仅包名不同，共享 [RenPyRuntimeModule] 的 intent 构造。
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
    installUrl = "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RPGM-Plugin-8.5.apk",
    defaultAlias = "internal.renpy",
    supportedAliases = setOf("external.renpy", "internal.renpy8"),
)

/** Ren'Py 7.7.1 runtime 模块（与 8.5 同协议，仅包名不同）。 */
object RenPy77ExternalEngineModule : RenPyRuntimeModule(
    id = "renpy77",
    displayName = "Ren'Py 7.7.1",
    packageName = "cyou.joiplay.runtime.renpy.v7d7d1",
    installUrl = "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/tag/test#:~:text=RenPy%2DPlugin%2D7.7.1.apk",
    defaultAlias = "internal.renpy7",
    supportedAliases = setOf("external.renpy7", "internal.renpy77"),
)
