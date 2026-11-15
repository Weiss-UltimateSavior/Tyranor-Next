package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.SpringSpec

/**
 * 全应用统一的「语义动效 token」：同一类运动在任何页面都用同一条弹簧，保证节奏一致、协调。
 * 新增 / 修改动画时按语义选 token，不要再随手写 `MotionSprings.xxx(数字)`。
 *
 * 所有 token 都是弹簧：被打断时从当前位置与速度直接转向新目标（不跳变、不重头播放）。
 * 每个函数都接受 visibilityThreshold（IntSize(1,1) / IntOffset(1,1) / 0.001f 等），用于对应类型的动画。
 *
 * | 语义 | token | 手感 |
 * |---|---|---|
 * | 透明度 / 模糊 / 颜色 出现 | [fadeIn] | 0.28s 临界阻尼 |
 * | 透明度 / 模糊 / 颜色 消失 | [fadeOut] | 0.18s 临界阻尼，比出现快 |
 * | 内容替换的缩放 / 位移 / 尺寸（模糊替换、数字滚轮、状态切换） | [content] | 0.38s，轻微回弹 0.15 |
 * | 内容消失的缩放 / 位移 | [contentExit] | 0.24s，不回弹 |
 * | 布局变化（列表增删排序、展开收起、尺寸过渡） | [layout] | 0.42s，轻微回弹 0.15 |
 * | 布局收起 | [layoutExit] | 0.3s，不回弹 |
 * | 小元素弹出（角标、图标、提示） | [pop] | 0.42s，回弹 0.3 |
 * | 控件状态（开关拇指、滑块圆钮、选中指示器） | [control] | 0.36s，回弹约 0.38 |
 * | 按下 / 松手 | [press] / [release] / [releaseCalm] | 跟手快、松手 Q 弹；被滚动接手时平静收回 |
 * | 大面板进场（弹窗、抽屉、侧边栏） | [surfaceEnter] | 0.46s，回弹 0.2 |
 * | 大面板退场 | [surfaceExit] | 0.3s 临界阻尼 |
 * | 页面级（主界面切页） | [page] | 0.4s，阻尼 0.86 |
 * | 数值 / 进度（进度条、环形占用） | [value] | 0.5s 平滑 |
 * | 抖动（输入无效、操作被拒绝） | [wobble] | 0.38s，回弹 0.72（欠阻尼来回衰减） |
 * | 提示条进场与交接 | [notice] | 0.33s 周期，回弹 0.22（约 0.26s 到位） |
 *
 * 连续跟手（拖拽、视差、蓄力）不用弹簧 token：输入直驱，或逐帧积分弹簧（见 `LensMotionController`），
 * 不要「目标每变就重启 animateTo」。
 */
object MotionTokens {
    fun <T> fadeIn(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.effects(280, visibilityThreshold)

    fun <T> fadeOut(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.effects(180, visibilityThreshold)

    fun <T> content(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.snappy(380, visibilityThreshold)

    fun <T> contentExit(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.smooth(240, visibilityThreshold)

    fun <T> layout(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.snappy(420, visibilityThreshold)

    fun <T> layoutExit(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.smooth(300, visibilityThreshold)

    fun <T> pop(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.bouncy(420, visibilityThreshold)

    fun <T> control(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.response(0.36f, 0.62f, visibilityThreshold)

    fun <T> press(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.response(0.2f, 0.58f, visibilityThreshold)

    fun <T> release(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.response(0.46f, 0.36f, visibilityThreshold)

    fun <T> releaseCalm(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.response(0.3f, 1f, visibilityThreshold)

    fun <T> surfaceEnter(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.spring(460, 0.2f, visibilityThreshold)

    fun <T> surfaceExit(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.response(0.3f, 1f, visibilityThreshold)

    fun <T> page(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.spring(400, 0.14f, visibilityThreshold)

    fun <T> value(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.smooth(500, visibilityThreshold)

    /** 补位（删除 / 重排后其余项移到新位置）：偏快、几乎不回弹，别让用户等。 */
    fun <T> reflow(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.response(0.26f, 0.9f, visibilityThreshold)

    /** 抖动 / 晃动（输入无效、操作被拒绝）：欠阻尼，来回几次后衰减回原位。 */
    fun <T> wobble(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.spring(380, 0.72f, visibilityThreshold)

    /** 应用内提示条的进场与位置交接：阻尼 ≈ 0.82，约 0.26s 到位。 */
    fun <T> notice(visibilityThreshold: T? = null): SpringSpec<T> = MotionSprings.spring(380, 0.22f, visibilityThreshold)
}
