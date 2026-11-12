package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque

/** Network waits hold no local locks; local order is versions -> active -> mutation -> session. */
internal object SaveSyncLocks {
    private val network = SaveSyncLockRegistry()
    private val versions = SaveSyncLockRegistry()
    private val active = SaveSyncOperationGate()
    private val sessions = SaveSyncLockRegistry()
    private val operations = SaveSyncOperationGate()

    suspend fun <T> network(uri: String, block: suspend () -> T): T = network.withLock(uri, block)

    suspend fun <T> versions(uri: String, block: suspend () -> T): T =
        versions.withLock(uri, block)

    suspend fun <T> versions(uri: String, waitMillis: Long, block: suspend () -> T): T =
        versions.withLock(uri, waitMillis, block)

    suspend fun <T> active(uri: String, block: suspend () -> T): T =
        active.withPermit(exclusive = false, scope = uri, block = block)

    suspend fun <T> active(uri: String, waitMillis: Long, block: suspend () -> T): T =
        active.withPermit(exclusive = false, waitMillis = waitMillis, scope = uri, block = block)

    /** Always acquired after active; one URI keeps its session even when its directory changes. */
    suspend fun <T> session(key: String, block: suspend () -> T): T = sessions.withLock(key, block)

    suspend fun <T> session(key: String, waitMillis: Long, block: suspend () -> T): T =
        sessions.withLock(key, waitMillis, block)

    /**
     * Unresolved/disabled launches cannot identify the directory they will touch. Keep their
     * entire preparation and dispatch exclusive with every resolved directory operation.
     */
    suspend fun <T> mutation(block: suspend () -> T): T = operations.withPermit(exclusive = true, block = block)

    suspend fun <T> mutation(waitMillis: Long, block: suspend () -> T): T =
        operations.withPermit(exclusive = true, waitMillis = waitMillis, block = block)

    /**
     * Caller holds active(uri) for this pinned save-directory identity. Shared admission lets
     * unrelated directories launch/capture/replace concurrently; active serializes aliases.
     * Never nest operation permits or acquire active/versions from inside a permit.
     */
    suspend fun <T> mutation(uri: String, block: suspend () -> T): T = mutation(uri, null, block)

    suspend fun <T> mutation(uri: String, waitMillis: Long?, block: suspend () -> T): T {
        require(uri.isNotBlank())
        return operations.withPermit(exclusive = false, waitMillis = waitMillis, block = block)
    }
}

/** FIFO conflicting admission; disjoint directory scopes may bypass a blocked directory waiter. */
internal class SaveSyncOperationGate {
    private class Waiter(val exclusive: Boolean, val scope: String?) {
        val ready = CompletableDeferred<Unit>()
        var granted = false
    }

    private val monitor = Any()
    private val waiters = ArrayDeque<Waiter>()
    private val holders = HashSet<Waiter>()

    suspend fun <T> withPermit(
        exclusive: Boolean,
        waitMillis: Long? = null,
        scope: String? = null,
        block: suspend () -> T,
    ): T {
        require(waitMillis == null || waitMillis >= 0)
        currentCoroutineContext().ensureActive()
        val waiter = Waiter(exclusive, scope)
        try {
            synchronized(monitor) {
                waiters.addLast(waiter)
                admitLocked()
            }
            awaitSaveSyncAdmission(waitMillis, waiter.ready.isCompleted) { waiter.ready.await() }
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            synchronized(monitor) {
                if (waiter.granted) {
                    holders.remove(waiter)
                } else {
                    waiters.remove(waiter)
                }
                admitLocked()
            }
        }
    }

    private fun admitLocked() {
        if (holders.any { it.exclusive }) return
        val blocked = ArrayList<Waiter>()
        val pending = waiters.iterator()
        while (pending.hasNext()) {
            val next = pending.next()
            if (next.exclusive && (holders.isNotEmpty() || blocked.isNotEmpty())) return
            fun conflicts(other: Waiter): Boolean = other.exclusive || next.exclusive ||
                (other.scope != null && next.scope != null && saveDirectoryScopesOverlap(other.scope, next.scope))
            if (holders.any(::conflicts) || blocked.any(::conflicts)) {
                blocked += next
                continue
            }
            pending.remove()
            next.granted = true
            holders += next
            next.ready.complete(Unit)
            if (next.exclusive) return
        }
    }
}

/** Only acquisition is timed; an admitted irreversible block has no cleanup deadline. */
private suspend fun awaitSaveSyncAdmission(
    waitMillis: Long?,
    admitted: Boolean,
    acquire: suspend () -> Unit,
) {
    if (admitted) return
    if (waitMillis == null) {
        acquire()
    } else if (withTimeoutOrNull(waitMillis) { acquire(); true } != true) {
        throw SaveSyncException(SaveSyncError.GAME_RUNNING)
    }
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

    suspend fun <T> withLock(uri: String, block: suspend () -> T): T = withLock(uri, null, block)

    suspend fun <T> withLock(uri: String, waitMillis: Long?, block: suspend () -> T): T {
        require(waitMillis == null || waitMillis >= 0)
        currentCoroutineContext().ensureActive()
        val entry = synchronized(monitor) {
            entries[uri]?.also { it.references++ } ?: Entry(Mutex()).also { entries[uri] = it }
        }
        var locked = false
        try {
            locked = entry.mutex.tryLock()
            awaitSaveSyncAdmission(waitMillis, locked) {
                entry.mutex.lock()
                locked = true
            }
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            if (locked) entry.mutex.unlock()
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
