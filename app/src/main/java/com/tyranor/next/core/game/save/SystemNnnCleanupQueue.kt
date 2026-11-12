package com.tyranor.next.core.game.save

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable list of games whose imported copy resources still need to be reclaimed.
 *
 * The app owns the copies, so this queue lives in app storage: the engine never learns about it and
 * keeps no game path. Entries are drained on app start and again when the game-directory drawer is
 * opened, which covers process death between removing a library row and finishing the cleanup.
 */
internal object SystemNnnCleanupQueue {
    private const val PREFS = "systemnnn_cleanup"
    private const val KEY = "pending"
    private val lock = Any()
    private val cache = ConcurrentHashMap<String, Unit>()

    /** Pending game URIs, oldest first. */
    fun pending(context: Context): List<String> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
        return stored.split('\n').filter { it.isNotBlank() }
    }

    fun isEmpty(context: Context): Boolean = pending(context).isEmpty()

    fun add(context: Context, uris: Collection<String>) {
        val targets = uris.filter { it.isNotBlank() }
        if (targets.isEmpty()) return
        synchronized(lock) {
            val merged = (pending(context) + targets).distinct()
            write(context, merged)
        }
    }

    fun remove(context: Context, uris: Collection<String>) {
        if (uris.isEmpty()) return
        synchronized(lock) {
            val remaining = pending(context).filterNot { it in uris }
            if (remaining.size != pending(context).size) write(context, remaining)
        }
    }

    private fun write(context: Context, uris: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, uris.joinToString("\n"))
            .commit()
    }
}
