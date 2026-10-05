package com.tyranor.next

import android.app.Application
import android.os.Build
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PrefsRenameMigration
import com.tyranor.next.core.updater.BackgroundUpdateWorker
import com.tyranor.next.core.updater.UpdateNotificationManager

/** 在整个应用进入后台时安排一次静默更新检查。 */
class TyranorNextApplication : Application(), DefaultLifecycleObserver, Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super<Application>.onCreate()
        // 多进程 WebView 数据目录隔离：主进程（Web 首页）与 :tyrano（Tyrano/VN/WebOther 游戏宿主，
        // 承载游戏 localStorage 存档，必须保持默认目录）在 API 28+ 不能共用默认目录，否则后初始化
        // 的进程创建 WebView 时直接抛异常。:rpgmaker 已在自身 Activity 设置 "rpgmaker" 后缀；
        // 本调用必须在主进程任何 WebView 实例创建之前（Application.onCreate 最早时机）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            Application.getProcessName() == packageName
        ) {
            runCatching { WebView.setDataDirectorySuffix("home") }
        }
        // 共享 prefs 文件更名（yukihub_prefs → tyranor_prefs）：所有进程（含引擎子进程）
        // 启动最早时机一次性迁移，必须先于任何 EngineSettingsStore/引擎偏好读取。
        PrefsRenameMigration.migrate(this)
        // 被删的旧 Winlator 设置实现遗留键清理（一次性，标记位保证只跑一次）。
        EngineSettingsStore.cleanupLegacyWinlatorKeys(this)
        UpdateNotificationManager.createChannel(this)
        // 游戏库 Room 迁移检查 + 首页缓存预热，避免主线程首次读库阻塞（迁移方案阶段 0）。
        GameLibraryRepository.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        BackgroundUpdateWorker.enqueue(this)
    }
}
