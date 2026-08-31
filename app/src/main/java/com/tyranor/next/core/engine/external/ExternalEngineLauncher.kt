package com.tyranor.next.core.engine.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController

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
        val localized = AppLocaleController.wrap(context)
        val moduleName = module.displayName(localized)
        if (module.requiresGameDirectoryPath && request.gameDirectoryPath.isBlank()) {
            return ExternalEngineLaunchResult.failure(
                localized.text(R.string.external_engine_resolve_dir_failed, request.game.engine.displayName),
                "invalid_game_path",
            )
        }
        if (!isPackageInstalled(context, module)) {
            return ExternalEngineLaunchResult.failure(
                localized.text(R.string.external_engine_module_missing, moduleName),
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
                localized.text(R.string.external_engine_no_activity, moduleName),
                "activity_not_found",
            )
        } catch (t: SecurityException) {
            ExternalEngineLaunchResult.failure(
                localized.text(R.string.external_engine_denied, moduleName),
                "security_exception",
            )
        } catch (t: Throwable) {
            ExternalEngineLaunchResult.failure(
                t.message ?: localized.text(R.string.external_engine_launch_failed, moduleName),
                "launch_exception",
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

    private fun Context.text(@StringRes id: Int, vararg args: Any): String =
        getString(id, *args)
}
