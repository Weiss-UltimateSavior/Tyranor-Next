package com.tyranor.next.core.unpack

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Rust 封包 JNI 调用的公共脚手架：阻塞式 native 调用 + 独立轮询线程上报进度。
 *
 * Rust 侧每格式只有一份全局进度槽，进度只能轮询取得；取消经 [cancel] 点火
 * Rust 侧 flag，native 调用随后以 `cancelled` 错误返回，此处转译为哨兵异常。
 * 同一格式禁止并发（Rust 全局槽会被互踩）；调用方必须经 [ArchiveOpGate] 取进程级单飞。
 */
internal object NativeArchiveOp {
    private const val POLL_INTERVAL_MS = 100L
    private const val POLLER_JOIN_MS = 2000L

    data class ProgressSnapshot(
        val bytes: Long,
        val total: Long,
        val fileBytes: Long,
        val fileTotal: Long,
        val name: String,
    )

    data class ResultCounts(val total: Int, val success: Int, val error: Int)

    /**
     * 执行阻塞式 [call] 并轮询 [snapshot] 上报进度（总字节 + 当前文件字节，双层进度条数据源）。
     * 取消或 `cancelled` 错误抛 [ArchiveCancelledException]。
     */
    fun run(
        what: String,
        isCancelled: () -> Boolean,
        onProgress: (
            writtenBytes: Long,
            totalBytes: Long,
            fileWrittenBytes: Long,
            fileTotalBytes: Long,
            entryName: String,
        ) -> Unit,
        snapshot: () -> ProgressSnapshot,
        cancel: () -> Unit,
        call: () -> String?,
    ): ResultCounts {
        if (isCancelled()) throw ArchiveCancelledException(what)
        val stop = AtomicBoolean(false)
        val poller = thread(isDaemon = true, name = "archive-progress") {
            while (!stop.get()) {
                if (isCancelled()) {
                    runCatching { cancel() }
                } else {
                    runCatching { snapshot() }.getOrNull()?.let { snap ->
                        onProgress?.invoke(snap.bytes, snap.total, snap.fileBytes, snap.fileTotal, snap.name)
                    }
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        try {
            val json = try {
                call()
            } catch (error: IOException) {
                // 精确匹配哨兵：路径里恰好含 "cancelled" 的普通 IO 失败不得误报为取消。
                if (isCancelled() || error.message == "cancelled") {
                    throw ArchiveCancelledException(what)
                }
                throw error
            } ?: throw IOException("Archive operation returned no result: $what")
            return parseResult(json, what)
        } finally {
            stop.set(true)
            poller.join(POLLER_JOIN_MS)
        }
    }

    fun parseResult(json: String, what: String): ResultCounts {
        return try {
            val obj = org.json.JSONObject(json)
            ResultCounts(
                total = obj.getInt("total"),
                success = obj.getInt("success"),
                error = obj.getInt("error"),
            )
        } catch (error: Exception) {
            throw IOException("Archive result unparseable for $what: ${json.take(200)}")
        }
    }
}
