package com.core.tyrano

import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 供 Tyrano JavaScript 桥使用的、限制在单一存档目录内的文件存储。 */
internal object TyranoStorage {
    private const val TAG = "YukiTyrano"
    private const val MAX_SAVE_BYTES = 8L * 1024L * 1024L
    private val directFileKey = Regex("[A-Za-z0-9._-]{1,128}")
    private const val MAX_KEY_CHARS = 512

    @JvmStatic
    fun read(directory: File?, key: String?): String {
        return read(directory, key, ".sav")
    }

    @JvmStatic
    fun read(directory: File?, key: String?, extension: String): String {
        return try {
            val file = resolveFile(directory, key, extension) ?: return ""
            if (!file.isFile || file.length() !in 0..MAX_SAVE_BYTES) return ""
            val bytes = file.inputStream().buffered().use { input ->
                val output = java.io.ByteArrayOutputStream(file.length().toInt())
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_SAVE_BYTES) return ""
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            String(bytes, StandardCharsets.UTF_8)
        } catch (error: Throwable) {
            Log.w(TAG, "getStorage failed key=$key", error)
            ""
        }
    }

    @JvmStatic
    fun write(directory: File?, key: String?, value: String?) {
        write(directory, key, value, ".sav")
    }

    @JvmStatic
    fun write(directory: File?, key: String?, value: String?, extension: String) {
        try {
            val file = resolveFile(directory, key, extension) ?: return
            val bytes = value.orEmpty().toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_SAVE_BYTES) return
            file.outputStream().use { it.write(bytes) }
        } catch (error: Throwable) {
            Log.w(TAG, "setStorage failed key=$key", error)
        }
    }

    @JvmStatic
    fun exists(directory: File?, key: String?, extension: String): Boolean =
        resolveFile(directory, key, extension)?.isFile == true

    @JvmStatic
    fun remove(directory: File?, key: String?, extension: String): Boolean = try {
        val file = resolveFile(directory, key, extension) ?: return false
        !file.exists() || file.delete()
    } catch (error: Throwable) {
        Log.w(TAG, "removeStorage failed key=$key", error)
        false
    }

    @JvmStatic
    fun resolveFile(directory: File?, key: String?): File? = resolveFile(directory, key, ".sav")

    @JvmStatic
    fun resolveFile(directory: File?, key: String?, extension: String): File? {
        if (directory == null || key == null) return null
        if (extension !in setOf(".sav", ".bin")) return null
        val clean = key.trim()
        if (clean.isEmpty() || clean.length > MAX_KEY_CHARS || clean.any { it == '\u0000' || it.isISOControl() }) return null
        // Keys are data, never paths.  Special filename characters are supported through the
        // hash mapping below, but directory separators remain invalid.
        if (clean.contains('/') || clean.contains('\\') || clean.contains("..")) return null
        val root = directory.canonicalFile
        // Preserve Tyranor/Rinne's established filename for normal keys, so existing saves
        // remain readable.  Non-standard keys use a deterministic SHA-256 name instead of
        // being rejected; the raw key never becomes part of a filesystem path.
        if (directFileKey.matches(clean)) return insideRoot(root, File(root, "$clean$extension"))

        // A legacy Tyranor save with spaces or Unicode may already exist under its raw key.
        // Continue using it when it is safely a single filename, otherwise migrate new writes
        // to the deterministic mapping below.
        legacyFile(root, clean, extension)?.takeIf(File::isFile)?.let { return it }
        return insideRoot(root, File(root, "key_${sha256(clean)}$extension"))
    }

    private fun legacyFile(root: File, key: String, extension: String): File? {
        return insideRoot(root, File(root, "$key$extension"))
    }

    private fun insideRoot(root: File, candidate: File): File? = try {
        candidate.canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
    } catch (_: Throwable) {
        // canonicalFile 解析失败时视为路径越界，返回 null 拒绝访问（边界兜底，§8）
        null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
