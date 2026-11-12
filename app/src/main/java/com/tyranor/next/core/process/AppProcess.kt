package com.tyranor.next.core.process

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/** 统一判断当前是否为 App 主进程，避免 Application 与游戏库各自复制兼容逻辑。 */
internal object AppProcess {
    /**
     * [unknownIsMainProcess] 仅用于启动门控：引导初始化宁可按主进程继续，也不能静默跳过。
     * 游戏库等主进程专属资源应传 false，避免低版本 ROM 无法枚举进程时误在子进程初始化。
     */
    fun isMain(context: Context, unknownIsMainProcess: Boolean = false): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName() == app.packageName
        }
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val processName = manager?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
        // API 26/27 在部分 ROM 上无法提供进程列表，由调用方决定未知状态的安全策略。
        return processName?.let { it == app.packageName } ?: unknownIsMainProcess
    }
}
