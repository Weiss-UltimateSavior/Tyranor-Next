package com.tyranor.next

import android.app.Application
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import bridge.PrimaryStorageRoot
import com.tyranor.next.core.game.launch.LaunchOrientationMonitor
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.process.AppProcess
import com.tyranor.next.core.game.save.sync.SaveSyncRuntime
import com.tyranor.next.core.game.playtime.GamePlaytimeRepository
import com.tyranor.next.core.game.playtime.GamePlaytimeRuntime
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.AppIconSwitcher
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.BackgroundImageCache
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
        runCatching { GamePlaytimeRuntime.install(this) }
            .onFailure { Log.w("GamePlaytimeRuntime", "Could not install playtime tracking", it) }
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
        val strictMainProcess = AppProcess.isMain(this, unknownIsMainProcess = false)
        if (strictMainProcess) {
            LaunchOrientationMonitor.install(this)
        }
        if (mainProcess) {
            // commit 失败时 OnboardingPreferences 保留基于旧数据的内存快照：存量用户仍直接进入主界面，
            // 新安装用户仍显示引导；下一次启动或完成引导时会再次尝试落盘。
            AppSettingsStore.initializeOnboarding(this)
            AppSettingsStore.initDefaultThemeParticles(this)
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
        if (strictMainProcess) {
            initializeMainLibrary()
            // 隐私设置与窗口级效果（FLAG_SECURE / 通知隐藏）在页面创建前就要读到正确值；
            // 应用锁只装主进程（引擎宿主为独立进程，不在锁定范围）。
            AppSettingsStore.initPrivacySettings(this)
            // 自定义背景图（URI/透明度/模糊）要在首帧前就绪，否则冷启动会先画默认背景再淡入
            AppSettingsStore.initBackgroundImage(this)
            // 启动即预热背景图：解码完成后所有页面同步可用（冷启动首帧可能早于解码完成，
            // 此时背景为空、解码完成后自动出现；只有锁门会等待就绪以对齐首帧）
            val backgroundUri = AppSettingsStore.getBackgroundImageUri(this)
            if (backgroundUri.isNotBlank()) {
                background.launch {
                    try {
                        BackgroundImageCache.ensureLoaded(applicationContext, backgroundUri)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        // 取消不是解码失败，必须原样向上传播（取消语义）
                        throw cancelled
                    } catch (_: Exception) {
                        // 解码失败按未就绪处理：进入页面时 ensureLoaded 会再次尝试
                    }
                }
            }
            AppLockController.install(this)
            // 上次删除游戏/目录或重扫丢弃条目时未完成的副本回收在这里续做；引擎侧不保存这些路径。
            background.launch {
                try {
                    GameSaveManager(this@TyranorNextApplication).drainSystemNnnCleanupQueue()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("SystemNNN", "Could not resume copy resource cleanup", failure)
                }
            }
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    /** 主线程调用；进程内只预热一次。 */
    private fun initializeMainLibrary() {
        if (libraryInitialized) return
        libraryInitialized = true
        AppSettingsStore.initGameConditions(this)
        GameLibraryRepository.init(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!AppProcess.isMain(this, unknownIsMainProcess = false)) return
        // 应用锁：回前台按解锁延迟档位判定是否上锁（未安装的子进程为空操作）
        AppLockController.onLauncherEntering()
        SaveSyncRuntime.onForeground(this)
        background.launch {
            GamePlaytimeRepository.refresh(this@TyranorNextApplication)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!AppProcess.isMain(this, unknownIsMainProcess = false)) return
        // 应用锁：退后台开始离开计时（熄屏由 AppLockController 自注册的广播驱动）
        AppLockController.onLauncherLeft()
        SaveSyncRuntime.onBackground(this)
        if (!AppSettingsStore.ONBOARDING_FEATURE_ENABLED ||
            AppSettingsStore.isOnboardingCompleted(this)
        ) {
            BackgroundUpdateWorker.enqueue(this)
        }
    }

    /** 内存紧张时优先释放整屏背景图（与封面缓存相比它只服务一个页面），回到图层时自动重解码。 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BackgroundImageCache.onTrimMemory(level)
    }

}
