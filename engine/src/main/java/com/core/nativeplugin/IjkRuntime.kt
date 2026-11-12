package com.core.nativeplugin

import android.content.Context
import android.util.Log
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 从引擎运行库包按绝对路径加载 IJK 三件套。
 *
 * IJK 与 Siglus / FVP 等运行库一样移出了 APK 的 `lib/`；`IjkMediaPlayer.loadLibrariesOnce`
 * 是官方的加载入口且带 `mIsLibLoaded` 幂等保护，重复调用无副作用。
 * 供 ONS 的视频覆盖层与 Artemis 的 `VideoViewActivity` 共用；加载失败时由调用方按
 * 既有的视频失败路径收场（不阻塞游戏本体）。
 */
object IjkRuntime {

    private const val TAG = "IjkRuntime"
    private val LIBRARIES = listOf("ijkffmpeg", "ijksdl", "ijkplayer")

    @JvmStatic
    @Synchronized
    fun load(context: Context): Boolean {
        val app = context.applicationContext
        val paths = LIBRARIES.associateWith { name ->
            NativeRuntimePack.libraryPath(app, NativeRuntimePack.PACK_IJK, "lib$name.so")
        }
        val missing = paths.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            Log.w(TAG, "IJK runtime pack incomplete: $missing")
            return false
        }
        return runCatching {
            IjkMediaPlayer.loadLibrariesOnce { name ->
                val path = paths[name] ?: throw UnsatisfiedLinkError("IJK library not packaged: $name")
                System.load(path)
            }
            true
        }.getOrElse { error ->
            Log.w(TAG, "IJK library load failed", error)
            false
        }
    }
}
