package com.tyranor.next.core.engine.external

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** RPG Maker Runtime 外置 APK 模块协议。 */
object RpgMakerExternalEngineModule : ExternalEngineModule {
    private const val TAG = "RpgMakerExternalModule"

    private const val TYPE_RPGMXP = "rpgmxp"
    private const val TYPE_RPGMVX = "rpgmvx"
    private const val TYPE_RPGMVXACE = "rpgmvxace"
    private const val TYPE_MKXP_Z = "mkxp-z"
    private const val LEGACY_GAME_DIR_TARGET = "\u005B\u6E38\u620F\u76EE\u5F55\u005D"

    override val id: String = "rpgmaker"
    override val engine: EngineType = EngineType.RPGMAKER
    override val displayName: String = "RPGM Module"
    override val displayNameRes: Int = R.string.external_rpgm_module_name
    override val packageName: String = "cyou.joiplay.runtime.rpgmaker"
    override val action: String = "cyou.joiplay.runtime.rpgmvxace.run"
    override val defaultAlias: String = "internal.rpgmaker"
    override val supportedAliases: Set<String> = setOf(
        "external.rpgmaker",
        "internal.rpgmxp",
        "internal.rpgmvx",
        "internal.rpgmvxace",
        "internal.mkxp-z",
        "internal.mkxpz",
    )
    override val installUrl: String =
        "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RPGM-Plugin.apk"

    override fun prepareForLaunch(
        context: Context,
        request: ExternalEngineLaunchRequest,
    ): ExternalEngineLaunchResult? {
        val gameType = resolveGameType(request)
        val folder = resolveGameFolder(request)
        if (folder.isBlank()) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.PREPARE_FAILED,
                messageRes = R.string.external_rpgm_resolve_dir_failed,
            )
        }
        val settings = request.resolvedSettings?.rpg ?: EngineSettingsStore.RpgMaker()
        ensureRtpEnvironment(context.applicationContext, gameType)
        if (gameType == TYPE_RPGMXP) {
            syncGameConfiguration(
                gameFolder = folder,
                gameId = gameIdFor(folder, request.game.title),
                useRuby18 = settings.useRuby18,
            )
        }
        return null
    }

    override fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent {
        val gameType = resolveGameType(request)
        return Intent(actionForGameType(gameType)).setPackage(packageName).apply {
            putExtra(ExternalEngineContract.GAME, buildGameJson(request))
            putExtra(
                ExternalEngineContract.SETTINGS,
                buildSettingsJson(gameType, request.resolvedSettings?.rpg),
            )
            putExtra(ExternalEngineContract.ORIENTATION, 6)
            putExtra(ExternalEngineContract.ROOT_URI, request.game.uri)
            putExtra(ExternalEngineContract.LAUNCH_TARGET, request.launchTarget)
        }
    }

    internal fun buildGameJson(request: ExternalEngineLaunchRequest): String {
        val gameType = resolveGameType(request)
        val folder = resolveGameFolder(request).trimEnd('/')
        val title = request.game.title.ifBlank {
            folder.substringAfterLast('/', missingDelimiterValue = "RPG Maker Game")
        }
        return buildString {
            append('{')
            appendJsonField("title", title)
            append(',')
            appendJsonField("id", gameIdFor(folder, title))
            append(',')
            appendJsonField("folder", folder)
            append(',')
            appendJsonField("execFile", "")
            append(',')
            appendJsonField("type", gameType)
            append('}')
        }
    }

    /**
     * 构造 JoiPlay 协议的嵌套 settings JSON：`{"app":..., "rpg":{<key>:{"boolean"|"string": value}}}`。
     *
     * 键集以 RPGM 插件 `MKXPConfigurationParser.parse(String)` 实际解析的字段为准：
     * - `rpg`：useRuby18 / windowSize / fontScale / speedUp / customFont / verticalScreenAlign /
     *   enablePostloadScripts / pathCache / prebuiltPathCache / fastPathEnum / smoothScaling /
     *   vsync / frameSkip / solidFonts / copyText / debug / useCJKFont；
     * - `app`：cheats（插件只从 app.cheats 读取金手指开关）。
     *
     * [settings] 为三级合并结果；为空时按默认值下发，其中 XP 的 useRuby18 默认 true，
     * 保证 RGSS1 脚本交给 Ruby 1.8 解析；非 XP 子类型不做强制改写。
     */
    internal fun buildSettingsJson(
        gameType: String,
        settings: EngineSettingsStore.RpgMaker? = null,
    ): String {
        val s = settings ?: EngineSettingsStore.RpgMaker()
        val useRuby18 = if (gameType == TYPE_RPGMXP && settings == null) true else s.useRuby18
        val app = JSONObject()
            .put("cheats", JSONObject().put("boolean", s.cheats))
        val rpg = JSONObject()
            .put("useRuby18", JSONObject().put("boolean", useRuby18))
            .put("debug", JSONObject().put("boolean", s.debug))
            .put("smoothScaling", JSONObject().put("boolean", s.smoothScaling))
            .put("vsync", JSONObject().put("boolean", s.vsync))
            .put("frameSkip", JSONObject().put("boolean", s.frameSkip))
            .put("solidFonts", JSONObject().put("boolean", s.solidFonts))
            .put("pathCache", JSONObject().put("boolean", s.pathCache))
            .put("prebuiltPathCache", JSONObject().put("boolean", s.prebuiltPathCache))
            .put("fastPathEnum", JSONObject().put("boolean", s.fastPathEnum))
            .put("copyText", JSONObject().put("boolean", s.copyText))
            .put("useCJKFont", JSONObject().put("boolean", s.useCJKFont))
            .put("enablePostloadScripts", JSONObject().put("boolean", s.enablePostloadScripts))
            .put("customFont", JSONObject().put("string", s.customFont))
            .put("verticalScreenAlign", JSONObject().put("string", s.verticalScreenAlign))
            .put("windowSize", JSONObject().put("string", s.windowSize))
            .put("speedUp", JSONObject().put("string", s.speedUp))
            .put("fontScale", JSONObject().put("string", s.fontScale))
        return JSONObject().put("app", app).put("rpg", rpg).toString()
    }

    internal fun resolveGameType(request: ExternalEngineLaunchRequest): String {
        val alias = request.game.externalModuleAlias?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when (alias.replace("-", "")) {
            "internal.rpgmxp", "external.rpgmxp" -> return TYPE_RPGMXP
            "internal.rpgmvx", "external.rpgmvx" -> return TYPE_RPGMVX
            "internal.rpgmvxace", "external.rpgmvxace" -> return TYPE_RPGMVXACE
            "internal.mkxpz", "external.mkxpz" -> return TYPE_MKXP_Z
        }

        val target = request.launchTarget.trim().lowercase(Locale.ROOT)
        return when {
            target.endsWith(".rgssad") -> TYPE_RPGMXP
            target.endsWith(".rgss2a") -> TYPE_RPGMVX
            target.endsWith(".rgss3a") -> TYPE_RPGMVXACE
            else -> TYPE_RPGMXP
        }
    }

    internal fun actionForGameType(gameType: String): String = when (gameType) {
        TYPE_RPGMXP -> "cyou.joiplay.runtime.rpgmxp.run"
        TYPE_RPGMVX -> "cyou.joiplay.runtime.rpgmvx.run"
        TYPE_MKXP_Z -> "cyou.joiplay.runtime.mkxp-z.run"
        else -> "cyou.joiplay.runtime.rpgmvxace.run"
    }

    internal fun resolveGameFolder(request: ExternalEngineLaunchRequest): String {
        val root = request.gameDirectoryPath.trimEnd('/')
        val target = request.launchTarget.trim()
        if (
            target.isEmpty() ||
            target == LEGACY_GAME_DIR_TARGET ||
            target.equals(EngineScanner.LAUNCH_TARGET_GAME_DIR, ignoreCase = true)
        ) {
            return root
        }
        if (target.startsWith('/')) {
            val file = File(target)
            return if (file.isFile) file.parentFile?.absolutePath ?: root else file.absolutePath
        }

        val candidate = File(root, target)
        if (candidate.isFile) return candidate.parentFile?.absolutePath ?: root
        if (candidate.isDirectory) return candidate.absolutePath
        val lower = target.lowercase(Locale.ROOT)
        return if (
            lower.endsWith(".rgssad") ||
            lower.endsWith(".rgss2a") ||
            lower.endsWith(".rgss3a")
        ) {
            root
        } else {
            candidate.absolutePath
        }
    }

    private fun ensureRtpEnvironment(context: Context, gameType: String) {
        runCatching {
            val rtpAppDir = File(
                Environment.getExternalStorageDirectory(),
                "JoiPlay" + File.separator + "RTP" + File.separator +
                    rtpDirNameForGameType(gameType) + File.separator + "app",
            )
            if (!rtpAppDir.exists() && !rtpAppDir.mkdirs()) {
                Log.w(TAG, "mkdirs failed for RTP dir: ${rtpAppDir.absolutePath}")
                return
            }
            val soundFont = File(rtpAppDir, "sf.sf2")
            if (!soundFont.exists() || soundFont.length() <= 0L) {
                copyAssetToFile(context, "rtp/sf.sf2", soundFont)
            }
        }.onFailure { error ->
            Log.w(TAG, "ensureRtpEnvironment failed (non-fatal)", error)
        }
    }

    /**
     * 同步 XP 的扁平 configuration.json（插件 `loadFromFile()` 读取），与生效 useRuby18 联动：
     * - 文件不存在：创建 `{"useRuby18":<value>}`；
     * - 文件已是 JSON 对象：仅更新 useRuby18（保留用户其余键）；
     * - 文件存在但非 JSON：跳过，不覆盖用户文件。
     */
    private fun syncGameConfiguration(gameFolder: String, gameId: String, useRuby18: Boolean) {
        runCatching {
            val externalRoot = Environment.getExternalStorageDirectory()
            val candidates = buildList {
                if (gameFolder.startsWith(externalRoot.absolutePath)) {
                    add(File(gameFolder, "configuration.json"))
                }
                add(
                    File(
                        externalRoot,
                        "JoiPlay" + File.separator + "games" + File.separator +
                            gameId + File.separator + "configuration.json",
                    ),
                )
            }
            for (configFile in candidates.distinctBy { it.absolutePath }) {
                syncFlatConfigurationFile(configFile, useRuby18)
            }
        }.onFailure { error ->
            Log.w(TAG, "syncGameConfiguration failed (non-fatal)", error)
        }
    }

    private fun syncFlatConfigurationFile(configFile: File, useRuby18: Boolean) {
        val exists = configFile.exists()
        val existing = if (exists) {
            runCatching { JSONObject(configFile.readText()) }.getOrNull()
        } else {
            null
        }
        if (exists && existing == null) {
            Log.w(TAG, "skip non-JSON configuration file: ${configFile.absolutePath}")
            return
        }
        if (existing != null && existing.has("useRuby18") &&
            existing.optBoolean("useRuby18") == useRuby18
        ) {
            return
        }
        val json = existing ?: JSONObject()
        json.put("useRuby18", useRuby18)
        configFile.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        configFile.writeText(json.toString())
    }

    private fun copyAssetToFile(context: Context, assetPath: String, destination: File) {
        runCatching {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "copy asset $assetPath → $destination failed (non-fatal)", error)
        }
    }

    private fun rtpDirNameForGameType(gameType: String): String = when (gameType) {
        TYPE_RPGMXP -> "RPGXP"
        TYPE_RPGMVX -> "RPGVX"
        TYPE_MKXP_Z -> "mkxp-z"
        else -> "RPGVXACE"
    }

    private fun gameIdFor(folder: String, title: String): String =
        Integer.toHexString(folder.ifBlank { title }.hashCode())
}
