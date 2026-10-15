package com.tyranor.next.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow

/**
 * 侧边栏右滑手势的「让位」登记处：[rememberSidePanelSwipeModifier] 在根布局的 Initial 传递上先于子内容处理手势，
 * 子内容自己的横滑（如首页海报轮播往回翻）会被它抢走。需要保留横滑的控件登记自己的区域与「当前是否需要这次手势」，
 * 起点落在该区域且 [register] 的条件成立时，侧栏手势不识别、整个手势交还子内容。
 */
internal class SidePanelGestureGuard {
    /** 侧栏手势宿主（根布局）的坐标，由宿主在布局后上报。 */
    var hostCoordinates: LayoutCoordinates? = null

    private var area: LayoutCoordinates? = null
    private var wantsGesture: () -> Boolean = { false }

    fun register(coordinates: LayoutCoordinates, wantsGesture: () -> Boolean) {
        area = coordinates
        this.wantsGesture = wantsGesture
    }

    fun unregister() {
        area = null
        wantsGesture = { false }
    }

    /** [hostPoint] 为手势起点（宿主布局坐标）；落在登记区域内且登记方当下需要手势时返回 true。 */
    fun owns(hostPoint: Offset): Boolean {
        val host = hostCoordinates?.takeIf { it.isAttached } ?: return false
        val registered = area?.takeIf { it.isAttached } ?: return false
        return wantsGesture() && registered.boundsInWindow().contains(host.localToWindow(hostPoint))
    }
}
