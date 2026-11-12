package com.tyranor.next.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.R

/**
 * 应用根背景统一组件：
 * - 复古玻璃：黑灰三档竖向渐变 + 白色顶部光晕 + 主题色对角环境光；
 * - 白雾玻璃：64dp 模糊图片底图 + 16% 白纱 + 38% 压暗 + 主题色环境光 + 轻暗角；
 * - 默认风格：纯色底 + 主题色粒子（由 [DefaultPageBackgroundLayer] 绘制）。
 * 所有顶层组合（MainActivity / AppScreenScaffold）必须经本组件包裹，否则透明页面背景会露出
 * 窗口底色。内部保留 Surface 以维持 contentColor 语义。
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                // 白雾玻璃：模糊图片底图 + 白纱提亮 + 主题色环境光
                AppThemeColors.isMilkGlass -> MilkGlassPageBackgroundLayer()
                // 复古玻璃：黑灰渐变 + 环境光；默认风格：纯色底 + 主题色粒子
                AppThemeColors.isGlass -> Box(Modifier.fillMaxSize().glassPageBackground(AppThemeColors.primary))
                else -> DefaultPageBackgroundLayer()
            }
            Box(Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

/**
 * 白雾玻璃外观的共享底图：模糊图片 + 16% 白纱 + 38% 压暗 + 主题色低透明度环境光。
 *
 * 当前底图是明亮彩色壁纸，需要压暗到参考图的深色调，但不能过暗；压暗后再叠白纱/
 * 环境光，保留蓝紫粉的低频色场。页面使用 64dp 实时模糊，浮层用 40dp。
 *
 * [blurRadius] 允许页面与浮层使用不同强度的实时高斯模糊；图片资源本身也做了轻度
 * 预模糊，API < 31 不支持 [blur] 时仍能保持柔和底图。
 */
@Composable
private fun MilkGlassBackdrop(
    modifier: Modifier = Modifier,
    blurRadius: Dp,
) {
    val accent = AppThemeColors.primary
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.bg_milk_glass),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment.Rectangle),
        )
        // 先提亮再整体压暗：既保留彩色壁纸的色相，又把明度落到参考图的深色区间。
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.16f)))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val glow = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = size.width * 0.85f,
                )
                onDrawBehind { drawRect(glow) }
            },
        )
    }
}

/**
 * 白雾玻璃外观的页面背景：共享底图 + 顶部/底部极轻暗角。
 *
 * 亮度关系对齐参考图：彩色底图中性区约 L160，16% 白纱 + 38% 压暗后约 L109；页面条目
 * 叠 [MilkSurface]（70% 白）后落在 L≈211，深色正文对比度约 9:1。
 */
@Composable
private fun MilkGlassPageBackgroundLayer() {
    Box(Modifier.fillMaxSize()) {
        MilkGlassBackdrop(Modifier.fillMaxSize(), blurRadius = 64.dp)
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val vignette = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.10f),
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.18f),
                )
                onDrawBehind { drawRect(vignette) }
            },
        )
    }
}

/**
 * 白雾玻璃弹窗/抽屉的面板底：同一张模糊页面底图 + 50% 白纱。
 *
 * 面板不铺浅色实底：用户要求浮层背景沿用页面背景。这里用同一张页面底图铺满面板，
 * 既保留页面背景的纹理，又遮住底层卡片/导航文字（直接半透明会让它们重影）。
 * 面板条目叠 [MilkItemOnPanel]（45% 白）；面板本身已有 50% 白纱，最终亮度约 L215，
 * 与页面 70% 条目（L≈211）对齐；面板与条目都使用深色正文。
 */
@Composable
fun MilkGlassPanelBackground(modifier: Modifier = Modifier) {
    Box(modifier) {
        MilkGlassBackdrop(Modifier.fillMaxSize(), blurRadius = 40.dp)
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.50f)))
    }
}

/**
 * 玻璃外观风格的页面背景绘制层（黑灰竖向渐变 + 白色顶部光晕 + 主题色对角环境光）。
 *
 * 单独抽出的原因（分析报告 §8.4 第 1 条 / §5.3 采样边界）：
 * 背景原本只画在 `GlassBackground`（Activity 根部），而液态玻璃底栏采样的是 `MainScreen`
 * 内容层的 `layerBackdrop` 录制结果——在采样层**之外**。玻璃风格下 `PageGrey` 为透明色，
 * 于是底栏会采到“没有背景”的内容（透镜边缘出现空采样/暗孔）。
 * 抽出后由需要被采样的内容层复用同一份绘制，保证背景与正文处于同一坐标原点的同一采样源，
 * 也避免两处各写一份渐变导致颜色漂移。
 *
 * [accent] 由调用方在组合期读取 `AppThemeColors.primary` 后传入（不在绘制期读取快照），
 * 保证「色调轮盘」换色时随重组刷新——与原实现把 accent 读在 `GlassBackground` 函数体内一致。
 * 注意：本 Modifier 不做风格判断，调用方需自行确认处于玻璃外观风格。
 */
fun Modifier.glassPageBackground(accent: Color): Modifier = this.drawWithCache {
    val baseBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to GlassBgTop,
            0.52f to GlassBgMid,
            1f to GlassBgBottom,
        ),
    )
    // 顶部白色环境光：圆心略高于屏幕顶端，半径约 55% 屏高，只做微弱层次
    val topGlow = Brush.radialGradient(
        colors = listOf(GlassBgGlow, Color.Transparent),
        center = Offset(size.width * 0.5f, -size.height * 0.08f),
        radius = size.height * 0.55f,
    )
    // 主题色环境光：右上（主，位于顶栏下方、卡片区内，透过玻璃可见）+ 左下（辅，补对角深度）
    val accentTopGlow = Brush.radialGradient(
        colors = listOf(accent.copy(alpha = 0.26f), Color.Transparent),
        center = Offset(size.width * 0.85f, size.height * 0.22f),
        radius = size.width * 1.05f,
    )
    val accentBottomGlow = Brush.radialGradient(
        colors = listOf(accent.copy(alpha = 0.12f), Color.Transparent),
        center = Offset(size.width * 0.10f, size.height * 0.78f),
        radius = size.width,
    )
    onDrawBehind {
        drawRect(baseBrush)
        drawRect(topGlow)
        drawRect(accentTopGlow)
        drawRect(accentBottomGlow)
    }
}

/**
 * 浅色档「悬浮玻璃」的边缘描边。
 *
 * 纯白/浅灰背景上，半透明玻璃表面与背景几乎同色，只剩内容透出时才看得见边界——需要一条
 * 中性色发丝线把悬浮轮廓勾出来。深色档不需要（深底本身有对比，且栏体自带高光/投影）。
 *
 * 与 [glassBorder] 的区别：后者只在玻璃外观风格下生效且用白色描边；本函数**不受外观风格限制**，
 * 用中性深色，供两档液态玻璃底栏在浅色档共用，保证同一套观感。
 */
fun Modifier.glassEdgeStroke(
    shape: Shape,
    width: Dp = GlassEdgeStrokeWidth,
    color: Color = GlassOpticalBlack.copy(alpha = GlassEdgeStrokeAlpha),
): Modifier =
    this.drawWithContent {
        drawContent()
        drawOutline(
            outline = shape.createOutline(size, layoutDirection, this),
            color = color,
            style = Stroke(width = width.toPx()),
        )
    }

/** 浅色档悬浮描边的统一宽度（两档底栏共用，避免各自漂移）。 */
val GlassEdgeStrokeWidth: Dp = 0.5.dp

/** 浅色档悬浮描边的统一不透明度（乘在中性黑上）。 */
const val GlassEdgeStrokeAlpha: Float = 0.10f

/**
 * 玻璃风格 0.5dp 发丝描边；默认风格原样返回。
 * 颜色/显隐由 [AppThemeColors] 快照驱动，风格切换自动重组刷新。
 * 用 drawWithContent 在内容之后绘制，保证描边盖在卡片/条目背景之上。
 */
fun Modifier.glassBorder(
    shape: Shape = AppComponentShape,
    width: Dp = 0.5.dp,
    color: Color = GlassBorder,
): Modifier =
    if (!AppThemeColors.isGlass) {
        this
    } else {
        this.drawWithContent {
            drawContent()
            drawOutline(
                outline = shape.createOutline(size, layoutDirection, this),
                color = color,
                style = Stroke(width = width.toPx()),
            )
        }
    }
