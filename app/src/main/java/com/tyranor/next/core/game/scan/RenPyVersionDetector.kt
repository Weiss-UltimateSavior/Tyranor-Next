package com.tyranor.next.core.game.scan

import com.tyranor.next.core.settings.EngineSettingsStore
import java.io.File

/** Ren'Py 游戏版本探测。按 script_version/runtime 自动匹配链路探测。 */
object RenPyVersionDetector {
    private const val RENPY_8_THRESHOLD = 80000
    private const val RENPY_86_THRESHOLD = 80600

    fun detect(gameDir: File): String? {
        if (!gameDir.isDirectory) return null
        detectFromScriptVersion(gameDir)?.let { return it }
        if (File(gameDir, "lib/pythonlib2.7").exists()) return EngineSettingsStore.RENPY_77
        return EngineSettingsStore.RENPY_85
    }

    fun detect(
        scriptVersionTxt: String?,
        scriptVersionRpy: String?,
        hasPython27: Boolean,
    ): String {
        val versionCode = readVersionCode(scriptVersionTxt, scriptVersionRpy)
        if (versionCode != null) return moduleVersionForCode(versionCode)
        return if (hasPython27) EngineSettingsStore.RENPY_77 else EngineSettingsStore.RENPY_85
    }

    internal fun readVersionCode(scriptVersionTxt: String?, scriptVersionRpy: String?): Int? {
        parseVersionCode(scriptVersionTxt)?.let { return it }
        val rpyVersionBlock = scriptVersionRpy
            ?.substringAfter("script_version", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
        return parseVersionCode(rpyVersionBlock)
    }

    internal fun parseVersionCode(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val parts = Regex("[^0-9,]").replace(raw.replace('.', ','), "")
            .split(',')
            .filter { it.isNotBlank() }
            .take(3)
        if (parts.size < 2) return null
        val normalized = buildString {
            parts.forEachIndexed { index, part ->
                val number = part.toIntOrNull() ?: return null
                append(if (number > 9) part else "0$number")
                if (index == 1 && parts.size == 2) append("00")
            }
            if (parts.size == 2 && length == 4) {
                append("00")
            }
        }
        return normalized.toIntOrNull()
    }

    internal fun moduleVersionForCode(versionCode: Int): String = when {
        versionCode < RENPY_8_THRESHOLD -> EngineSettingsStore.RENPY_77
        versionCode < RENPY_86_THRESHOLD -> EngineSettingsStore.RENPY_85
        else -> EngineSettingsStore.RENPY_86
    }

    /**
     * 仅凭游戏自带的 script_version 判定版本；没有可读的版本文件时返回 null（不做 Python 2.7 / 默认推断）。
     * 启动时用来修正媒体库里旧扫描记录的版本（例如 8.6 游戏曾被记成 8.x 默认值），读不到时保留旧记录。
     */
    fun detectFromScriptVersion(gameDir: File): String? {
        val gameSubdir = File(gameDir, "game")
        val versionCode = readVersionCode(
            scriptVersionTxt = File(gameSubdir, "script_version.txt").takeIf { it.isFile }?.readTextSafely(),
            scriptVersionRpy = File(gameSubdir, "script_version.rpy").takeIf { it.isFile }?.readTextSafely(),
        ) ?: return null
        return moduleVersionForCode(versionCode)
    }

    private fun File.readTextSafely(): String? = runCatching {
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            val count = input.read(buffer)
            if (count <= 0) "" else String(buffer, 0, count, Charsets.UTF_8)
        }
    }.getOrNull()
}
