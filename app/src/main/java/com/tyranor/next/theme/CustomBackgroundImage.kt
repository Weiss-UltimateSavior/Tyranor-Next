package com.tyranor.next.theme

import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.collectSettingAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** 模糊强度换算上限：100 → 24dp 高斯模糊。 */
private const val MaxBlurDp = 24f

/** ARGB_8888 每像素字节数。 */
private const val DecodedBytesPerPixel = 4L

/**
 * 背景图解码字节预算上限（64MiB）：实际预算取进程堆的 1/8，并限制在 16–64MiB。
 * 64MiB 同时低于 API 29 约 100MiB 的绘制限制与 Android 16 的 150MiB 硬件位图上限；
 * 低 RAM 设备按堆比例收紧，避免与封面缓存叠加后 OOM。
 */
private const val MaxDecodedBytes = 64L * 1024 * 1024

/** 背景图解码预算下限：再低会导致常见手机截图也明显发糊。 */
private const val MinDecodedBytes = 16L * 1024 * 1024

/** 解码后按实际字节数重采样的最大次数（超出仍超预算则放弃背景，不把超限位图交给绘制）。 */
private const val MaxDecodeResampleAttempts = 3

/** 背景位图替换 / 清理后的延迟回收：等下一帧绘制切到新图后再释放旧位图。 */
private const val OldBitmapRecycleDelayMillis = 500L

/**
 * 自定义背景图的进程级缓存。
 *
 * 背景图在**所有页面**共用同一份位图：若每个页面各自解码（原来的做法），进入新页面时
 * 第一帧还没有图、解码完成后才突然出现——就是"进入时闪一下"。这里只解码一次并缓存，
 * 之后所有页面（含二级页、主界面切换）在组合期就能同步拿到位图，**不会闪**。
 *
 * 切换图片时保留旧图直到新图就绪（State 一次性替换），避免换图瞬间出现空背景；
 * 新图接管后旧位图延迟回收。
 */
internal object BackgroundImageCache {
    private const val TAG = "BackgroundImageCache"

    /**
     * 当前已就绪的位图。**必须是同一个 State 实例**：每次赋值都新建 `mutableStateOf` 的话，
     * 已经订阅旧实例的界面永远读不到新值（表现就是"刚开始没图、之后也不出现"）。
     */
    private val mutableImage = mutableStateOf<ImageBitmap?>(null)
    val image: androidx.compose.runtime.State<ImageBitmap?> = mutableImage

    /** 解码失败 / 永久不可用的 uri（锁门据此不再等待背景就绪）。 */
    private val mutableUnavailableUri = mutableStateOf<String?>(null)
    val unavailableUri: androidx.compose.runtime.State<String?> = mutableUnavailableUri

    /**
     * CRITICAL / COMPLETE 释放后的**立即**重载信号：这两档按既有取舍即使处于后台也马上重解码。
     * 后台 RUNNING_LOW 释放不递增该信号（否则会在后台立刻解回来，释放目的落空）；
     * 它由图层在回到前台（ON_START）时按当前存储 uri 自动补解码。
     */
    private val mutableReloadSignal = mutableIntStateOf(0)
    val reloadSignal: androidx.compose.runtime.State<Int> = mutableReloadSignal

    /** 当前位图对应的 uri（用于换图时判断是否需要重新解码）。 */
    private var currentUri: String = ""

    /** 正在解码中的 uri：避免同图重复解码。 */
    private var loadingUri: String = ""

    /** 串行化：并发调用（启动预热 + 设置页 effect）会同时读写 currentUri/loadingUri。 */
    private val loadMutex = Mutex()

    /** 旧位图延迟回收的作用域：等绘制切到新图后再释放，避免 RenderThread 仍在引用。 */
    private val recycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private sealed interface LoadResult {
        data class Ready(val bitmap: Bitmap) : LoadResult

        /** 本次解码失败（OOM / 解码异常）：保留设置，下次冷启动重试。 */
        data object TransientFailure : LoadResult

        /** 持久授权丢失 / 文件不存在：清除 uri，避免每次冷启动都等待超时。 */
        data object PermanentlyUnavailable : LoadResult
    }

    /** 应用启动或设置里换图时调用：解码完成后一次性替换缓存（旧图保留到新图就绪）。 */
    suspend fun ensureLoaded(context: Context, uri: String) = loadMutex.withLock {
        ensureLoadedLocked(context, uri)
    }

    private suspend fun ensureLoadedLocked(context: Context, uri: String) {
        if (uri.isBlank()) {
            currentUri = ""
            loadingUri = ""
            mutableUnavailableUri.value = null
            val previous = mutableImage.value?.asAndroidBitmap()
            mutableImage.value = null
            if (previous != null) recycleAfterDraw(previous, keep = null)
            return
        }
        if (uri == currentUri && mutableImage.value != null) return
        if (uri == loadingUri) return
        loadingUri = uri
        // finally 必须清理：解码途中被取消（清空背景 / 快速换图）时若停在 loadingUri，
        // 之后重选同一张图会命中上面的 early return，背景永远加载不出来
        val result = try {
            withContext(Dispatchers.IO) { load(context, uri) }
        } finally {
            if (loadingUri == uri) loadingUri = ""
        }
        when (result) {
            is LoadResult.Ready -> {
                currentUri = uri
                mutableUnavailableUri.value = null
                val previous = mutableImage.value?.asAndroidBitmap()
                mutableImage.value = result.bitmap.asImageBitmap()
                // 换图 / 首次就绪：旧图等下一帧绘制切走后回收，避免两份大图长期同时驻留
                if (previous != null && previous !== result.bitmap) {
                    recycleAfterDraw(previous, keep = result.bitmap)
                }
            }
            LoadResult.TransientFailure -> {
                // 本次进程内标记不可用：锁门不再为它等 400ms；设置保留，下次冷启动重试
                mutableUnavailableUri.value = uri
            }
            LoadResult.PermanentlyUnavailable -> {
                // 备份恢复后没有持久授权 / 文件已删除：清除设置，避免每次启动都等解码失败。
                // commit() 是同步落盘，放到 IO，避免从锁门 / 图层的主线程调用点阻塞。
                withContext(Dispatchers.IO) {
                    AppSettingsStore.setBackgroundImageUri(context, "")
                }
                mutableUnavailableUri.value = uri
                currentUri = ""
                mutableImage.value = null
            }
        }
    }

    /**
     * Android 内存紧张时释放背景位图（Application.onTrimMemory 调用）：
     * 与封面缓存不同，背景图是整屏大图且只服务一个页面，低内存时应优先让出。
     *
     * [ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW] 在前台正常使用时也会派发；若立即释放，
     * 图层马上会重新解码并弹回背景，高压力设备反复触发就是解码风暴。因此 RUNNING_LOW 只在
     * 应用处于后台时释放（复用 ProcessLifecycleOwner 的进程前后台信号），且释放后**不递增**
     * 重载信号：后台不立即重解码，等回前台由图层在 ON_START 按存储 uri 补一次。
     * RUNNING_CRITICAL / COMPLETE 维持总是释放并立即重解码（既有取舍）。
     *
     * 位图在下一帧绘制切走后回收。
     */
    fun onTrimMemory(level: Int) {
        val backgroundLow = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            !isAppForeground()
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (!backgroundLow && !critical) return
        val previous = mutableImage.value?.asAndroidBitmap() ?: return
        currentUri = ""
        loadingUri = ""
        mutableImage.value = null
        // 后台 RUNNING_LOW 只释放不重载；CRITICAL / COMPLETE 维持立即重解码
        if (critical) mutableReloadSignal.intValue++
        recycleAfterDraw(previous, keep = null)
    }

    /** Application.onTrimMemory 在主线程派发；STARTED 及以上视为前台。 */
    private fun isAppForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * 延迟回收旧位图：State 更新后当前帧可能仍在绘制旧图，立即 recycle 会让 RenderThread
     * 用到已回收位图；等约半秒（下一帧早已切到新图）再释放。动画结束后 GC 也会兜底。
     */
    private fun recycleAfterDraw(bitmap: Bitmap, keep: Bitmap?) {
        if (bitmap === keep) return
        recycleScope.launch {
            delay(OldBitmapRecycleDelayMillis)
            runCatching { bitmap.recycle() }
        }
    }

    /**
     * 读取并解码背景图：[LoadResult.PermanentlyUnavailable] 表示授权丢失 / 文件不存在，
     * 其余异常按临时失败处理，不误删用户设置。
     */
    private fun load(context: Context, uriText: String): LoadResult {
        val uri = Uri.parse(uriText)
        if (uri.scheme == ContentResolver.SCHEME_CONTENT && !hasPersistedReadPermission(context, uri)) {
            Log.w(TAG, "Background image has no persisted read permission: $uri")
            return LoadResult.PermanentlyUnavailable
        }
        return try {
            decode(context, uri)?.let(LoadResult::Ready) ?: LoadResult.TransientFailure
        } catch (missing: FileNotFoundException) {
            Log.w(TAG, "Background image file is missing: $uri", missing)
            LoadResult.PermanentlyUnavailable
        } catch (security: SecurityException) {
            Log.w(TAG, "Background image permission was revoked: $uri", security)
            LoadResult.PermanentlyUnavailable
        } catch (error: Exception) {
            Log.w(TAG, "Background image decode failed: $uri", error)
            LoadResult.TransientFailure
        } catch (_: OutOfMemoryError) {
            // 极端低内存：放弃本次背景，不把 OOM 抛给调用方 / 进程
            LoadResult.TransientFailure
        }
    }

    private fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /**
     * 走与卡片封面相同的采样解码：只按需尺寸解码，避免整张大图进内存。
     *
     * Android 10+ 的 BitmapFactory 对 16 位高精度源图（如 16-bit PNG）可能自动选择
     * RGBA_F16（8 字节/像素），只按 4 字节/像素计算的预算会低估一倍（4000×4000 可解出
     * 128MB）。因此显式要求 ARGB_8888，并在解码后按实际 `byteCount` 兜底重采样。
     *
     * EXIF 需要旋转 / 镜像时会再生成一张同尺寸目标位图：此时把源图预算减半，
     * 让「源图 + 变换副本」的峰值仍落在总预算内；变换完成后立即回收源图。
     */
    private fun decode(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 采样上限按**屏幕长边**而不是固定宽度：背景只需铺满当前屏幕，
        // 固定 1440 宽在平板上偏小、在高分屏上又可能超出 2 倍。
        // 下限 1 防止极端窗口上报 0 时采样循环永不退出。
        val maxEdge = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
            .coerceAtLeast(1)
        val orientation = readExifOrientation(context, uri)
        val needsTransform = orientation != ExifInterface.ORIENTATION_NORMAL &&
            orientation != ExifInterface.ORIENTATION_UNDEFINED
        val maxBytes = maxDecodedBytes()
        // 变换副本与源图同尺寸：源图预算减半，避免两者叠加超过总预算
        val sourceBudget = if (needsTransform) {
            (maxBytes / 2).coerceAtLeast(MinDecodedBytes / 2)
        } else {
            maxBytes
        }
        val sample = backgroundSampleSize(bounds.outWidth, bounds.outHeight, maxEdge, sourceBudget)
        val decoded = decodeWithinBudget(context, uri, sample, sourceBudget) ?: return null
        // 按 EXIF 方向摆正：旋转与镜像都要处理——只映射三个纯旋转方向时，
        // TRANSPOSE / TRANSVERSE 这类「旋转 + 翻转」的照片会侧转且镜像错误
        val matrix = exifMatrix(orientation) ?: return decoded
        return try {
            val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (transformed !== decoded) decoded.recycle()
            transformed
        } catch (_: Throwable) {
            // 变换失败时保留原图：方向可能不对，但至少不会丢背景
            decoded
        }
    }

    /** 读取 EXIF 方向；读取失败按 NORMAL 处理（与旧实现一致，不阻断背景显示）。 */
    private fun readExifOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** 按进程堆比例取解码预算（1/8，夹在 16–64MiB）：低 RAM 设备不会与封面缓存叠加 OOM。 */
    private fun maxDecodedBytes(): Long =
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(MinDecodedBytes, MaxDecodedBytes)

    /**
     * 按 [initialSample] 解码，并确保实际字节数不超过 [maxBytes]。
     *
     * 显式 `inPreferredConfig = ARGB_8888` 消除 Android 10+ 的 RGBA_F16 分支；解码后再按
     * 真实 `byteCount` 兜底：仍超预算就加大 sample 重解（有界循环，超限则放弃背景，
     * 宁可不显示也不把超限位图交给绘制）。旧位图在重解前回收，避免两份大图同时驻留。
     */
    private fun decodeWithinBudget(
        context: Context,
        uri: Uri,
        initialSample: Int,
        maxBytes: Long,
    ): Bitmap? {
        var sample = initialSample
        var attempts = 0
        while (true) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val candidate = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            if (candidate.byteCount <= maxBytes) return candidate
            candidate.recycle()
            if (++attempts > MaxDecodeResampleAttempts) return null
            sample *= 2
        }
    }

    /**
     * EXIF 方向 → 位图变换矩阵；NORMAL / UNDEFINED 返回 null（无需变换）。
     * 翻转与旋转成对的方向必须两步都做（先转再镜像），否则得到镜像错误的图像。
     */
    private fun exifMatrix(orientation: Int): Matrix? = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> return null
        }
    }
}

/**
 * 背景图采样档位：同时满足两个预算才停止——
 * 1) 长边 < 2×屏长边（再减半就会低于屏长边）；
 * 2) 解码字节 ≤ [maxBytes]（默认 64MiB；实际调用按进程堆比例传入 16–64MiB）。只按长边不够：
 *    3200 长边的屏幕上 6000×6000 的长边仍在 2× 以内，会解出约 144MiB，超过 API 29 的绘制
 *    上限与 Android 16 的硬件位图上限。
 *
 * 纯函数（便于单测）：原实现要求宽高同时超限才减半，30000×2000 这类极端宽高比第一次迭代
 * 就停，按原尺寸解码约 229MiB。
 */
internal fun backgroundSampleSize(
    width: Int,
    height: Int,
    maxEdge: Int,
    maxBytes: Long = MaxDecodedBytes,
): Int {
    // 当前调用方已把 maxEdge 收敛到 >= 1；这里再防一手，避免预算为 0 时循环不终止
    if (width <= 0 || height <= 0 || maxEdge <= 0 || maxBytes <= 0) return 1
    var sample = 1
    while (true) {
        val sampledWidth = width.toLong() / sample
        val sampledHeight = height.toLong() / sample
        val longEdgeInBounds = maxOf(sampledWidth, sampledHeight) < 2L * maxEdge
        val bytesInBudget =
            sampledWidth * sampledHeight * DecodedBytesPerPixel <= maxBytes
        if (longEdgeInBounds && bytesInBudget) return sample
        sample *= 2
    }
}

/**
 * 自定义背景图片层：铺在页面背景之上、主题色粒子之下。
 *
 * - 未设置背景图时不组合任何内容，零开销；
 * - 图片来自 [BackgroundImageCache]（进程级、只解码一次），因此进入任何页面都**不会先空后闪**；
 * - 切换图片 / 调整透明度 / 调整模糊都会即时重绘（新图解码完成前保留旧图）。
 */
@Composable
fun CustomBackgroundImageLayer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri by AppSettingsStore.backgroundImageUriState.collectSettingAsState()
    val alphaPercent by AppSettingsStore.backgroundImageAlphaState.collectSettingAsState()
    val blurPercent by AppSettingsStore.backgroundImageBlurState.collectSettingAsState()

    if (uri.isBlank()) return

    // 首次组合 / 换图 / CRITICAL 释放后立即重解码（旧图保留到新图就绪）
    val appContext = context.applicationContext
    val reloadSignal by BackgroundImageCache.reloadSignal
    LaunchedEffect(uri, reloadSignal) {
        BackgroundImageCache.ensureLoaded(appContext, uri)
    }
    // 后台 RUNNING_LOW 释放后不递增信号；回到前台（ON_START）按当前存储 uri 补一次，
    // 避免后台刚释放就被 LaunchedEffect 立即解回来。
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        scope.launch { BackgroundImageCache.ensureLoaded(appContext, uri) }
    }

    // 组合期直接读同一个 State：启动预热完成后会即时刷新，不需要等页面重新组合
    val image: ImageBitmap = BackgroundImageCache.image.value ?: return
    val blurDp = (blurPercent.coerceIn(0, 100) / 100f) * MaxBlurDp
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = alphaPercent.coerceIn(0, 100) / 100f,
        modifier = modifier
            .fillMaxSize()
            .then(
                if (blurDp > 0f) {
                    Modifier.blur(radius = blurDp.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                } else {
                    Modifier
                },
            ),
    )
}



/**
 * 背景图是否已「可以同步呈现」：未设置背景图时立即为真；已设置时等解码完成，
 * 或超过 [timeoutMillis] 兜底（安全相关界面不能因为图片解码慢而迟迟不出现）。
 *
 * 解码已明确失败（本次进程内标记不可用）时立即为真：锁门不再为一个永远不会出现的
 * 背景空等 400ms。冷启动时锁门与页面背景在同一帧组合，若不等就绪，门会先带着
 * 纯色底出现、背景图随后才弹出来（画面跳动）。锁门用它把两者对齐到同一帧。
 */
@Composable
fun rememberBackgroundImageReady(timeoutMillis: Long = 400L): Boolean {
    val uri by AppSettingsStore.backgroundImageUriState.collectSettingAsState()
    if (uri.isBlank()) return true
    if (BackgroundImageCache.unavailableUri.value == uri) return true
    val decoded = BackgroundImageCache.image.value
    if (decoded != null) return true
    var timedOut by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        delay(timeoutMillis)
        timedOut = true
    }
    return timedOut
}
