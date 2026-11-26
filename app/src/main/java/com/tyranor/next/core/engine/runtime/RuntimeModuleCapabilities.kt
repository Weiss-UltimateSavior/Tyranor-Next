package com.tyranor.next.core.engine.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** One validated runtime-module setting declaration. Values are JSON primitives only. */
data class RuntimeModuleSettingItemSpec(
    val key: String,
    val type: Type,
    val title: String,
    val description: String? = null,
    val defaultValue: Any,
    val options: List<RuntimeModuleSettingOption> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val maxLength: Int? = null,
    val sliderMin: Double? = null,
    val sliderMax: Double? = null,
    val sliderStep: Double? = null,
) {
    enum class Type { BOOLEAN, ENUM, TEXT, INTEGER, SLIDER, FILE, FOLDER }
}

data class RuntimeModuleSettingOption(val value: String, val title: String)

data class RuntimeModuleSettingCardSpec(
    val key: String,
    val title: String,
    val description: String?,
    val items: List<RuntimeModuleSettingItemSpec>,
)

sealed interface RuntimeModuleSettingsEntry {
    val key: String

    data class Item(val spec: RuntimeModuleSettingItemSpec) : RuntimeModuleSettingsEntry {
        // Keep the original stable key for flat v1 settings entries.
        override val key: String get() = spec.key
    }

    data class Card(val spec: RuntimeModuleSettingCardSpec) : RuntimeModuleSettingsEntry {
        override val key: String get() = "card:${spec.key}"
    }
}

/** A validated v1 settings declaration; its fingerprint is stable across JSON key ordering. */
data class RuntimeModuleSettingsSpec(
    val version: Int,
    val items: List<RuntimeModuleSettingItemSpec>,
    val entries: List<RuntimeModuleSettingsEntry>,
    val fingerprint: String,
) {
    /** Resolve only currently declared keys; malformed/stale overrides use the declared default. */
    fun resolve(overrides: JSONObject): RuntimeModuleEffectiveSettings {
        val values = LinkedHashMap<String, Any>(items.size)
        val invalid = LinkedHashSet<String>()
        items.forEach { item ->
            val value = if (overrides.has(item.key)) overrides.opt(item.key) else null
            if (value == null || value === JSONObject.NULL) {
                values[item.key] = item.defaultValue
            } else if (RuntimeModuleSettingsParser.isValidValue(item, value)) {
                values[item.key] = value
            } else {
                values[item.key] = item.defaultValue
                invalid += item.key
            }
        }
        return RuntimeModuleEffectiveSettings(fingerprint, values, invalid)
    }

    /** Encode full effective values using the v1 launch payload shape. */
    fun encode(effective: RuntimeModuleEffectiveSettings): String? {
        if (effective.fingerprint != fingerprint || effective.values.keys != items.map { it.key }.toSet()) return null
        val values = JSONObject()
        for (item in items) {
            val value = effective.values[item.key] ?: return null
            if (!RuntimeModuleSettingsParser.isValidValue(item, value)) return null
            values.put(item.key, value)
        }
        val encoded = JSONObject().put("version", version).put("values", values).toString()
        return encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= RuntimeModuleSettingsParser.MAX_PAYLOAD_BYTES }
    }
}

data class RuntimeModuleEffectiveSettings(
    val fingerprint: String,
    val values: Map<String, Any>,
    val invalidOverrideKeys: Set<String> = emptySet(),
) {
    fun toJsonObject(): JSONObject = JSONObject().also { json -> values.forEach(json::put) }
}

/** A v1 declaration of one game-root-relative save directory. */
data class RuntimeModuleSaveSpec(
    val version: Int = 1,
    val directory: String,
    val fingerprint: String = RuntimeModuleSettingsParser.fingerprint("saves", version.toString(), directory),
)

internal object RuntimeModuleSettingsParser {
    const val MAX_ITEMS = 32
    const val MAX_OPTIONS = 32
    const val MAX_PAYLOAD_BYTES = 48 * 1024
    private const val MAX_CARDS = 16
    private const val MAX_SLIDER_INTERVALS = 10_000

    private val KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

    fun parseDetailed(value: Any?): Pair<RuntimeModuleSettingsSpec?, String?> {
        if (value == null) return null to null
        val root = value as? JSONObject ?: return null to "settings must be an object"
        if (strictInt(root.opt("version")) != 1) return null to "unsupported settings version"
        val array = root.opt("items") as? JSONArray ?: return null to "settings items must be an array"
        val items = ArrayList<RuntimeModuleSettingItemSpec>()
        val entries = ArrayList<RuntimeModuleSettingsEntry>()
        val keys = HashSet<String>()
        val cardKeys = HashSet<String>()
        for (index in 0 until array.length()) {
            if (items.size >= MAX_ITEMS) break
            val obj = array.opt(index) as? JSONObject ?: continue
            if (obj.opt("type") == "card") {
                if (entries.count { it is RuntimeModuleSettingsEntry.Card } >= MAX_CARDS) continue
                val card = parseCard(obj, items.size, keys, cardKeys) ?: continue
                items += card.items
                entries += RuntimeModuleSettingsEntry.Card(card)
            } else {
                val item = parseItem(obj) ?: continue
                if (keys.add(item.key)) {
                    items += item
                    entries += RuntimeModuleSettingsEntry.Item(item)
                }
            }
        }
        if (items.isEmpty()) return null to "settings has no valid items"
        val budget = payloadUpperBound(items)
        if (budget > MAX_PAYLOAD_BYTES) return null to "settings payload upper bound exceeds 48 KiB"
        val canonical = JSONArray()
        entries.forEach { entry ->
            when (entry) {
                is RuntimeModuleSettingsEntry.Item -> canonical.put(canonicalItem(entry.spec))
                is RuntimeModuleSettingsEntry.Card -> {
                    val card = entry.spec
                    val obj = JSONObject().put("key", card.key).put("type", "card").put("title", card.title)
                    card.description?.let { obj.put("description", it) }
                    obj.put("items", JSONArray().also { children ->
                        card.items.forEach { children.put(canonicalItem(it)) }
                    })
                    canonical.put(obj)
                }
            }
        }
        val spec = RuntimeModuleSettingsSpec(1, items, entries, fingerprint("settings", "1", canonical.toString()))
        return spec to null
    }

    private fun parseCard(
        obj: JSONObject,
        existingItemCount: Int,
        itemKeys: MutableSet<String>,
        cardKeys: MutableSet<String>,
    ): RuntimeModuleSettingCardSpec? {
        val key = (obj.opt("key") as? String)?.takeIf(KEY::matches) ?: return null
        if (key in cardKeys) return null
        val title = (obj.opt("title") as? String)?.takeIf { it.isNotBlank() && it.length <= 128 && validUnicode(it) } ?: return null
        val description = if (obj.has("description")) {
            (obj.opt("description") as? String)?.takeIf { it.length <= 512 && validUnicode(it) } ?: return null
        } else null
        val children = obj.opt("items") as? JSONArray ?: return null
        val items = ArrayList<RuntimeModuleSettingItemSpec>()
        for (index in 0 until children.length()) {
            if (existingItemCount + items.size >= MAX_ITEMS) break
            val item = parseItem(children.opt(index)) ?: continue
            if (itemKeys.add(item.key)) items += item
        }
        if (items.isEmpty()) return null
        cardKeys += key
        return RuntimeModuleSettingCardSpec(key, title, description, items)
    }

    private fun canonicalItem(item: RuntimeModuleSettingItemSpec): JSONObject = JSONObject()
        .put("key", item.key).put("type", item.type.name.lowercase())
        .put("title", item.title).put("default", item.defaultValue).also { obj ->
            item.description?.let { obj.put("description", it) }
            item.min?.let { obj.put("min", it) }
            item.max?.let { obj.put("max", it) }
            item.maxLength?.let { obj.put("maxLength", it) }
            item.sliderMin?.let { obj.put("min", it) }
            item.sliderMax?.let { obj.put("max", it) }
            item.sliderStep?.let { obj.put("step", it) }
            if (item.options.isNotEmpty()) {
                obj.put("options", JSONArray().also { options ->
                    item.options.forEach { options.put(JSONObject().put("value", it.value).put("title", it.title)) }
                })
            }
        }

    fun parseItem(value: Any?): RuntimeModuleSettingItemSpec? {
        val obj = value as? JSONObject ?: return null
        val key = (obj.opt("key") as? String)?.takeIf(KEY::matches) ?: return null
        val type = when (obj.opt("type")) {
            "boolean" -> RuntimeModuleSettingItemSpec.Type.BOOLEAN
            "enum" -> RuntimeModuleSettingItemSpec.Type.ENUM
            "text" -> RuntimeModuleSettingItemSpec.Type.TEXT
            "integer" -> RuntimeModuleSettingItemSpec.Type.INTEGER
            "slider" -> RuntimeModuleSettingItemSpec.Type.SLIDER
            "file" -> RuntimeModuleSettingItemSpec.Type.FILE
            "folder" -> RuntimeModuleSettingItemSpec.Type.FOLDER
            else -> return null
        }
        val title = (obj.opt("title") as? String)?.takeIf { it.isNotBlank() && it.length <= 128 && validUnicode(it) } ?: return null
        val description = if (obj.has("description")) {
            (obj.opt("description") as? String)?.takeIf { it.length <= 512 && validUnicode(it) } ?: return null
        } else null
        val default = obj.opt("default")?.takeUnless { it === JSONObject.NULL } ?: return null
        val options = if (type == RuntimeModuleSettingItemSpec.Type.ENUM) {
            val arr = obj.opt("options") as? JSONArray ?: return null
            val distinct = LinkedHashMap<String, RuntimeModuleSettingOption>()
            for (i in 0 until arr.length()) {
                if (distinct.size >= MAX_OPTIONS) break
                val option = arr.opt(i) as? JSONObject ?: continue
                val raw = option.opt("value") as? String ?: continue
                val optionTitle = option.opt("title") as? String ?: continue
                if (raw.isEmpty() || raw.length > 64 || !validUnicode(raw) || optionTitle.isEmpty() || optionTitle.length > 128 || !validUnicode(optionTitle)) continue
                distinct.putIfAbsent(raw, RuntimeModuleSettingOption(raw, optionTitle))
            }
            distinct.values.toList().takeIf { it.isNotEmpty() } ?: return null
        } else emptyList()
        val min: Int?
        val max: Int?
        val maxLength: Int?
        val sliderMin: Double?
        val sliderMax: Double?
        val sliderStep: Double?
        val normalizedDefault: Any
        when (type) {
            RuntimeModuleSettingItemSpec.Type.BOOLEAN -> {
                if (default !is Boolean) return null
                normalizedDefault = default
                min = null; max = null; maxLength = null
                sliderMin = null; sliderMax = null; sliderStep = null
            }
            RuntimeModuleSettingItemSpec.Type.ENUM -> {
                if (default !is String || options.none { it.value == default }) return null
                normalizedDefault = default
                min = null; max = null; maxLength = null
                sliderMin = null; sliderMax = null; sliderStep = null
            }
            RuntimeModuleSettingItemSpec.Type.TEXT -> {
                maxLength = if (obj.has("maxLength")) strictInt(obj.opt("maxLength")) else 256
                if (maxLength == null || maxLength !in 1..1024 || default !is String || !validText(default, maxLength)) return null
                normalizedDefault = default
                min = null; max = null
                sliderMin = null; sliderMax = null; sliderStep = null
            }
            RuntimeModuleSettingItemSpec.Type.INTEGER -> {
                val parsedMin = strictInt(obj.opt("min")) ?: return null
                val parsedMax = strictInt(obj.opt("max")) ?: return null
                val parsedDefault = strictInt(default) ?: return null
                min = parsedMin
                max = parsedMax
                if (parsedMin > parsedMax || parsedDefault !in parsedMin..parsedMax) return null
                normalizedDefault = parsedDefault
                maxLength = null
                sliderMin = null; sliderMax = null; sliderStep = null
            }
            RuntimeModuleSettingItemSpec.Type.SLIDER -> {
                val parsedMin = strictDouble(obj.opt("min")) ?: return null
                val parsedMax = strictDouble(obj.opt("max")) ?: return null
                val parsedDefault = strictDouble(default) ?: return null
                val parsedStep = if (obj.has("step")) strictDouble(obj.opt("step")) ?: return null else null
                if (parsedMin >= parsedMax || parsedDefault !in parsedMin..parsedMax || parsedStep?.let { it <= 0.0 } == true) return null
                if (parsedStep == null &&
                    (!parsedMin.toFloat().isFinite() || !parsedMax.toFloat().isFinite() || parsedMin.toFloat() >= parsedMax.toFloat())
                ) return null
                if (parsedStep != null && !validSliderStep(parsedDefault, parsedMin, parsedMax, parsedStep)) return null
                if (!isSliderRangeSupportedByFloatUi(parsedMin, parsedMax, parsedStep)) return null
                min = null; max = null; maxLength = null
                sliderMin = parsedMin; sliderMax = parsedMax; sliderStep = parsedStep
                normalizedDefault = parsedDefault
            }
            RuntimeModuleSettingItemSpec.Type.FILE,
            RuntimeModuleSettingItemSpec.Type.FOLDER -> {
                val pathLength = if (obj.has("maxLength")) strictInt(obj.opt("maxLength")) else 1024
                if (pathLength == null || pathLength !in 1..4096 || default !is String || !validPath(default, pathLength)) return null
                normalizedDefault = default
                min = null; max = null; maxLength = pathLength
                sliderMin = null; sliderMax = null; sliderStep = null
            }
        }
        return RuntimeModuleSettingItemSpec(
            key, type, title, description, normalizedDefault, options, min, max, maxLength,
            sliderMin, sliderMax, sliderStep,
        )
    }

    fun isValidValue(item: RuntimeModuleSettingItemSpec, value: Any): Boolean = when (item.type) {
        RuntimeModuleSettingItemSpec.Type.BOOLEAN -> value is Boolean
        RuntimeModuleSettingItemSpec.Type.ENUM -> value is String && item.options.any { it.value == value }
        RuntimeModuleSettingItemSpec.Type.TEXT -> value is String && validText(value, item.maxLength ?: 256)
        RuntimeModuleSettingItemSpec.Type.INTEGER -> strictInt(value)?.let { intValue ->
            intValue >= (item.min ?: Int.MIN_VALUE) && intValue <= (item.max ?: Int.MAX_VALUE)
        } == true
        RuntimeModuleSettingItemSpec.Type.SLIDER -> strictDouble(value)?.let { sliderValue ->
            val min = item.sliderMin ?: return false
            val max = item.sliderMax ?: return false
            sliderValue in min..max && (item.sliderStep?.let { validSliderStep(sliderValue, min, max, it) } ?: true)
        } == true
        RuntimeModuleSettingItemSpec.Type.FILE,
        RuntimeModuleSettingItemSpec.Type.FOLDER -> value is String && validPath(value, item.maxLength ?: 1024)
    }

    private fun payloadUpperBound(items: List<RuntimeModuleSettingItemSpec>): Int {
        // Includes wrapper, key quoting, separators, and worst-case string escaping (6 bytes/code unit).
        var total = "{\"version\":1,\"values\":{}}".toByteArray(Charsets.UTF_8).size
        items.forEachIndexed { index, item ->
            total += JSONObject.quote(item.key).toByteArray(Charsets.UTF_8).size + 1 + (if (index > 0) 1 else 0)
            total += when (item.type) {
                RuntimeModuleSettingItemSpec.Type.BOOLEAN -> 5
                RuntimeModuleSettingItemSpec.Type.ENUM -> item.options.maxOf { JSONObject.quote(it.value).toByteArray(Charsets.UTF_8).size }
                RuntimeModuleSettingItemSpec.Type.TEXT -> (item.maxLength ?: 256) * 6 + 2
                RuntimeModuleSettingItemSpec.Type.INTEGER -> maxOf(item.min!!.toString().length, item.max!!.toString().length)
                RuntimeModuleSettingItemSpec.Type.SLIDER -> 32
                RuntimeModuleSettingItemSpec.Type.FILE,
                RuntimeModuleSettingItemSpec.Type.FOLDER -> (item.maxLength ?: 1024) * 6 + 2
            }
        }
        return total
    }

    private fun validSliderStep(value: Double, min: Double, max: Double, step: Double): Boolean {
        if (!step.isFinite() || step <= 0.0) return false
        val intervals = (max - min) / step
        if (!intervals.isFinite() || intervals < 1.0 || intervals > MAX_SLIDER_INTERVALS) return false
        val roundedIntervals = kotlin.math.round(intervals)
        if (kotlin.math.abs(intervals - roundedIntervals) > 1e-6 * maxOf(1.0, kotlin.math.abs(intervals))) return false
        val position = (value - min) / step
        return kotlin.math.abs(position - kotlin.math.round(position)) <= 1e-6 * maxOf(1.0, kotlin.math.abs(position))
    }

    private fun isSliderRangeSupportedByFloatUi(min: Double, max: Double, step: Double?): Boolean {
        if (step != null) return true
        val floatMin = min.toFloat()
        val floatMax = max.toFloat()
        val floatSpan = floatMax - floatMin
        return floatSpan.isFinite() && floatSpan > 0f
    }

    private fun strictDouble(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun validPath(value: String, maxLength: Int): Boolean {
        if (value.isEmpty()) return true
        if (value.length > maxLength || !value.startsWith('/') || !validUnicode(value)) return false
        if (value.any { it == '\\' || it.code < 0x20 || it.code in 0x7f..0x9f }) return false
        if (value.contains("://")) return false
        if (value == "/") return true
        val segments = value.removePrefix("/").split('/')
        return segments.all { it.isNotEmpty() && it != "." && it != ".." }
    }

    private fun validText(value: String, maxLength: Int): Boolean =
        value.length <= maxLength && validUnicode(value) && value.none { ch ->
            ch.code < 0x20 || ch.code in 0x7f..0x9f || ch == '\u2028' || ch == '\u2029'
        }

    private fun validUnicode(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                    i += 2
                }
                Character.isLowSurrogate(ch) -> return false
                else -> i++
            }
        }
        return true
    }

    private fun strictInt(value: Any?): Int? {
        val text = when (value) {
            is Int -> value.toString()
            is Long -> value.toString()
            is Number -> value.toString()
            else -> return null
        }
        if (!text.matches(Regex("-?(0|[1-9][0-9]*)"))) return null
        return text.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    fun fingerprint(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal object RuntimeModuleSaveSpecParser {
    fun parseDetailed(value: Any?): Pair<RuntimeModuleSaveSpec?, String?> {
        if (value == null) return null to null
        val root = value as? JSONObject ?: return null to "saves must be an object"
        val version = root.opt("version") as? Int ?: return null to "saves version must be integer 1"
        if (version != 1) return null to "unsupported saves version"
        val directory = root.opt("directory") as? String ?: return null to "saves directory must be a string"
        if (!RuntimeModulePaths.isValidRelativePath(directory)) return null to "invalid saves directory"
        return RuntimeModuleSaveSpec(version, directory) to null
    }
}

internal object RuntimeModulePaths {
    private val VALID_SEGMENT = Regex("^[^/\\\\:*?\"<>|]+$")

    fun isValidRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.length > 256 || path.startsWith('/') || path.endsWith('/')) return false
        if (!isValidUnicode(path)) return false
        if (path.any { it.code < 0x20 || it.code in 0x7f..0x9f }) return false
        if (path.contains("://") || path.any { it == '\\' || it == '*' || it == '?' }) return false
        val parts = path.split('/')
        return parts.size <= 8 && parts.all { it.isNotEmpty() && it != "." && it != ".." && VALID_SEGMENT.matches(it) }
    }

    private fun isValidUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            when {
                Character.isHighSurrogate(value[index]) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(value[index]) -> return false
                else -> index++
            }
        }
        return true
    }
}
