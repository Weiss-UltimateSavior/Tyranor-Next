package com.tyranor.next.ui.common.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/** 页面容器 alpha 不超过它就视为隐藏（肉眼不可见）。 */
internal const val HiddenPageAlpha = 0.002f

/**
 * 常驻组合但当前不可见（alpha = 0）的页面不把内容画进显示列表。
 *
 * 为什么：HWUI 每一帧都要遍历整棵渲染节点树（`prepareTree`，同时阻塞 UI 线程做同步），而主界面 / 引擎页为了切页不卡常驻组合了
 * 好几个页面，每个页面有几十上百个图层节点。隐藏页虽然 alpha = 0 不可见，节点照样每帧被遍历——空闲时约 45% 的渲染线程 CPU 花在这上面。
 * 容器不画内容 = 子节点不在显示列表里 = 不被遍历；页面一显现（[visible] 变真）当帧就重新画回来（子节点自己的显示列表仍然有效，
 * 重新引用即可）。只影响绘制：布局 / 测量 / 状态 / 无障碍 / 触摸都不受影响，显示效果逐像素不变。
 *
 * 用法：挂在页面容器的 `graphicsLayer`（位移 / 透明度）之后；[visible] 必须是**只在显隐边界翻转**的派生状态（`derivedStateOf`），
 * 不能直接读逐帧变化的 alpha（否则切页动画每帧都要重录容器的显示列表）。
 * 不要用于靠 Compose 绘制回调才画出来的原生视图（WebView 首页）。
 */
internal fun Modifier.skipDrawWhenHidden(visible: () -> Boolean): Modifier =
    drawWithContent { if (visible()) drawContent() }
