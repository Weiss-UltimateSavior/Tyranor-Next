package com.tyranor.next.core.game.storage

/** A library registration cannot land between the final reference check and resource eviction. */
internal class GameLibraryWriteGate {
    private val lock = Any()

    fun <T> update(action: () -> T): T = synchronized(lock, action)

    fun <T> evictIfUnused(referenced: () -> Boolean, evict: () -> T): T? =
        synchronized(lock) { if (referenced()) null else evict() }
}
