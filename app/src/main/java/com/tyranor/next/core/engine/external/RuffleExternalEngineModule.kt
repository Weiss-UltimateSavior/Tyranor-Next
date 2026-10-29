package com.tyranor.next.core.engine.external

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Ruffle 外置 APK 引擎模块（SWF / Flash）。
 *
 * 插件协议（RufflePlugin-1.02.00）：
 * - 入口 Activity 为 `cyou.joiplay.runtime.ruffle.PermissionActivity`，实际由插件内部的
 *   MainActivity 消费 extras；`cyou.joiplay.runtime.flash.run` 与
 *   `cyou.joiplay.runtime.ruffle.run` 均可，默认使用前者；
 * - `game` JSON 的 `folder` 为 SWF 所在目录，`execFile` 为文件名，`type = "flash"`；
 * - `settings` JSON 使用 `{"app":..., "ruffle":...}` 嵌套结构，值必须带 `int` / `string` 包装。
 *
 * 启动协议集中在本文件内，扫描器 / UI 不散落 package/action 字符串。
 */
object RuffleExternalEngineModule : ExternalEngineModule {
    override val id: String = "ruffle"
    override val engine: EngineType = EngineType.RUFFLE
    override val displayName: String = "Ruffle"
    override val displayNameRes: Int = R.string.external_ruffle_module_name
    override val packageName: String = "cyou.joiplay.runtime.ruffle"
    override val action: String = "cyou.joiplay.runtime.flash.run"
    override val defaultAlias: String = "internal.flash"
    override val supportedAliases: Set<String> = setOf(
        "external.flash",
        "internal.ruffle",
        "external.ruffle",
    )
    override val installUrl: String =
        "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RufflePlugin-1.02.00.apk"
    override val requiresGameDirectoryPath: Boolean = true

    /** SWF 游戏记录指向文件本身，启动前需换成其所在目录。 */
    override val launchEntryIsFile: Boolean = true

    override fun prepareForLaunch(
        context: Context,
        request: ExternalEngineLaunchRequest,
    ): ExternalEngineLaunchResult? {
        val folder = resolveGameFolder(request)
        val execFile = resolveExecFile(request, folder)
        if (folder.isBlank() || execFile.isNullOrBlank()) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.PREPARE_FAILED,
                messageRes = R.string.external_ruffle_resolve_swf_failed,
            )
        }
        val settings = request.resolvedSettings?.ruffle ?: EngineSettingsStore.Ruffle()
        // 插件读取顺序：`<gameFolder>/configuration.json`（存在时）> intent `settings`。
        // 目录级配置是共享的：多 SWF 目录或外部存储根不能按单个游戏改写它，
        // 此时保留 intent settings 作为事实源，避免同一目录的游戏互相串设置。
        val swfFileCount = File(folder).listFiles()
            ?.count { it.isFile && !it.name.startsWith(".") && it.length() > 0L && it.name.endsWith(".swf", ignoreCase = true) }
            ?: 0
        if (RuffleRuntimeEnvironment.shouldSyncConfigurationFile(folder, swfFileCount, Environment.getExternalStorageDirectory())) {
            runCatching {
                RuffleRuntimeEnvironment.syncConfigurationFile(
                    RuffleRuntimeEnvironment.configFileFor(folder, gameIdFor(folder, execFile)),
                    settings,
                )
            }
        }
        return null
    }

    override fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent {
        val execFile = resolveExecFile(request).orEmpty()
        return Intent(action).setPackage(packageName).apply {
            putExtra(ExternalEngineContract.GAME, buildGameJson(request))
            putExtra(
                ExternalEngineContract.SETTINGS,
                buildSettingsJson(request.resolvedSettings?.ruffle),
            )
            putExtra(ExternalEngineContract.ORIENTATION, 6)
            putExtra(ExternalEngineContract.ROOT_URI, request.game.uri)
            putExtra(
                ExternalEngineContract.LAUNCH_TARGET,
                execFile.ifBlank { request.launchTarget },
            )
        }
    }

    internal fun buildGameJson(request: ExternalEngineLaunchRequest): String {
        val folder = resolveGameFolder(request)
        val execFile = resolveExecFile(request, folder).orEmpty()
        val title = request.game.title.ifBlank {
            folder.substringAfterLast('/').ifBlank { "Flash Game" }
        }
        return JSONObject()
            .put("title", title)
            .put("id", gameIdFor(folder, execFile))
            .put("folder", folder)
            .put("execFile", execFile)
            .put("type", "flash")
            .toString()
    }

    /**
     * 构造 JoiPlay 协议 settings JSON：`{"app":{"screenTimeout":{"int":0}},
     * "ruffle":{<key>:{"string":value}}}`。
     *
     * [settings] 为生效设置；为空时按默认模型全量下发，避免插件缺键走自身默认。
     */
    internal fun buildSettingsJson(settings: EngineSettingsStore.Ruffle? = null): String {
        val s = settings ?: EngineSettingsStore.Ruffle()
        val app = JSONObject()
            .put(
                "screenTimeout",
                JSONObject().put("int", EngineSettingsStore.normalizeRuffleScreenTimeout(s.screenTimeout)),
            )
        val ruffle = JSONObject()
            .put("backend", JSONObject().put("string", EngineSettingsStore.normalizeRuffleBackend(s.backend)))
            .put("quality", JSONObject().put("string", EngineSettingsStore.normalizeRuffleQuality(s.quality)))
            .put("scaleMode", JSONObject().put("string", EngineSettingsStore.normalizeRuffleScaleMode(s.scaleMode)))
            .put("letterbox", JSONObject().put("string", EngineSettingsStore.normalizeRuffleLetterbox(s.letterbox)))
            .put(
                "loadBehavior",
                JSONObject().put("string", EngineSettingsStore.normalizeRuffleLoadBehavior(s.loadBehavior)),
            )
        return JSONObject().put("app", app).put("ruffle", ruffle).toString()
    }

    /**
     * 解析 SWF 所在目录：请求值为文件（或 .swf 入口）时取父目录，并去掉末尾分隔符。
     * 先判目录再判 `.swf` 后缀：名字本身以 `.swf` 结尾的目录不能被误当成入口文件。
     */
    internal fun resolveGameFolder(request: ExternalEngineLaunchRequest): String {
        val raw = request.gameDirectoryPath.trim()
        if (raw.isEmpty()) return ""
        val file = File(raw)
        if (file.isDirectory) {
            val trimmed = raw.trimEnd('/')
            return if (trimmed.isEmpty() && raw.startsWith("/")) "/" else trimmed
        }
        // 权限尚未授予时 isFile/isDirectory 可能都为 false：按 .swf 后缀仍可判定这条路径是入口文件。
        val isEntryFile = file.isFile || raw.endsWith(".swf", ignoreCase = true)
        val folder = if (isEntryFile) file.parentFile?.absolutePath ?: raw else raw
        val trimmed = folder.trimEnd('/')
        return if (trimmed.isEmpty() && folder.startsWith("/")) "/" else trimmed
    }

    /**
     * 解析启动 SWF：优先 `game.launchFile`（手动覆盖），其次 [ExternalEngineLaunchRequest.launchTarget]，
     * 最后目录内唯一的 `*.swf`。
     *
     * 显式给出的 `.swf` 入口（launchFile / launchTarget）只要存在就必须是目录内的有效文件：
     * 缺失或越界时返回 null，绝不静默换成同目录另一个 SWF。只有没有任何显式 `.swf`
     * （例如包装目录的哨兵/空 target）时才使用「目录内唯一 SWF」兜底。
     */
    internal fun resolveExecFile(
        request: ExternalEngineLaunchRequest,
        folder: String = resolveGameFolder(request),
    ): String? {
        if (folder.isBlank()) return null
        val directory = File(folder)
        val explicit = listOfNotNull(
            request.game.launchFile?.trim()?.takeIf { it.isNotEmpty() },
            request.launchTarget.trim().takeIf { it.isNotEmpty() },
        ).firstOrNull { it.endsWith(".swf", ignoreCase = true) }
        if (explicit != null) {
            val file = if (explicit.startsWith('/')) File(explicit) else File(directory, explicit)
            if (file.name.startsWith(".") || !file.isFile || file.length() <= 0L) return null
            val parent = file.parentFile?.absolutePath ?: return null
            if (parent != directory.absolutePath) return null
            return file.name
        }
        return directory.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.length() > 0L && it.name.endsWith(".swf", ignoreCase = true) }
            ?.singleOrNull()
            ?.name
    }

    /**
     * 稳定的单游戏 ID：以完整 SWF 路径（目录 + 文件名）取 SHA-256 前 24 个十六进制字符。
     * 同一目录下的多个 SWF 不会碰撞，且长度足够避免 32 位哈希冲突。
     */
    internal fun gameIdFor(folder: String, execFile: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(File(folder, execFile).path.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}
