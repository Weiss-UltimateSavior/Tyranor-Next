package com.tyranor.next.core.engine.external

import android.content.Context
import android.content.Intent
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject

/**
 * Ren'Py 外置 APK 引擎模块家族（issue #52）。
 *
 * - Ren'Py 8.5.3 / 7.7.1 走外置 runtime 模块协议（`cyou.joiplay.runtime.renpy.run`），
 *   两者仅包名不同，共享 [RenPyRuntimeModule] 的 intent 构造。
 * 启动协议集中在模块文件内，UI / 扫描器不散落 package/action 字符串。
 */

/** Ren'Py Runtime 外置模块通用协议（runtime.run）。 */
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

    override fun prepareForLaunch(
        context: Context,
        request: ExternalEngineLaunchRequest,
    ): ExternalEngineLaunchResult? {
        val settings = request.resolvedSettings?.renpy ?: EngineSettingsStore.RenPy()
        val folder = request.gameDirectoryPath.trimEnd('/')
        if (folder.isNotBlank()) {
            // 插件 configuration.json 同键优先于 intent，存在时必须同步为 App 生效值（非致命）
            runCatching {
                RenPyRuntimeEnvironment.syncConfigurationFile(
                    RenPyRuntimeEnvironment.configFileFor(folder, gameIdFor(folder)),
                    settings,
                )
            }
        }
        return null
    }

    override fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent =
        Intent(action).setPackage(packageName).apply {
            putExtra(ExternalEngineContract.GAME, buildGameJson(request))
            putExtra(
                ExternalEngineContract.SETTINGS,
                buildSettingsJson(request.resolvedSettings?.renpy),
            )
            putExtra(ExternalEngineContract.ORIENTATION, 6)
            putExtra(ExternalEngineContract.ROOT_URI, request.game.uri)
            putExtra(ExternalEngineContract.LAUNCH_TARGET, request.launchTarget)
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
            appendJsonField("id", gameIdFor(folder))
            append(',')
            appendJsonField("folder", folder)
            append(',')
            appendJsonField("execFile", "")
            append(',')
            appendJsonField("type", "renpy")
            append('}')
        }
    }

    /**
     * 构造 JoiPlay 协议的嵌套 settings JSON（插件 `RenPyConfigurationParser.parse`）：
     * - `app.cheats`：插件只从 app 节读金手指；
     * - `renpy.*`：8 项运行时开关，值带 `{"boolean": ...}` 包装。
     *
     * [settings] 为三级合并结果；为空时按默认模型全量下发，避免插件缺键走自身默认。
     */
    internal fun buildSettingsJson(settings: EngineSettingsStore.RenPy? = null): String {
        val s = settings ?: EngineSettingsStore.RenPy()
        val app = JSONObject()
            .put("cheats", JSONObject().put("boolean", s.cheats))
        val renpy = JSONObject()
            .put("renpy_hw_video", JSONObject().put("boolean", s.hwVideo))
            .put("renpy_autosave", JSONObject().put("boolean", s.autosave))
            .put("renpy_phonesmallvariant", JSONObject().put("boolean", s.phoneSmallVariant))
            .put("renpy_vsync", JSONObject().put("boolean", s.vsync))
            .put("renpy_less_memory", JSONObject().put("boolean", s.lessMemory))
            .put("renpy_less_updates", JSONObject().put("boolean", s.lessUpdates))
            .put("renpy_dont_use_gl2", JSONObject().put("boolean", s.dontUseGl2))
            .put("renpy_recompile", JSONObject().put("boolean", s.recompile))
        return JSONObject().put("app", app).put("renpy", renpy).toString()
    }

    internal fun gameIdFor(folder: String): String = Integer.toHexString(folder.hashCode())
}

/** Ren'Py 8.5.3 runtime 模块（默认版本，兼容旧 8.5 选择值）。 */
object RenPyExternalEngineModule : RenPyRuntimeModule(
    id = "renpy85",
    displayName = "Ren'Py 8.5.3",
    packageName = "cyou.joiplay.runtime.renpy.v8d4d1",
    installUrl = "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RenPyPlugin-8.5.3-1.02.00.apk",
    defaultAlias = "internal.renpy",
    supportedAliases = setOf("external.renpy", "internal.renpy8"),
) {
    override val minimumVersionCode: Long = 10200L
}

/** Ren'Py 7.7.1 runtime 模块（与 8.5 同协议，仅包名不同）。 */
object RenPy77ExternalEngineModule : RenPyRuntimeModule(
    id = "renpy77",
    displayName = "Ren'Py 7.7.1",
    packageName = "cyou.joiplay.runtime.renpy.v7d7d1",
    installUrl = "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RenPy-Plugin-7.7.1.apk",
    defaultAlias = "internal.renpy7",
    supportedAliases = setOf("external.renpy7", "internal.renpy77"),
)
