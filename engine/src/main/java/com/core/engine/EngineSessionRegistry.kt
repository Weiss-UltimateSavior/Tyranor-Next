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
    const val HOST_ETHORNELL = "ethornell"

    /** Conservative cross-process liveness, scoped to our UID and exact host process name. */
    @JvmStatic
    fun isHostAlive(context: Context, host: String): Boolean {
        if (host !in setOf(HOST_TYRANO, HOST_RPGMAKER, HOST_ETHORNELL)) return false
        return runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return@runCatching false
            manager.runningAppProcesses.orEmpty().any {
                it.uid == android.os.Process.myUid() && it.processName == "${context.packageName}:$host"
            }
        }.getOrDefault(false)
    }

    /** A leftover file after LMK/crash is not an active session. This never kills any process. */
    @JvmStatic
    fun currentLiveGame(context: Context, host: String): String? =
        currentGame(context, host)?.takeIf { game ->
            if (host != HOST_ETHORNELL) return@takeIf isHostAlive(context, host)
            runCatching {
                val owner = File(sessionFile(context, host).path + ".owner").readLines()
                if (owner.size != 3 || owner[2] != game) return@runCatching false
                val pid = owner[0].toIntOrNull() ?: return@runCatching false
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    ?: return@runCatching false
                manager.runningAppProcesses.orEmpty().any {
                    it.pid == pid && it.uid == android.os.Process.myUid() &&
                        it.processName == "${context.packageName}:$host"
                } && processStart(pid) == owner[1]
            }.getOrDefault(false)
        }

    /** /proc starttime distinguishes PID reuse after a killed engine session. No paths are logged. */
    private fun processStart(pid: Int): String? = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ').getOrNull(19)
    }.getOrNull()

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
            if (host == HOST_ETHORNELL) {
                val pid = android.os.Process.myPid()
                val start = processStart(pid) ?: return@runCatching
                val owner = File(f.path + ".owner")
                val temp = File(f.path + ".owner.tmp")
                temp.writeText("$pid\n$start\n$gameDir")
                if (!temp.renameTo(owner)) temp.delete()
            }
        }
    }

    /** 清除宿主登记（onDestroy；重复清除安全）。 */
    @JvmStatic
    fun clear(context: Context, host: String) {
        runCatching {
            val file = sessionFile(context, host)
            file.delete()
            if (host == HOST_ETHORNELL) File(file.path + ".owner").delete()
        }
    }

    /** 读取宿主当前登记的游戏目录；未登记返回 null。 */
    @JvmStatic
    fun currentGame(context: Context, host: String): String? = runCatching {
        sessionFile(context, host).takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotBlank)
    }.getOrNull()
}
