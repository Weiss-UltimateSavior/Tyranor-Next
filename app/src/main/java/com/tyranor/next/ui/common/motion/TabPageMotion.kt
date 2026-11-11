package com.tyranor.next.ui.common.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/** The main tabs and engine categories share the same enhanced motion; their ordinary transitions differ. */
internal object TabPageMotion {
    val slide = MotionTokens.page(0.001f)
    val fade = MotionTokens.fadeIn(0.002f)
    const val DepthScale = 0.06f
}

/**
 * 常驻分页（主界面四页、引擎页四个分类）切换时的位移 / 透明度 / 景深缩放，主界面底栏切页与引擎页分类翻页共用。
 *
 * 「增强」档：整宽水平位移与景深缩放放在**绘制阶段图层**里（[drawPhaseLayersInOwnedLayer]），透明度仍在外层 `graphicsLayer`。
 * 原来这几项都写在整页容器的 `graphicsLayer` 里，位移 / 缩放一变，Compose 就要把整页（几百个节点）的包围盒重新算一遍，
 * 切页期间每帧一次，真机 debug 包上占主线程约 18%；画出来的结果不变。
 * 命中测试不再跟着位移 / 缩放：切页期间主界面页本来就不接收触摸（`pageInteractive` 要等到位），引擎页只在刚起步时偏得多。
 * 「普通」档保持原版：位移与透明度写在同一个 `graphicsLayer` 里。
 *
 * @param key 节点身份（同一个分页的状态持有者），相同就不重建节点
 * @param offset 当前页的水平偏移（以整宽为单位，0 = 就位）；在图层阶段读取
 * @param alpha 当前页的透明度；在图层阶段读取
 * @param modulateAlpha 是否逐元素调制透明度、不用离屏图层合成（引擎页的列表用）
 */
internal fun Modifier.tabPageTransform(
    key: Any,
    offset: () -> Float,
    alpha: () -> Float,
    modulateAlpha: Boolean = false,
): Modifier = if (MotionMode.enhanced) {
    this
        .graphicsLayer {
            this.alpha = alpha()
            if (modulateAlpha) compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        .drawPhaseLayersInOwnedLayer(
            key,
            LayerSource { p, _ ->
                val d = offset()
                p.translationX = d * p.width
                val depth = 1f - TabPageMotion.DepthScale * kotlin.math.abs(d).coerceAtMost(1f)
                p.scaleX = depth
                p.scaleY = depth
            },
        )
} else {
    this.graphicsLayer {
        translationX = offset() * size.width
        this.alpha = alpha()
        if (modulateAlpha) compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}
