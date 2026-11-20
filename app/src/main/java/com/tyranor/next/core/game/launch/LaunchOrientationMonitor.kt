package com.tyranor.next.core.game.launch

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.core.engine.EnginePlayerOrientation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 主进程方向监测与启动朝向判定器。
 *
 * 在主进程进入前台时监听设备物理倾角，仅在拉起引擎子进程前判定本次应锁定的单向横屏
 * （[ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE] 或 [ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE]），
 * 让子进程在初始化阶段即锁定该方向、运行期不再随传感器旋转。切入后台后自动停用监听，
 * 引擎子进程永不安装本监听器。
 *
 * 监听窗口与显示状态缓存取舍（P2-2/P3-8）：监听器随主进程前台（[ProcessLifecycleOwner]）
 * 启用、退后台停用，前台期间传感器回调以 SENSOR_DELAY_NORMAL 频率固定进入主线程；为避免
 * 每次回调都执行完整显示查询（`maximumWindowMetrics`/`getRealMetrics`），显示状态由
 * [DisplayManager.DisplayListener] 事件失效并在 [DISPLAY_CACHE_STALE_MS] 内复用缓存——
 * 事件上报缺失的设备最多滞后一个 TTL 后重算。启动判定（[resolvePlayerOrientation]）始终
 * 读取实时显示状态，不受缓存影响。
 */
object LaunchOrientationMonitor {
    // 握持切回竖屏死区与传感器短时丢帧的滞回保留窗口：足够覆盖从竖屏轻斜或平放抬起点击启动按钮，
    // 又不会把几秒前的旧握持角带入本次启动。
    private const val SENSOR_STALE_MS = 3_000L

    // 冷启动或刚离开竖屏死区且无横屏缓存时，后台线程等待首帧加速度计样本的上限：控制在用户无感范围内，
    // 超时即回落标准横屏而不阻塞启动。
    private const val COLD_START_SENSOR_TIMEOUT_MS = 80L

    /** 显示状态缓存的存活窗口：事件失效为主、该 TTL 为兜底重算间隔。 */
    private const val DISPLAY_CACHE_STALE_MS = 1_000L

    // 扇区与滞回阈值（度）：横屏扇区取倾角中心 ±45°，竖屏死区取中心 ±25°；两组中心相隔
    // 90°，按 45°/25° 对分后各留 20° 滞回带（扇区边界 45° ± 20°），保证「写入横屏样本」与
    // 「回到竖屏清缓存」之间有稳定间隙，握持在分界附近抖动时不会反复翻转缓存。自然横屏
    // 设备（平板）两组中心互换，宽度不变。
    private const val LANDSCAPE_SECTOR_HALF_WIDTH_DEGREES = 45
    private const val PORTRAIT_DEADZONE_HALF_WIDTH_DEGREES = 25

    private data class SensorState(
        val landscapeDegrees: Int? = null,
        val landscapeAtMs: Long = 0L,
    )

    private data class DisplayState(
        val orientation: Int,
        val rotation: Int,
    )

    private data class DisplayCache(
        val state: DisplayState,
        val capturedAtMs: Long,
    )

    private val installed = AtomicBoolean(false)
    private val sensorState = AtomicReference(SensorState())
    private val displayStateCache = AtomicReference<DisplayCache?>(null)

    /** 仅应在主进程 `Application.onCreate` 调用；幂等且在异常设备上静默降级。 */
    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = application.applicationContext
        val registerOnMain = Runnable {
            val sensorListener = runCatching {
                object : OrientationEventListener(appContext, SensorManager.SENSOR_DELAY_NORMAL) {
                    override fun onOrientationChanged(orientation: Int) {
                        val nowMs = SystemClock.elapsedRealtime()
                        val displayState = cachedDisplayState(appContext, nowMs)
                        recordSensorSample(
                            degrees = orientation,
                            nowMs = nowMs,
                            naturalIsLandscape = isNaturalLandscape(
                                displayState.orientation,
                                displayState.rotation,
                            ),
                        )
                    }
                }
            }.getOrNull()
            if (sensorListener == null) {
                installed.set(false)
                return@Runnable
            }
            registerDisplayStateInvalidationListener(appContext)
            val observer = object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    runCatching {
                        if (sensorListener.canDetectOrientation()) sensorListener.enable()
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    runCatching { sensorListener.disable() }
                    onBackgroundStop()
                }
            }
            val attached = runCatching {
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            }.isSuccess
            if (!attached) {
                runCatching { sensorListener.disable() }
                installed.set(false)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            registerOnMain.run()
        } else {
            Handler(Looper.getMainLooper()).post(registerOnMain)
        }
    }

    /**
     * 显示状态缓存失效监听：显示旋转/增删事件即清缓存，下一次传感器回调重算一次；
     * 注册失败只影响缓存新鲜度（TTL 兜底重算仍在），不阻断传感器监听安装。
     */
    private fun registerDisplayStateInvalidationListener(appContext: Context) {
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                displayStateCache.set(null)
            }

            override fun onDisplayRemoved(displayId: Int) {
                displayStateCache.set(null)
            }

            override fun onDisplayChanged(displayId: Int) {
                displayStateCache.set(null)
            }
        }
        runCatching {
            val displayManager = appContext.getSystemService(DisplayManager::class.java)
            displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        }
    }

    /**
     * 判定本次拉起播放器应使用的具体单向横屏常量（`0` 或 `8`）。
     *
     * 优先信任当前物理显示已处于横屏时的旋转角；若当前尚处于竖屏（如系统锁定自动旋转），
     * 则结合主进程前台监听到的设备倾角（或瞬时加速度计采样）判断正/反横屏，
     * 无法区分时回落标准横屏。
     */
    fun resolvePlayerOrientation(context: Context): Int {
        val displayState = readDisplayState(context)
        if (displayState.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return resolveOrientationFromSignals(
                displayOrientation = displayState.orientation,
                displayRotation = displayState.rotation,
                sensorDegrees = null,
            )
        }

        val nowMs = SystemClock.elapsedRealtime()
        val snapshot = sensorState.get()
        var sensorDegrees = recentLandscapeDegrees(snapshot, nowMs)
        // 探测条件（合并口径）：只要没有可用的横屏样本就允许一次探测——「快照过期」与
        // 「快照新鲜但只有竖屏死区样本」（LOGIC-03 窗口）都覆盖，不再区分快照新鲜度；
        // 探测仅在后台线程发生（启动协程，频率为启动级、单次上限 80ms）。
        if (shouldProbeAccelerometer(
                sensorDegrees = sensorDegrees,
                installed = installed.get(),
                onMainThread = Looper.myLooper() == Looper.getMainLooper(),
            )
        ) {
            val probeContext = context.applicationContext ?: context
            val sampled = sampleAccelerometerDegrees(probeContext)
            if (sampled != null) {
                val sampleNowMs = SystemClock.elapsedRealtime()
                recordSensorSample(
                    degrees = sampled,
                    nowMs = sampleNowMs,
                    naturalIsLandscape = isNaturalLandscape(
                        displayState.orientation,
                        displayState.rotation,
                    ),
                )
                sensorDegrees = recentLandscapeDegrees(sensorState.get(), sampleNowMs)
            }
        }

        return resolveOrientationFromSignals(
            displayOrientation = displayState.orientation,
            displayRotation = displayState.rotation,
            sensorDegrees = sensorDegrees,
        )
    }

    internal fun onBackgroundStop() {
        sensorState.set(SensorState())
        displayStateCache.set(null)
    }

    internal fun recordSensorSample(degrees: Int, nowMs: Long, naturalIsLandscape: Boolean) {
        sensorState.updateAndGet { prev ->
            when {
                degrees == OrientationEventListener.ORIENTATION_UNKNOWN -> prev
                isLandscapeSector(degrees, naturalIsLandscape) ->
                    SensorState(
                        landscapeDegrees = degrees,
                        landscapeAtMs = nowMs,
                    )
                isPortraitDeadzone(degrees, naturalIsLandscape) ->
                    SensorState()
                else -> prev
            }
        }
    }

    internal fun recentLandscapeDegrees(nowMs: Long): Int? =
        recentLandscapeDegrees(sensorState.get(), nowMs)

    private fun recentLandscapeDegrees(snapshot: SensorState, nowMs: Long): Int? {
        val recorded = snapshot.landscapeDegrees ?: return null
        return if (nowMs - snapshot.landscapeAtMs <= SENSOR_STALE_MS) recorded else null
    }

    internal fun resetForTest() {
        sensorState.set(SensorState())
        displayStateCache.set(null)
    }

    /**
     * 回调路径专用的显示状态读取：缓存未失效时直接复用，避免每次传感器回调都在主线程做
     * 完整显示查询；缓存为空（事件失效、退后台清理或首次）或达到或超过 [DISPLAY_CACHE_STALE_MS]
     * 时重算一次并写回。启动判定路径（[resolvePlayerOrientation]）不走本函数。
     */
    private fun cachedDisplayState(context: Context, nowMs: Long): DisplayState {
        val cache = displayStateCache.get()
        if (cache != null && !isDisplayCacheStale(cache.capturedAtMs, nowMs, DISPLAY_CACHE_STALE_MS)) {
            return cache.state
        }
        val fresh = readDisplayState(context)
        displayStateCache.set(DisplayCache(state = fresh, capturedAtMs = nowMs))
        return fresh
    }

    /**
     * 显示缓存失效纯判定（JVM 测试钉住）：[capturedAtMs] 为 null 表示缓存已被
     * [DisplayManager.DisplayListener] 事件、退后台清理或测试重置清除，必须重算；
     * 缓存存在时自捕获起达到或超过 [ttlMs] 同样视为失效。
     */
    internal fun isDisplayCacheStale(capturedAtMs: Long?, nowMs: Long, ttlMs: Long): Boolean =
        capturedAtMs == null || nowMs - capturedAtMs >= ttlMs

    private fun readDisplayState(context: Context): DisplayState {
        // 显示探测实现收口到 engine（P3-2）：本类只负责把三态结果映射为 DisplayState。
        val display = EnginePlayerOrientation.resolveDisplay(context)
        val rotation = runCatching { display?.rotation }.getOrNull() ?: Surface.ROTATION_0
        val physicalLandscape = EnginePlayerOrientation.isPhysicalDisplayLandscape(context, display)
        val orientation = when (physicalLandscape) {
            true -> Configuration.ORIENTATION_LANDSCAPE
            false -> Configuration.ORIENTATION_PORTRAIT
            null -> context.resources?.configuration?.orientation ?: Configuration.ORIENTATION_UNDEFINED
        }
        return DisplayState(orientation = orientation, rotation = rotation)
    }

    internal fun isNaturalLandscape(displayOrientation: Int, displayRotation: Int): Boolean =
        when (displayOrientation) {
            Configuration.ORIENTATION_LANDSCAPE ->
                displayRotation == Surface.ROTATION_0 || displayRotation == Surface.ROTATION_180
            Configuration.ORIENTATION_PORTRAIT ->
                displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270
            else -> false
        }

    private fun landscapeSectorCenters(naturalIsLandscape: Boolean): IntArray =
        if (naturalIsLandscape) intArrayOf(0, 180) else intArrayOf(90, 270)

    private fun portraitDeadzoneCenters(naturalIsLandscape: Boolean): IntArray =
        if (naturalIsLandscape) intArrayOf(90, 270) else intArrayOf(0, 180)

    /** 倾角到扇区中心的最小角距（0..180）。 */
    private fun angularDistanceToCenter(degrees: Int, center: Int): Int {
        val diff = Math.floorMod(degrees - center, 360)
        return if (diff > 180) 360 - diff else diff
    }

    internal fun isLandscapeSector(degrees: Int, naturalIsLandscape: Boolean): Boolean =
        landscapeSectorCenters(naturalIsLandscape).any { center ->
            angularDistanceToCenter(degrees, center) <= LANDSCAPE_SECTOR_HALF_WIDTH_DEGREES
        }

    internal fun isPortraitDeadzone(degrees: Int, naturalIsLandscape: Boolean): Boolean =
        portraitDeadzoneCenters(naturalIsLandscape).any { center ->
            angularDistanceToCenter(degrees, center) <= PORTRAIT_DEADZONE_HALF_WIDTH_DEGREES
        }

    /**
     * 是否需要一次瞬时加速度计探测（纯判定，JVM 测试钉住）：
     * 1. 缓存中没有可用的横屏样本（[sensorDegrees] == null）——无论是从未观测、竖屏死区
     *    清除还是样本过期；
     * 2. 方向监听已安装（未安装的进程不做方向判定，探测无意义）；
     * 3. 仅后台线程——主线程绝不阻塞等待传感器；探测发生在拉起外置引擎前的启动协程，
     *    频率为启动级，单次上限 [COLD_START_SENSOR_TIMEOUT_MS]。
     */
    internal fun shouldProbeAccelerometer(
        sensorDegrees: Int?,
        installed: Boolean,
        onMainThread: Boolean,
    ): Boolean = sensorDegrees == null && installed && !onMainThread

    /**
     * 纯判定函数：根据当前界面方向、显示旋转角和传感器倾角，输出具体单向横屏常量。
     */
    internal fun resolveOrientationFromSignals(
        displayOrientation: Int,
        displayRotation: Int,
        sensorDegrees: Int?,
    ): Int {
        if (displayOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            return EnginePlayerOrientation.landscapeOrientationForDisplay(displayOrientation, displayRotation)
        }
        val degrees = sensorDegrees ?: return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val naturalIsLandscape = isNaturalLandscape(displayOrientation, displayRotation)
        // 反向横屏扇区中心：自然横屏设备为 180°，自然竖屏设备为 90°；宽度与 isLandscapeSector 共用常量。
        val reverseCenter = if (naturalIsLandscape) 180 else 90
        return if (angularDistanceToCenter(degrees, reverseCenter) <= LANDSCAPE_SECTOR_HALF_WIDTH_DEGREES) {
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    internal fun degreesFromAccelerometer(ax: Float, ay: Float, az: Float): Int {
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite()) {
            return OrientationEventListener.ORIENTATION_UNKNOWN
        }
        val x = -ax
        val y = -ay
        val z = -az
        val magnitude = x * x + y * y
        // 平放拒绝边界对齐 AOSP OrientationEventListener（其接受条件为 magnitude * 4 >= Z*Z）：
        // 等值点（|z| 恰为 2·|xy|）仍接受，|z| 严格超过才判「接近平放、屏幕方向无意义」。
        if (magnitude <= 0f || magnitude * 4f < z * z) {
            return OrientationEventListener.ORIENTATION_UNKNOWN
        }
        val angle = atan2(-y.toDouble(), x.toDouble()) * (180.0 / Math.PI)
        if (!angle.isFinite()) return OrientationEventListener.ORIENTATION_UNKNOWN
        var degrees = 90 - angle.roundToInt()
        while (degrees >= 360) degrees -= 360
        while (degrees < 0) degrees += 360
        return degrees
    }

    private fun sampleAccelerometerDegrees(context: Context): Int? {
        val manager = runCatching { context.getSystemService(SensorManager::class.java) }.getOrNull()
            ?: return null
        val sensor = runCatching { manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }.getOrNull()
            ?: return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Int?>(null)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                try {
                    val values = event?.values
                    if (values != null && values.size >= 3) {
                        result.set(degreesFromAccelerometer(values[0], values[1], values[2]))
                    }
                } catch (_: RuntimeException) {
                    // 传感器回调异常时静默降级为 null，不吞 Error
                } finally {
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val probeThread = HandlerThread("tyranor-orientation-probe")
        probeThread.start()
        try {
            val probeLooper = probeThread.looper ?: return null
            val probeHandler = Handler(probeLooper)
            val registered = runCatching {
                manager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    probeHandler,
                )
            }.getOrDefault(false)
            if (!registered) return null
            try {
                latch.await(COLD_START_SENSOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } finally {
                runCatching { manager.unregisterListener(listener) }
            }
        } finally {
            runCatching { probeThread.quitSafely() }
        }
        return result.get()
    }
}
