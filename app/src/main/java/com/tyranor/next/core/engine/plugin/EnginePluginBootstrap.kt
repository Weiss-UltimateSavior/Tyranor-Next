package com.tyranor.next.core.engine.plugin

import android.content.Context
import android.content.SharedPreferences
import com.core.engine.EnginePrefs
import com.core.nativeplugin.NativePluginConstants
import com.core.nativeplugin.NativePluginInstallState
import com.core.nativeplugin.NativePluginManager
import com.tyranor.next.core.engine.EngineType
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 直接集成（非模块化）：把随 APK 打包在 assets 的引擎原生插件 zip，
 * 首次启动时自动"安装"到 app 私有插件目录，并标记为已安装+已启用。
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
            EngineType.RENPY,
            EngineType.UNKNOWN -> return null
        }
        val app = context.applicationContext
        val spec = engines.firstOrNull { it.engineId == engineId }
            ?: return "未知引擎插件：$engineId"
        return if (provisionEngineIfNeeded(app, spec, requireEnabled = true)) {
            null
        } else {
            "引擎插件安装失败，请重启应用或检查安装包完整性"
        }
    }

    @Synchronized
    private fun provisionEngineIfNeeded(app: Context, spec: EngineSpec, requireEnabled: Boolean): Boolean {
        val prefs = app.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
        val state = installState(app, spec.engineId)
        if (state == NativePluginInstallState.INSTALLED_ENABLED) {
            markInstalled(prefs, spec, enabled = true)
            return true
        }
        if (!requireEnabled && state == NativePluginInstallState.INSTALLED_DISABLED) {
            markInstalled(prefs, spec, enabled = false)
            return true
        }
        if (requireEnabled && state == NativePluginInstallState.INSTALLED_DISABLED) {
            markInstalled(prefs, spec, enabled = true)
            return isReady(app, spec.engineId)
        }
        return try {
            val target = currentDirFor(app, spec.engineId)
            if (target.exists()) target.deleteRecursively()
            extractPluginZip(app, spec.engineId, target)
            markInstalled(prefs, spec, enabled = true)
            val ready = isReady(app, spec.engineId)
            if (ready) {
                android.util.Log.i(TAG, "provisioned native plugin: ${spec.engineId}")
            } else {
                android.util.Log.w(TAG, "provision ${spec.engineId} finished but validation failed")
            }
            ready
        } catch (t: Throwable) {
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
}
