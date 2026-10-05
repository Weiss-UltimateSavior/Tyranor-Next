package com.tyranor.next.core.unpack

import kotlinx.coroutines.sync.Mutex

/**
 * 进程级解包/封包操作闸：Rust 全局进度/取消槽与 cache 暂存区都是进程级资源，
 * 同一时刻只允许一个操作。分屏/平行窗口会出现两个页面实例，各自 ViewModel 的
 * working 单飞互斥管不住对方，必须在进程层收口。
 */
object ArchiveOpGate {
    private val mutex = Mutex()

    /** 尝试独占操作闸；false = 另一操作（可能来自其他页面实例）正在进行。 */
    fun tryLock(): Boolean = mutex.tryLock()

    /** 释放操作闸（仅限 [tryLock] 返回 true 的调用方在收尾时调用）。 */
    fun unlock() = mutex.unlock()

    /** 闸空闲时执行一次清理型任务；被占用（有操作进行）则直接跳过返回 null。 */
    suspend fun <T> runIfIdle(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try { block() } finally { mutex.unlock() }
    }
}
