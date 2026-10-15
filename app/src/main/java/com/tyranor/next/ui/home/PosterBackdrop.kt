package com.tyranor.next.ui.home

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.ui.common.boxBlurArgb
import com.tyranor.next.ui.game.coverColor
import com.tyranor.next.ui.game.rememberCoverBitmap

/** 光幕缩略图宽度：越小越接近「封面平均色分布」，复杂配色会被压成柔和的一团。 */
private const val BACKDROP_SAMPLE_WIDTH = 16

/**
 * 海报光幕：当前海报封面的模糊版铺在页面上半部分（含状态栏/顶栏），压暗后自上而下渐隐进页面底色。
 * 光幕随 [pagerState] 的滑动位置在当前页与下一页之间线性混合（下层恒不透明，上层按小数部分叠加）。
 * 调用方负责给本组件限定高度并放在页面内容之后（z 序在下）。
 */
@Composable
internal fun PosterBackdrop(
    games: List<ScanGame>,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    if (games.isEmpty()) return
    val lastIndex by rememberUpdatedState(games.size - 1)
    // 只在翻过整页时才触发重组；页内的逐帧混合走 graphicsLayer。
    // 必须以 pagerState 为 key：调用方切换轮播来源时会换新的 PagerState，而 derivedStateOf 的 lambda 会捕获创建时的
    // 那个，不重建就会一直读已经停住的旧 pager（光幕卡在第 1、2 张之间）
    val base by remember(pagerState) { derivedStateOf { pagerState.position().toInt().coerceIn(0, lastIndex) } }
    // 深色：压暗；浅色：提亮，避免在浅色页面上形成脏灰
    val dim = if (AppThemeColors.isDark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.28f)

    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // 自上而下渐隐：顶部全显，约 40% 处剩 60%，底部全透明
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        0.42f to Color.Black.copy(alpha = 0.6f),
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        // 当前页与下一页两层，按 (uri, 封面) 做 key：① rememberCoverBitmap 的状态只记一次，同一位置换游戏时若新封面
        // 已有缓存不会更新，会一直显示上一个游戏的封面；② 翻过整页时下一页的层直接升为当前页的层、状态保留，
        // 不会因为换了组合位置而重建、重新解码、闪一帧兜底色
        for (index in base..minOf(base + 1, games.lastIndex)) {
            val game = games[index]
            key(game.uri, game.coverUri) {
                BackdropLayer(
                    game,
                    Modifier.fillMaxSize().graphicsLayer {
                        // 下层（当前页）恒不透明；上层按滑动小数部分叠加 = 线性混合
                        alpha = if (index == base) 1f else (pagerState.position() - base).coerceIn(0f, 1f)
                    },
                )
            }
        }
        Box(Modifier.fillMaxSize().background(dim))
    }
}

@Composable
private fun BackdropLayer(game: ScanGame, modifier: Modifier) {
    val cover by rememberCoverBitmap(game.coverUri)
    val sample = remember(cover) { cover?.let(::sampleBackdrop) }
    // 无封面时退回引擎色，避免光幕出现空洞
    Box(modifier.background(game.engine.coverColor())) {
        sample?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // 放大后再模糊：边缘不出现透明渐变
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                    }
                    .blur(36.dp, BlurredEdgeTreatment.Unbounded),
            )
        }
    }
}

/**
 * 封面缩成 [BACKDROP_SAMPLE_WIDTH] 像素宽的小图再拉伸：API 31+ 由 Modifier.blur（RenderEffect）做真高斯；
 * API 26-30 无 RenderEffect，对小图先做 CPU 盒式模糊保证同样柔和（与旧快捷启动卡同一做法）。
 */
private fun sampleBackdrop(bmp: ImageBitmap): ImageBitmap {
    val src = bmp.asAndroidBitmap()
    val height = (src.height * BACKDROP_SAMPLE_WIDTH / src.width).coerceAtLeast(1)
    // 逐级减半再缩到目标：一次性大比例双线性缩小每个输出像素只采样约 4 个点（是抽样不是平均），繁杂封面颜色会抖；
    // 逐级减半近似盒式平均
    var halved = src
    while (halved.width >= BACKDROP_SAMPLE_WIDTH * 4) {
        halved = Bitmap.createScaledBitmap(halved, halved.width / 2, (halved.height / 2).coerceAtLeast(1), true)
    }
    val scaled = Bitmap.createScaledBitmap(halved, BACKDROP_SAMPLE_WIDTH, height, true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return scaled.asImageBitmap()
    val pixels = IntArray(BACKDROP_SAMPLE_WIDTH * height)
    scaled.getPixels(pixels, 0, BACKDROP_SAMPLE_WIDTH, 0, 0, BACKDROP_SAMPLE_WIDTH, height)
    boxBlurArgb(pixels, BACKDROP_SAMPLE_WIDTH, height, radius = 2)
    return Bitmap.createBitmap(pixels, BACKDROP_SAMPLE_WIDTH, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
