package com.core.input

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 游戏画面截屏（PixelCopy）。
 *
 * 为什么用 PixelCopy：游戏画面由 WebView 或 SurfaceView 承载，View 层截屏
 * （`View.draw(Canvas)`）拿不到它们的绘制内容（WebView 会得到空白、SurfaceView 直接黑帧），
 * 而 PixelCopy 从窗口的合成结果取像素，对两类宿主都有效。minSdk 26 起
 * `PixelCopy.request(Window, …)` 可用，无需考虑旧 API 分支。
 *
 * 落盘位置：`<应用外部私有目录>/screenshots/shot-<时间戳>.png`——不需要任何存储权限，
 * 卸载应用时随沙箱清除；文件名回传给调用方用于提示。
 */
object ScreenCapture {

    private const val TAG = "TyranorScreenshot"
    private const val DIR_NAME = "screenshots"

    /** PNG 编码在后台线程执行（整屏图编码数百毫秒，放主线程会明显卡顿）。 */
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tyranor-screenshot").apply { isDaemon = true }
    }

    /** 同一时刻只允许一次截屏（连点不叠加、避免写同名文件）。 */
    @Volatile
    private var capturing = false

    /**
     * 截取 [activity] 当前窗口并保存为 PNG。
     *
     * [onResult] 在主线程回调：成功给保存的文件，失败给 null（窗口未就绪、编码失败等）。
     * 已在进行中的截屏会被直接忽略，回调不会被调用。
     */
    fun capture(activity: Activity, onResult: (File?) -> Unit) {
        if (capturing) return
        val window = activity.window
        val decor = window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "capture skipped: window size ${width}x$height")
            onResult(null)
            return
        }
        capturing = true
        val mainHandler = Handler(Looper.getMainLooper())
        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            capturing = false
            onResult(null)
            return
        }
        val finish: (File?) -> Unit = { file ->
            capturing = false
            mainHandler.post { onResult(file) }
        }
        try {
            PixelCopy.request(
                window,
                bitmap,
                { result ->
                    if (result != PixelCopy.SUCCESS) {
                        Log.w(TAG, "PixelCopy failed result=$result")
                        bitmap.recycle()
                        finish(null)
                        return@request
                    }
                    ioExecutor.execute {
                        val saved = save(activity, bitmap)
                        bitmap.recycle()
                        finish(saved)
                    }
                },
                mainHandler,
            )
        } catch (error: Throwable) {
            // 窗口尚未 attach / 正在销毁时 PixelCopy 会抛异常
            Log.w(TAG, "PixelCopy request failed", error)
            bitmap.recycle()
            finish(null)
        }
    }

    /** 截屏保存目录（首次调用时创建）。 */
    fun directory(activity: Activity): File {
        val root = activity.getExternalFilesDir(null) ?: activity.filesDir
        return File(root, DIR_NAME)
    }

    private fun save(activity: Activity, bitmap: Bitmap): File? = try {
        val dir = directory(activity)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val name = "shot-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".png"
        val target = File(dir, name)
        FileOutputStream(target).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.flush()
        }
        target
    } catch (error: Throwable) {
        Log.w(TAG, "save screenshot failed", error)
        null
    }
}
