package com.tyranor.next.core.engine.external

/** JoiPlay 外置模块共用的 JSON 转义与字段拼接工具（避免各引擎模块重复实现）。 */

internal fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"')
    append(escapeJson(name))
    append("\":\"")
    append(escapeJson(value))
    append('"')
}

internal fun escapeJson(value: String): String = buildString {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
}