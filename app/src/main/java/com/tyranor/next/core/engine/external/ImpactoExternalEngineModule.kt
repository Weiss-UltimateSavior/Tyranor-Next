package com.tyranor.next.core.engine.external

import android.content.Context
import android.content.Intent
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.scan.ImpactoGameCatalog
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Impacto 外置 APK 引擎模块。
 *
 * 被承载的是**未改动**的 CommitteeOfZero/impacto（固定提交见宿主仓库 yuukana1/Tyranor-Next-MAGES 的 `engine/impacto/source-lock.json`）。它自带 SDL3
 * 的 `org.libsdl.app`，与主 APK 内置的 SDL2 同名 Java 类冲突，所以必须是独立 APK：
 * 由宿主仓库 yuukana1/Tyranor-Next-MAGES 构建发布，包内只有上游 Java 源码、上游库 `libimpacto.so` 和 TYN 自有的启动子类。
 * 启动协议集中在本文件和 [ImpactoHostContract] 内，扫描器 / UI 不散落 package/action/extra 字符串。
 */
object ImpactoExternalEngineModule : ExternalEngineModule {
    override val id: String = "impacto"
    override val engine: EngineType = EngineType.IMPACTO
    override val displayName: String = "MAGES."
    override val displayNameRes: Int = R.string.external_impacto_module_name
    override val packageName: String = ImpactoHostContract.PACKAGE
    override val action: String = ImpactoHostContract.ACTION_RUN
    override val defaultAlias: String = "internal.impacto"
    override val supportedAliases: Set<String> = setOf("external.impacto")

    /** 宿主仓库最新发布的 APK 直链（资产名固定）；宿主每发布新版本，这里自动指向它，无需改动本 App。 */
    override val installUrl: String =
        "https://github.com/yuukana1/Tyranor-Next-MAGES/releases/latest/download/Tyranor-Next-MAGES.apk"

    /** 低于本版本的宿主不认识当前请求协议，按「已过期」处理并引导更新。 */
    override val minimumVersionCode: Long = ImpactoHostContract.VERSION_CODE.toLong()
    override val requiresGameDirectoryPath: Boolean = true

    override fun prepareForLaunch(
        context: Context,
        request: ExternalEngineLaunchRequest,
    ): ExternalEngineLaunchResult? {
        val game = ImpactoGameCatalog.byProfileId(request.launchTarget)
        val folder = File(request.gameDirectoryPath.trim())
        if (game == null || !folder.isAbsolute) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.PREPARE_FAILED,
                messageRes = R.string.external_impacto_profile_unresolved,
            )
        }
        return null
    }

    override fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent {
        val folder = request.gameDirectoryPath.trim().trimEnd('/')
        return Intent(action).setPackage(packageName).apply {
            putExtra(ImpactoHostContract.EXTRA_GAME_DIR, folder)
            putExtra(ImpactoHostContract.EXTRA_PROFILE, request.launchTarget)
            putExtra(ImpactoHostContract.EXTRA_GAME_KEY, gameKeyFor(folder, request.launchTarget))
            putExtra(ImpactoHostContract.EXTRA_TITLE, request.game.title)
            putExtra(
                ImpactoHostContract.EXTRA_SETTINGS,
                buildSettingsJson(request.resolvedSettings?.impacto),
            )
        }
    }

    /**
     * 宿主 settings extra：扁平 JSON，字段与 [EngineSettingsStore.Impacto] 一一对应。
     * 宿主在启动引擎前把它合并进该游戏的 `userconfig.toml`（只改这些键，其余保持）；
     * [settings] 为生效设置，为空时按默认模型全量下发，避免宿主缺键走自身默认。
     */
    internal fun buildSettingsJson(settings: EngineSettingsStore.Impacto? = null): String {
        val s = settings ?: EngineSettingsStore.Impacto()
        return JSONObject()
            .put("swapConfirmCancel", s.swapConfirmCancel)
            .put("vsync", s.vsync)
            .put("videoPlayback", s.videoPlayback)
            .put("subtitles", EngineSettingsStore.normalizeImpactoSubtitles(s.subtitles))
            .put("closeBacklogAtEnd", s.closeBacklogAtEnd)
            .put("delusionMouse", s.delusionMouse)
            .put("logLevel", EngineSettingsStore.normalizeImpactoLogLevel(s.logLevel))
            .toString()
    }

    /**
     * 稳定的单游戏键：游戏目录 + profile 的 SHA-256 前 24 个十六进制字符。
     * 宿主据此隔离每个游戏的存档与用户配置目录；同一目录换 profile 也不会串。
     */
    internal fun gameKeyFor(folder: String, profileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$profileId\u0000$folder".toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}

/** Impacto 宿主 APK 的 Intent 协议键；两端（本 App 与宿主仓库 yuukana1/Tyranor-Next-MAGES）各持一份常量，改动须同步。 */
object ImpactoHostContract {
    const val PACKAGE = "com.tyranor.next.impacto"

    /**
     * 本版 App 需要的最低宿主 versionCode；须不高于宿主仓库最新发布的版本。只在请求协议不兼容时才需要抬高。
     * 3 = 宿主 0.1.2，起认识 [EXTRA_SETTINGS]；更老的宿主会忽略该 extra，设置不会生效，所以按“需要更新”处理。
     */
    const val VERSION_CODE = 3

    const val ACTION_RUN = "com.tyranor.next.impacto.action.RUN"
    const val EXTRA_GAME_DIR = "com.tyranor.next.impacto.extra.GAME_DIR"
    const val EXTRA_PROFILE = "com.tyranor.next.impacto.extra.PROFILE"
    const val EXTRA_GAME_KEY = "com.tyranor.next.impacto.extra.GAME_KEY"
    const val EXTRA_TITLE = "com.tyranor.next.impacto.extra.TITLE"
    const val EXTRA_SETTINGS = "com.tyranor.next.impacto.extra.SETTINGS"
}
