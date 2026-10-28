package com.core.web

/** Shared path nodes avoid retaining every complete ancestor prefix of a flat archive key. */
internal class AsarChildrenIndex(entries: Iterable<Pair<String, Boolean>>, includeEmptyNames: Boolean = false) {
    private class Child {
        var visible = false
        var directory = false
        val children = LinkedHashMap<String, Child>()
    }
    private val root = LinkedHashMap<String, Child>()

    init {
        for ((key, explicitDirectory) in entries) {
            val parts = key.split('/')
            var children = root
            for (index in parts.indices) {
                val name = parts[index]
                val child = children.getOrPut(name) { Child() }
                // Preserve each reader's malformed-path policy instead of normalizing it.
                if (name.isNotEmpty() || (includeEmptyNames && index < parts.lastIndex)) {
                    child.visible = true
                    child.directory = child.directory || index < parts.lastIndex || explicitDirectory
                }
                children = child.children
            }
        }
    }

    /** Return a fresh direct-child list; callers cannot mutate the cached adjacency. */
    fun children(parent: String): List<Pair<String, Boolean>> {
        var children = root
        if (parent.isNotEmpty()) {
            for (part in parent.split('/')) children = children[part]?.children ?: return emptyList()
        }
        return children.filterValues { it.visible }.map { it.key to it.value.directory }
    }
}
