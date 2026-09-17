package com.core.ons

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import android.util.Log
import com.core.nativeplugin.NativeLibraryLoader
import com.core.nativeplugin.NativePluginConstants
import com.core.nativeplugin.NativePluginManager
import java.io.File
import java.io.FileOutputStream

/**
 * 负责定位并加载 ONS 引擎（onsyuri）的运行库。
 *
 * 引擎版本以 nativeplugin 插件目录下的子目录区分：
 * `engine_plugins/ons/current/arm64-v8a/` 是基础版本，`.../v0.7.7/` 是可选新版本。
 * 支持多版本并存的理由是出问题时能立刻切回旧版本，不必重新打包 APK。
 *
 * 关于 libONSPatch.so：它是对 libonsyuri.so 做单点 inline hook 的补丁库
 * （改写一条 BL 指令，靠运行期计算的绝对地址定位，不依赖符号名）。
 * 因此它与具体的 onsyuri 版本是绑定关系——换引擎版本后 patch 可能打偏。
 * [NativePluginConstants.ONS_PATCH_COMPATIBLE_VERSION] 显式声明它只对哪个版本生效，
 * 其他版本一律跳过加载，避免把指令写到错误位置导致硬崩。
 */
object OnsLibLoader {
    private const val TAG = "OnsLibLoader"

    /** 与 OnsSettings 共用同一份 prefs 文件，但键独立，互不覆盖。 */
    private const val PREF_NAME = OnsSettings.PREF_NAME
    private const val KEY_ENGINE_VERSION = "engine_version"

    private var loaded = false

    /** 本次进程实际加载成功的版本，供 ONScripter 定位 main so 路径。 */
    private var loadedVersion: String? = null

    /**
     * 读取用户选择的引擎版本，非法值一律回落到默认版本。
     * 返回的是插件目录名（如 "v0.7.7"），不是 Yuri_0.7.7 这种显示名。
     */
    @JvmStatic
    fun getSelectedVersion(context: Context): String {
        return try {
            val value = prefs(context).getString(KEY_ENGINE_VERSION, null)
            if (value != null && NativePluginConstants.ONS_AVAILABLE_VERSIONS.contains(value)) {
                value
            } else {
                NativePluginConstants.ONS_AVAILABLE_VERSIONS.first()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read engine version failed", t)
            NativePluginConstants.ONS_AVAILABLE_VERSIONS.first()
        }
    }

    /** 写入用户选择的引擎版本；未知版本直接拒绝，避免把无效值写进 prefs。 */
    @JvmStatic
    fun setSelectedVersion(context: Context, version: String?) {
        if (version.isNullOrBlank()) return
        if (!NativePluginConstants.ONS_AVAILABLE_VERSIONS.contains(version)) {
            Log.w(TAG, "reject unknown engine version: $version")
            return
        }
        try {
            prefs(context).edit().putString(KEY_ENGINE_VERSION, version).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "save engine version failed", t)
        }
    }

    /**
     * 返回本次已加载的版本。加载前调用则返回用户选择的版本。
     * ONScripter 用它拼 main shared object 路径，必须与实际加载的一致。
     */
    @JvmStatic
    @Synchronized
    fun getActiveVersion(context: Context): String = loadedVersion ?: getSelectedVersion(context)

    /**
     * 加载用户选择的引擎版本；失败时按 [NativePluginConstants.ONS_AVAILABLE_VERSIONS]
     * 顺序回退，避免新版 so 在个别设备上加载失败时整个 ONS 功能不可用。
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    @JvmStatic
    fun load(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        copyAssetFile(app, "DroidSansFallback.ttf", File(app.filesDir, "DroidSansFallback.ttf"))

        val preferred = getSelectedVersion(app)
        if (tryLoadVersion(app, preferred)) return

        for (fallback in NativePluginConstants.ONS_AVAILABLE_VERSIONS) {
            if (fallback == preferred) continue
            Log.w(TAG, "fallback to engine version $fallback")
            if (tryLoadVersion(app, fallback)) {
                // 记住可用版本，下次直接用，不必每次都撞一遍失败的版本。
                setSelectedVersion(app, fallback)
                return
            }
        }
        throw IllegalStateException("no usable onsyuri engine version")
    }

    /**
     * 尝试加载指定版本。
     *
     * 注意：System.load 无法卸载，所以某个版本一旦加载成功就不能再换另一个版本；
     * 这也是回退只在「加载失败」而不是「运行出错」时生效的原因。
     */
    private fun tryLoadVersion(app: Context, version: String): Boolean {
        return when (val result = NativeLibraryLoader.loadOns(app, version)) {
            is NativeLibraryLoader.OnsLoadResult.Success -> {
                loadPatchIfCompatible(version)
                loaded = true
                loadedVersion = version
                Log.i(TAG, "engine ready: $version (${result.mainSharedObject})")
                true
            }

            is NativeLibraryLoader.OnsLoadResult.NothingLoaded -> {
                // 一个 so 都没载入，本进程换版本重试是安全的。
                Log.w(TAG, "engine version unavailable: $version (${result.reason})")
                false
            }

            is NativeLibraryLoader.OnsLoadResult.PartialLoad -> {
                // 已载入部分 so，本进程不能再加载别的版本：System.load 无法卸载，
                // 而各版本 so 的 DT_SONAME 相同，继续加载会让 DT_NEEDED 解析到先载入
                // 的映像，形成混合版本的运行库。
                // 改为记录下一个候选版本，交由**新的引擎进程**重试。
                val next = nextVersionAfter(version)
                if (next != null) {
                    Log.w(
                        TAG,
                        "partial load of $version (${result.loadedLibs.size} libs); " +
                            "switch to $next on next launch",
                    )
                    setSelectedVersion(app, next)
                }
                throw IllegalStateException(
                    "ONS engine $version partially loaded; retry in a new process",
                    result.cause,
                )
            }
        }
    }

    /** 加载顺序里 [current] 之后的下一个候选版本；没有则返回 null。 */
    private fun nextVersionAfter(current: String): String? {
        val index = NativePluginConstants.ONS_AVAILABLE_VERSIONS.indexOf(current)
        if (index < 0) return null
        return NativePluginConstants.ONS_AVAILABLE_VERSIONS.drop(index + 1).firstOrNull()
    }

    /**
     * ONSPatch 只在版本匹配时加载。
     * 它靠硬编码偏移改写 libonsyuri.so 指令，版本不符时会写到错误位置，
     * 后果是运行中随机崩溃且难以排查，所以宁可不加载。
     */
    private fun loadPatchIfCompatible(version: String) {
        if (NativePluginConstants.ONS_PATCH_COMPATIBLE_VERSION != version) {
            Log.i(
                TAG,
                "skip ONSPatch: only verified for " +
                    NativePluginConstants.ONS_PATCH_COMPATIBLE_VERSION + ", current=" + version,
            )
            return
        }
        try {
            System.loadLibrary("ONSPatch")
            Log.i(TAG, "ONSPatch loaded for $version")
        } catch (t: Throwable) {
            Log.w(TAG, "load ONSPatch failed, continue", t)
        }
    }

    /** 当前激活版本的 libonsyuri.so（主入口）文件，缺插件时抛异常交给上层提示。 */
    @JvmStatic
    fun getMainSharedObject(context: Context): File {
        val version = getActiveVersion(context)
        val libPath = NativePluginManager.onsLibPath(context, NativePluginConstants.LIB_ONSYURI, version)
            ?: throw IllegalStateException("ONS plugin missing libonsyuri.so for $version")
        val main = File(libPath)
        if (!main.isFile) {
            throw IllegalStateException("ONS plugin missing libonsyuri.so for $version")
        }
        return main
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun copyAssetFile(context: Context, asset: String, out: File): File {
        try {
            val parent = out.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "mkdir failed: $parent")
            }
            if (!out.exists() || out.length() <= 0) {
                context.assets.open(asset).use { input ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            fos.write(buf, 0, n)
                        }
                    }
                }
            }
            Os.chmod(out.absolutePath, 384)
        } catch (t: Throwable) {
            throw RuntimeException("copy asset failed: $asset", t)
        }
        return out
    }
}