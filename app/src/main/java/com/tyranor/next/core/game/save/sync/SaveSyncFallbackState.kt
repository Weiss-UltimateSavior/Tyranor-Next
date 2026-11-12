package com.tyranor.next.core.game.save.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Durable exclusions shared by each open/exit fallback chain. Scheduling a successor transfers
 * ownership, while late writes from its running predecessor still contribute exclusions.
 * Only the current owner may clear the chain. Legacy per-request records alone expire.
 */
internal class SaveSyncFallbackStateStore(
    directory: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val states = SaveSyncStateStore(directory)

    fun load(workId: String): Set<String> = synchronized(LOCK) {
        val key = key(workId)
        val state = states.load(key)
        if (state.length() == 0) return@synchronized emptySet()
        if (!isCurrent(state)) {
            states.remove(key)
            return@synchronized emptySet()
        }
        val uris = state.optJSONArray(KEY_URIS) ?: throw SaveSyncException(SaveSyncError.LOCAL_IO)
        normalize(buildList {
            for (index in 0 until uris.length()) {
                val uri = uris.opt(index) as? String
                if (uri.isNullOrBlank()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
                add(uri)
            }
        })
    }

    fun save(workId: String, excludedUris: Iterable<String>): Unit = synchronized(LOCK) {
        pruneExpired()
        val uris = normalize(excludedUris)
        val key = key(workId)
        if (uris.isEmpty()) {
            states.remove(key)
            return@synchronized
        }
        val encoded = JSONArray()
        uris.sorted().forEach { encoded.put(it) }
        states.save(key, JSONObject()
            .put(KEY_VERSION, LEGACY_VERSION)
            .put(KEY_UPDATED_AT, clock())
            .put(KEY_URIS, encoded))
    }

    /** Persist before enqueue, including exclusions discovered by the previous retry chain. */
    fun claim(chain: String, owner: String, excludedUris: Iterable<String>): Unit = synchronized(LOCK) {
        require(owner.isNotBlank())
        writeChain(chain, owner, load(chain) + normalize(excludedUris))
    }

    fun schedule(chain: String, owner: String, excludedUris: Iterable<String>, enqueue: () -> Unit): Unit = synchronized(LOCK) {
        val previousOwner = states.load(key(chain)).optString(KEY_OWNER)
        claim(chain, owner, excludedUris)
        try {
            enqueue()
        } catch (failure: Throwable) {
            // A synchronous rejection must not turn the still-scheduled predecessor into
            // superseded work. Keep the merged exclusions under its accepted ownership.
            if (previousOwner.isNotBlank()) {
                try {
                    writeChain(chain, previousOwner, load(chain))
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    fun isSuperseded(chain: String, owner: String): Boolean = synchronized(LOCK) {
        val state = states.load(key(chain))
        state.length() == 0 || (state.has(KEY_OWNER) && state.optString(KEY_OWNER) != owner)
    }

    fun merge(chain: String, excludedUris: Iterable<String>, sharedChain: Boolean = false): Unit = synchronized(LOCK) {
        val state = states.load(key(chain))
        // A late worker must not resurrect a chain its successor completed.
        if (sharedChain && state.length() == 0) return@synchronized
        val owner = state.optString(KEY_OWNER).takeIf { it.isNotBlank() }
        if (owner == null) save(chain, load(chain) + normalize(excludedUris))
        else writeChain(chain, owner, load(chain) + normalize(excludedUris))
    }

    /** A retry keeps its scheduling lease even when there are no storage-full exclusions. */
    fun complete(
        workId: String,
        owner: String,
        retry: Boolean,
        excludedUris: Iterable<String>,
        sharedChain: Boolean,
    ): Unit = synchronized(LOCK) {
        if (retry) merge(workId, excludedUris, sharedChain) else remove(workId, owner)
    }

    /** A running predecessor must not remove its successor's state. */
    fun remove(workId: String, owner: String? = null): Unit = synchronized(LOCK) {
        val state = states.load(key(workId))
        if (owner != null && state.has(KEY_OWNER) && state.optString(KEY_OWNER) != owner) return@synchronized
        states.remove(key(workId))
    }

    fun removeAll(): Unit = synchronized(LOCK) {
        for (key in states.keys()) states.remove(key)
    }

    fun pruneExpired(): Unit = synchronized(LOCK) {
        val now = clock()
        for (key in states.keys()) {
            val state = states.load(key)
            if (state.length() == 0 || !isCurrent(state, now)) states.remove(key)
        }
    }

    private fun writeChain(chain: String, owner: String, uris: Set<String>) {
        val encoded = JSONArray()
        uris.sorted().forEach { encoded.put(it) }
        states.save(key(chain), JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_OWNER, owner)
            .put(KEY_URIS, encoded))
    }

    private fun key(workId: String): String {
        require(workId.isNotBlank())
        return SaveContentHash.text(workId)
    }

    private fun isCurrent(state: JSONObject, now: Long = clock()): Boolean {
        if (state.optInt(KEY_VERSION) == VERSION) {
            if (state.optString(KEY_OWNER).isBlank() || state.optJSONArray(KEY_URIS) == null) {
                throw SaveSyncException(SaveSyncError.LOCAL_IO)
            }
            // A request can wait for unmetered connectivity longer than seven days.
            return true
        }
        if (state.optInt(KEY_VERSION) != LEGACY_VERSION) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        val updatedAt = state.optLong(KEY_UPDATED_AT, -1L)
        val age = now - updatedAt
        return updatedAt > 0 && age >= 0 && age <= MAX_AGE_MILLIS
    }

    companion object {
        internal val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)
        internal fun normalize(uris: Iterable<String>): Set<String> =
            uris.filter { it.isNotBlank() }.toSet()

        private val LOCK = Any()
        private const val LEGACY_VERSION = 1
        private const val VERSION = 2
        private const val KEY_OWNER = "owner"
        private const val KEY_VERSION = "version"
        private const val KEY_UPDATED_AT = "updatedAt"
        private const val KEY_URIS = "uris"
    }
}
