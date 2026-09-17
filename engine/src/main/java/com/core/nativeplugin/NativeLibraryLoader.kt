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

    @JvmStatic
    fun loadOns(context: Context): String? = loadOns(context, null)

    /**
     * 按版本加载 ONS 引擎 so。
     *
     * @param version 版本目录名（如 "v0.7.7"），null 表示基础版本。
     * @return libonsyuri.so 的绝对路径；任一 so 缺失或加载失败时返回 null。
     */
    @JvmStatic
    fun loadOns(context: Context, version: String?): String? {
        // 先一次性预检全部 .so，任一缺失即整体失败，避免加载到一半无法回滚。
        val paths = NativePluginConstants.ONS_REQUIRED_LIBS.map { lib ->
            NativePluginManager.onsLibPath(context, lib, version) ?: return null
        }
        return try {
            paths.forEach { loadPath(it) }
            // ONS_REQUIRED_LIBS 末尾即 libonsyuri.so（主入口）。
            paths.last()
        } catch (t: Throwable) {
            Log.w(TAG, "load ons engine $version failed", t)
            null
        }
    }

    @Synchronized
    private fun loadPath(path: String) {
        if (path !in loadedPaths) {
            System.load(path)
            loadedPaths.add(path)
        }
    }
}
