package com.core.input

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import androidx.annotation.RequiresApi
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
 * 落盘策略（逐级降级，回调给出的路径即实际落点，由调用方据实提示）：
 *  1. **MediaStore（API 29+，首选）**：写入 `DCIM/tyranornext/`，scoped storage 下
 *     无需任何权限且自动进相册索引，兼容面最大；
 *  2. **直写文件**：API 30+ 需「所有文件访问」（MANAGE_EXTERNAL_STORAGE），
 *     API 26-28 需存储权限；成功后主动通知媒体扫描；
 *  3. **应用外部私有目录**回退（`Android/data/<包名>/files/screenshots/`，无需权限），
 *     保证任何情况下截屏都不丢。
 *
 * 三个层级都落在名为 tyranornext / screenshots 的独立目录，不与相机、其它应用的
 * 截图混在同一层。
 */
object ScreenCapture {

    private const val TAG = "TyranorScreenshot"

    /** 公共相册下的子目录名（与游戏无关，所有截图平铺在同一目录）。 */
    private const val PUBLIC_DIR_NAME = "tyranornext"

    /** 回退目录名（应用外部私有目录下）。 */
    private const val FALLBACK_DIR_NAME = "screenshots"

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
     * [onResult] 在主线程回调：成功给实际保存的文件（公共目录或回退目录），失败给 null
     * （窗口未就绪、编码失败等）。已在进行中的截屏会被直接忽略，回调不会被调用。
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
                    val activityContext = activity.applicationContext
                    ioExecutor.execute {
                        val saved = save(activityContext, bitmap)
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

    /** 首选公共相册目录：`/storage/emulated/0/DCIM/tyranornext`。 */
    fun publicDirectory(): File =
        File(File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DCIM), PUBLIC_DIR_NAME)

    /** 回退目录：应用外部私有目录（无需权限，卸载随沙箱清除）。 */
    fun fallbackDirectory(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, FALLBACK_DIR_NAME)
    }

    private fun save(context: Context, bitmap: Bitmap): File? {
        val name = "shot-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".png"

        // 1) API 29+ 首选 MediaStore：scoped storage 下应用可自由写入自己的媒体条目，
        //    无需任何权限，且自动进相册索引（比直写文件兼容面更大）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreSave(context, name, bitmap)?.let { return it }
            Log.w(TAG, "MediaStore save failed, trying direct write")
        }

        // 2) 直接写文件：API 30+ 需「所有文件访问」，API 26-28 需存储权限
        val publicDir = publicDirectory()
        if (publicDir.isDirectory || publicDir.mkdirs()) {
            val target = File(publicDir, name)
            if (writePng(bitmap, target)) {
                // 直写不经过 MediaStore，主动扫一次让相册立即可见
                notifyMediaScanner(context, target)
                return target
            }
            Log.w(TAG, "public dir not writable, falling back: ${publicDir.absolutePath}")
        }

        // 3) 回退应用私有目录：保证截屏不丢（回调路径即真实位置）
        val fallbackDir = fallbackDirectory(context)
        if (!fallbackDir.isDirectory && !fallbackDir.mkdirs()) return null
        val target = File(fallbackDir, name)
        return target.takeIf { writePng(bitmap, it) }
    }

    /**
     * 经 MediaStore 写入公共相册目录（API 29+，无需存储权限）。
     *
     * 用 IS_PENDING 标记「写入中」：写完再置 0，避免相册在文件未完整时读到半张图；
     * 失败时删除已插入的空条目，不留垃圾记录。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreSave(context: Context, name: String, bitmap: Bitmap): File? {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + PUBLIC_DIR_NAME)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.flush()
            } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        // RELATIVE_PATH 决定物理落点就是 DCIM/tyranornext/<name>
        return File(publicDirectory(), name)
    }

    private fun writePng(bitmap: Bitmap, target: File): Boolean = try {
        FileOutputStream(target).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.flush()
        }
        true
    } catch (error: Throwable) {
        Log.w(TAG, "write png failed: ${target.absolutePath}", error)
        target.delete()
        false
    }

    /**
     * 通知媒体扫描。
     *
     * 经「所有文件访问」直接写文件不会进 MediaStore 索引，相册要等系统空闲才扫到；
     * 主动扫一次让截图立即可见（扫描失败不影响截图本身，仅记日志）。
     */
    private fun notifyMediaScanner(context: Context, file: File) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        }.onFailure { Log.w(TAG, "media scan request failed", it) }
    }
}
