package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Network waits hold no local locks; local order is versions -> active -> mutation -> session. */
internal object SaveSyncLocks {
    private val network = SaveSyncLockRegistry()
    private val versions = SaveSyncLockRegistry()
    private val active = SaveSyncLockRegistry()
    private val sessions = SaveSyncLockRegistry()
    private val mutations = Mutex()

    suspend fun <T> network(uri: String, block: suspend () -> T): T = network.withLock(uri, block)

    suspend fun <T> versions(uri: String, block: suspend () -> T): T =
        versions.withLock(uri, block)

    suspend fun <T> active(uri: String, block: suspend () -> T): T =
        active.withLock(uri, block)

    /** Always acquired after active; one URI keeps its session even when its directory changes. */
    suspend fun <T> session(key: String, block: suspend () -> T): T = sessions.withLock(key, block)

    /**
     * Serializes save-directory replacements/backfills (activation import, recovery/failure
     * cleanup, sync finish, clear) with launches that do not hold the directory lock.
     */
    suspend fun <T> mutation(block: suspend () -> T): T = mutations.withLock { block() }
}

/**
 * One mutex per URI, retained until every holder and waiter has released it.
 * Counting waiters before suspension prevents a finishing holder from replacing the entry under them.
 */
internal class SaveSyncLockRegistry {
    private class Entry(val mutex: Mutex) {
        var references = 1
    }

    private val monitor = Any()
    private val entries = HashMap<String, Entry>()

    suspend fun <T> withLock(uri: String, block: suspend () -> T): T {
        val entry = synchronized(monitor) {
            entries[uri]?.also { it.references++ } ?: Entry(Mutex()).also { entries[uri] = it }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(monitor) {
                check(entries[uri] === entry)
                entry.references--
                if (entry.references == 0) entries.remove(uri)
            }
        }
    }

    internal val size: Int
        get() = synchronized(monitor) { entries.size }

    internal fun referenceCount(uri: String): Int =
        synchronized(monitor) { entries[uri]?.references ?: 0 }
}
