package com.tyranor.next.core.game.save.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Version operations may wait for the network; active-save operations never hold that wait. */
internal object SaveSyncLocks {
    private val versions = ConcurrentHashMap<String, Mutex>()
    private val active = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> versions(uri: String, block: suspend () -> T): T =
        versions.computeIfAbsent(uri) { Mutex() }.withLock { block() }

    suspend fun <T> active(uri: String, block: suspend () -> T): T =
        active.computeIfAbsent(uri) { Mutex() }.withLock { block() }
}
