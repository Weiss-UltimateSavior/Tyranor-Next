package com.tyranor.next.core.unpack

import android.util.Log
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import kotlin.text.Charsets.UTF_8

/**
 * Artemis base patcher for titles whose required startup files are packed in .pfs archives.
 *
 * Some Artemis games ship without loose Android startup files. The native engine expects a few
 * bootstrap entries such as system.ini and the BOOT script to exist on disk, so we extract only
 * those small required entries and let the engine continue streaming the rest from the archive.
 */
object ArtemisPfsUnpacker {
    private const val TAG = "ArtemisPfsUnpacker"

    private const val MAX_ENTRY_BYTES = 50L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    private const val MAX_ENTRY_COUNT = 10_000
    private const val MAX_NAME_BYTES = 4096
    private const val MIN_ENCRYPTED_LEN = 8

    fun needsBasePatch(rootPath: String?): Boolean {
        if (rootPath.isNullOrBlank() || rootPath.startsWith("content://")) return false
        val dir = File(rootPath)
        if (!dir.isDirectory) return false
        val pfsFiles = listPfsFiles(dir)
        if (pfsFiles.isEmpty()) return false
        val systemIni = File(dir, "system.ini")
        if (!systemIni.isFile) return true
        return hasMissingStartupFile(dir, systemIni)
    }

    fun applyBasePatch(rootPath: String?): Boolean {
        if (!needsBasePatch(rootPath)) return true
        val dir = File(rootPath.orEmpty())
        var totalBytes = 0L
        return try {
            for (pfs in listPfsFiles(dir)) {
                totalBytes += unpackPfs(dir, pfs)
                if (totalBytes > MAX_TOTAL_BYTES) {
                    logWarn("base patch total bytes exceed limit, abort")
                    return false
                }
            }
            ensureSystemIni(dir)
            ensureBootFileAvailable(dir)
            true
        } catch (error: Exception) {
            logWarn("apply base patch failed root=$rootPath", error)
            false
        }
    }

    private fun listPfsFiles(dir: File): List<File> {
        val files = runCatching { dir.listFiles()?.filter { it.isFile && isPfsName(it.name) } }
            .getOrNull() ?: return emptyList()
        return files.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun isPfsName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".pfs") || Regex("^[^.]+\\.pfs\\.\\d{3}$").matches(lower)
    }

    private fun unpackPfs(gameDir: File, pfs: File): Long {
        var written = 0L
        var entries = 0
        RandomAccessFile(pfs, "r").use { raf ->
            if (raf.read() != 0x70 || raf.read() != 0x66) {
                return written
            }
            raf.read()
            val tableLen = readM(raf)
            if (tableLen <= 0 || tableLen > MAX_ENTRY_BYTES) {
                logWarn("invalid pfs table length $tableLen")
                return written
            }
            val tableStart = raf.filePointer
            val table = ByteArray(tableLen)
            raf.readFully(table)
            val key = MessageDigest.getInstance("SHA-1").digest(table)
            raf.seek(tableStart)
            val entryCount = readM(raf)
            if (entryCount < 0 || entryCount > MAX_ENTRY_COUNT) {
                logWarn("invalid pfs entry count $entryCount")
                return written
            }
            val fileLength = raf.length()
            for (i in 0 until entryCount) {
                val nameLen = readM(raf)
                if (nameLen < 0 || nameLen > MAX_NAME_BYTES) {
                    logWarn("invalid pfs entry name length $nameLen")
                    return written
                }
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                val rawName = String(nameBytes, UTF_8)
                readM(raf)
                val offset = readM(raf).toLong()
                val dataLen = readM(raf).toLong()
                val relPath = rawName.replace('\\', '/')
                if (!shouldExtract(relPath)) continue
                if (offset < 0 || dataLen < 0 || offset + dataLen > fileLength || dataLen > MAX_ENTRY_BYTES) {
                    logWarn("invalid pfs entry bounds name=$rawName offset=$offset len=$dataLen")
                    continue
                }
                val target = safeTarget(gameDir, relPath) ?: continue
                val data = readEntryData(raf, offset, dataLen.toInt(), key) ?: return written
                writeEntry(target, data)
                written += data.size
                entries++
                postProcessEntry(target, relPath)
            }
        }
        logInfo("unpacked ${pfs.name}: entries=$entries bytes=$written")
        return written
    }

    private fun shouldExtract(relPath: String): Boolean {
        val lower = relPath.lowercase(Locale.ROOT)
        if (lower == "system.ini") return true
        if (isArtemisStartupSystemFile(lower)) return true
        if (!lower.contains("movie")) return false
        return lower.endsWith(".dat") || lower.endsWith(".mp4") || lower.endsWith(".ogv") ||
            lower.endsWith(".wmv") || lower.endsWith(".mpg") || lower.endsWith(".webm")
    }

    private fun isArtemisStartupSystemFile(lowerRelPath: String): Boolean {
        if (!lowerRelPath.startsWith("system/")) return false
        if (lowerRelPath.contains("/../")) return false
        return lowerRelPath.endsWith(".iet") ||
            lowerRelPath.endsWith(".lua") ||
            lowerRelPath.endsWith(".asb") ||
            lowerRelPath.endsWith(".tbl") ||
            lowerRelPath.endsWith(".glsl") ||
            lowerRelPath.endsWith(".ini") ||
            lowerRelPath.endsWith(".json") ||
            lowerRelPath.endsWith(".txt")
    }

    private fun readEntryData(raf: RandomAccessFile, offset: Long, dataLen: Int, key: ByteArray): ByteArray? {
        val saved = raf.filePointer
        return try {
            raf.seek(offset)
            val data = ByteArray(dataLen)
            raf.readFully(data)
            if (dataLen >= MIN_ENCRYPTED_LEN) {
                for (j in data.indices) {
                    data[j] = (data[j].toInt() xor key[j % key.size].toInt()).toByte()
                }
            }
            data
        } finally {
            raf.seek(saved)
        }
    }

    private fun writeEntry(target: File, data: ByteArray) {
        val parent = target.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) {
            logWarn("cannot create directory ${parent.path}")
            return
        }
        target.writeBytes(data)
    }

    private fun safeTarget(gameDir: File, relPath: String): File? {
        if (relPath.isBlank()) return null
        val root = runCatching { gameDir.canonicalFile }.getOrElse { gameDir.absoluteFile }
        val target = File(root, relPath)
        val canonical = runCatching { target.canonicalFile }.getOrElse { return null }
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            logWarn("blocked path traversal entry=$relPath")
            return null
        }
        return canonical
    }

    private fun postProcessEntry(target: File, relPath: String) {
        val lower = relPath.lowercase(Locale.ROOT)
        when {
            lower.contains("system.ini") -> patchSystemIni(target)
            lower.contains("list_windows") -> renameListWindows(target)
        }
    }

    private fun patchSystemIni(file: File) {
        try {
            val lines = file.readLines(UTF_8).toMutableList()
            if (lines.any { it.trim() == "[ANDROID]" }) return
            lines += "\n[ANDROID]"
            lines += "SIDECUT = 0"
            lines += "BOOT = system/first.iet"
            lines += "FONT_CACHE_SIZE = 8388608"
            file.writeText(lines.joinToString("\n"), UTF_8)
        } catch (error: Exception) {
            logWarn("patch system.ini failed ${file.path}", error)
        }
    }

    private fun renameListWindows(file: File) {
        try {
            val target = File(file.parentFile, file.name.replace("list_windows", "list_android"))
            if (!target.exists() && file.exists() && file.renameTo(target)) {
                logInfo("renamed ${file.name} -> ${target.name}")
                if (target.name.equals("list_android.tbl", ignoreCase = true)) {
                    flipListAndroidTblConfig(target)
                }
            }
        } catch (error: Exception) {
            logWarn("rename list_windows failed ${file.path}", error)
        }
    }

    private fun flipListAndroidTblConfig(file: File) {
        try {
            val out = buildString {
                file.readLines(UTF_8).forEach { raw ->
                    val key = raw.trim().lowercase(Locale.ROOT)
                    when {
                        key.contains("config_tablet=0") -> append("config_tablet=1,\n")
                        key.contains("config_tabletui=0") -> append("config_tabletui=1,\n")
                        else -> {
                            append(raw)
                            append("\n")
                        }
                    }
                }
            }
            file.writeText(out, UTF_8)
        } catch (error: Exception) {
            logWarn("flip list_android.tbl config failed ${file.path}", error)
        }
    }

    private fun ensureSystemIni(dir: File) {
        val file = File(dir, "system.ini")
        if (file.exists()) return
        try {
            file.writeText(
                buildString {
                    appendLine("[SYSTEM]")
                    appendLine("WIDTH = 1280")
                    appendLine("HEIGHT = 720")
                    appendLine("CHARSET = UTF-8")
                    appendLine()
                    appendLine("[ANDROID]")
                    appendLine("SIDECUT = 0")
                    appendLine("BOOT = system/first.iet")
                    appendLine("FONT_CACHE_SIZE = 8388608")
                },
                UTF_8,
            )
            logInfo("generated fallback system.ini at ${file.path}")
        } catch (error: Exception) {
            logWarn("generate system.ini failed ${file.path}", error)
        }
    }

    private fun ensureBootFileAvailable(dir: File) {
        val systemIni = File(dir, "system.ini")
        val bootPath = readBootPath(systemIni) ?: DEFAULT_ANDROID_BOOT
        val bootFile = safeTarget(dir, bootPath)
        if (bootFile?.isFile == true) return
        logWarn("Artemis BOOT script still missing after base patch: $bootPath")
    }

    private fun hasMissingStartupFile(dir: File, systemIni: File): Boolean {
        val bootPath = readBootPath(systemIni) ?: DEFAULT_ANDROID_BOOT
        val bootFile = safeTarget(dir, bootPath)
        if (bootFile?.isFile != true) return true

        // Android Artemis 启动脚本通常立刻 include system/init.lua，随后 init.lua 再挂载
        // system/adv、system/ui、system/table 等基础系统脚本/表。若这些仍留在 PFS 内，
        // 部分 native revision 首屏会只完成窗口初始化但无法进入标题画面。
        if (!File(dir, "system/init.lua").isFile) return true
        if (!File(dir, "system/table/list_android.tbl").isFile &&
            !File(dir, "system/table/list_android_ja.tbl").isFile
        ) return true
        return false
    }

    private fun readBootPath(systemIni: File): String? {
        if (!systemIni.isFile) return null
        val lines = runCatching { systemIni.readLines(UTF_8) }
            .getOrElse {
                runCatching { systemIni.readLines(Charsets.ISO_8859_1) }.getOrNull()
            }
            ?: return null
        var section = ""
        var androidBoot: String? = null
        var firstBoot: String? = null
        for (raw in lines) {
            val line = raw.substringBefore(';').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().uppercase(Locale.ROOT)
                continue
            }
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val key = line.substring(0, equals).trim().uppercase(Locale.ROOT)
            if (key != "BOOT") continue
            val value = line.substring(equals + 1)
                .trim()
                .trim('"')
                .replace('\\', '/')
                .takeIf { it.isNotBlank() }
                ?: continue
            if (firstBoot == null) firstBoot = value
            if (section == "ANDROID") androidBoot = value
        }
        return androidBoot ?: firstBoot
    }

    private fun readM(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("pfs truncated")
        return ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0) and 0x7fffffff
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarn(message: String, error: Throwable? = null) {
        runCatching { if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error) }
    }

    private const val DEFAULT_ANDROID_BOOT = "system/first.iet"
}
