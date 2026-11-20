package com.tyranor.next.core.auth

import androidx.annotation.VisibleForTesting
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * 刷新去重协调器（单飞，JVM 可测；Bangumi / Hikarinagi 两域共用的 core/auth 中性组件）：
 * 同一时刻至多一个线程执行任务；并发到达的其余线程等待在途任务结束后仅重走 [run] 的
 * recheck（命中即采用其写回结果，失败则同步放弃），不重复执行任务。等待用谓词循环排除
 * 虚假唤醒；获取执行权前先做一次 recheck，关闭「快路径检查与进闸之间恰有并发刷新完成
 * 写回」窗口内的顺序重复刷新。
 *
 * 契约：任务回调（网络 IO）不处于任何共享监视器临界区——两域刷新的取令牌入口因此不得
 * 在对象锁内执行网络请求，主线程的授权服务取锁点才不被最长 ~35s 的刷新阻塞。
 */
internal class SingleFlightRefresh {
    private val lock = ReentrantLock()
    private val idle: Condition = lock.newCondition()
    private var running = false
    private var lastResult: Any? = null

    /**
     * 无在途任务时由当前线程执行 [task]（返回其结果）；已有在途任务时等待其结束后
     * 调用 [adoptWaiter] 决定采纳结果（默认重走 [recheck]，使等待方兼容极短 TTL 等场景，
     * 同时仍能在主线程未登出时验证本地存储未在等待期间被清空或失效）。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> run(
        recheck: () -> T?,
        adoptWaiter: (leaderResult: T?) -> T? = { _ -> recheck() },
        task: () -> T,
    ): T? {
        var waited = false
        var observedLeaderResult: Any? = null
        lock.lock()
        try {
            while (running) {
                waited = true
                idle.awaitUninterruptibly()
            }
            if (waited) {
                observedLeaderResult = lastResult
            } else {
                // 双重检查：快速路径检查与进闸之间可能有并发刷新刚完成写回
                recheck()?.let { return it }
                running = true
            }
        } finally {
            lock.unlock()
        }
        if (waited) {
            // 在途任务已结束：不重复执行任务，交由 adoptWaiter 核对最新存储态
            return adoptWaiter(observedLeaderResult as T?)
        }
        var taskResult: Any? = null
        return try {
            task().also { taskResult = it }
        } finally {
            lock.lock()
            try {
                lastResult = taskResult
                running = false
                idle.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }

    @VisibleForTesting
    internal fun waiterCount(): Int {
        lock.lock()
        return try {
            lock.getWaitQueueLength(idle)
        } finally {
            lock.unlock()
        }
    }
}
