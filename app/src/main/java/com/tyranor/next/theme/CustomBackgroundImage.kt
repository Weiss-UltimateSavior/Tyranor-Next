package com.tyranor.next.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.collectSettingAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 模糊强度换算上限：100 → 24dp 高斯模糊。 */
private const val MaxBlurDp = 24f

/** ARGB_8888 每像素字节数。 */
private const val DecodedBytesPerPixel = 4L

/**
 * 背景图解码字节上限（64MiB）：独立于屏幕长边预算，同时低于 API 29 约 100MiB 的绘制限制
 * 与 Android 16 的 150MiB 硬件位图上限。
 */
private const val MaxDecodedBytes = 64L * 1024 * 1024

/**
 * 自定义背景图的进程级缓存。
 *
 * 背景图在**所有页面**共用同一份位图：若每个页面各自解码（原来的做法），进入新页面时
 * 第一帧还没有图、解码完成后才突然出现——就是"进入时闪一下"。这里只解码一次并缓存，
 * 之后所有页面（含二级页、主界面切换）在组合期就能同步拿到位图，**不会闪**。
 *
 * 切换图片时保留旧图直到新图就绪（[set] 一次性替换），避免换图瞬间出现空背景。
 */
internal object BackgroundImageCache {
    /**
     * 当前已就绪的位图。**必须是同一个 State 实例**：每次赋值都新建 `mutableStateOf` 的话，
     * 已经订阅旧实例的界面永远读不到新值（表现就是"刚开始没图、之后也不出现"）。
     */
    private val mutableImage = mutableStateOf<ImageBitmap?>(null)
    val image: androidx.compose.runtime.State<ImageBitmap?> = mutableImage

    /** 当前位图对应的 uri（用于换图时判断是否需要重新解码）。 */
    private var currentUri: String = ""

    /** 正在解码中的 uri：避免同图重复解码。 */
    private var loadingUri: String = ""

    /** 串行化：并发调用（启动预热 + 设置页 effect）会同时读写 currentUri/loadingUri。 */
    private val loadMutex = Mutex()

    /** 应用启动或设置里换图时调用：解码完成后一次性替换缓存（旧图保留到新图就绪）。 */
    suspend fun ensureLoaded(context: Context, uri: String) = loadMutex.withLock {
        ensureLoadedLocked(context, uri)
    }

    private suspend fun ensureLoadedLocked(context: Context, uri: String) {
        if (uri.isBlank()) {
            currentUri = ""
            loadingUri = ""
            mutableImage.value = null
            return
        }
        if (uri == currentUri && mutableImage.value != null) return
        if (uri == loadingUri) return
        loadingUri = uri
        // finally 必须清理：解码途中被取消（清空背景 / 快速换图）时若停在 loadingUri，
        // 之后重选同一张图会命中上面的 early return，背景永远加载不出来
        val decoded = try {
            withContext(Dispatchers.IO) { decode(context, uri) }
        } finally {
            if (loadingUri == uri) loadingUri = ""
        }
        if (decoded == null) return
        currentUri = uri
        mutableImage.value = decoded.asImageBitmap()
    }

    /** 走与卡片封面相同的采样解码：只按需尺寸解码，避免整张大图进内存。 */
    private fun decode(context: Context, uriText: String): Bitmap? = runCatching {
        val uri = Uri.parse(uriText)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        // 采样上限按**屏幕长边**而不是固定宽度：背景只需铺满当前屏幕，
        // 固定 1440 宽在平板上偏小、在高分屏上又可能超出 2 倍。
        // 下限 1 防止极端窗口上报 0 时采样循环永不退出。
        val maxEdge = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
            .coerceAtLeast(1)
        val sample = backgroundSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null
        // 按 EXIF 方向摆正：旋转与镜像都要处理——只映射三个纯旋转方向时，
        // TRANSPOSE / TRANSVERSE 这类「旋转 + 翻转」的照片会侧转且镜像错误
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        }.getOrNull()?.let { exif ->
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            val matrix = exifMatrix(orientation) ?: return@let decoded
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { transformed -> if (transformed !== decoded) decoded.recycle() }
        } ?: decoded
    }.getOrNull()

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
 * 2) 解码字节 ≤ [MaxDecodedBytes]。只按长边不够：3200 长边的屏幕上 6000×6000 的长边仍在
 *    2× 以内，会解出约 144MiB，超过 API 29 的绘制上限与 Android 16 的硬件位图上限。
 *
 * 纯函数（便于单测）：原实现要求宽高同时超限才减半，30000×2000 这类极端宽高比第一次迭代
 * 就停，按原尺寸解码约 229MiB。
 */
internal fun backgroundSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    // 当前调用方已把 maxEdge 收敛到 >= 1；这里再防一手，避免预算为 0 时循环不终止
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    while (true) {
        val sampledWidth = width.toLong() / sample
        val sampledHeight = height.toLong() / sample
        val longEdgeInBounds = maxOf(sampledWidth, sampledHeight) < 2L * maxEdge
        val bytesInBudget =
            sampledWidth * sampledHeight * DecodedBytesPerPixel <= MaxDecodedBytes
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

    // 启动时预加载；换图后重新解码（旧图保留到新图就绪）
    LaunchedEffect(uri) {
        BackgroundImageCache.ensureLoaded(context.applicationContext, uri)
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
 * 冷启动时锁门与页面背景在同一帧组合，若不等就绪，门会先带着纯色底出现、
 * 背景图随后才弹出来（画面跳动）。锁门用它把两者对齐到同一帧。
 */
@Composable
fun rememberBackgroundImageReady(timeoutMillis: Long = 400L): Boolean {
    val uri by AppSettingsStore.backgroundImageUriState.collectSettingAsState()
    if (uri.isBlank()) return true
    val decoded = BackgroundImageCache.image.value
    if (decoded != null) return true
    var timedOut by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        delay(timeoutMillis)
        timedOut = true
    }
    return timedOut
}
