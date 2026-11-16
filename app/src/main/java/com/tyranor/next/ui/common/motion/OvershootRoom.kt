package com.tyranor.next.ui.common.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/**
 * 给「按压 / 回弹会冲出自身边界」的内容留出绘制余量：对外布局尺寸不变，内部实际每侧多出 [horizontal] / [vertical]，
 * 多出的部分只是留白（不参与点击与布局）。
 *
 * 为什么需要：`pressSquish` 松手回弹时形变会略微冲过停位（Q 弹的过冲，长按蓄力后松手可达 3~5%）；
 * 如果卡片外面包着一个会裁切到自身边界的滚动容器（`HorizontalPager` 等），冲出去的那一圈会被整齐地切掉，
 * 四个圆角被切成直角、卡片像被框住了一样「卡」一下。把该容器放大一圈、里面的内容再缩回原位
 * （容器的 `contentPadding` 取同样的值），过冲就画在容器的边界之内，不会被切。
 *
 * 用法（只在「增强」档，「普通」档没有 `pressSquish` 过冲，保持原样）：
 * ```
 * HorizontalPager(
 *     modifier = Modifier.fillMaxWidth().overshootRoom(horizontal = 9.dp, vertical = 6.dp),
 *     contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
 *     ...
 * )
 * ```
 * 约束：宽高约束必须有界（上游已有 `fillMaxWidth` / 固定尺寸），横向余量需小于页间距的一半，免得相邻页从余量里露出来。
 */
fun Modifier.overshootRoom(horizontal: Dp, vertical: Dp = 0.dp): Modifier =
    if (horizontal <= 0.dp && vertical <= 0.dp) {
        this
    } else {
        layout { measurable, constraints ->
            val h = horizontal.roundToPx().coerceAtLeast(0)
            val v = vertical.roundToPx().coerceAtLeast(0)
            // 按放大一圈的约束测量：容器（及其 contentPadding）比对外报告的尺寸大，页面内容正好是原来的大小
            val placeable = measurable.measure(constraints.offset(horizontal = 2 * h, vertical = 2 * v))
            layout((placeable.width - 2 * h).coerceAtLeast(0), (placeable.height - 2 * v).coerceAtLeast(0)) {
                // 放大的那一圈对称地落在对外尺寸之外
                placeable.place(-h, -v)
            }
        }
    }
