package com.tyranor.next.core.engine.external

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.game.scan.EngineScanner
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** JoiPlay RPG Maker Runtime 外置 APK 模块协议。 */
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
                AppLocaleController.wrap(context).getString(R.string.external_rpgm_resolve_dir_failed),
                "invalid_game_path",
            )
        }
        ensureRtpEnvironment(context.applicationContext, gameType)
        if (gameType == TYPE_RPGMXP) {
            ensureGameConfiguration(folder, gameIdFor(folder, request.game.title), gameType)
        }
        return null
    }

    override fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent {
        val gameType = resolveGameType(request)
        return Intent(actionForGameType(gameType)).setPackage(packageName).apply {
            putExtra("game", buildGameJson(request))
            putExtra("settings", buildSettingsJson(gameType))
            putExtra("orientation", 6)
            putExtra("rootUri", request.game.uri)
            putExtra("launchTarget", request.launchTarget)
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

    internal fun buildSettingsJson(gameType: String): String {
        if (gameType != TYPE_RPGMXP) return "{}"
        return "{\"rpg\":{\"useRuby18\":{\"boolean\":true}}}"
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

    private fun ensureGameConfiguration(gameFolder: String, gameId: String, gameType: String) {
        if (gameType != TYPE_RPGMXP) return
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
                if (configFile.exists()) continue
                configFile.parentFile?.let { parent ->
                    if (!parent.exists()) parent.mkdirs()
                }
                configFile.writeText("{\"useRuby18\":true}")
            }
        }.onFailure { error ->
            Log.w(TAG, "ensureGameConfiguration failed (non-fatal)", error)
        }
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
