package com.tyranor.next.core.game.save

import android.content.Context
import com.tyranor.next.core.game.save.sync.SaveSyncError
import com.tyranor.next.core.game.save.sync.SaveSyncException
import com.tyranor.next.core.game.save.sync.SaveSyncStateStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Durable list of games whose imported copy resources still need to be reclaimed.
 *
 * The queue lives in app storage. Draining it after app start or when the game directory drawer
 * opens covers process death between removing a library row and finishing cleanup.
 */
internal class SystemNnnCleanupQueue(
    directory: File,
    private val legacyPending: () -> List<String> = { emptyList() },
) {
    data class Target(val uri: String, val retireCopy: Boolean)

    private val store = SaveSyncStateStore(directory)

    fun pending(): List<Target> = synchronized(lock) { read() }

    fun add(targets: Collection<Target>) {
        if (targets.isEmpty()) return
        synchronized(lock) {
            val merged = read().associateByTo(linkedMapOf<String, Target>(), Target::uri)
            for (target in targets) {
                require(target.uri.isNotBlank())
                merged[target.uri] = target.copy(retireCopy = target.retireCopy || merged[target.uri]?.retireCopy == true)
            }
            // Repeated requests still have to reach disk before the library write.
            write(merged.values)
        }
    }

    fun retainCopies(matches: (String) -> Boolean) {
        synchronized(lock) {
            val current = read()
            val updated = current.map { target ->
                if (target.retireCopy && matches(target.uri)) target.copy(retireCopy = false) else target
            }
            if (updated != current) write(updated)
        }
    }

    fun complete(target: Target) {
        synchronized(lock) {
            val current = read()
            if (target in current) write(current - target)
        }
    }

    private fun read(): List<Target> {
        val state = store.inspect(KEY)
        try {
            if (state.length() == 0) return legacyPending().distinct().map { Target(it, false) }
            val verifiedRetirement = state.optInt("version") == CURRENT_VERSION
            val targets = state.getJSONArray("pending")
            return List(targets.length()) { index ->
                val target = targets.getJSONObject(index)
                val uri = target.getString("uri")
                if (uri.isBlank()) throw JSONException("Empty cleanup target URI")
                Target(uri, target.getBoolean("retire") && verifiedRetirement)
            }
        } catch (failure: JSONException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    private fun write(targets: Collection<Target>) {
        val pending = JSONArray()
        targets.forEach { pending.put(JSONObject().put("uri", it.uri).put("retire", it.retireCopy)) }
        try {
            // An empty record also retires the old preference queue after migration.
            store.save(KEY, JSONObject().put("version", CURRENT_VERSION).put("pending", pending))
        } catch (failure: IOException) {
            if (failure is SaveSyncException) throw failure
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        } catch (failure: SecurityException) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    companion object {
        private const val PREFS = "systemnnn_cleanup"
        private const val CURRENT_VERSION = 2
        private val KEY = "0".repeat(64)
        private val lock = Any()

        fun open(context: Context) = SystemNnnCleanupQueue(File(context.noBackupFilesDir, PREFS)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pending", "").orEmpty()
                .lineSequence().filter { it.isNotBlank() }.toList()
        }
    }
}
