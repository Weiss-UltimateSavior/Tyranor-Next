package com.tyranor.next.ui.common.motion

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 浮层留在原处、所在页面跳去二级页面再回来时的共用等待（操作抽屉 `MotionSheetController`、「游戏盒」翻开的书
 * `ShelfBookMotion` 共用，不各写一份）。
 *
 * 点了跳转之后等宿主真的让出前台：[HOST_NAVIGATION_START_TIMEOUT_MILLIS] 内没让位就当跳转没发生，返回 false。
 */
internal suspend fun awaitHostLeft(owner: LifecycleOwner): Boolean =
    withTimeoutOrNull(HOST_NAVIGATION_START_TIMEOUT_MILLIS) {
        owner.lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
    } != null

/**
 * 等宿主回来：上层页面一开始退出（[PageTransitions.notifyPopStarted]，与窗口返回动画、页面区的收回同一个起点）、
 * 宿主重新 RESUMED 或宿主销毁（[Lifecycle.Event.ON_DESTROY]），先到者为准。返回时登记已撤销。
 */
internal suspend fun awaitHostBack(owner: LifecycleOwner) {
    if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
    val back = CompletableDeferred<Unit>()
    val pop: () -> Unit = { back.complete(Unit) }
    val destroyObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) back.complete(Unit)
    }
    PageTransitions.registerPop(pop)
    owner.lifecycle.addObserver(destroyObserver)
    try {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            back.complete(Unit)
        }
        coroutineScope {
            val stateWatcher = launch {
                owner.lifecycle.currentStateFlow.first {
                    it.isAtLeast(Lifecycle.State.RESUMED) || it == Lifecycle.State.DESTROYED
                }
                back.complete(Unit)
            }
            back.await()
            stateWatcher.cancel()
        }
    } finally {
        owner.lifecycle.removeObserver(destroyObserver)
        PageTransitions.unregisterPop(pop)
    }
}

/** 点了跳转后最多等多久宿主让出前台（毫秒）。 */
internal const val HOST_NAVIGATION_START_TIMEOUT_MILLIS = 700L
