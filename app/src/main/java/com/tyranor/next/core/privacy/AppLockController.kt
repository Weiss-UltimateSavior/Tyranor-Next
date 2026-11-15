package com.tyranor.next.core.privacy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用锁运行时（进程级；仅主进程安装）：启动器界面离开前台（退后台 / 熄屏）后计时，
 * 回前台时按解锁延迟档位判定是否需要先解锁。锁定状态经 [lockedState] 广播，
 * 由 `ui/common/privacy` 的 PrivacyLockGate 渲染解锁门。
 *
 * 触发口径采用进程级方案：**进程级前后台 + 进程级 requireUnlock**——应用退到后台
 * （ProcessLifecycleOwner.onStop）即开始计时，回前台（onStart）按延迟档判定；
 * **进程冷启动一律要求解锁**（进程被杀后重新打开先解锁）。
 * 不做逐 Activity / 逐路径的启发式判断：立即档（默认）下任何退后台都锁——
 * 路径选择器等系统界面往返同样计入，这是应用锁的标准语义；不想被打扰就选 1/5/15 分钟。
 * 熄屏（SCREEN_OFF 广播）无论原因都开始计时；亮屏不改变 Activity 生命周期，由广播驱动判定。
 *
 * 锁定范围仅启动器界面：引擎宿主为独立进程（不安装本控制器），运行中的游戏不受影响；
 * 从游戏回到启动器同样视为「回前台」按延迟档位判定（启动器曾被退后台）。
 */
object AppLockController {

    private val mutableLocked = MutableStateFlow(false)

    /** 当前是否要求解锁（true 时启动器界面的所有窗口渲染解锁门）。 */
    val lockedState: StateFlow<Boolean> = mutableLocked.asStateFlow()

    private var installed = false
    private var appContext: Context? = null

    /**
     * 是否需要在本进程首个「回前台」时重查一次图案凭据。
     *
     * `install()` 在 Application.onCreate 极早期读取 Keystore，冷启动时可能瞬时失败而被判成
     * 「无凭据」→ 锁被 fail-open（宁可不锁也不能把人锁在外面）。首个 onLauncherEntering 时
     * Keystore 已就绪，重查一次即可把这种瞬时失败纠正回来。
     */
    private var recheckPatternOnEnter = false

    /** 是否处于「已离开启动器」状态；进程冷启动不视为离开（首次启动不上锁）。 */
    private var away = false

    /** 离开前台的时间戳（`SystemClock.elapsedRealtime()`；0 = 不在离开计时中）。 */
    private var leftAtMillis = 0L

    /** 主进程 Application 启动阶段调用（幂等）：注册熄屏 / 亮屏广播，并恢复进程级锁定状态。 */
    fun install(application: Application) {
        synchronized(this) {
            if (installed) return
            installed = true
            appContext = application.applicationContext
            // 进程冷启动（被杀后重开）先要求解锁；锁未启用或没有图案凭据时不上锁
            val enabled = AppSettingsStore.isPrivacyAppLockEnabled(application)
            val hasPattern = AppLockCredentialStore.hasPattern(application)
            mutableLocked.value = enabled && hasPattern
            // 启用中但没有凭据 → 可能是 Keystore 瞬时异常，首个回前台时重查
            recheckPatternOnEnter = enabled && !hasPattern
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> onScreenOff()
                        // 亮屏不等于回到前台：只做「是否该上锁」的窥视判定，
                        // 不消费离开计时器（否则在后台期间熄屏再亮屏会把计时清零，
                        // 之后真正回前台时因 !away 早退而永不上锁，1/5/15 分钟档形同虚设）
                        Intent.ACTION_SCREEN_ON -> peekLockOnScreenOn()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                application.registerReceiver(receiver, filter)
            }
        }
    }

    /**
     * 熄屏：无论是否用户主动（电源键 / 自动熄屏）都开始离开计时。
     * 已经处于离开状态时不覆盖起点：从后台熄屏再亮屏、反复熄屏亮屏都要保留**最早**离开时刻，
     * 否则计时会被反复顺延，延迟档被绕过。
     */
    fun onScreenOff() {
        synchronized(this) {
            if (!installed) return
            if (away) return
            away = true
            leftAtMillis = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 亮屏窥视判定（SCREEN_ON）：不修改 [away] / [leftAtMillis]，仅在已离开、锁已启用且
     * 已超过延迟档时直接上锁。真实回前台的计时消费在 [onLauncherEntering]。
     */
    private fun peekLockOnScreenOn() {
        synchronized(this) {
            if (!installed || !away) return
            val context = appContext ?: return
            if (!AppSettingsStore.isPrivacyAppLockEnabled(context)) return
            if (!AppLockCredentialStore.hasPattern(context)) return
            val timeout = PrivacyLockTimeout.fromStorageIndex(
                AppSettingsStore.getPrivacyAppLockTimeoutIndex(context),
            )
            val elapsed = SystemClock.elapsedRealtime() - leftAtMillis
            if (shouldRequireUnlock(elapsed, timeout)) mutableLocked.value = true
        }
    }

    /** 启动器退后台（ProcessLifecycleOwner.onStop）：开始离开计时。未安装（引擎子进程）为空操作。 */
    fun onLauncherLeft() {
        synchronized(this) {
            if (!installed) return
            away = true
            leftAtMillis = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 启动器回到前台（仅 ProcessLifecycleOwner.onStart；亮屏走 peekLockOnScreenOn，不消费计时器）：
     * 按延迟档位判定是否上锁（并消费计时器）。
     *
     * 「锁已启用但没有凭据」统一按**关闭**处理：Keystore 在冷启动极早期读取失败时会
     * 造成这种状态，此时不能上锁（否则用户永远无法解锁），用户可在隐私设置重新设置图案。
     */
    fun onLauncherEntering() {
        synchronized(this) {
            if (!installed) return
            val context = appContext ?: return
            // 进程内首个回前台：纠正 install() 阶段可能的 Keystore 瞬时失败
            if (recheckPatternOnEnter) {
                recheckPatternOnEnter = false
                if (AppSettingsStore.isPrivacyAppLockEnabled(context) &&
                    AppLockCredentialStore.hasPattern(context)
                ) {
                    // 凭据补齐：按「刚离开」处理，让本次进入按延迟档判定
                    if (!away) {
                        away = true
                        leftAtMillis = SystemClock.elapsedRealtime()
                    }
                }
            }
            if (!away) return
            if (!AppSettingsStore.isPrivacyAppLockEnabled(context)) return
            if (!AppLockCredentialStore.hasPattern(context)) return
            val timeout = PrivacyLockTimeout.fromStorageIndex(
                AppSettingsStore.getPrivacyAppLockTimeoutIndex(context),
            )
            val elapsed = SystemClock.elapsedRealtime() - leftAtMillis
            away = false
            leftAtMillis = 0L
            if (shouldRequireUnlock(elapsed, timeout)) mutableLocked.value = true
        }
    }

    /** 解锁成功（图案或设备凭据）：解除锁定。 */
    fun unlock() {
        synchronized(this) {
            mutableLocked.value = false
            away = false
            leftAtMillis = 0L
        }
    }

    /**
     * 图案或开关变更后调用：丢弃进行中的离开计时，避免「设置途中离开过几分钟」
     * 的旧计时在下次回前台时立刻上锁。
     */
    fun resetTimer() {
        synchronized(this) {
            away = false
            leftAtMillis = 0L
        }
    }

    /**
     * 图案校验并解锁（UI 的唯一入口）：校验与状态变更都归本控制器，
     * 避免页面直接触碰凭据存储后再单独调 [unlock]，绕过门面。
     */
    fun verifyAndUnlock(context: Context, points: List<Int>): Boolean {
        if (!AppLockCredentialStore.verifyPattern(context, points)) return false
        unlock()
        return true
    }

    /**
     * 设置图案并启用应用锁（设置门确认后调用的唯一入口）：凭据保存成功才落开关，
     * 并丢弃进行中的计时；保存失败返回 false 且不改任何状态。
     */
    fun savePatternAndEnable(context: Context, points: List<Int>): Boolean {
        val saved = AppLockCredentialStore.savePattern(context, points)
        if (!saved) return false
        AppSettingsStore.setPrivacyAppLockEnabled(context, true)
        resetTimer()
        return true
    }

    /** 清除图案并关闭应用锁（设置页关闭开关或修改凭据失败回退时调用）。 */
    fun clearPatternAndDisable(context: Context) {
        AppSettingsStore.setPrivacyAppLockEnabled(context, false)
        AppLockCredentialStore.clearPattern(context)
        unlock()
    }
}
