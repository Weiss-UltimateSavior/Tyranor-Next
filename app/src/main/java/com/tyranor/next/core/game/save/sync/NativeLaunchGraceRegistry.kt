package com.tyranor.next.core.game.save.sync

/**
 * Thread-safe, bounded collection of native launch hints.
 *
 * Every URI may own multiple token generations. Callers can only retire the exact token they
 * registered, while activity checks still honor a newer generation observed by an older caller.
 */
internal class NativeLaunchGraceRegistry(maxEntries: Int) {
    private data class EntryKey(val uri: String, val generation: Long, val token: String)

    private val lock = Any()
    private val entries = LinkedHashMap<EntryKey, NativeLaunchGrace>()
    private val maxEntries = maxEntries.also { require(it > 0) { "maxEntries must be positive" } }

    fun mark(uri: String, generation: Long, token: String, nowMillis: Long, durationMillis: Long) {
        val entryKey = EntryKey(uri, generation, token)
        synchronized(lock) {
            entries.remove(entryKey)
            if (durationMillis <= 0L) return@synchronized

            pruneExpired(nowMillis)
            val untilMillis = saturatingAdd(nowMillis, durationMillis)
            if (untilMillis <= nowMillis) return@synchronized
            entries[entryKey] = NativeLaunchGrace(generation, untilMillis, token)
            while (entries.size > maxEntries) {
                val oldest = entries.minByOrNull { it.value.untilMillis } ?: break
                entries.remove(oldest.key)
            }
        }
    }

    fun active(uri: String, generation: Long, nowMillis: Long): Boolean = synchronized(lock) {
        pruneExpired(nowMillis)
        entries.any { (entryKey, grace) ->
            entryKey.uri == uri && nativeLaunchGraceActive(grace, generation, nowMillis)
        }
    }

    fun clear(uri: String, token: String, generation: Long? = null) {
        synchronized(lock) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val (entryKey, grace) = iterator.next()
                val matchesToken = entryKey.uri == uri && entryKey.token == token
                val matchesGeneration = generation == null || nativeLaunchGraceOwnedBy(grace, generation, token)
                if (matchesToken && matchesGeneration) iterator.remove()
            }
        }
    }

    private fun pruneExpired(nowMillis: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val grace = iterator.next().value
            if (!nativeLaunchGraceActive(grace, grace.generation, nowMillis)) iterator.remove()
        }
    }

    private fun saturatingAdd(base: Long, increment: Long): Long =
        if (base > Long.MAX_VALUE - increment) Long.MAX_VALUE else base + increment
}
