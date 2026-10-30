package com.tyranor.next

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PrefsRenameMigration
import com.tyranor.next.core.updater.BackgroundUpdateWorker
import com.tyranor.next.core.updater.GitHubUpdateChecker
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
        GitHubUpdateChecker.restoreState(this)
        // 游戏库 Room 迁移检查 + 首页缓存预热，避免主线程首次读库阻塞（迁移方案阶段 0）。
        GameLibraryRepository.init(this)
        // 游戏页的排序与过滤在 UI 可交互之前灌进内存态：
        // 1) 放到 MainScreen 的 effect 里做的话，初始化读到的旧值可能在用户已经点过筛选之后
        //    才写回，把新选择覆盖掉；
        // 2) 排序也要同步就位，否则进程重建时库可能先加载完、排序仍是默认值，游戏页会把
        //    「排序刚就位」误判成条件变化而重置滚动位置。
        // 引擎子进程不展示游戏页，跳过这两次 prefs 读取。
        if (isMainProcess()) {
            AppSettingsStore.initGameFilters(this)
            AppSettingsStore.initGameSort(this)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * 引擎子进程（:tyrano/:rpgmaker 等）复用同一 Application 类，设置只在主进程预热。
     *
     * API 28 以下没有 `Application.getProcessName()`，只能查 `runningAppProcesses`：必须比较
     * processName，`pid == myPid()` 对任何进程都成立。列表拿不到时按主进程处理——多读一次 prefs
     * 只是浪费，而误判成子进程会让主进程跳过初始化，用户保存的排序/筛选就永久回落默认值。
     */
    private fun isMainProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName() == packageName
        }
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val processes = am.runningAppProcesses ?: return true
        val self = processes.firstOrNull { it.pid == Process.myPid() } ?: return true
        return self.processName == packageName
    }

    override fun onStop(owner: LifecycleOwner) {
        BackgroundUpdateWorker.enqueue(this)
    }
}
