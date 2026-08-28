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
                "无法解析 ${request.game.engine.displayName} 游戏目录真实路径，外置引擎模块无法启动",
                "invalid_game_path",
            )
        }
        if (!isPackageInstalled(context, module)) {
            return ExternalEngineLaunchResult.failure(
                "未检测到 ${module.displayName}，请先下载安装模块",
                "package_not_installed",
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
                "${module.displayName} 未提供可接收启动请求的 Activity",
                "activity_not_found",
            )
        } catch (t: SecurityException) {
            ExternalEngineLaunchResult.failure(
                "${module.displayName} 启动被系统拒绝，请检查模块权限",
                "security_exception",
            )
        } catch (t: Throwable) {
            ExternalEngineLaunchResult.failure(
                t.message ?: "${module.displayName} 启动失败，请检查模块权限",
                "launch_exception",
            )
        }
    }

    fun openInstallPage(context: Context, module: ExternalEngineModule): Boolean {
        val url = module.installUrl?.takeIf { it.isNotBlank() } ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.applicationContext.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
