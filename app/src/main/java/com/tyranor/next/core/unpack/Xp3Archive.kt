package com.tyranor.next.core.unpack

import com.core.archive.Xp3Core
import com.core.archive.NativeMissingException
import java.io.File
import java.io.IOException

/**
 * Kirikiri XP3 封包能力（Rust `archive_xp3-core`，含 KSD mode-2 文本解扰）。
 *
 * 所有函数均为阻塞式（内部 JNI 调用），调用方负责切 `Dispatchers.IO`；
 * 进度经独立轮询线程回调，取消经 [ArchiveCancelledException] 报告。
 */
object Xp3Archive {
    data class EntryInfo(val name: String, val size: Long, val isDirectory: Boolean)

    data class ExtractStats(val extracted: Int, val skipped: Int)

    data class PackStats(val entryCount: Int)

    /** 列出条目（含派生目录），名称为 `/` 分隔。 */
    private fun ensureLoaded() {
        try {
            Xp3Core.ensureLoaded()
        } catch (missing: NativeMissingException) {
            throw ArchiveNativeMissingException()
        }
    }

    fun listEntries(archive: File): List<EntryInfo> {
        if (!archive.isFile) throw IOException("XP3 archive missing: ${archive.path}")
        ensureLoaded()
        val json = try {
            Xp3Core.xp3ListEntries(archive.absolutePath)
        } catch (error: IOException) {
            throw IOException("XP3 list failed for ${archive.path}: ${error.message}")
        } ?: throw IOException("XP3 list failed for ${archive.path}")
        return parseListEntries(json)
    }

    fun extractAll(
        archive: File,
        outputDir: File,
        onProgress: (
            writtenBytes: Long,
            totalBytes: Long,
            fileWrittenBytes: Long,
            fileTotalBytes: Long,
            entryName: String,
        ) -> Unit = { _, _, _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ExtractStats {
        if (!archive.isFile) throw IOException("XP3 archive missing: ${archive.path}")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("XP3 extract: cannot create output directory: ${outputDir.path}")
        }
        ensureLoaded()
        val counts = NativeArchiveOp.run(
            what = archive.path,
            isCancelled = isCancelled,
            onProgress = onProgress,
            snapshot = {
                NativeArchiveOp.ProgressSnapshot(
                    bytes = Xp3Core.xp3ExtractProgressCount(),
                    total = Xp3Core.xp3ExtractProgressTotal(),
                    fileBytes = Xp3Core.xp3ExtractProgressFileCount(),
                    fileTotal = Xp3Core.xp3ExtractProgressFileTotal(),
                    name = Xp3Core.xp3ExtractProgressName().orEmpty(),
                )
            },
            cancel = { Xp3Core.xp3ExtractCancel() },
            call = { Xp3Core.xp3Extract("", archive.absolutePath, outputDir.absolutePath) },
        )
        // 尾检：取消恰好落在末条目时 Rust 可能已正常返回，必须复检。
        if (isCancelled()) throw ArchiveCancelledException(archive.path)
        return ExtractStats(counts.success, counts.error)
    }

    /**
     * 封包，[level] 0 = 明文存放，1..9 zlib 等级（Rust 侧钳制）。
     * 输出已存在会被覆盖。
     */
    fun pack(
        source: File,
        output: File,
        level: Int = 6,
        onProgress: (
            writtenBytes: Long,
            totalBytes: Long,
            fileWrittenBytes: Long,
            fileTotalBytes: Long,
            entryName: String,
        ) -> Unit = { _, _, _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): PackStats {
        if (!source.exists()) throw IOException("XP3 pack: source missing: ${source.path}")
        output.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("XP3 pack: cannot create output directory: ${parent.path}")
            }
        }
        ensureLoaded()
        // Rust 侧封包失败不删残留输出（与 PF6 的 fail-closed 对齐）：此处统一清理。
        try {
            val counts = NativeArchiveOp.run(
                what = source.path,
                isCancelled = isCancelled,
                onProgress = onProgress,
                snapshot = {
                    NativeArchiveOp.ProgressSnapshot(
                        bytes = Xp3Core.xp3CompressProgressCount(),
                        total = Xp3Core.xp3CompressProgressTotal(),
                        fileBytes = Xp3Core.xp3CompressProgressFileCount(),
                        fileTotal = Xp3Core.xp3CompressProgressFileTotal(),
                        name = Xp3Core.xp3CompressProgressName().orEmpty(),
                    )
                },
                cancel = { Xp3Core.xp3CompressCancel() },
                call = {
                    Xp3Core.xp3CreateArchive(
                        "",
                        source.absolutePath,
                        output.absolutePath,
                        level.coerceIn(0, 9).toString(),
                    )
                },
            )
            if (isCancelled()) throw ArchiveCancelledException(source.path)
            return PackStats(counts.total)
        } catch (error: Throwable) {
            runCatching { output.delete() }
            if (output.exists()) runCatching { output.deleteOnExit() }
            throw error
        }
    }

    /** 纯解析（不碰 native 库，单测可直测）：`[{"n","s","d","e"}]` → 条目表。 */
    fun parseListEntries(json: String): List<EntryInfo> {
        return try {
            val array = org.json.JSONArray(json)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                EntryInfo(
                    name = obj.getString("n"),
                    size = obj.getLong("s"),
                    isDirectory = obj.getBoolean("d"),
                )
            }
        } catch (error: Exception) {
            // 敌意归档的条目表可达数 MB：异常消息只带前缀，防止在 compose 状态里驻留大字符串。
            throw IOException("XP3 list unparseable: ${json.take(200)}")
        }
    }
}
