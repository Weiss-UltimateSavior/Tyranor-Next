package com.tyranor.next.core.game.save.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reconstructible per-request exclusions for a fallback retry chain.
 *
 * A canceled WorkManager request never runs its completion path, so old records expire
 * independently instead of retaining device identifiers indefinitely.
 */
internal class SaveSyncFallbackStateStore(
    directory: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val states = SaveSyncStateStore(directory, recoverCorrupt = true)

    fun load(workId: String): Set<String> {
        val key = key(workId)
        val state = states.load(key)
        if (state.length() == 0) return emptySet()
        if (!isCurrent(state)) {
            states.remove(key)
            return emptySet()
        }
        val uris = state.optJSONArray(KEY_URIS) ?: return emptySet()
        return normalize(buildList {
            for (index in 0 until uris.length()) {
                (uris.opt(index) as? String)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        })
    }

    fun addAll(workId: String, excludedUris: Iterable<String>) {
        val combined = load(workId).toMutableSet().apply { addAll(normalize(excludedUris)) }
        save(workId, combined)
    }

    fun save(workId: String, excludedUris: Iterable<String>) {
        pruneExpired()
        val uris = normalize(excludedUris)
        val key = key(workId)
        if (uris.isEmpty()) {
            states.remove(key)
            return
        }
        val encoded = JSONArray()
        uris.sorted().forEach { encoded.put(it) }
        states.save(key, JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_UPDATED_AT, clock())
            .put(KEY_URIS, encoded))
    }

    fun remove(workId: String) {
        states.remove(key(workId))
    }

    fun removeAll() {
        for (key in states.keys()) states.remove(key)
    }

    fun pruneExpired() {
        val now = clock()
        for (key in states.keys()) {
            val state = states.load(key)
            if (state.length() == 0 || !isCurrent(state, now)) states.remove(key)
        }
    }

    private fun key(workId: String): String {
        require(workId.isNotBlank())
        return SaveContentHash.text(workId)
    }

    private fun isCurrent(state: JSONObject, now: Long = clock()): Boolean {
        if (state.optInt(KEY_VERSION) != VERSION) return false
        val updatedAt = state.optLong(KEY_UPDATED_AT, -1L)
        val age = now - updatedAt
        return updatedAt > 0 && age >= 0 && age <= MAX_AGE_MILLIS
    }

    companion object {
        internal val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)
        internal fun normalize(uris: Iterable<String>): Set<String> =
            uris.filter { it.isNotBlank() }.toSet()

        private const val VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_UPDATED_AT = "updatedAt"
        private const val KEY_URIS = "uris"
    }
}
