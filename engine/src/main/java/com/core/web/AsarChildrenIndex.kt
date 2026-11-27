package com.core.web

import java.io.IOException
import java.io.InterruptedIOException

/** Shared path nodes avoid retaining every complete ancestor prefix of a flat archive key. */
internal class AsarChildrenIndex(entries: Iterable<Pair<String, Boolean>>, includeEmptyNames: Boolean = false) {
    class LimitExceeded : IOException("ASAR directory index exceeds node limit")

    private class Child {
        var visible = false
        var directory = false
        var children: LinkedHashMap<String, Child>? = null
    }
    private val root = LinkedHashMap<String, Child>()

    init {
        var nodeCount = 0
        for ((key, explicitDirectory) in entries) {
            checkActive()
            var children = root
            var start = 0
            while (true) {
                checkActive()
                val slash = key.indexOf('/', start)
                val isLast = slash < 0
                val name = key.substring(start, if (isLast) key.length else slash)
                val child = children.getOrPut(name) {
                    if (++nodeCount > MAX_NODES) throw LimitExceeded()
                    Child()
                }
                // Preserve each reader's malformed-path policy instead of normalizing it.
                if (name.isNotEmpty() || (includeEmptyNames && !isLast)) {
                    child.visible = true
                    child.directory = child.directory || !isLast || explicitDirectory
                }
                if (isLast) break
                children = child.children ?: LinkedHashMap<String, Child>().also { child.children = it }
                start = slash + 1
            }
        }
    }

    /** Return a fresh direct-child list; callers cannot mutate the cached adjacency. */
    fun children(parent: String): List<Pair<String, Boolean>> {
        var children = root
        if (parent.isNotEmpty()) {
            for (part in parent.split('/')) {
                checkActive()
                children = children[part]?.children ?: return emptyList()
            }
        }
        return children.mapNotNull { (name, child) ->
            checkActive()
            if (child.visible) name to child.directory else null
        }
    }

    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("ASAR directory index cancelled")
    }

    companion object {
        private const val MAX_NODES = 250_000

        /** List direct children of [parent] without allocating an archive-wide tree. */
        fun directChildren(
            entries: Iterable<Pair<String, Boolean>>,
            parent: String,
            includeEmptyNames: Boolean = false,
        ): List<Pair<String, Boolean>> {
            val prefix = if (parent.isEmpty()) "" else "$parent/"
            val result = LinkedHashMap<String, Boolean>()
            for ((key, explicitDirectory) in entries) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("ASAR directory index cancelled")
                if (prefix.isNotEmpty() && !key.startsWith(prefix)) continue
                val slash = key.indexOf('/', prefix.length)
                val isLast = slash < 0
                val name = key.substring(prefix.length, if (isLast) key.length else slash)
                if (name.isEmpty() && (!includeEmptyNames || isLast)) continue
                val directory = !isLast || explicitDirectory
                val existing = result[name]
                if (existing == null) {
                    if (result.size >= MAX_NODES) throw LimitExceeded()
                    result[name] = directory
                } else if (!existing && directory) {
                    result[name] = true
                }
            }
            return result.entries.map { it.key to it.value }
        }

        /** Filter before allocating nodes; unrelated trees cannot consume a focused scan's budget. */
        fun focused(
            entries: Iterable<Pair<String, Boolean>>,
            prefix: String,
            roots: Set<String>,
            includeEmptyNames: Boolean = false,
        ): AsarChildrenIndex {
            val selected = entries.asSequence().filter { (key, _) ->
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("ASAR directory index cancelled")
                if (!key.startsWith(prefix)) return@filter false
                val end = key.indexOf('/', prefix.length).let { if (it < 0) key.length else it }
                val rootName = key.substring(prefix.length, end)
                roots.any { it.equals(rootName, ignoreCase = true) }
            }
            return AsarChildrenIndex(selected.asIterable(), includeEmptyNames)
        }
    }
}
