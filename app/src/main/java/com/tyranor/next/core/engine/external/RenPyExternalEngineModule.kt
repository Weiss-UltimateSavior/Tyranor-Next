package com.tyranor.next.core.engine.external

import android.content.Intent
import com.tyranor.next.core.engine.EngineType

/** JoiPlay Ren'Py Runtime 外置 APK 模块协议。 */
object RenPyExternalEngineModule : ExternalEngineModule {
    override val id: String = "renpy"
    override val engine: EngineType = EngineType.RENPY
    override val displayName: String = "RenPy 模块"
    override val packageName: String = "cyou.joiplay.runtime.renpy.v8d4d1"
    override val action: String = "cyou.joiplay.runtime.renpy.run"
    override val defaultAlias: String = "internal.renpy"
    override val supportedAliases: Set<String> = setOf(
        "external.renpy",
        "internal.renpy8",
    )
    override val installUrl: String =
        "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RenPy-Plugin.apk"

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

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append('"')
        append(escapeJson(name))
        append("\":\"")
        append(escapeJson(value))
        append('"')
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
}
