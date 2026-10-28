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
            val parts = key.split('/')
            var children = root
            for (index in parts.indices) {
                checkActive()
                val name = parts[index]
                val child = children.getOrPut(name) {
                    if (++nodeCount > MAX_NODES) throw LimitExceeded()
                    Child()
                }
                // Preserve each reader's malformed-path policy instead of normalizing it.
                if (name.isNotEmpty() || (includeEmptyNames && index < parts.lastIndex)) {
                    child.visible = true
                    child.directory = child.directory || index < parts.lastIndex || explicitDirectory
                }
                if (index < parts.lastIndex) {
                    children = child.children ?: LinkedHashMap<String, Child>().also { child.children = it }
                }
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
    }
}
