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
            val value = prefs(context).getString(NativePluginConstants.KEY_ONS_ENGINE_VERSION, null)
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
     *
     * 注意：引擎进程**不写回** prefs。「哪个版本可用」是用户的选择
     * （[NativePluginConstants.KEY_ONS_ENGINE_VERSION]，只由 App 写入），
     * 引擎擅自改写会被 App 进程的陈旧内存值反向覆盖，反而复活坏版本。
     * 因此这里只做「本次启动内」的回退，不做跨启动的版本记忆。
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    @JvmStatic
    fun load(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        copyAssetFile(app, "DroidSansFallback.ttf", File(app.filesDir, "DroidSansFallback.ttf"))

        // 尝试顺序：首选版本优先，其余版本按 ONS_AVAILABLE_VERSIONS 顺序回退。
        val preferred = getSelectedVersion(app)
        val attemptOrder = ArrayList<String>(NativePluginConstants.ONS_AVAILABLE_VERSIONS.size + 1)
        attemptOrder.add(preferred)
        for (version in NativePluginConstants.ONS_AVAILABLE_VERSIONS) {
            if (version != preferred) attemptOrder.add(version)
        }

        for (index in attemptOrder.indices) {
            val version = attemptOrder[index]
            if (index > 0) Log.w(TAG, "fallback to engine version $version")
            if (tryLoadVersion(app, version)) return
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
                // 的映像，形成混合版本的运行库。抛给上层走友好失败（SDLActivity 的
                // 错误对话框），由用户改用其他引擎版本后重启游戏。
                throw OnsEngineRetryRequiredException(version, result.loadedLibs.size, result.cause)
            }
        }
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

/** ONS 引擎加载失败的稳定错误码前缀，供上层分支判断。 */
private const val ONS_ENGINE_RETRY_ERROR_CODE = "ons_engine_retry_required"

/**
 * 需要在**新的引擎进程**中重试 ONS 引擎。
 *
 * 触发条件：本进程已经载入过部分 ONS so 之后加载失败。System.load 无法卸载，
 * 且各版本 so 的 DT_SONAME 相同，继续在本进程加载其他版本会让 DT_NEEDED 解析到
 * 先载入的映像，形成混合版本的运行库。
 *
 * 上层应依据异常类型或 [errorCode] 分支处理（并持久化下一个候选版本、重启引擎进程），
 * 不要解析 message 文本。
 */
class OnsEngineRetryRequiredException(
    /** 本次尝试并部分载入的引擎版本目录名。 */
    val engineVersion: String,
    /** 失败前已成功载入的 so 数量。 */
    val loadedLibCount: Int,
    cause: Throwable?,
) : IllegalStateException(
    "$ONS_ENGINE_RETRY_ERROR_CODE: ONS engine $engineVersion partially loaded " +
        "($loadedLibCount libs)",
    cause,
) {
    /** 稳定错误码，与 message 无关，可安全用于分支判断。 */
    val errorCode: String get() = ONS_ENGINE_RETRY_ERROR_CODE

    companion object {
        /** 稳定错误码常量。 */
        const val ERROR_CODE = ONS_ENGINE_RETRY_ERROR_CODE
    }
}
