package com.tyranor.next.core.engine.plugin

import android.content.Context
import android.content.SharedPreferences
import com.core.engine.EnginePrefs
import com.core.nativeplugin.NativePluginConstants
import com.core.nativeplugin.NativePluginInstallState
import com.core.nativeplugin.NativePluginManager
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.i18n.AppLocaleController
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 直接集成（非模块化）：把随 APK 打包在 assets 的引擎原生插件 zip，
 * 首次启动时自动安装到 app 私有插件目录，并标记为已安装+已启用。
 *
 * 引擎加载器（NativeLibraryLoader/OnsLibLoader/Artemis 相关）从
 * filesDir/engine_plugins/<engine>/current/arm64-v8a/ 读取 .so；
 * 此处解压 assets/nativeplugins/<engine>.zip 到该目录，无需用户手动导入 zip。
 */
object EnginePluginBootstrap {

    private const val TAG = "EnginePluginBootstrap"
    private const val ASSET_PLUGIN_DIR = "nativeplugins"

    private class EngineSpec(
        val engineId: String,
        val installedKey: String,
        val enabledKey: String,
    )

    private val engines = listOf(
        EngineSpec(
            NativePluginConstants.ENGINE_KIRIKIROID2,
            EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED,
            EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED,
        ),
        EngineSpec(
            NativePluginConstants.ENGINE_ONS,
            EnginePrefs.KEY_NATIVE_PLUGIN_ONS_INSTALLED,
            EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ENABLED,
        ),
        EngineSpec(
            NativePluginConstants.ENGINE_ARTEMIS,
            EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_INSTALLED,
            EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_ENABLED,
        ),
    )

    /** 幂等：仅对尚未安装的引擎执行一次复制。每次应用启动调用开销极低。 */
    @JvmStatic
    fun provisionIfNeeded(context: Context) {
        val app = context.applicationContext
        for (spec in engines) {
            provisionEngineIfNeeded(app, spec, requireEnabled = false)
        }
    }

    /** 启动前同步保障：对应引擎插件必须已安装、已启用且文件完整。 */
    @JvmStatic
    fun ensureForLaunch(context: Context, engine: EngineType): String? {
        val engineId = when (engine) {
            EngineType.KIRIKIRI -> NativePluginConstants.ENGINE_KIRIKIROID2
            EngineType.ONS -> NativePluginConstants.ENGINE_ONS
            EngineType.ARTEMIS -> NativePluginConstants.ENGINE_ARTEMIS
            EngineType.TYRANO,
            EngineType.RPG_MV,
            EngineType.RPG_MZ,
            EngineType.VN,
            EngineType.WEB_OTHER,
            EngineType.RPGMAKER,
            EngineType.RENPY,
            EngineType.UNKNOWN -> return null
        }
        val app = context.applicationContext
        val spec = engines.firstOrNull { it.engineId == engineId }
            ?: return text(context, R.string.plugin_unknown_engine, engineId)
        return if (provisionEngineIfNeeded(app, spec, requireEnabled = true)) {
            null
        } else {
            text(context, R.string.plugin_install_failed)
        }
    }

    /** 单飞锁：并发触发（快速重建/双入口）时仅一个线程执行解压与状态写入，避免同目录交错写坏插件 */
    private val provisionLock = Any()

    private fun provisionEngineIfNeeded(app: Context, spec: EngineSpec, requireEnabled: Boolean): Boolean {
        // 锁外快速路径：纯读，不修改任何状态。若在此写 enabledKey，启动线程（requireEnabled=true）
        // 与 MainActivity 后台预热线程（requireEnabled=false）并发时会互相覆盖标记（CodeRabbit 意见）
        if (isInstalledReadOnly(app, spec, requireEnabled)) return true
        synchronized(provisionLock) {
            // 锁内完整路径：状态刷新 + 安装，写操作全部在锁内原子完成
            return if (resolveAlreadyInstalled(app, spec, requireEnabled)) {
                true
            } else {
                installNow(app, spec)
            }
        }
    }

    /** 纯读快速检查：已装且满足启用要求即视为就绪。不修改任何标记（写操作统一在锁内完成）。 */
    private fun isInstalledReadOnly(app: Context, spec: EngineSpec, requireEnabled: Boolean): Boolean {
        return when (installState(app, spec.engineId)) {
            NativePluginInstallState.INSTALLED_ENABLED -> true
            NativePluginInstallState.INSTALLED_DISABLED -> !requireEnabled
            else -> false
        }
    }

    /** 已安装则按需刷新启用标记并返回 true。 */
    private fun resolveAlreadyInstalled(app: Context, spec: EngineSpec, requireEnabled: Boolean): Boolean {
        val prefs = app.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
        when (installState(app, spec.engineId)) {
            NativePluginInstallState.INSTALLED_ENABLED -> {
                markInstalled(prefs, spec, enabled = true)
                return true
            }
            NativePluginInstallState.INSTALLED_DISABLED -> {
                if (!requireEnabled) {
                    markInstalled(prefs, spec, enabled = false)
                    return true
                }
                markInstalled(prefs, spec, enabled = true)
                return isReady(app, spec.engineId)
            }
            else -> return false
        }
    }

    private fun installNow(app: Context, spec: EngineSpec): Boolean {
        val prefs = app.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
        return try {
            val target = currentDirFor(app, spec.engineId)
            if (target.exists() && !target.deleteRecursively()) {
                // 删除失败（可能部分删除）：中止安装，避免旧残留与解压产物混杂（CodeRabbit 意见）
                throw IllegalStateException("cleanup failed for ${spec.engineId}")
            }
            extractPluginZip(app, spec.engineId, target)
            // 先写标记再校验：installState 的 ENABLED 判定依赖 enabled 标记，须先标记才能读到就绪；
            // 目录缺必需文件会返回 INVALID（与标记无关），据此兜底回滚
            markInstalled(prefs, spec, enabled = true)
            if (!isReady(app, spec.engineId)) {
                // 解压产物校验失败：回滚标记并清理目录，避免残留“已安装”元数据（CodeRabbit 意见）
                clearInstalled(prefs, spec)
                if (target.exists()) target.deleteRecursively()
                android.util.Log.w(TAG, "provision ${spec.engineId} validation failed, rolled back")
                return false
            }
            android.util.Log.i(TAG, "provisioned native plugin: ${spec.engineId}")
            true
        } catch (t: Throwable) {
            // 解压/清理中途异常：回滚元数据并清理残留目录，保证下次可干净重装（CodeRabbit 意见）
            try {
                clearInstalled(prefs, spec)
                val target = currentDirFor(app, spec.engineId)
                if (target.exists()) target.deleteRecursively()
            } catch (_: Throwable) {
                // 清理失败仅记录在日志，不覆盖原始异常
            }
            android.util.Log.w(TAG, "provision ${spec.engineId} failed", t)
            false
        }
    }

    private fun isReady(app: Context, engineId: String): Boolean {
        return installState(app, engineId) == NativePluginInstallState.INSTALLED_ENABLED
    }

    private fun installState(app: Context, engineId: String): NativePluginInstallState {
        val state = when (engineId) {
            NativePluginConstants.ENGINE_KIRIKIROID2 -> NativePluginManager.kirikiroid2InstallState(app)
            NativePluginConstants.ENGINE_ONS -> NativePluginManager.onsInstallState(app)
            NativePluginConstants.ENGINE_ARTEMIS -> NativePluginManager.artemisInstallState(app)
            else -> NativePluginInstallState.INVALID
        }
        return state
    }

    private fun markInstalled(prefs: SharedPreferences, spec: EngineSpec, enabled: Boolean) {
        prefs.edit()
            .putBoolean(spec.installedKey, true)
            .putBoolean(spec.enabledKey, enabled)
            .apply()
    }

    private fun clearInstalled(prefs: SharedPreferences, spec: EngineSpec) {
        prefs.edit()
            .remove(spec.installedKey)
            .remove(spec.enabledKey)
            .apply()
    }

    private fun currentDirFor(app: Context, engineId: String): File = when (engineId) {
        NativePluginConstants.ENGINE_KIRIKIROID2 -> NativePluginManager.kirikiroid2CurrentDir(app)
        NativePluginConstants.ENGINE_ONS -> NativePluginManager.onsCurrentDir(app)
        NativePluginConstants.ENGINE_ARTEMIS -> NativePluginManager.artemisCurrentDir(app)
        else -> error("unknown engine: $engineId")
    }

    private fun extractPluginZip(context: Context, engineId: String, destDir: File) {
        val canonicalDest = destDir.canonicalFile
        val canonicalDestPath = canonicalDest.path + File.separator
        destDir.mkdirs()
        context.assets.open("$ASSET_PLUGIN_DIR/$engineId.zip").use { asset ->
            ZipInputStream(asset.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(destDir, entry.name)
                    val canonicalOut = out.canonicalFile
                    if (canonicalOut.path != canonicalDest.path &&
                        !canonicalOut.path.startsWith(canonicalDestPath)
                    ) {
                        throw SecurityException("Invalid native plugin zip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        canonicalOut.mkdirs()
                    } else {
                        canonicalOut.parentFile?.mkdirs()
                        canonicalOut.outputStream().use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                }
            }
        }
        require(destDir.isDirectory) {
            "native plugin extraction produced no directory: $engineId"
        }
    }

    private fun text(context: Context, id: Int, vararg args: Any): String =
        AppLocaleController.wrap(context).getString(id, *args)
}
