package com.tyranor.next.core.game.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 宿主进程失败（例如 CMVS 在 separate process 里启动失败）发生在启动调用返回之后。
 * UI 侧的启动协程可能已被导航取消，因此把失败发布到进程级总线，由常驻界面消费一次。
 */
object LaunchFailureBus {
    private val current = MutableStateFlow<LaunchResult.Failure?>(null)

    val failure: StateFlow<LaunchResult.Failure?> = current.asStateFlow()

    @JvmStatic
    fun publish(value: LaunchResult.Failure) {
        current.value = value
    }

    @JvmStatic
    fun clear() {
        current.value = null
    }
}
