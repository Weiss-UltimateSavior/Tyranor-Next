package com.tyranor.next.core.engine.external

import android.content.ActivityNotFoundException
import android.content.ComponentName
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

    const val CODE_PACKAGE_NOT_INSTALLED = "package_not_installed"
    const val CODE_INVALID_ROM_URI = "invalid_rom_uri"
    const val CODE_ACTIVITY_NOT_FOUND = "activity_not_found"
    const val CODE_SECURITY_EXCEPTION = "security_exception"
    const val CODE_LAUNCH_EXCEPTION = "launch_exception"

    /** 已安装但缺少外置启动入口（旧版 Winlator）。 */
    const val CODE_EXTERNAL_LAUNCH_UNSUPPORTED = "external_launch_unsupported"

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
        if (!isInstalled(app, target)) return Result(false, CODE_PACKAGE_NOT_INSTALLED, target)
        val uri = resolveRomUri(app, romUriText) ?: return Result(false, CODE_INVALID_ROM_URI, target)
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
            Result(false, CODE_ACTIVITY_NOT_FOUND, target)
        } catch (_: SecurityException) {
            Result(false, CODE_SECURITY_EXCEPTION, target)
        } catch (_: Throwable) {
            Result(false, CODE_LAUNCH_EXCEPTION, target)
        }
    }

    /**
     * Winlator 外置启动：只传「游戏目录 + 相对 exe 文件名」，目录由 Winlator 自动分配空闲盘符
     * 临时挂载（`save=false` 不写回容器配置），再按相对路径启动 exe；
     * [options] 为引擎设置解析出的容器 / 图形 / 分辨率等下发参数（空值不下发）。
     *
     * 错误码：package_not_installed / external_launch_unsupported（已装但不支持外置启动的旧版，
     * 缺少导出入口）/ activity_not_found / security_exception / launch_exception。
     */
    fun launchWinlator(
        context: Context,
        target: EmulatorTarget,
        dirPath: String,
        exeName: String,
        launchId: String?,
        options: WinlatorContract.LaunchOptions = WinlatorContract.LaunchOptions(),
    ): Result {
        val app = context.applicationContext
        if (!isInstalled(app, target)) return Result(false, CODE_PACKAGE_NOT_INSTALLED, target)
        val component = ComponentName(target.packageName, target.activityName)
        if (!resolvesComponent(app, component)) return Result(false, CODE_EXTERNAL_LAUNCH_UNSUPPORTED, target)
        val intent = Intent().apply {
            this.component = component
            putExtra(WinlatorContract.EXTRA_DIR_PATH, dirPath)
            putExtra(WinlatorContract.EXTRA_EXE_PATH, exeName)
            putExtra(WinlatorContract.EXTRA_CONFIRM, true)
            launchId?.takeIf { it.isNotBlank() }?.let { putExtra(WinlatorContract.EXTRA_LAUNCH_ID, it) }
            WinlatorContract.extras(options).forEach { (key, value) ->
                when (value) {
                    is Int -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                    else -> putExtra(key, value.toString())
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            app.startActivity(intent)
            Result(true, "success", target)
        } catch (_: ActivityNotFoundException) {
            Result(false, CODE_ACTIVITY_NOT_FOUND, target)
        } catch (_: SecurityException) {
            Result(false, CODE_SECURITY_EXCEPTION, target)
        } catch (_: Throwable) {
            Result(false, CODE_LAUNCH_EXCEPTION, target)
        }
    }

    /** 目标组件是否可解析（包可见性由 manifest `<queries>` 保障）。 */
    private fun resolvesComponent(context: Context, component: ComponentName): Boolean = try {
        context.packageManager.resolveActivity(Intent().setComponent(component), 0) != null
    } catch (_: Throwable) {
        false
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
