package com.tyranor.next.core.engine.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * 外置主机模拟器跳转（PPSSPP / Eden）。
 *
 * 只负责安装探测、ROM URI 归一与 `ACTION_VIEW` 显式组件启动；不接管模拟器设置与存档。
 * ROM URI 优先使用扫描产物的 SAF `content://`（自带 tree 授权），File 回退路径经
 * FileProvider 转为可授权的 content URI（跨进程传 `file://` 会触发 FileUriExposedException）。
 */
object ExternalEmulatorLauncher {

    /** 错误码：package_not_installed / invalid_rom_uri / activity_not_found / security_exception / launch_exception。 */
    data class Result(
        val success: Boolean,
        val code: String,
        val target: EmulatorTarget,
    )

    fun isInstalled(context: Context, target: EmulatorTarget): Boolean = try {
        val pm = context.applicationContext.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(target.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(target.packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun launch(context: Context, target: EmulatorTarget, romUriText: String): Result {
        val app = context.applicationContext
        if (!isInstalled(app, target)) return Result(false, "package_not_installed", target)
        val uri = resolveRomUri(app, romUriText) ?: return Result(false, "invalid_rom_uri", target)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, target.mime)
            setClassName(target.packageName, target.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (target.grantWrite) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return try {
            app.startActivity(intent)
            Result(true, "success", target)
        } catch (_: ActivityNotFoundException) {
            Result(false, "activity_not_found", target)
        } catch (_: SecurityException) {
            Result(false, "security_exception", target)
        } catch (_: Throwable) {
            Result(false, "launch_exception", target)
        }
    }

    /** 打开模拟器主界面（引擎页“已安装”条目点击）。 */
    fun openHome(context: Context, target: EmulatorTarget): Boolean {
        val app = context.applicationContext
        val intent = app.packageManager.getLaunchIntentForPackage(target.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** ROM URI 归一：content:// 直接用；file:// 或绝对路径走 FileProvider。失败返回 null。 */
    fun resolveRomUri(context: Context, romUriText: String): Uri? {
        if (romUriText.isBlank()) return null
        val uri = runCatching { Uri.parse(romUriText) }.getOrNull() ?: return null
        when (uri.scheme?.lowercase()) {
            "content" -> return uri
            "file" -> return uri.path?.let { fileProviderUri(context, File(it)) }
        }
        return if (romUriText.startsWith("/")) fileProviderUri(context, File(romUriText)) else null
    }

    private fun fileProviderUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
