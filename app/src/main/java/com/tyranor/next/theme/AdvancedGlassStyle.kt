package com.tyranor.next.theme

import android.graphics.BlurMaskFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 高级玻璃外观风格的**独立实现**（复古玻璃的绘制代码不参与本文件，见 `GlassStyle.kt`）。
 *
 * 复刻参考图（系统通知栏）的材质：
 * 1. 背景 = 真实图片的重度模糊（游戏封面拼贴，见 `ui/common/glass/AmbientBackdrop.kt`）
 *    + 压暗 scrim + 四角暗角；无封面时退化为主题色软色斑（仍呈“被模糊”的观感）；
 * 2. 卡片 = 浅色磨砂膜（[AdvancedGlassSurface]，15% 白）+ 上亮下暗渐变描边 + 顶边高光；
 * 3. 悬浮元素（默认导航条）= 真 backdrop 采样 + 高光 + 投影（在 `MainScreen` 组装）。
 *
 * 弹窗/抽屉是独立 window，采不到主窗口内容，仍用深色玻璃面板 + 光学描边保证可读性。
 */

/**
 * 高级玻璃描边：与悬浮默认导航条完全同款的两层边缘——
 * 1. 加色内描边（导航条 `Highlight.Default` 的等价实现）：45° 方向白色高光、`BlendMode.Plus`、
 *    描边加宽后裁剪到形状内部只露内侧一半——这是导航栏那圈亮边的来源；
 * 2. 竖向渐变发丝描边（上 [AdvancedGlassBorder] → 下 [AdvancedGlassBorderFaint]）+ 顶边高光条
 *    （[AdvancedGlassSpecular] 向下渐隐）。
 * 由 [glassBorder] 在高级档转发，调用点无需感知。
 */
fun Modifier.advancedGlassBorder(
    shape: Shape = AppComponentShape,
    width: Dp = 0.5.dp,
    rim: Boolean = true,
): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val stroke = Stroke(width.toPx())
    val borderBrush = Brush.verticalGradient(listOf(AdvancedGlassBorder, AdvancedGlassBorderFaint))
    val path = Path().apply { addOutline(outline) }
    val specularBrush = Brush.verticalGradient(
        colors = listOf(AdvancedGlassSpecular, Color.Transparent),
        startY = 0f,
        endY = size.height * AdvancedGlassSpecularHeightFraction,
    )
    // 受光内描边：45° 对角渐变模拟导航条 Highlight 的方向性高光（falloff 越大越集中在受光侧）
    val rimBrush = Brush.linearGradient(
        colors = listOf(AdvancedGlassRim, AdvancedGlassRimFaint),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    // 描边宽度 ×2 再裁剪到形状内，只保留内侧一半（库 HighlightNode 同款做法）
    val rimStroke = Stroke(width.toPx() * 2f)
    onDrawWithContent {
        drawContent()
        clipPath(path) {
            drawRect(brush = specularBrush)
            if (rim) {
                drawOutline(
                    outline = outline,
                    brush = rimBrush,
                    style = rimStroke,
                    blendMode = BlendMode.Plus,
                )
            }
        }
        drawOutline(outline = outline, brush = borderBrush, style = stroke)
    }
}

/**
 * 高级玻璃投影：与悬浮默认导航条同款柔光投影（[Shadow.Default] 的等价实现）——
 * 用 BlurMaskFilter 画偏移后的轮廓，并把形状内部挖空，保证半透明玻璃膜不会被投影压暗；
 * 默认风格与复古玻璃原样返回。
 *
 * 必须挂在组件**背景之前**（或 Card/MiuixCard 这类背景在内部的组件链上），
 * 否则投影会画在背景之上。列表行等平铺元素不必挂。
 */
fun Modifier.glassShadow(
    shape: Shape = AppComponentShape,
    radius: Dp = AdvancedGlassShadowRadius,
    offsetY: Dp = AdvancedGlassShadowOffsetY,
    color: Color = AdvancedGlassShadowColor,
): Modifier =
    if (!AppThemeColors.isAdvancedGlass) {
        this
    } else {
        this.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val blurPx = radius.toPx()
            val dy = offsetY.toPx()
            val shadowPaint = Paint().apply {
                this.color = color
                asFrameworkPaint().maskFilter =
                    if (blurPx > 0f) BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) else null
            }
            // 只在形状外绘制：整块矩形与形状做 even-odd 差集，避免投影透进半透明膜里
            val outsidePath = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(
                    Rect(
                        -blurPx * 2f,
                        -blurPx * 2f,
                        size.width + blurPx * 2f,
                        size.height + blurPx * 2f,
                    ),
                )
                addOutline(outline)
            }
            onDrawBehind {
                clipPath(outsidePath) {
                    drawIntoCanvas { canvas ->
                        canvas.save()
                        canvas.translate(0f, dy)
                        canvas.drawOutline(outline, shadowPaint)
                        canvas.restore()
                    }
                }
            }
        }
    }

/**
 * 高级玻璃弹窗/抽屉面板的表面：从页面背景取色的竖向渐变 + 顶端取色（供抽屉把手条使用）。
 * 抽屉的位置由内部 anchors 布局偏移决定，背景必须画在内容层，把手条用 [topColor] 续色避免接缝。
 */
@Immutable
data class AdvancedGlassPanelSurface(
    val brush: Brush,
    val topColor: Color,
)

/**
 * 高级玻璃弹窗/抽屉面板渐变：**从页面背景取色**（环境底图按高度三等分的平均色），
 * 而不是渲染成中性灰。取到的颜色向深色基底压暗 [AdvancedGlassPanelDarken] 后保留少量
 * 透明度（[AdvancedGlassPanelAlpha]），使面板呈「页面背景的磨砂版」且正文可读；
 * 底图为空（无封面库）时用主题色 + 兜底背景色生成。
 *
 * 弹窗/抽屉是独立 window，采不到主窗口 backdrop，本渐变是「跨窗口取色」的替代实现。
 */
@Composable
fun rememberAdvancedGlassPanelSurface(): AdvancedGlassPanelSurface {
    val backdrop = AppThemeColors.ambientBackdrop
    val primary = AppThemeColors.primary
    return remember(backdrop, primary) {
        // 取色失败（尺寸异常）时退回主题色兜底，保证渐变至少有两档颜色
        val stops = backdrop?.let(::sampleBackdropBandColors)
            ?.takeIf { it.size >= 2 }
            ?: fallbackPanelStops(primary)
        val colors = stops.map { stop ->
            lerp(stop, AdvancedGlassPanelBase, AdvancedGlassPanelDarken)
                .copy(alpha = AdvancedGlassPanelAlpha)
        }
        AdvancedGlassPanelSurface(
            brush = Brush.verticalGradient(colors),
            topColor = colors.first(),
        )
    }
}

/** 面板压暗基底：中性近黑（避免彩色底图取色后发灰或过亮）。 */
private val AdvancedGlassPanelBase = Color(0xFF121418)

/** 面板取色向基底压暗的比例（0.72 ≈ 保留 28% 底色；保证白色正文对比，同时保留底图色相）。 */
private const val AdvancedGlassPanelDarken = 0.72f

/** 面板不透明度：足够实（用户要求不那么透明），保留少量背景透色。 */
private const val AdvancedGlassPanelAlpha = 0.94f

/** 组件顶边高光条高度占组件高度的比例（0.22，避免压灰顶部内容）。 */
private const val AdvancedGlassSpecularHeightFraction = 0.22f

/** 受光内描边（受光侧）的白色不透明度：对齐导航条 Highlight.Default 的 intensity，压暗避免刺眼。 */
private val AdvancedGlassRim = Color.White.copy(alpha = 0.30f)

/** 受光内描边（背光侧）的白色不透明度：保留一圈微亮轮廓，方向性由 45° 渐变体现。 */
private val AdvancedGlassRimFaint = Color.White.copy(alpha = 0.05f)

/** 高级玻璃组件投影的模糊半径（与导航条 Shadow.Default 的 24dp 同量级，略收紧）。 */
private val AdvancedGlassShadowRadius = 16.dp

/** 高级玻璃组件投影的垂直偏移。 */
private val AdvancedGlassShadowOffsetY = 3.dp

/** 高级玻璃组件投影颜色（比导航条略实，保证浅底上可见）。 */
private val AdvancedGlassShadowColor = Color.Black.copy(alpha = 0.22f)

/** 底图按高度三等分取平均色（步进采样，288×432 底图上开销可忽略）。 */
private fun sampleBackdropBandColors(image: ImageBitmap): List<Color> {
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return emptyList()
    val pixels = image.toPixelMap().buffer
    return (0 until 3).map { band ->
        val startY = height * band / 3
        val endY = (height * (band + 1) / 3).coerceAtLeast(startY + 1)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        var y = startY
        while (y < endY) {
            var x = 0
            while (x < width) {
                val pixel = pixels[y * width + x]
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
                x += 8
            }
            y += 8
        }
        if (count == 0) {
            Color.Transparent
        } else {
            Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        }
    }
}

/** 无封面库时的兜底取色：主题色 + 兜底背景渐变，仍保持彩色而非灰。 */
private fun fallbackPanelStops(primary: Color): List<Color> = listOf(
    lerp(primary, AdvancedGlassBgTop, 0.55f),
    AdvancedGlassBgMid,
    AdvancedGlassBgBottom,
)

/**
 * 高级玻璃根背景：封面拼贴模糊图（[AppThemeColors.ambientBackdrop]）按 cover 缩放铺满，
 * 叠压暗 scrim 与四角暗角；底图尚未生成（或库里没有封面）时退化为主题色软色斑。
 *
 * 底图本身已由 `AmbientBackdrop` 做过多轮盒式模糊（全 API 一致），这里只负责铺图与压暗，
 * 不做 RenderEffect——滚动/切页零管线开销。绘制期读取 [AppThemeColors.ambientBackdrop]
 * 快照，底图生成后会自行失效重绘。
 */
fun Modifier.advancedGlassPageBackground(): Modifier = this.drawWithCache {
    val image = AppThemeColors.ambientBackdrop
    // 四角暗角：中心 55% 半径内透明，向外渐黑压边（对齐参考图的暗角层次）
    val vignette = Brush.radialGradient(
        colorStops = arrayOf(
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.40f),
        ),
        center = Offset(size.width * 0.5f, size.height * 0.5f),
        radius = size.maxDimension * 0.72f,
    )
    if (image != null) {
        // cover 缩放：短边铺满，长边居中裁切
        val scale = max(size.width / image.width, size.height / image.height)
        val dstWidth = image.width * scale
        val dstHeight = image.height * scale
        val dstOffset = Offset(
            (size.width - dstWidth) * 0.5f,
            (size.height - dstHeight) * 0.5f,
        )
        onDrawBehind {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(dstOffset.x.toInt(), dstOffset.y.toInt()),
                dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt()),
                filterQuality = FilterQuality.Low,
            )
            // 压暗：保证卡片上的浅色文字与顶部栏标题可读（亮封面经 58% 压暗后仍在安全区）
            drawRect(Color.Black.copy(alpha = 0.58f))
            drawRect(vignette)
        }
    } else {
        // 兜底：主题色软色斑（软过渡天然“模糊”，无封面库时仍成立）
        val accent = AppThemeColors.primary
        val baseBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to AdvancedGlassBgTop,
                0.52f to AdvancedGlassBgMid,
                1f to AdvancedGlassBgBottom,
            ),
        )
        val blobMain = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.34f), Color.Transparent),
            center = Offset(size.width * 0.78f, size.height * 0.20f),
            radius = size.width * 1.15f,
        )
        val blobAux = Brush.radialGradient(
            colors = listOf(lerp(accent, Color.White, 0.35f).copy(alpha = 0.20f), Color.Transparent),
            center = Offset(size.width * 0.15f, size.height * 0.45f),
            radius = size.width * 0.95f,
        )
        val blobCool = Brush.radialGradient(
            colors = listOf(lerp(accent, Color(0xFF4D7CFE), 0.30f).copy(alpha = 0.14f), Color.Transparent),
            center = Offset(size.width * 0.55f, size.height * 0.85f),
            radius = size.width,
        )
        onDrawBehind {
            drawRect(baseBrush)
            drawRect(blobMain)
            drawRect(blobAux)
            drawRect(blobCool)
            drawRect(vignette)
        }
    }
}
