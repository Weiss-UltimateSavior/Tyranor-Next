package com.tyranor.next.core.settings

import android.content.Context
import com.core.engine.EnginePrefs

/**
 * 共享 prefs 文件一次性迁移：yukihub_prefs → tyranor_prefs（文件更名）。
 *
 * 必须在**所有进程**（含 :tyrano/:kirikiri2 等引擎子进程，它们同样读取该文件）的
 * Application.onCreate 最早时机执行，早于任何 EngineSettingsStore/引擎偏好读取。
 *
 * 幂等策略：以独立标记键 [MIGRATED_MARKER] 判断是否已迁移，而非「目标文件非空」——
 * 升级场景下任何组件（如引擎子进程先写入了版本号）都可能抢在迁移前往目标文件写键，
 * 按「目标非空即跳过」会让旧文件里的全部引擎设置整批静默丢失。合并语义为「目标缺失的键
 * 才拷贝」：已迁移后再次执行不会覆盖用户在目标文件中的新值。旧文件保留不删，
 * 回滚旧版本仍可读取（与本仓库迁移方案的回滚约定一致）。
 */
object PrefsRenameMigration {

    private const val LEGACY_PREFS = "yukihub_prefs"
    private const val MIGRATED_MARKER = "prefs_rename_migrated_v1"

    fun migrate(context: Context) {
        val app = context.applicationContext
        try {
            val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val target = app.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
            if (target.getBoolean(MIGRATED_MARKER, false)) return
            val editor = target.edit()
            for ((key, value) in computeMerge(legacy.all, target.all.keys)) {
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
            editor.putBoolean(MIGRATED_MARKER, true).commit()
        } catch (_: Throwable) {
            // 迁移失败不阻断启动：目标 prefs 缺失键时各设置走默认值，下次启动重试
        }
    }

    /** 纯函数核心：按键合并——目标缺失的键才从旧文件拷入，已有的键（含升级后新写入值）不覆盖。 */
    internal fun computeMerge(legacy: Map<String, Any?>, targetExistingKeys: Set<String>): Map<String, Any?> =
        legacy.filterKeys { it !in targetExistingKeys }
}
