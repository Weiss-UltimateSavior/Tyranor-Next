package com.tyranor.next.core.game.storage

/** A library registration cannot land between the final reference check and resource eviction. */
internal class GameLibraryWriteGate {
    private val lock = Any()

    fun <T> update(action: () -> T): T = synchronized(lock, action)

    fun evictIfUnused(referenced: () -> Boolean, evict: () -> Boolean): Boolean =
        synchronized(lock) { !referenced() && evict() }
}
