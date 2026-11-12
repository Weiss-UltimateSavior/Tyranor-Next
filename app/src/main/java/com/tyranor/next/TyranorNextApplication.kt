package com.tyranor.next

import android.app.Application
import android.os.Build
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import bridge.PrimaryStorageRoot
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.SystemNnnCleanupQueue
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.process.AppProcess
import com.tyranor.next.core.game.save.sync.SaveSyncRuntime
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.AppIconSwitcher
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.PrefsRenameMigration
import com.tyranor.next.core.updater.BackgroundUpdateWorker
import com.tyranor.next.core.updater.GitHubUpdateChecker
import com.tyranor.next.core.updater.UpdateNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 在整个应用进入后台时安排一次静默更新检查。 */
class TyranorNextApplication : Application(), DefaultLifecycleObserver, Configuration.Provider {
    private var libraryInitialized = false
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super<Application>.onCreate()
        // 进程视角主存储挂载根尽早解析并缓存（issue #152）：每个进程（含引擎子进程）的挂载
        // 命名空间独立，StorageManager 视角 per-user 正确；解析失败由内部回退链兜底，不阻断启动。
        PrimaryStorageRoot.resolve(this)
        SaveSyncRuntime.install(this)
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

    /** 主线程调用；引导标记落盘失败不阻断应用启动，快照仍用于本次启动判定。 */
    private fun initializeStartup() {
        val mainProcess = AppProcess.isMain(this, unknownIsMainProcess = true)
        if (mainProcess) {
            // commit 失败时 OnboardingPreferences 保留基于旧数据的内存快照：存量用户仍直接进入主界面，
            // 新安装用户仍显示引导；下一次启动或完成引导时会再次尝试落盘。
            AppSettingsStore.initializeOnboarding(this)
            // 启动器图标别名与设置对齐（升级、清除数据或上次切换失败时兜底）；幂等，无变化不写。
            AppIconSwitcher.apply(this, AppSettingsStore.isClassicIconEnabled(this))
        }
        // 共享 prefs 文件更名（yukihub_prefs → tyranor_prefs）：所有进程（含引擎子进程）
        // 启动最早时机一次性迁移，必须先于任何 EngineSettingsStore/引擎偏好读取。
        PrefsRenameMigration.migrate(this)
        // 被删的旧 Winlator 设置实现遗留键清理（一次性，标记位保证只跑一次）。
        EngineSettingsStore.cleanupLegacyWinlatorKeys(this)
        UpdateNotificationManager.createChannel(this)
        GitHubUpdateChecker.restoreState(this)
        // 游戏库 Room 迁移检查 + 首页缓存预热在启动阶段触发，避免首帧 SettingsScreen 同步开库。
        if (mainProcess) {
            initializeMainLibrary()
            // 上次删除游戏/目录或重扫丢弃条目时未完成的副本回收在这里续做；引擎侧不保存这些路径。
            if (!SystemNnnCleanupQueue.isEmpty(this)) background.launch {
                runCatching { GameSaveManager(this@TyranorNextApplication).drainSystemNnnCleanupQueue() }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** 主线程调用；进程内只预热一次。 */
    private fun initializeMainLibrary() {
        if (libraryInitialized) return
        libraryInitialized = true
        AppSettingsStore.initGameConditions(this)
        GameLibraryRepository.init(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        SaveSyncRuntime.onForeground(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        SaveSyncRuntime.onBackground(this)
        if (AppSettingsStore.isOnboardingCompleted(this)) BackgroundUpdateWorker.enqueue(this)
    }

}
