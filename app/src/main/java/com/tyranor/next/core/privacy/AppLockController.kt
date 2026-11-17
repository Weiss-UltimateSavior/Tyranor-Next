package com.tyranor.next.core.privacy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * **已知边界（700ms 防抖窗口）**：ProcessLifecycleOwner 对 ON_STOP 有约 700ms 的延迟派发
 * （用于排除配置变更等瞬时停止），若在这段时间内回到前台，ON_STOP 会被取消，本次离开不计时。
 * 该窗口通常短于「按 Home 再切回」的往返耗时；它只影响返回后是否弹出解锁门，不改变切后台
 * 瞬间的任务快照（快照是否置空由安全屏幕 FLAG_SECURE 单独决定）；熄屏不走这条路径，始终计时。
 * 不改用 Activity.onPause 作为离开信号：切换二级页、系统权限弹窗 / 生物识别弹层同样会触发
 * onPause，会把应用内操作误记成离场，并让 1/5/15 分钟的离开时长从错误时刻起算。
 *
 * **主线程不读 Keystore**：`Application.onCreate`、ProcessLifecycleOwner.onStart 与
 * SCREEN_ON 都只消费内存中的 [credentialPresence] 缓存；三态读取在 IO 线程完成后再更新
 * StateFlow。启动时先按「有凭据」fail-closed 上锁，异步读到 ABSENT（备份恢复 / 数据被清）
 * 再回写关闭开关并放行，显示与实际保持一致。图案校验本身仍是阻塞调用，UI 层必须放到
 * IO 线程（见 PrivacyLockGate）。
 *
 * 锁定范围仅启动器界面：引擎宿主为独立进程（不安装本控制器），运行中的游戏不受影响；
 * 从游戏回到启动器同样视为「回前台」按延迟档位判定（启动器曾被退后台）。
 */
object AppLockController {

    private val mutableLocked = MutableStateFlow(false)

    /** 当前是否要求解锁（true 时启动器界面的所有窗口渲染解锁门）。 */
    val lockedState: StateFlow<Boolean> = mutableLocked.asStateFlow()

    private val mutableCredentialUnreadable = MutableStateFlow(false)

    /**
     * 图案凭据「存在但读取失败」（Keystore 瞬时异常）：锁门据此给出设备凭据恢复路径。
     * 读取失败时**保持锁定**（不能 fail-open），图案校验每次都会重新读取，恢复后即可正常解锁。
     */
    val credentialUnreadableState: StateFlow<Boolean> = mutableCredentialUnreadable.asStateFlow()

    private var installed = false
    private var appContext: Context? = null

    /** 凭据三态缓存（null = 尚未完成首次读取）；主线程的计时判定只读这里。 */
    private var credentialPresence: PatternCredentialState? = null

    /** 凭据状态代数：保存 / 清除 / 校验 / 设置页查询后递增，丢弃迟到的异步读取结果。 */
    private var credentialGeneration = 0L

    /** 是否有一次凭据三态读取在 IO 线程进行中：避免 onStart 连续触发时堆积重复读取。 */
    private var credentialReadInFlight = false

    /**
     * 是否需要在本进程首个「回前台」时异步重查一次图案凭据。
     *
     * `install()` 在 Application.onCreate 极早期发起异步读取，冷启动时可能瞬时失败。首个
     * onLauncherEntering 时再查一次即可把这种瞬时失败纠正回来（不阻塞主线程）。
     */
    private var recheckPatternOnEnter = false

    /** 是否处于「已离开启动器」状态；进程冷启动不视为离开（首次启动不上锁）。 */
    private var away = false

    /** 离开前台的时间戳（`SystemClock.elapsedRealtime()`；0 = 不在离开计时中）。 */
    private var leftAtMillis = 0L

    /** 离开前台的代数：每次从「在应用内」转为「已离开」时递增（被最早离开时刻守卫挡下的重复信号不递增）。 */
    private var leaveGeneration = 0L

    /**
     * 凭据操作 + 开关更新的串行锁：保存（修改图案）与清除（关开关 / 锁门重置）不能交错，
     * 否则最终可能停在「开关已开、凭据已被清掉」的状态，冷启动会因凭据不存在而放行。
     * 不并入 `synchronized(this)`：这里要持有整段阻塞的 Keystore 操作，会挡住计时状态同步。
     */
    private val credentialOpLock = Any()

    /** 凭据异步读取的进程级作用域（Application 生命周期，不需要取消）。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 主进程 Application 启动阶段调用（幂等）：注册熄屏 / 亮屏广播，并恢复进程级锁定状态。 */
    fun install(application: Application) {
        synchronized(this) {
            if (installed) return
            installed = true
            appContext = application.applicationContext
            val enabled = AppSettingsStore.isPrivacyAppLockEnabled(application)
            // 进程冷启动（被杀后重开）先要求解锁：这里不读 Keystore，按「有凭据」fail-closed
            // 上锁；异步读到 ABSENT（备份恢复 / 数据被清）再回写关闭并放行
            mutableLocked.value = enabled
            recheckPatternOnEnter = enabled
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
            if (enabled) refreshCredentialPresenceAsync(application.applicationContext)
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
            leaveGeneration++
        }
    }

    /**
     * 亮屏窥视判定（SCREEN_ON）：不修改 [away] / [leftAtMillis]，仅在已离开、锁已启用且
     * 已超过延迟档时直接上锁。真实回前台的计时消费在 [onLauncherEntering]。
     *
     * 先算离开时长、不超档直接返回：亮屏广播可能很频繁，注定不上锁时不做任何额外工作。
     * 凭据状态读内存缓存，**不在主线程做 Keystore 解密**；缓存尚未就绪时按「有凭据」处理
     * （fail-closed），迟到的 ABSENT 结果会把锁定与开关一起纠正。
     */
    private fun peekLockOnScreenOn() {
        synchronized(this) {
            if (!installed || !away) return
            val context = appContext ?: return
            if (!AppSettingsStore.isPrivacyAppLockEnabled(context)) return
            val timeout = PrivacyLockTimeout.fromStorageIndex(
                AppSettingsStore.getPrivacyAppLockTimeoutIndex(context),
            )
            val elapsed = SystemClock.elapsedRealtime() - leftAtMillis
            if (!shouldRequireUnlock(elapsed, timeout)) return
            val presence = credentialPresence
            if (presence == PatternCredentialState.ABSENT) return
            mutableCredentialUnreadable.value = presence == PatternCredentialState.UNREADABLE
            mutableLocked.value = true
        }
    }

    /**
     * 启动器退后台（ProcessLifecycleOwner.onStop）：开始离开计时。未安装（引擎子进程）为空操作。
     * 已处于离开状态时保留最早的离开时刻：熄屏后约 0.7s 才到的 onStop 不能把起点改晚，
     * 否则一分钟档下 60.1s 的往返会被算成 59.4s 而不上锁。
     */
    fun onLauncherLeft() {
        synchronized(this) {
            if (!installed) return
            if (away) return
            away = true
            leftAtMillis = SystemClock.elapsedRealtime()
            leaveGeneration++
        }
    }

    /**
     * 启动器回到前台（仅 ProcessLifecycleOwner.onStart；亮屏走 peekLockOnScreenOn，不消费计时器）：
     * 按延迟档位判定是否上锁（并消费计时器）。
     *
     * 冷启动口径优先于延迟档：进程首次回前台时凭据存在（含暂时读取失败 / 尚未读取完成）就
     * **直接保持锁定**，不按离开时长判定（否则选了 1/5/15 分钟档时冷启动会漏锁）。
     * 凭据确实不存在时不能上锁（否则用户永远无法解锁），并回写关闭开关。
     */
    fun onLauncherEntering() {
        synchronized(this) {
            if (!installed) return
            val context = appContext ?: return
            val enabled = AppSettingsStore.isPrivacyAppLockEnabled(context)
            if (recheckPatternOnEnter) {
                recheckPatternOnEnter = false
                // 首个回前台再异步确认一次：纠正 install() 阶段可能的 Keystore 瞬时失败，
                // 或凭据确已不存在（备份恢复 / 数据被清）；主线程只消费缓存与 StateFlow
                if (enabled) refreshCredentialPresenceAsync(context)
            }
            if (!enabled) {
                away = false
                leftAtMillis = 0L
                return
            }
            if (credentialPresence == null) {
                // 启动读取尚未完成 / 设置页刚启用：异步补一次，当前判定按「有凭据」处理
                refreshCredentialPresenceAsync(context)
            }
            val presence = credentialPresence
            if (presence == PatternCredentialState.ABSENT) {
                // 开关开着但凭据确实不存在：回写关闭，保证显示与实际一致并避免永久锁死。
                // 放在 !away 早退之前：即使冷启动首次 onStart 也先把这种不一致纠正掉。
                AppSettingsStore.setPrivacyAppLockEnabled(context, false)
                mutableLocked.value = false
                mutableCredentialUnreadable.value = false
                away = false
                leftAtMillis = 0L
                return
            }
            mutableCredentialUnreadable.value = presence == PatternCredentialState.UNREADABLE
            if (!away) return
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
     * 设置页查询是否已有可用图案（UI 的唯一读取入口）：同时刷新控制器缓存的凭据三态，
     * 避免「设置页读到 PRESENT、控制器还停留在 ABSENT」导致启用后首次回前台被误关闭。
     *
     * 阻塞调用（Keystore 解密），必须在后台线程执行。返回 false 包括凭据不存在与读取失败
     * （读取失败时设置页应拉起重新设置，而不是静默启用一个无法校验的锁）。
     */
    fun isPatternConfigured(context: Context): Boolean {
        val state = synchronized(credentialOpLock) {
            AppLockCredentialStore.credentialState(context)
        }
        synchronized(this) {
            credentialGeneration++
            credentialPresence = state
            mutableCredentialUnreadable.value = state == PatternCredentialState.UNREADABLE
        }
        return state == PatternCredentialState.PRESENT
    }

    /**
     * 图案校验并解锁（UI 的唯一入口）：校验与状态变更都归本控制器，
     * 避免页面直接触碰凭据存储后再单独调 [unlock]，绕过门面。
     *
     * 读取失败不算「图案错误」：置 [credentialUnreadableState]，锁门据此显示设备凭据恢复路径。
     * 凭据确实不存在（备份恢复）时回写关闭应用锁并放行，避免永久锁死。
     *
     * 阻塞调用（Keystore 解密），必须在后台线程执行。
     */
    fun verifyAndUnlock(context: Context, points: List<Int>): Boolean =
        when (AppLockCredentialStore.verifyPattern(context, points)) {
            PatternVerifyResult.SUCCESS -> {
                synchronized(this) {
                    credentialGeneration++
                    credentialPresence = PatternCredentialState.PRESENT
                    mutableCredentialUnreadable.value = false
                }
                unlock()
                true
            }
            PatternVerifyResult.MISMATCH -> {
                // 能读出凭据、只是与图案不匹配：清除「读取失败」标记，
                // 否则锁门会一直显示设备凭据 / 重置恢复入口
                synchronized(this) {
                    credentialGeneration++
                    credentialPresence = PatternCredentialState.PRESENT
                    mutableCredentialUnreadable.value = false
                }
                false
            }
            PatternVerifyResult.UNREADABLE -> {
                synchronized(this) {
                    credentialGeneration++
                    credentialPresence = PatternCredentialState.UNREADABLE
                    mutableCredentialUnreadable.value = true
                }
                false
            }
            PatternVerifyResult.ABSENT -> {
                // 凭据确实不存在：不能把用户永久锁在门外；关闭开关并放行，
                // 设置页显示关闭状态，用户重新设置图案后才会再次启用
                synchronized(this) {
                    credentialGeneration++
                    credentialPresence = PatternCredentialState.ABSENT
                    mutableCredentialUnreadable.value = false
                }
                AppSettingsStore.setPrivacyAppLockEnabled(context, false)
                unlock()
                false
            }
        }

    /**
     * 设置图案并启用应用锁（设置门确认后调用的唯一入口）：凭据保存成功才落开关，
     * 并丢弃进行中的计时；保存失败返回 false 且不改任何状态。
     *
     * 阻塞调用（Keystore 加密 + commit 落盘），必须在后台线程执行。保存期间用户可能按 Home
     * 退到后台：只有代数未变（保存期间没有发生新的离开）才作废计时，否则那次离开会被抹掉，
     * 回前台时 `!away` 早退，立即档也不锁。
     *
     * 与 [clearPatternAndDisable] 经 [credentialOpLock] 互斥：整段「凭据 + 开关」不被清除操作插队。
     */
    fun savePatternAndEnable(context: Context, points: List<Int>): Boolean =
        synchronized(credentialOpLock) {
            val generation = synchronized(this) { leaveGeneration }
            val saved = AppLockCredentialStore.savePattern(context, points)
            if (!saved) return@synchronized false
            AppSettingsStore.setPrivacyAppLockEnabled(context, true)
            synchronized(this) {
                credentialGeneration++
                credentialPresence = PatternCredentialState.PRESENT
                mutableCredentialUnreadable.value = false
                if (leaveGeneration == generation) {
                    away = false
                    leftAtMillis = 0L
                }
            }
            true
        }

    /**
     * 清除图案并关闭应用锁（设置页关闭开关 / 锁门重置共用）。
     * 清除凭据失败时**不改开关、不解锁**并返回 false：避免「清除假象」——用户以为锁已关闭，
     * 重启后旧图案又复活。阻塞调用，需在后台线程执行。
     *
     * 与 [savePatternAndEnable] 经 [credentialOpLock] 互斥：整段「凭据 + 开关」不被保存操作插队。
     */
    fun clearPatternAndDisable(context: Context): Boolean = synchronized(credentialOpLock) {
        if (!AppLockCredentialStore.clearPattern(context)) return@synchronized false
        AppSettingsStore.setPrivacyAppLockEnabled(context, false)
        synchronized(this) {
            credentialGeneration++
            credentialPresence = PatternCredentialState.ABSENT
            mutableCredentialUnreadable.value = false
        }
        unlock()
        true
    }

    /**
     * 异步读取凭据三态并更新缓存（IO 线程）。只消费结果、不阻塞调用方：
     * 迟到的结果若与代数不匹配（保存 / 清除 / 校验 / 设置页查询已给出更新的事实）则丢弃。
     *
     * 读到 ABSENT 且开关仍开着时回写关闭并放行：备份恢复把 `privacy_app_lock_enabled`
     * 带回来、但 `app_lock_secure.xml` 不在备份范围内，开关与实际必须保持一致。
     */
    private fun refreshCredentialPresenceAsync(context: Context) {
        val generation = synchronized(this) {
            if (credentialReadInFlight) return
            credentialReadInFlight = true
            credentialGeneration
        }
        ioScope.launch {
            try {
                val state = AppLockCredentialStore.credentialState(context)
                synchronized(this@AppLockController) {
                    if (!installed || generation != credentialGeneration) return@launch
                    credentialPresence = state
                    mutableCredentialUnreadable.value = state == PatternCredentialState.UNREADABLE
                    if (state == PatternCredentialState.ABSENT &&
                        AppSettingsStore.isPrivacyAppLockEnabled(context)
                    ) {
                        AppSettingsStore.setPrivacyAppLockEnabled(context, false)
                        mutableLocked.value = false
                    }
                }
            } finally {
                synchronized(this@AppLockController) { credentialReadInFlight = false }
            }
        }
    }
}
