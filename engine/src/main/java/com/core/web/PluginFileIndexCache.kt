package com.core.web

import java.io.IOException
import java.io.InterruptedIOException

/** 会话内索引缓存；限额失败短时退避，临时 I/O 失败允许下一次立即重试。 */
internal class PluginFileIndexCache(
    private val build: (focused: Boolean) -> ByteArray,
    private val isLimitFailure: (IOException) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
    private val retryNanos: Long = 5_000_000_000L,
    private val onDegraded: (IOException) -> Unit = {},
) {
    private var complete: ByteArray? = null
    private var degraded: ByteArray? = null
    private var failure: IOException? = null
    private var retryAt = 0L
    private var backingOff = false
    @Volatile private var stopped = false

    @Synchronized fun get(): ByteArray {
        checkActive()
        complete?.let { return it }
        if (backingOff && nanoTime() - retryAt < 0L) {
            degraded?.let { return it }
            throw requireNotNull(failure)
        }
        try {
            val bytes = build(false)
            checkActive()
            complete = bytes
            degraded = null
            failure = null
            backingOff = false
            return bytes
        } catch (error: IOException) {
            checkActive()
            if (!isLimitFailure(error)) throw error
            onDegraded(error)
            try {
                val bytes = build(true)
                checkActive()
                degraded = bytes
                failure = null
                return bytes
            } catch (focusedError: IOException) {
                checkActive()
                degraded = null
                failure = focusedError
                throw focusedError
            } finally {
                retryAt = nanoTime() + retryNanos
                backingOff = true
            }
        }
    }

    /** 不等待构建锁；扫描方同时检查宿主 running 状态与线程中断。 */
    fun stop() { stopped = true }

    private fun checkActive() {
        if (stopped || Thread.currentThread().isInterrupted) throw InterruptedIOException("Plugin file index stopped")
    }
}
