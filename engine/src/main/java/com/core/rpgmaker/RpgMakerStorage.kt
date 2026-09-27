package com.core.rpgmaker

import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

/** 供 RPG Maker MV/MZ 存档桥（RpgMakerSaveBridge，StorageManager 兼容接口）使用的、限制在单一存档目录内的文件存储。 */
internal object RpgMakerStorage {
    private const val TAG = "YukiRpgMaker"
    private const val MAX_SAVE_BYTES = 8L * 1024L * 1024L
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
    fun write(directory: File?, key: String?, value: String?, extension: String): Boolean {
        // 原子写：先写同目录临时文件并 fsync，再 rename 覆盖；写入中途进程被杀
        // （finish 500ms 自杀 / 系统回收）不会留下截断的存档文件（PR review 意见）
        return try {
            val file = resolveFile(directory, key, extension) ?: return false
            val text = value.orEmpty()
            // 预检（无分配）：UTF-8 字节数恒 ≥ UTF-16 字符数，字符数已超限即可直接拒绝，
            // 避免为必然被拒的载荷先分配一份全量字节数组
            if (text.length > MAX_SAVE_BYTES) {
                Log.w(TAG, "setStorage rejected pre-encode (chars=${text.length})")
                return false
            }
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_SAVE_BYTES) return false
            val dir = file.parentFile ?: return false
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            val tmp = File(dir, file.name + ".tmp." + System.nanoTime())
            var committed = false
            try {
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                committed = if (tmp.renameTo(file)) {
                    true
                } else {
                    // rename 失败（目标被占用等）退回直接覆盖，保底不丢数据
                    file.outputStream().use { it.write(bytes) }
                    tmp.delete()
                    true
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            committed
        } catch (error: Throwable) {
            Log.w(TAG, "setStorage failed key=$key", error)
            false
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
    fun resolveFile(directory: File?, key: String?, extension: String): File? {
        if (directory == null) return null
        if (extension !in setOf(".sav", ".bin")) return null
        // 键校验与「键 → 落盘名」规则统一由 RpgSaveKeyMapping 提供：应用侧转化按同一实现
        // 写入，两处漂移会导致「转化写进去、引擎读不到」且无任何报错。
        val clean = RpgSaveKeyMapping.sanitizeKey(key) ?: return null
        val root = directory.canonicalFile

        // A legacy Tyranor save with spaces or Unicode may already exist under its raw key.
        // Continue using it when it is safely a single filename, otherwise migrate new writes
        // to the deterministic mapping below.
        if (!RpgSaveKeyMapping.DIRECT_FILE_KEY.matches(clean)) {
            legacyFile(root, clean, extension)?.takeIf(File::isFile)?.let { return it }
        }
        return insideRoot(root, File(root, RpgSaveKeyMapping.canonicalFileName(clean, extension)))
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

}
