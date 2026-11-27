package com.tyranor.next.core.engine.external

import android.content.Context
import android.content.Intent
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import java.io.File

/**
 * EasyRPG 外置 APK 引擎模块。
 *
 * 上游 EasyRPG/Player Android 端依赖 SDL3（`org.libsdl.app`），与主 APK 内置的 SDL2 同名 Java 类冲突，
 * 且遵循 GPLv3 隔离边界，因此以独立宿主 APK（`SIRENA-Core/Tyranor-Next-EasyRPG`）承载。
 * 启动协议集中在本文件和 [EasyRpgHostContract] 内，扫描器 / UI 不散落 package/action/extra 字符串。
 */
object EasyRpgExternalEngineModule : ExternalEngineModule {
    override val id: String = "easyrpg"
    override val engine: EngineType = EngineType.EASYRPG
    override val displayName: String = "EasyRPG"
    override val displayNameRes: Int = R.string.external_easyrpg_module_name
    override val packageName: String = EasyRpgHostContract.PACKAGE
    override val action: String = EasyRpgHostContract.ACTION_RUN
    override val defaultAlias: String = "internal.easyrpg"
    override val supportedAliases: Set<String> = setOf("external.easyrpg")

    override val updateSource = ExternalEngineReleaseSource("SIRENA-Core/Tyranor-Next-EasyRPG", "easyrpg-host-")

    /** 宿主仓库最新发布的 APK 直链（资产名固定）；宿主每发布新版本，这里自动指向它，无需改动本 App。 */
    override val installUrl: String =
        "${updateSource.releasesUrl}/latest/download/Tyranor-Next-EasyRPG.apk"

    /** 低于本版本的宿主不认识当前请求协议，按「已过期」处理并引导更新。 */
    override val minimumVersionCode: Long = EasyRpgHostContract.VERSION_CODE.toLong()

    override val requiresGameDirectoryPath: Boolean = true

    override fun prepareForLaunch(
        context: Context,
        request: ExternalEngineLaunchRequest,
    ): ExternalEngineLaunchResult? = validateGameDirectoryPath(request.gameDirectoryPath)

    internal fun validateGameDirectoryPath(gameDirectoryPath: String): ExternalEngineLaunchResult? {
        val trimmed = gameDirectoryPath.trim()
        if (trimmed.isEmpty() || !File(trimmed).isAbsolute) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.INVALID_GAME_PATH,
                engineName = displayName,
            )
        }
        return null
    }

    internal fun resolveLaunchFolder(request: ExternalEngineLaunchRequest): String {
        val rawFolder = request.gameDirectoryPath.trim()
        return rawFolder.trimEnd('/').ifEmpty { if (rawFolder.startsWith("/")) "/" else "" }
    }

    internal fun resolveLaunchTitle(request: ExternalEngineLaunchRequest, folder: String = resolveLaunchFolder(request)): String =
        request.game.title.ifBlank { File(folder).name.ifBlank { displayName } }

    override fun buildLaunchIntent(context: Context, request: ExternalEngineLaunchRequest): Intent {
        val folder = resolveLaunchFolder(request)
        val title = resolveLaunchTitle(request, folder)
        return Intent(action).setPackage(packageName).apply {
            putExtra(EasyRpgHostContract.EXTRA_GAME_DIR, folder)
            putExtra(EasyRpgHostContract.EXTRA_TITLE, title)
            putExtra(
                EasyRpgHostContract.EXTRA_SETTINGS,
                buildSettingsJson(request.resolvedSettings?.easyRpg),
            )
        }
    }

    /**
     * 宿主 settings extra：扁平 JSON，字段与 [EngineSettingsStore.EasyRpg] 一一对应。
     * [settings] 为生效设置，为空时按默认模型全量下发，避免宿主缺键走自身默认。
     */
    internal fun buildSettingsJson(settings: EngineSettingsStore.EasyRpg? = null): String {
        val s = settings ?: EngineSettingsStore.EasyRpg()
        return JSONObject()
            .put("encoding", EngineSettingsStore.normalizeEasyRpgEncoding(s.encoding))
            .put("scaling", EngineSettingsStore.normalizeEasyRpgScaling(s.scaling))
            .put("gameResolution", EngineSettingsStore.normalizeEasyRpgGameResolution(s.gameResolution))
            .put("stretch", s.stretch)
            .put("vsync", s.vsync)
            .put("fpsLimit", EngineSettingsStore.normalizeEasyRpgFpsLimit(s.fpsLimit))
            .put("showFps", s.showFps)
            .put("disableAudio", s.disableAudio)
            .put("musicVolume", EngineSettingsStore.normalizeEasyRpgVolume(s.musicVolume))
            .put("soundVolume", EngineSettingsStore.normalizeEasyRpgVolume(s.soundVolume))
            .put("noRtp", s.noRtp)
            .put("patchMode", EngineSettingsStore.normalizeEasyRpgPatchMode(s.patchMode))
            .put("testPlay", s.testPlay)
            .toString()
    }
}

/** EasyRPG 宿主 APK 的 Intent 协议键；两端（本 App 与宿主仓库 SIRENA-Core/Tyranor-Next-EasyRPG）各持一份常量，改动须同步。 */
object EasyRpgHostContract {
    const val PACKAGE = "com.tyranor.next.easyrpg"
    const val VERSION_CODE = 1
    const val ACTION_RUN = "com.tyranor.next.easyrpg.action.RUN"
    const val EXTRA_GAME_DIR = "com.tyranor.next.easyrpg.extra.GAME_DIR"
    const val EXTRA_TITLE = "com.tyranor.next.easyrpg.extra.TITLE"
    const val EXTRA_SETTINGS = "com.tyranor.next.easyrpg.extra.SETTINGS"
}
