package com.tyranor.next.core.engine.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 一次发现得到的第三方运行时模块。
 *
 * [enabled] 是持久化开关在本次快照中的投影：新发现模块默认关闭，只有用户启用后才参与扫描索引。
 * [scan] 为 null 表示模块没有（或没有合法）扫描描述：仍可被发现/启动，但不参与自动扫描。
 */
data class RuntimeModuleDescriptor(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val protocol: Int,
    val activityName: String,
    val scan: RuntimeModuleScanSpec?,
    val enabled: Boolean,
) {
    companion object {
        /** 解析模块描述文件 JSON，只返回描述文件能声明的字段。 */
        fun parse(json: String?): RuntimeModuleManifest? = RuntimeModuleManifestParser.parse(json)

        fun fromJson(json: String?): RuntimeModuleManifest? = parse(json)
    }
}

/**
 * 模块描述文件（`res/raw/tyranor_runtime.json`）解析后的扫描特征。
 *
 * [extensions] 已归一为小写并带前导点；[markers] 已归一为小写相对路径。
 */
data class RuntimeModuleScanSpec(
    val match: Match,
    val extensions: List<String>,
    val markers: List<String>,
) {
    enum class Match { ANY, ALL }
}

/** 描述文件能声明的字段；模块的包名/Activity/版本由 PackageManager 提供。 */
data class RuntimeModuleManifest(
    val protocol: Int,
    val label: String?,
    val scan: RuntimeModuleScanSpec?,
) {
    companion object {
        fun parse(json: String?): RuntimeModuleManifest? = RuntimeModuleManifestParser.parse(json)

        fun fromJson(json: String?): RuntimeModuleManifest? = parse(json)
    }
}

/**
 * 容错解析模块描述文件：非法 JSON 返回 null；单个非法模式被丢弃；模式数量超过上限时只保留
 * 上限内的有效项；若最终没有任何有效模式，`scan = null`。绝不抛出未受控异常影响模块发现。
 *
 * 上限与路径规则见协议：描述文件 ≤ 64 KiB、extensions ≤ 32 项（每项 ≤ 16 字符）、
 * markers ≤ 64 项（每项 ≤ 256 字符）；绝对路径、`..`、反斜杠、空串、`**` 与多层路径一律丢弃。
 */
object RuntimeModuleManifestParser {

    /** 描述文件大小上限（64 KiB）；由读取方保证，解析方再做一次字符数防御。 */
    const val MAX_BYTES = 64 * 1024

    /** JSON 嵌套深度上限；只用于拒绝恶意/损坏描述，不影响正常清单。 */
    private const val MAX_NESTING_DEPTH = 32

    private fun exceedsNestingDepth(text: String, maxDepth: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > maxDepth) return true
                }
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return false
    }

    fun parse(json: String?): RuntimeModuleManifest? {
        val text = json
            ?.trim()
            ?.removePrefix("\uFEFF")
            ?.trim()
            .orEmpty()
        if (text.isEmpty() || text.length > MAX_BYTES) return null
        // 字符数只是快速上界；再按 UTF-8 字节数复核，避免多字节文本绕过 64 KiB 限制。
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        // 不可信模块可能提供极深嵌套 JSON；org.json 递归解析会抛 StackOverflowError（Error 不被 catch）。
        if (exceedsNestingDepth(text, MAX_NESTING_DEPTH)) return null
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        return RuntimeModuleManifest(
            protocol = readProtocol(root),
            label = (root.opt("label") as? String)?.trim()?.takeIf { it.isNotEmpty() },
            scan = parseScan(root.opt("scan")),
        )
    }

    fun fromJson(json: String?): RuntimeModuleManifest? = parse(json)

    private fun readProtocol(root: JSONObject): Int {
        val value = root.opt("protocol")
        val parsed = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
        return parsed?.takeIf { it > 0 } ?: RuntimeModuleContract.PROTOCOL_VERSION
    }

    private fun parseScan(value: Any?): RuntimeModuleScanSpec? {
        val scan = value as? JSONObject ?: return null
        val match = if ((scan.opt("match") as? String)?.trim()?.lowercase(Locale.ROOT) == "all") {
            RuntimeModuleScanSpec.Match.ALL
        } else {
            RuntimeModuleScanSpec.Match.ANY
        }
        val extensions = parseExtensions(scan.optJSONArray("extensions"))
        val markers = parseMarkers(scan.optJSONArray("markers"))
        if (extensions.isEmpty() && markers.isEmpty()) return null
        return RuntimeModuleScanSpec(match = match, extensions = extensions, markers = markers)
    }

    private fun parseExtensions(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            if (result.size >= RuntimeModulePatterns.MAX_EXTENSIONS) break
            val raw = array.opt(index) as? String ?: continue
            RuntimeModulePatterns.normalizeExtension(raw, RuntimeModulePatterns.MAX_EXTENSION_LENGTH)
                ?.let(result::add)
        }
        return result.toList()
    }

    private fun parseMarkers(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            if (result.size >= RuntimeModulePatterns.MAX_MARKERS) break
            val raw = array.opt(index) as? String ?: continue
            RuntimeModulePatterns.normalizeMarker(raw, RuntimeModulePatterns.MAX_MARKER_LENGTH)
                ?.let(result::add)
        }
        return result.toList()
    }
}

/** 扩展名/标记模式的归一与校验；解析与匹配共用，防止手写 spec 绕过上限。 */
internal object RuntimeModulePatterns {

    const val MAX_EXTENSIONS = 32
    const val MAX_EXTENSION_LENGTH = 16
    const val MAX_MARKERS = 64
    const val MAX_MARKER_LENGTH = 256

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

    /** 返回小写 `".ext"`；非法模式返回 null。 */
    fun normalizeExtension(raw: String?, maxLength: Int = MAX_EXTENSION_LENGTH): String? {
        val value = raw?.trim().orEmpty()
        if (value.length !in 2..maxLength) return null
        if (value[0] != '.') return null
        if (value.any { it == '/' || it == '\\' || it == '*' || it == '?' || it.isWhitespace() || it.code < 0x20 }) {
            return null
        }
        return value.lowercase(Locale.ROOT)
    }

    /** 返回小写相对路径；绝对路径、`..`、反斜杠、空串、`**`、多层或中间段通配返回 null。 */
    fun normalizeMarker(raw: String?, maxLength: Int = MAX_MARKER_LENGTH): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.length !in 1..maxLength) return null
        if (trimmed.any { it == '\\' || it.code < 0x20 }) return null
        if (trimmed.contains("**")) return null
        if (trimmed.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(trimmed)) return null
        if (trimmed.contains("..")) return null

        val value = trimmed.trimEnd('/')
        if (value.isEmpty()) return null
        val segments = value.split('/')
        if (segments.size !in 1..2) return null
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        // 中间段必须是字面量；只有最后一段允许 * / ?。
        if (segments.size == 2 && (segments[0].contains('*') || segments[0].contains('?'))) return null
        // 纯通配 marker（如 "*" / "?"）会认领任意目录，直接拒绝；至少保留一个字面量字符。
        if (segments.all { segment -> segment.all { ch -> ch == '*' || ch == '?' } }) return null
        return value.lowercase(Locale.ROOT)
    }
}
