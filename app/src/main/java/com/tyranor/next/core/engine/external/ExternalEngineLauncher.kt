package com.tyranor.next.core.engine.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.tyranor.next.core.engine.graphics.putDisabledGraphicsRequest

/** 外置 APK 引擎启动与安装状态检查。 */
object ExternalEngineLauncher {
    fun isPackageInstalled(context: Context, module: ExternalEngineModule): Boolean =
        checkInstallation(context, module) == null

    /**
     * 模块缺失或版本不匹配时返回类型化失败，可用时返回 null。
     * 传入 [launchTarget] 时按该目标要求的最低版本（[ExternalEngineModule.minimumVersionCodeFor]）判定，
     * 不传则只看模块级要求（引擎页、设置页的安装状态）。
     */
    fun checkInstallation(
        context: Context,
        module: ExternalEngineModule,
        launchTarget: String? = null,
    ): ExternalEngineLaunchResult? =
        try {
            val packageManager = context.applicationContext.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(module.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(module.packageName, 0)
            }
            val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val versionError = versionCodeError(installedVersionCode, module, launchTarget)
            if (versionError != null) {
                ExternalEngineLaunchResult.failure(
                    versionError,
                    moduleNameRes = module.displayNameRes,
                    moduleNameFallback = module.displayName,
                    engineName = module.engine.displayName,
                    installUrl = module.installUrl,
                )
            } else {
                null
            }
        } catch (_: PackageManager.NameNotFoundException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.PACKAGE_NOT_INSTALLED,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
                engineName = module.engine.displayName,
                installUrl = module.installUrl,
            )
        }

    internal fun isVersionCodeSupported(installed: Long, minimum: Long): Boolean =
        minimum <= 0L || installed >= minimum

    internal fun versionCodeError(
        installed: Long,
        module: ExternalEngineModule,
        launchTarget: String? = null,
    ): ExternalEngineErrorCode? {
        val minimum = launchTarget?.let(module::minimumVersionCodeFor) ?: module.minimumVersionCode
        return when {
            !isVersionCodeSupported(installed, minimum) -> ExternalEngineErrorCode.PACKAGE_OUTDATED
            installed > module.maximumVersionCode -> ExternalEngineErrorCode.PACKAGE_VERSION_MISMATCH
            else -> null
        }
    }

    fun launch(
        context: Context,
        module: ExternalEngineModule,
        request: ExternalEngineLaunchRequest,
        orientationResolver: (() -> Int)? = null,
        beforeStartActivity: (() -> Unit)? = null,
    ): ExternalEngineLaunchResult {
        if (module.requiresGameDirectoryPath && request.gameDirectoryPath.isBlank()) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.INVALID_GAME_PATH,
                engineName = request.game.engine.displayName,
            )
        }
        checkInstallation(context, module, request.launchTarget)?.let { return it }
        val (resolvedRequest, prepareFailure) = prepareRequest(
            prepareStep = { module.prepareForLaunch(context, request) },
            request = request,
            orientationResolver = orientationResolver,
            beforeStartActivity = beforeStartActivity,
        )
        if (prepareFailure != null) return prepareFailure

        val intent = module.buildLaunchIntent(resolvedRequest ?: request).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        intent.putDisabledGraphicsRequest(onlyMissing = true)
        return try {
            context.applicationContext.startActivity(intent)
            ExternalEngineLaunchResult.success()
        } catch (_: ActivityNotFoundException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.ACTIVITY_NOT_FOUND,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
                installUrl = module.installUrl,
            )
        } catch (_: SecurityException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.SECURITY_EXCEPTION,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
            )
        } catch (t: Throwable) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.LAUNCH_EXCEPTION,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
                detail = t.message,
            )
        }
    }

    internal fun prepareRequest(
        prepareStep: () -> ExternalEngineLaunchResult?,
        request: ExternalEngineLaunchRequest,
        orientationResolver: (() -> Int)? = null,
        beforeStartActivity: (() -> Unit)? = null,
    ): Pair<ExternalEngineLaunchRequest?, ExternalEngineLaunchResult?> {
        prepareStep()?.let { return null to it }
        // 外置模块准备（如 RPG Maker 创建 RTP 环境、写 configuration.json）完成后再检查取消
        // 并解析此刻握持方向，避免准备耗时期间设备旋转导致沿用过期方向。
        beforeStartActivity?.invoke()
        val resolvedRequest = if (orientationResolver != null) {
            request.copy(orientation = ExternalEngineContract.normalizeOrientation(orientationResolver()))
        } else {
            request
        }
        beforeStartActivity?.invoke()
        return resolvedRequest to null
    }

    fun openInstallPage(context: Context, module: ExternalEngineModule): Boolean =
        module.installUrl?.let { openInstallPage(context, it) } ?: false

    fun openInstallPage(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.applicationContext.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
