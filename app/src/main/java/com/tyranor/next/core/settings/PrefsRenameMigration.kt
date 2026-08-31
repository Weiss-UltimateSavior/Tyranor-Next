package com.tyranor.next.core.settings

import android.content.Context
import com.core.engine.EnginePrefs

/**
 * 共享 prefs 文件一次性迁移：yukihub_prefs → tyranor_prefs（文件更名）。
 *
 * 必须在**所有进程**（含 :tyrano/:kirikiri2 等引擎子进程，它们同样读取该文件）的
 * Application.onCreate 最早时机执行，早于任何 EngineSettingsStore/引擎偏好读取。
 * 幂等策略：目标文件已有任意键视为已迁移；旧文件保留不删，回滚旧版本仍可读取
 * （与本仓库迁移方案的回滚约定一致）。两进程同时首启会各自拷贝同一份旧内容，结果一致。
 */
object PrefsRenameMigration {

    private const val LEGACY_PREFS = "yukihub_prefs"

    fun migrate(context: Context) {
        val app = context.applicationContext
        try {
            val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val target = app.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
            if (legacy.all.isEmpty() || target.all.isNotEmpty()) return
            val editor = target.edit()
            for ((key, value) in legacy.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                }
            }
            // 同步落盘：引擎子进程可能在启动早期读取；旧文件保留至少一个版本便于回滚
            editor.commit()
        } catch (_: Throwable) {
            // 迁移失败不阻断启动：目标 prefs 为空时各设置走默认值，下次启动重试
        }
    }
}
