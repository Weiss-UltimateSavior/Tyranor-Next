package com.core.web

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.DirectoryIteratorException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** 会话内索引缓存；扫描只在独立执行器执行，HTTP 请求只读取已发布的快照。 */
internal class PluginFileIndexCache(
    private val build: (focused: Boolean) -> ByteArray,
    private val isLimitFailure: (IOException) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
    private val retryNanos: Long = 5_000_000_000L,
    private val maxTransientAttempts: Int = 3,
    private val onDegraded: (IOException) -> Unit = {},
) {
    private data class Snapshot(val bytes: ByteArray?, val failure: IOException?, val retryAt: Long? = null)
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var stopped = false
    private var consecutiveTransientFailures = 0
    private val building = AtomicBoolean()
    private val queued = AtomicBoolean()

    /** Returns null while scanning; never waits for an executor or an active scan. */
    fun request(executor: Executor): ByteArray? {
        checkActive()
        available()?.let { return it }
        if (queued.compareAndSet(false, true)) {
            try {
                executor.execute {
                    try { get() } catch (_: IOException) {
                        // Deterministic failures are published; transient errors retry on the next poll.
                    } finally { queued.set(false) }
                }
            } catch (error: RejectedExecutionException) {
                queued.set(false)
                throw error
            }
        }
        return available()
    }

    /** Background builder. Concurrent readers receive pending rather than waiting on a scan lock. */
    fun get(): ByteArray {
        checkActive()
        available()?.let { return it }
        if (!building.compareAndSet(false, true)) throw IOException("Plugin file index pending")
        try {
            available()?.let { return it }
            try {
                val bytes = invokeBuild(false)
                checkActive()
                consecutiveTransientFailures = 0
                snapshot = Snapshot(bytes, null)
                return bytes
            } catch (error: IOException) {
                checkActive()
                if (!isLimitFailure(error)) {
                    publishTransientOrTerminal(error)
                    throw error
                }
                onDegraded(error)
                try {
                    val bytes = invokeBuild(true)
                    checkActive()
                    consecutiveTransientFailures = 0
                    snapshot = Snapshot(bytes, null, nanoTime() + retryNanos)
                    return bytes
                } catch (focusedError: IOException) {
                    checkActive()
                    if (isLimitFailure(focusedError)) {
                        consecutiveTransientFailures = 0
                        snapshot = Snapshot(null, focusedError, nanoTime() + retryNanos)
                    } else {
                        publishTransientOrTerminal(focusedError)
                    }
                    throw focusedError
                }
            }
        } finally { building.set(false) }
    }

    private fun invokeBuild(focused: Boolean): ByteArray = try {
        build(focused)
    } catch (error: DirectoryIteratorException) {
        throw (error.cause ?: error)
    }

    private fun publishTransientOrTerminal(error: IOException) {
        if (++consecutiveTransientFailures >= maxTransientAttempts) {
            consecutiveTransientFailures = 0
            snapshot = Snapshot(null, error, nanoTime() + retryNanos)
        } else {
            snapshot = null
        }
    }

    private fun available(): ByteArray? {
        val current = snapshot ?: return null
        if (current.retryAt != null && nanoTime() - current.retryAt >= 0L) return null
        current.failure?.let { throw it }
        return current.bytes
    }

    /** Does not wait for the builder; its executor is interrupted by the owning host. */
    fun stop() { stopped = true }

    private fun checkActive() {
        if (stopped || Thread.currentThread().isInterrupted) throw InterruptedIOException("Plugin file index stopped")
    }
}
