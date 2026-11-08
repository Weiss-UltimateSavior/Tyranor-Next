package com.tyranor.next.core.process

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/** 统一判断当前是否为 App 主进程，避免 Application 与游戏库各自复制兼容逻辑。 */
internal object AppProcess {
    /**
     * [unknownIsMainProcess] 仅用于启动门控：引导初始化宁可按主进程继续，也不能静默跳过。
     * 游戏库等主进程专属资源应传 false，避免低版本 ROM 无法枚举进程时误在子进程初始化。
     */
    fun isMain(context: Context, unknownIsMainProcess: Boolean = false): Boolean {
        val app = context.applicationContext
        val processName = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
            }
        }.getOrNull()
        return matchesMainProcess(app.packageName, processName, unknownIsMainProcess) {
            readProcessName(File("/proc/self/cmdline"))
        }
    }

    /** A known platform name wins; old ROMs without a process list use the current process's cmdline. */
    internal fun matchesMainProcess(
        packageName: String,
        processName: String?,
        unknownIsMainProcess: Boolean,
        fallback: () -> String?,
    ): Boolean {
        val name = processName?.takeIf { it.isNotBlank() } ?: fallback()
        return name?.let { it == packageName } ?: unknownIsMainProcess
    }

    /** Reads only argv[0], stopping at NUL; command arguments never become part of the process identity. */
    internal fun readProcessName(cmdline: File): String? = runCatching {
        cmdline.inputStream().use { input ->
            val name = StringBuilder()
            repeat(256) {
                val next = input.read()
                if (next <= 0) return@use name.toString().takeIf { it.isNotBlank() }
                name.append(next.toChar())
            }
            // An overlong identity must not be mistaken for a matching truncated prefix.
            null
        }
    }.getOrNull()
}
