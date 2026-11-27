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
 * 被承载的是 CommitteeOfZero/impacto：宿主仓库 SIRENA-Core/Tyranor-Next-MAGES 固定上游提交，再按序应用该仓库的补丁系列
 * （`engine/impacto/source-lock.json`、`engine/impacto/patches/`，逐个记录 SHA-256）。补丁里既有通用修复，也有只服务
 * Windows／Steam 版《秋之回忆6》的 `mo6tw-pc*` profile；其他游戏没有在设备上逐一回归。上游 impacto 自带 SDL3
 * 的 `org.libsdl.app`，与主 APK 内置的 SDL2 同名 Java 类冲突，所以必须是独立 APK：
 * 由宿主仓库 SIRENA-Core/Tyranor-Next-MAGES 构建发布，包内只有上游 Java 源码、固定上游加补丁系列编出的库 `libimpacto.so` 和 TYN 自有的启动子类。
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

    override val updateSource = ExternalEngineReleaseSource("SIRENA-Core/Tyranor-Next-MAGES", "impacto-host-")

    /** 宿主仓库最新发布的 APK 直链（资产名固定）；宿主每发布新版本，这里自动指向它，无需改动本 App。 */
    override val installUrl: String =
        "${updateSource.releasesUrl}/latest/download/Tyranor-Next-MAGES.apk"

    /** 低于本版本的宿主不认识当前请求协议，按「已过期」处理并引导更新。 */
    override val minimumVersionCode: Long = ImpactoHostContract.VERSION_CODE.toLong()

    /**
     * 个别 profile 要求更新的宿主：`mo6tw-pc*` 由宿主补丁系列提供，宿主 0.2.0 之前的版本会直接拒绝它们。
     * 只对这些 profile 提示更新，不抬高模块级的 [minimumVersionCode]，免得只玩其余 profile 的用户被迫升级宿主。
     */
    override fun minimumVersionCodeFor(launchTarget: String): Long =
        maxOf(
            minimumVersionCode,
            ImpactoGameCatalog.minHostVersionCodeFor(launchTarget).toLong(),
        )

    override val requiresGameDirectoryPath: Boolean = true

    override fun prepareForLaunch(
        context: Context,
        request: ExternalEngineLaunchRequest,
    ): ExternalEngineLaunchResult? {
        // 识别规则可能来自已安装的宿主（App 启动后还没扫描过时尚未读取）：先刷新，宿主新增的 profile 才能找到。
        ImpactoGameCatalog.refresh(context)
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

    override fun buildLaunchIntent(context: Context, request: ExternalEngineLaunchRequest): Intent {
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
            // 宿主 0.4.3 起认识；更早的宿主逐键读取，忽略不认识的键
            .put("debugMode", s.debugMode)
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

/** Impacto 宿主 APK 的 Intent 协议键；两端（本 App 与宿主仓库 SIRENA-Core/Tyranor-Next-MAGES）各持一份常量，改动须同步。 */
object ImpactoHostContract {
    const val PACKAGE = "com.tyranor.next.impacto"

    /**
     * 本版 App 需要的最低宿主 versionCode；须不高于宿主仓库最新发布的版本。只在请求协议不兼容时才需要抬高。
     * 3 = 宿主 0.1.2，起认识 [EXTRA_SETTINGS]；更老的宿主会忽略该 extra，设置不会生效，所以按“需要更新”处理。
     * 只有部分 profile 才需要的新版本（`mo6tw-pc*` 要 4 = 宿主 0.2.0）由 [ImpactoGameCatalog.Game.minHostVersionCode]
     * 单独约束，不抬高这里的全局值。
     */
    const val VERSION_CODE = 3

    const val ACTION_RUN = "com.tyranor.next.impacto.action.RUN"
    const val EXTRA_GAME_DIR = "com.tyranor.next.impacto.extra.GAME_DIR"
    const val EXTRA_PROFILE = "com.tyranor.next.impacto.extra.PROFILE"
    const val EXTRA_GAME_KEY = "com.tyranor.next.impacto.extra.GAME_KEY"
    const val EXTRA_TITLE = "com.tyranor.next.impacto.extra.TITLE"
    const val EXTRA_SETTINGS = "com.tyranor.next.impacto.extra.SETTINGS"
}
