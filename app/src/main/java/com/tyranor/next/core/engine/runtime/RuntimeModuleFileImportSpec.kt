package com.tyranor.next.core.engine.runtime

import org.json.JSONObject
import java.io.Serializable

data class RuntimeModuleFileImportSpec(
    val key: String,
    val title: String,
    val directory: String,
    val fileName: String?,
    val multiple: Boolean,
) : Serializable {
    val displayPath: String
        get() = fileName?.let(::destination) ?: directory

    internal fun destination(name: String): String =
        if (directory == ".") name else "$directory/$name"
}

internal object RuntimeModuleFileImportParser {
    private val keyPattern = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private const val MAX_ITEMS = 32

    fun parse(value: Any?): List<RuntimeModuleFileImportSpec> {
        val root = value as? JSONObject ?: return emptyList()
        if (root.opt("version") != 1) return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        val imports = linkedMapOf<String, RuntimeModuleFileImportSpec>()
        for (index in 0 until items.length()) {
            if (imports.size >= MAX_ITEMS) break
            val item = items.optJSONObject(index) ?: continue
            val key = (item.opt("key") as? String)?.takeIf(keyPattern::matches) ?: continue
            val title = (item.opt("title") as? String)?.trim()?.takeIf { it.length in 1..128 } ?: continue
            val directory = item.opt("directory") as? String ?: continue
            if (directory != "." && !RuntimeModulePaths.isValidRelativePath(directory)) continue
            val fileName = if (item.has("fileName")) item.opt("fileName") as? String ?: continue else null
            if (fileName != null && !isValidFileName(fileName)) continue
            val multiple = if (item.has("multiple")) item.opt("multiple") as? Boolean ?: continue else false
            if (multiple && fileName != null) continue
            imports.putIfAbsent(key, RuntimeModuleFileImportSpec(key, title, directory, fileName, multiple))
        }
        return imports.values.toList()
    }

    fun isValidFileName(name: String): Boolean =
        '/' !in name && RuntimeModulePaths.isValidRelativePath(name) &&
            name.toByteArray(Charsets.UTF_8).size <= 255
}
