package com.tyranor.next

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.OnboardingWriteResult
import com.tyranor.next.core.settings.PrefsRenameMigration
import com.tyranor.next.core.updater.BackgroundUpdateWorker
import com.tyranor.next.core.updater.GitHubUpdateChecker
import com.tyranor.next.core.updater.UpdateNotificationManager

/** 在整个应用进入后台时安排一次静默更新检查。 */
class TyranorNextApplication : Application(), DefaultLifecycleObserver, Configuration.Provider {
    var startupReady = false
        private set
    private var libraryInitialized = false

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
        initializeStartup()
    }

    /** 主线程调用；初始标记写入失败时允许入口页重试，不执行会污染安装判定的迁移。 */
    fun initializeStartup(): Boolean {
        if (startupReady) return true
        if (isMainProcess() && AppSettingsStore.initializeOnboarding(this) != OnboardingWriteResult.SAVED) {
            return false
        }
        // 共享 prefs 文件更名（yukihub_prefs → tyranor_prefs）：所有进程（含引擎子进程）
        // 启动最早时机一次性迁移，必须先于任何 EngineSettingsStore/引擎偏好读取。
        PrefsRenameMigration.migrate(this)
        // 被删的旧 Winlator 设置实现遗留键清理（一次性，标记位保证只跑一次）。
        EngineSettingsStore.cleanupLegacyWinlatorKeys(this)
        UpdateNotificationManager.createChannel(this)
        GitHubUpdateChecker.restoreState(this)
        // 游戏库 Room 迁移检查 + 首页缓存预热，避免主线程首次读库阻塞（迁移方案阶段 0）。
        if (AppSettingsStore.isOnboardingCompleted(this)) initializeMainLibrary()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        startupReady = true
        return true
    }

    /** 主线程调用；保存期间重建后也从主界面入口补齐，进程内只预热一次。 */
    fun initializeMainLibrary() {
        if (libraryInitialized) return
        libraryInitialized = true
        GameLibraryRepository.init(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        if (AppSettingsStore.isOnboardingCompleted(this)) BackgroundUpdateWorker.enqueue(this)
    }

    private fun isMainProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return getProcessName() == packageName
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        return manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }
            ?.processName == packageName
    }
}
