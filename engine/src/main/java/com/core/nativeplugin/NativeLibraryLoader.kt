package com.core.nativeplugin

import android.content.Context
import android.util.Log

/**
 * Loads Kirikiroid2 native libraries from the installed zip plugin directory.
 *
 * Return values are absolute `libgame*.so` paths and must be passed unchanged to
 * NativeBridge so the C++ bridge can dlopen the same file.
 */
object NativeLibraryLoader {
    private const val TAG = "NativeLibraryLoader"
    private val loadedPaths = LinkedHashSet<String>()

    /**
     * 本进程已加载过的 ONS 引擎 so（不论来自哪个版本目录）。
     *
     * 用于判断"能否在同一进程内换版本重试"：System.load 无法卸载，而各版本目录下的
     * libSDL2.so / liblua.so / libonsyuri.so 使用相同的 DT_SONAME，一旦载入过其中任意
     * 一个，再加载另一版本会让 DT_NEEDED 解析到先载入的映像，形成混合版本的运行库。
     */
    private val onsLoadedPaths = LinkedHashSet<String>()

    /** ONS 引擎 so 的单次加载结果。 */
    sealed class OnsLoadResult {
        /** 全部 so 加载成功。 */
        class Success(val mainSharedObject: String) : OnsLoadResult()

        /** 本次一个 so 都没载入，调用方可以安全地在同进程内换版本重试。 */
        class NothingLoaded(val reason: String) : OnsLoadResult()

        /**
         * 已载入部分 so 之后失败。此时**不可**在同进程内换版本重试，
         * 必须换新的引擎进程（见 [OnsLibLoader.load]）。
         */
        class PartialLoad(val loadedLibs: List<String>, val cause: Throwable?) : OnsLoadResult()
    }

    @JvmStatic
    fun loadKirikiroid139(context: Context): String? {
        val sdl2 = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_SDL2) ?: return null
        val ffmpeg = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_FFMPEG) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_GAME_139) ?: return null
        loadPath(sdl2)
        loadPath(ffmpeg)
        loadPath(game)
        return game
    }

    @JvmStatic
    fun loadKirikiroid134(context: Context): String? {
        val ffmpeg = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_FFMPEG) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_GAME_134) ?: return null
        loadPath(ffmpeg)
        loadPath(game)
        return game
    }

    @JvmStatic
    fun loadKirikiroid126(context: Context): String? {
        val ffmpeg = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_FFMPEG) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_GAME_126) ?: return null
        loadPath(ffmpeg)
        loadPath(game)
        return game
    }

    /**
     * 按版本加载 ONS 引擎 so。
     *
     * 返回值区分「一个都没载入」与「载入了一半」，调用方据此决定能否在同进程内换版本：
     * System.load 无法卸载，各版本 so 的 DT_SONAME 又相同，部分载入后再加载另一版本会
     * 得到混合版本的运行库。
     *
     * @param version 版本目录名（如 "v0.7.7"），null 表示基础版本。
     */
    @JvmStatic
    fun loadOns(context: Context, version: String?): OnsLoadResult {
        // 先一次性预检全部 .so：任一缺失即整体失败。此时尚未执行任何 System.load，
        // 是否可回退由 onsLoadedPaths 决定（可能已被更早的加载填充）。
        val paths = ArrayList<String>(NativePluginConstants.ONS_REQUIRED_LIBS.size)
        for (lib in NativePluginConstants.ONS_REQUIRED_LIBS) {
            val path = NativePluginManager.onsLibPath(context, lib, version)
                ?: return failureResult("missing $lib", null, emptyList())
            paths.add(path)
        }

        val loadedNow = ArrayList<String>(paths.size)
        for (path in paths) {
            try {
                loadOnsPath(path)
            } catch (t: Throwable) {
                Log.w(TAG, "load ons engine $version failed after ${loadedNow.size} libs", t)
                return failureResult("System.load failed: ${t.message}", t, loadedNow)
            }
            loadedNow.add(path)
        }
        // ONS_REQUIRED_LIBS 末尾即 libonsyuri.so（主入口）。
        return OnsLoadResult.Success(paths.last())
    }

    /**
     * 依据「本进程是否已经载入过任意 ONS so」决定失败语义。
     * 只要载入过（本次或更早），就必须换新进程重试，否则会混版本。
     */
    private fun failureResult(
        reason: String,
        cause: Throwable?,
        loadedNow: List<String>,
    ): OnsLoadResult = if (onsLoadedPaths.isEmpty()) {
        OnsLoadResult.NothingLoaded(reason)
    } else {
        OnsLoadResult.PartialLoad(loadedNow, cause)
    }

    @Synchronized
    private fun loadPath(path: String) {
        if (path !in loadedPaths) {
            System.load(path)
            loadedPaths.add(path)
        }
    }

    /** ONS 专用加载：额外记录到 [onsLoadedPaths]，用于判断能否同进程换版本。 */
    @Synchronized
    private fun loadOnsPath(path: String) {
        val alreadyLoaded = path in loadedPaths
        loadPath(path)
        if (!alreadyLoaded) onsLoadedPaths.add(path)
    }
}
