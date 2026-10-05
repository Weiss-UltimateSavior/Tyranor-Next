package com.tyranor.next.core.settings

/**
 * Web 首页地址的规范化与校验（纯函数，便于单测）。
 *
 * 规则：仅接受 http/https；缺省协议补 `https://`；协议统一小写；拒绝 userinfo 凭据；
 * 主机名须为含 `.` 的合法标签序列（允许 IDN 字母数字与连字符，拒绝下划线/空标签/首尾连字符），
 * 端口须在 1..65535；长度上限 2048。其余（mailto/javascript/file/data、空白等）返回 null，由 UI 提示无效。
 */
object HomeWebUrls {

    private const val MAX_LENGTH = 2048

    /** 规范化用户输入；非法返回 null。 */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        val hasHttpScheme = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        val candidate = if (hasHttpScheme) {
            val scheme = if (trimmed.startsWith("https://", ignoreCase = true)) "https" else "http"
            "$scheme://${trimmed.substringAfter("://")}"
        } else {
            // 含 :// 但不是 http(s) 一律拒绝；形如 mailto: 或 javascript: 的 scheme 前缀
            // （冒号前无点）也拒绝，但保留 example.com:8080 这类 host:port 写法
            if (trimmed.contains("://")) return null
            val colon = trimmed.indexOf(':')
            if (colon > 0) {
                val prefix = trimmed.substring(0, colon)
                if (!prefix.contains('.') && prefix.all { it.isLetterOrDigit() || it in "+-." }) return null
            }
            "https://$trimmed"
        }
        val scheme = candidate.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        // 整体拒绝空白与控制字符（路径/查询里也不允许）
        if (candidate.any { it.isWhitespace() || it.isISOControl() }) return null
        val authority = candidate.substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        // 拒绝 userinfo：凭据会被明文持久化并在设置/弹窗中完整展示
        if (authority.contains('@')) return null
        // 显式端口必须非空（拒绝 https://example.com: 这类写法）
        if (authority.contains(':') && authority.substringAfter(':').isEmpty()) return null
        val host = authority.substringBefore(':')
        val portPart = authority.substringAfter(':', "")
        if (host.isBlank() || host.any { it.isWhitespace() } || !host.contains('.')) return null
        val labels = host.split('.')
        if (labels.any { label ->
                label.isEmpty() ||
                    label.startsWith('-') ||
                    label.endsWith('-') ||
                    label.any { !(it.isLetterOrDigit() || it == '-') }
            }
        ) {
            return null
        }
        if (portPart.isNotEmpty()) {
            val port = portPart.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        }
        return candidate
    }

    /** 是否命中内置预设（鲲Gal / 一起萌）。 */
    fun isPreset(url: String): Boolean =
        url == AppSettingsStore.HOME_WEB_URL_KUNGAL || url == AppSettingsStore.HOME_WEB_URL_LETMOE
}
