package com.core.input

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 游戏画面截屏（PixelCopy）。
 *
 * 为什么用 PixelCopy：游戏画面由 WebView 或 SurfaceView 承载，View 层截屏
 * （`View.draw(Canvas)`）拿不到它们的绘制内容（WebView 会得到空白、SurfaceView 直接黑帧），
 * 而 PixelCopy 从窗口的合成结果取像素，对两类宿主都有效。minSdk 26 起
 * `PixelCopy.request(Window, …)` 可用，无需考虑旧 API 分支。
 *
 * 取的是**整个窗口**的合成结果，因此虚拟按键层 / FAB 也会进图（它们与游戏画面同窗）。
 *
 * 落盘策略（逐级降级；[CaptureResult.Saved.file] 是核对过存在性的真实路径，不猜测）：
 *  1. **MediaStore（API 29+）**：写入 `DCIM/tyranornext/`，scoped storage 下无需任何权限
 *     且自动进相册索引；
 *  2. **直写文件**：仅 API 30+ 且已授予「所有文件访问」时可用（MANAGE_EXTERNAL_STORAGE）。
 *     API 26–28 的 `WRITE_EXTERNAL_STORAGE` 是危险权限，而应用侧从未运行时申请，故这一段
 *     在 26–28 上必然失败；
 *  3. **应用外部私有目录**回退（`Android/data/<包名>/files/screenshots/`，无需权限），
 *     保证任何情况下截屏都不丢。
 */
object ScreenCapture {

    private const val TAG = "TyranorScreenshot"

    /** 公共相册下的子目录名（与游戏无关，所有截图平铺在同一目录）。 */
    private const val PUBLIC_DIR_NAME = "tyranornext"

    /** 回退目录名（应用外部私有目录下）。 */
    private const val FALLBACK_DIR_NAME = "screenshots"

    /** PixelCopy 回调的等待上限；超时按失败处理并复位状态。 */
    private const val CAPTURE_TIMEOUT_MS = 5_000L

    /** 截屏结果：调用方按实际落点提示，不猜测路径。 */
    sealed interface CaptureResult {

        /** 已保存；[file] 为核对过存在性的实际路径。 */
        data class Saved(val file: File) : CaptureResult

        /** 上一张仍在保存中，本次未执行（连点保护）。 */
        data object Busy : CaptureResult

        /** 窗口未就绪、PixelCopy 失败或写盘失败。 */
        data object Failed : CaptureResult
    }

    /** PNG 编码在后台线程执行（整屏图编码数百毫秒，放主线程会明显卡顿）。 */
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tyranor-screenshot").apply { isDaemon = true }
    }

    /** 同一时刻只允许一次截屏（连点不叠加、避免写同名文件）。 */
    private val capturing = AtomicBoolean(false)

    /**
     * 当前截屏的代际号。
     *
     * 用于区分「本次截屏」与「上一次的迟到回调」：A 超时后 B 开始，A 的 PixelCopy
     * 回调迟到时若不校验代际，会以全局 capturing 判断「仍在进行」，其收尾逻辑
     * 会偷走 B 的单飞标志（B 结果回调丢失、位图不回收）。
     */
    private val generation = AtomicInteger(0)

    /**
     * 截取 [activity] 当前窗口并保存为 PNG。
     *
     * [onResult] 在主线程回调，且**总会**被调用一次：成功给 [CaptureResult.Saved]，
     * 上一张仍在保存时给 [CaptureResult.Busy]（连点不再静默丢弃），其余失败给
     * [CaptureResult.Failed]。
     */
    fun capture(activity: Activity, onResult: (CaptureResult) -> Unit) {
        val window = activity.window
        val decor = window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "capture skipped: window size ${width}x$height")
            onResult(CaptureResult.Failed)
            return
        }
        if (!capturing.compareAndSet(false, true)) {
            onResult(CaptureResult.Busy)
            return
        }
        val myGeneration = generation.incrementAndGet()
        val mainHandler = Handler(Looper.getMainLooper())
        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            capturing.set(false)
            onResult(CaptureResult.Failed)
            return
        }
        // 兜底：PixelCopy 在极少数情况下可能不回调（窗口被立即销毁等），
        // 若不复位 capturing，之后所有截屏都会被判为 Busy 而永久失效。
        // 超时只复位状态与回调失败，**不回收位图**：PixelCopy 可能仍在写它，
        // 回收交给（带代际校验的）迟到回调，或最终由 GC 处理。
        val pixelCopyDone = AtomicBoolean(false)
        // 看门狗与迟到回调共用：超时后本次结果已对用户报过「失败」，
        // 迟到的成功回调不得再落盘（否则用户看到失败提示、相册却多一张图）
        val timedOut = AtomicBoolean(false)
        val watchdog = Runnable {
            if (!pixelCopyDone.get() && generation.get() == myGeneration &&
                capturing.compareAndSet(true, false)
            ) {
                timedOut.set(true)
                Log.w(TAG, "capture watchdog fired: no PixelCopy callback")
                mainHandler.post { onResult(CaptureResult.Failed) }
            }
        }
        mainHandler.postDelayed(watchdog, CAPTURE_TIMEOUT_MS)
        // 本次截屏的收尾：校验代际，避免上一次的迟到回调偷走本次的单飞标志
        val finish: (CaptureResult) -> Unit = runner@ { result ->
            if (generation.get() == myGeneration &&
                capturing.compareAndSet(true, false)
            ) {
                mainHandler.removeCallbacks(watchdog)
                bitmap.recycle()
                mainHandler.post { onResult(result) }
                return@runner
            }
            // 非当前代（本次已被看门狗判定失败，或已被更新的截屏接替）：
            // 只回收自己的位图，不改动全局状态、不回调
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        try {
            PixelCopy.request(
                window,
                bitmap,
                { result ->
                    pixelCopyDone.set(true)
                    // 代际已推进（本次被判超时或被新截屏接替）：迟到回调不得再触碰全局状态，
                    // 位图无人再引用，就地回收而不是等 GC
                    if (generation.get() != myGeneration) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@request
                    }
                    // 看门狗已对用户报过失败：此时只回收位图，不落盘、不回调
                    if (timedOut.get()) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@request
                    }
                    if (result != PixelCopy.SUCCESS) {
                        Log.w(TAG, "PixelCopy failed result=$result")
                        finish(CaptureResult.Failed)
                        return@request
                    }
                    val appContext = activity.applicationContext
                    ioExecutor.execute {
                        val file = save(appContext, bitmap)
                        finish(if (file != null) CaptureResult.Saved(file) else CaptureResult.Failed)
                    }
                },
                mainHandler,
            )
        } catch (error: Throwable) {
            // 窗口尚未 attach / 正在销毁时 PixelCopy 会抛异常
            pixelCopyDone.set(true)
            Log.w(TAG, "PixelCopy request failed", error)
            finish(CaptureResult.Failed)
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

        // 2) 直接写文件：需「所有文件访问」（API 30+）。API 26–28 因未申请运行时存储权限，
        //    这里必然拿不到写权限而落到下一级——这是已知取舍，不是缺陷。
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
     * 用 IS_PENDING 标记「写入中」：写完再置 0，避免相册在文件未完整时读到半张图。
     * 三步都显式判定成败——编码失败、清 pending 失败都按失败处理并删除条目，
     * 否则会在相册里留下不可见或残缺的记录，却对用户报「已保存」。
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

        // compress 返回 false 表示编码失败；use{} 的结果是 lambda 末值，必须把它带出来
        val encoded = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.flush()
                ok
            } ?: false
        }.getOrDefault(false)
        if (!encoded) {
            Log.w(TAG, "MediaStore write failed for $uri")
            deleteQuietly(resolver, uri)
            return null
        }

        val cleared = runCatching {
            val pendingOff = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, pendingOff, null, null) > 0
        }.getOrDefault(false)
        if (!cleared) {
            // 条目保持 pending：对相册与其它应用不可见，且 7 天后被系统清理，
            // 不能对用户报「已保存到相册」
            Log.w(TAG, "clearing IS_PENDING failed, discarding entry $uri")
            deleteQuietly(resolver, uri)
            return null
        }

        // 落点以 MediaStore 记录为准（系统可能去重改名）；查不到时退化为约定路径再核对存在性
        val resolved = queryDataPath(resolver, uri)?.let(::File) ?: File(publicDirectory(), name)
        if (!resolved.isFile) {
            // 条目已发布但路径核验失败：删掉它再降级写盘，否则会留下一张重复/游离的截图
            Log.w(TAG, "MediaStore entry not found at ${resolved.absolutePath}, discarding entry")
            deleteQuietly(resolver, uri)
            return null
        }
        return resolved
    }

    /** 查询 MediaStore 条目的实际磁盘路径；DATA 已废弃但仍是唯一可反解落点的列。 */
    @Suppress("DEPRECATION")
    private fun queryDataPath(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private fun deleteQuietly(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "delete pending entry failed: $uri", it) }
    }

    /** 直写 PNG；编码失败也删掉半成品，避免留下空文件被当成有效截图。 */
    private fun writePng(bitmap: Bitmap, target: File): Boolean = try {
        val encoded = FileOutputStream(target).use { output ->
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.flush()
            ok
        }
        if (!encoded) {
            Log.w(TAG, "png encode failed: ${target.absolutePath}")
            target.delete()
        }
        encoded
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
