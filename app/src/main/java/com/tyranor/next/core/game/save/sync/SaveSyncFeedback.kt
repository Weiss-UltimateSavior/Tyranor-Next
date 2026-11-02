package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Process-wide completion feedback that remains pending until one resumed Activity consumes it. */
object SaveSyncFeedback {
    data class Notice(val id: Long, val report: SaveSyncReport)

    private val nextId = AtomicLong()
    private val pending = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = pending.asStateFlow()

    internal fun publish(report: SaveSyncReport) {
        if (report.failures.isEmpty() &&
            report.uploaded == 0 &&
            report.downloaded == 0 &&
            report.unchanged == 0 &&
            report.cleanupPending == 0
        ) return
        pending.update { Notice(nextId.incrementAndGet(), report) }
    }

    fun consume(notice: Notice): Boolean = pending.compareAndSet(notice, null)
}
