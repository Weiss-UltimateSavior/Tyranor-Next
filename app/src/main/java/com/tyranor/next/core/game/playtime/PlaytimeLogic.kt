package com.tyranor.next.core.game.playtime

import java.util.Collections
import java.util.IdentityHashMap

internal fun nextSessionOrder(currentTimeMillis: Long, previousOrderMillis: Long): Long {
    val current = currentTimeMillis.coerceAtLeast(0)
    val previous = previousOrderMillis.coerceAtLeast(0)
    val nextAfterPrevious = if (previous == Long.MAX_VALUE) Long.MAX_VALUE else previous + 1
    return maxOf(current, nextAfterPrevious)
}

/** Persisted snapshot from one process-local active segment. */
internal data class PlaytimeRecordSnapshot(
    val recordId: String,
    val sessionId: String,
    val gameUri: String,
    val durationMillis: Long,
    val startedAtMillis: Long,
    val closed: Boolean,
    val processId: Int,
)

/** A segment counts once while any Activity in the same session is resumed. */
internal class PlaytimeSessionClock {
    private val resumedActivities = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var accumulatedMillis = 0L
    private var activeSinceMillis: Long? = null

    fun resume(activity: Any, nowElapsedMillis: Long) {
        if (resumedActivities.add(activity) && resumedActivities.size == 1) {
            activeSinceMillis = nowElapsedMillis
        }
    }

    fun pause(activity: Any, nowElapsedMillis: Long) {
        if (!resumedActivities.remove(activity) || resumedActivities.isNotEmpty()) return
        val activeSince = activeSinceMillis ?: return
        accumulatedMillis += (nowElapsedMillis - activeSince).coerceAtLeast(0)
        activeSinceMillis = null
    }

    fun snapshot(nowElapsedMillis: Long): Long {
        val activeSince = activeSinceMillis ?: return accumulatedMillis
        return accumulatedMillis + (nowElapsedMillis - activeSince).coerceAtLeast(0)
    }

    fun isRunning(): Boolean = activeSinceMillis != null
}

internal object PlaytimeRecordLogic {
    /** Replaying an atomic checkpoint is idempotent; a record only advances to its largest snapshot. */
    fun merge(records: Iterable<PlaytimeRecordSnapshot>): List<PlaytimeRecordSnapshot> =
        records.groupBy(PlaytimeRecordSnapshot::recordId).values.map { versions ->
            val latest = versions.maxBy { it.durationMillis }
            latest.copy(
                durationMillis = versions.maxOf { it.durationMillis }.coerceAtLeast(0),
                startedAtMillis = versions.minOf { it.startedAtMillis },
                closed = versions.any(PlaytimeRecordSnapshot::closed),
            )
        }

    fun statsFor(gameUri: String, records: Iterable<PlaytimeRecordSnapshot>): GamePlaytimeStats {
        val games = merge(records).filter { it.gameUri == gameUri }
        val total = games.sumOf(PlaytimeRecordSnapshot::durationMillis)
        val latestSession = games.groupBy(PlaytimeRecordSnapshot::sessionId)
            .maxByOrNull { (_, sessionRecords) -> sessionRecords.minOf(PlaytimeRecordSnapshot::startedAtMillis) }
            ?.key
        val lastSession = if (latestSession == null) 0L else {
            games.asSequence().filter { it.sessionId == latestSession }
                .sumOf(PlaytimeRecordSnapshot::durationMillis)
        }
        return GamePlaytimeStats(totalMillis = total, lastSessionMillis = lastSession)
    }
}
