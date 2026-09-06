package com.core.engine

import android.content.Context
import java.io.File

/**
 * 引擎宿主会话登记（跨进程）。
 *
 * 每个引擎宿主 Activity 在 onCreate 时登记当前游戏目录、onDestroy 时清除；
 * 启动器在启动相反宿主前据此判断"同一游戏是否已在另一宿主运行"，仅在会话
 * 匹配时回收对方进程——避免按进程名后缀误杀后台无关游戏（PR #73 review 意见）。
 *
 * 用文件而非 SharedPreferences：launcher 主进程长驻，SharedPreferences 的进程内
 * 缓存不会感知引擎子进程的写入；文件读取始终是最新值。进程被强杀时条目可能
 * 残留，由读取方结合进程存活状态兜底（launch 前宿主进程必为后台态）。
 */
object EngineSessionRegistry {
    const val HOST_TYRANO = "tyrano"
    const val HOST_RPGMAKER = "rpgmaker"

    private fun sessionFile(context: Context, host: String): File =
        File(File(context.filesDir, "engine_sessions"), "$host.session")

    /** 登记宿主当前游戏目录（重复启动同一游戏时覆盖为最新值）。 */
    @JvmStatic
    fun record(context: Context, host: String, gameDir: String?) {
        if (gameDir.isNullOrBlank()) return
        runCatching {
            val f = sessionFile(context, host)
            f.parentFile?.mkdirs()
            f.writeText(gameDir)
        }
    }

    /** 清除宿主登记（onDestroy；重复清除安全）。 */
    @JvmStatic
    fun clear(context: Context, host: String) {
        runCatching { sessionFile(context, host).delete() }
    }

    /** 读取宿主当前登记的游戏目录；未登记返回 null。 */
    @JvmStatic
    fun currentGame(context: Context, host: String): String? = runCatching {
        sessionFile(context, host).takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotBlank)
    }.getOrNull()
}
