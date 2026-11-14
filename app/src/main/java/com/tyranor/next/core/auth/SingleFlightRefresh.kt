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

    /**
     * 无在途任务时由当前线程执行 [task]（返回其结果）；已有在途任务时等待其结束后
     * 执行一次 [recheck] 并返回其结果（null 表示在途任务失败或未命中快速路径）。
     */
    fun <T> run(recheck: () -> T?, task: () -> T): T? {
        var waited = false
        lock.lock()
        try {
            while (running) {
                waited = true
                idle.awaitUninterruptibly()
            }
            if (!waited) {
                // 双重检查：快速路径检查与进闸之间可能有并发刷新刚完成写回
                recheck()?.let { return it }
                running = true
            }
        } finally {
            lock.unlock()
        }
        if (waited) {
            // 在途任务已结束：不重复执行任务，重走快速路径即可（失败则同步放弃）
            return recheck()
        }
        try {
            return task()
        } finally {
            lock.lock()
            try {
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
