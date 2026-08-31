package com.tyranor.next

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.PrefsRenameMigration
import com.tyranor.next.core.updater.BackgroundUpdateWorker
import com.tyranor.next.core.updater.UpdateNotificationManager

/** 在整个应用进入后台时安排一次静默更新检查。 */
class TyranorNextApplication : Application(), DefaultLifecycleObserver, Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super<Application>.onCreate()
        // 共享 prefs 文件更名（yukihub_prefs → tyranor_prefs）：所有进程（含引擎子进程）
        // 启动最早时机一次性迁移，必须先于任何 EngineSettingsStore/引擎偏好读取。
        PrefsRenameMigration.migrate(this)
        UpdateNotificationManager.createChannel(this)
        // 游戏库 Room 迁移检查 + 首页缓存预热，避免主线程首次读库阻塞（迁移方案阶段 0）。
        GameLibraryRepository.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        BackgroundUpdateWorker.enqueue(this)
    }
}
