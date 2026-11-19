package com.tyranor.next.core.game.launch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
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
 */
object LaunchOrientationMonitor {
    private const val SENSOR_STALE_MS = 3_000L
    private const val COLD_START_SENSOR_TIMEOUT_MS = 80L

    private data class SensorState(
        val lastObservedAtMs: Long = 0L,
        val landscapeDegrees: Int? = null,
        val landscapeAtMs: Long = 0L,
    )

    private data class DisplayState(
        val orientation: Int,
        val rotation: Int,
    )

    private val installed = AtomicBoolean(false)
    private val sensorState = AtomicReference(SensorState())

    /** 仅应在主进程 `Application.onCreate` 调用；幂等且在异常设备上静默降级。 */
    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = application.applicationContext
        val registerOnMain = Runnable {
            val sensorListener = runCatching {
                object : OrientationEventListener(appContext, SensorManager.SENSOR_DELAY_NORMAL) {
                    override fun onOrientationChanged(orientation: Int) {
                        val displayState = readDisplayState(appContext)
                        recordSensorSample(
                            degrees = orientation,
                            nowMs = SystemClock.elapsedRealtime(),
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
        val sensorNeedsRefresh = snapshot.lastObservedAtMs == 0L ||
            nowMs - snapshot.lastObservedAtMs > SENSOR_STALE_MS
        if (sensorDegrees == null &&
            installed.get() &&
            sensorNeedsRefresh &&
            Looper.myLooper() != Looper.getMainLooper()
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
    }

    internal fun recordSensorSample(degrees: Int, nowMs: Long, naturalIsLandscape: Boolean) {
        sensorState.updateAndGet { prev ->
            when {
                degrees == OrientationEventListener.ORIENTATION_UNKNOWN ->
                    prev.copy(lastObservedAtMs = nowMs)
                isLandscapeSector(degrees, naturalIsLandscape) ->
                    SensorState(
                        lastObservedAtMs = nowMs,
                        landscapeDegrees = degrees,
                        landscapeAtMs = nowMs,
                    )
                isPortraitDeadzone(degrees, naturalIsLandscape) ->
                    SensorState(
                        lastObservedAtMs = nowMs,
                        landscapeDegrees = null,
                        landscapeAtMs = 0L,
                    )
                else -> prev.copy(lastObservedAtMs = nowMs)
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
    }

    private fun readDisplayState(context: Context): DisplayState {
        val display = resolveDisplay(context)
        val rotation = runCatching { display?.rotation }.getOrNull() ?: Surface.ROTATION_0
        val physicalLandscape = isPhysicalDisplayLandscape(context, display)
        val orientation = when (physicalLandscape) {
            true -> Configuration.ORIENTATION_LANDSCAPE
            false -> Configuration.ORIENTATION_PORTRAIT
            null -> context.resources?.configuration?.orientation ?: Configuration.ORIENTATION_UNDEFINED
        }
        return DisplayState(orientation = orientation, rotation = rotation)
    }

    private fun resolveDisplay(context: Context): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context is Activity) {
            runCatching { context.display }.getOrNull()?.let { return it }
        }
        val appContext = context.applicationContext ?: context
        return runCatching {
            appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
    }

    private fun isPhysicalDisplayLandscape(context: Context, display: Display?): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wmContext = if (context is Activity) context else (context.applicationContext ?: context)
            val bounds = runCatching {
                wmContext.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() > bounds.height()
            }
        }
        if (display == null) return null
        return runCatching {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                metrics.widthPixels > metrics.heightPixels
            } else {
                null
            }
        }.getOrNull()
    }

    internal fun isNaturalLandscape(displayOrientation: Int, displayRotation: Int): Boolean =
        when (displayOrientation) {
            Configuration.ORIENTATION_LANDSCAPE ->
                displayRotation == Surface.ROTATION_0 || displayRotation == Surface.ROTATION_180
            Configuration.ORIENTATION_PORTRAIT ->
                displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270
            else -> false
        }

    internal fun isLandscapeSector(degrees: Int, naturalIsLandscape: Boolean): Boolean =
        if (naturalIsLandscape) {
            degrees in 0..45 || degrees in 315..359 || degrees in 135..225
        } else {
            degrees in 45..135 || degrees in 225..315
        }

    internal fun isPortraitDeadzone(degrees: Int, naturalIsLandscape: Boolean): Boolean =
        if (naturalIsLandscape) {
            degrees in 65..115 || degrees in 245..295
        } else {
            degrees in 0..25 || degrees in 335..359 || degrees in 155..205
        }

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
        return if (naturalIsLandscape) {
            if (degrees in 135..225) {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        } else {
            if (degrees in 45..135) {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
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
        if (magnitude <= 0f || magnitude * 4f <= z * z) {
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
                } catch (_: Throwable) {
                    // 传感器回调异常时静默降级为 null
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
