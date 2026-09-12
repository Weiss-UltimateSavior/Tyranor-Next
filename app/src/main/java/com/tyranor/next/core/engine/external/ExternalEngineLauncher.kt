package com.tyranor.next.core.engine.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** 外置 APK 引擎启动与安装状态检查。 */
object ExternalEngineLauncher {
    fun isPackageInstalled(context: Context, module: ExternalEngineModule): Boolean =
        try {
            val packageManager = context.applicationContext.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(module.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(module.packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun launch(context: Context, module: ExternalEngineModule, request: ExternalEngineLaunchRequest): ExternalEngineLaunchResult {
        if (module.requiresGameDirectoryPath && request.gameDirectoryPath.isBlank()) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.INVALID_GAME_PATH,
                engineName = request.game.engine.displayName,
            )
        }
        if (!isPackageInstalled(context, module)) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.PACKAGE_NOT_INSTALLED,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
            )
        }
        module.prepareForLaunch(context, request)?.let { return it }

        val intent = module.buildLaunchIntent(request).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        return try {
            context.applicationContext.startActivity(intent)
            ExternalEngineLaunchResult.success()
        } catch (_: ActivityNotFoundException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.ACTIVITY_NOT_FOUND,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
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
