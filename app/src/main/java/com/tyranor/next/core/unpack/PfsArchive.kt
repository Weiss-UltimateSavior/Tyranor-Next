package com.tyranor.next.core.unpack

import com.core.archive.PfsCore
import com.core.archive.NativeMissingException
import java.io.File
import java.io.IOException

/**
 * Artemis PFS/PF6/PF8 封包能力（Rust `archive_pfs_core`）。
 *
 * 与 [ArtemisPfsUnpacker]（启动链 base-patch，纯 Kotlin）的区别：本对象面向
 * 通用文件管理（独立 Activity），走 native 实现以获得与 usefulunpack 一致的
 * 全量解包/回封行为；启动链仍走 Kotlin 路径，不加载 native 库。
 *
 * 所有函数均为阻塞式（内部 JNI 调用），调用方负责切 `Dispatchers.IO`。
 */
object PfsArchive {
    data class EntryInfo(val name: String, val size: Long, val isDirectory: Boolean)

    data class ExtractStats(val extracted: Int, val skipped: Int)

    data class PackStats(val entryCount: Int)

    /** 列出条目（含派生目录），名称为 `/` 分隔。 */
    private fun ensureLoaded() {
        try {
            PfsCore.ensureLoaded()
        } catch (missing: NativeMissingException) {
            throw ArchiveNativeMissingException()
        }
    }

    fun listEntries(archive: File): List<EntryInfo> {
        if (!archive.isFile) throw IOException("PFS archive missing: ${archive.path}")
        ensureLoaded()
        val json = try {
            PfsCore.pfsListEntries(archive.absolutePath)
        } catch (error: IOException) {
            throw IOException("PFS list failed for ${archive.path}: ${error.message}")
        } ?: throw IOException("PFS list failed for ${archive.path}")
        return parseListEntries(json)
    }

    fun extractAll(
        archive: File,
        outputDir: File,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): ExtractStats {
        if (!archive.isFile) throw IOException("PFS archive missing: ${archive.path}")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("PFS extract: cannot create output directory: ${outputDir.path}")
        }
        ensureLoaded()
        val counts = NativeArchiveOp.run(
            what = archive.path,
            isCancelled = isCancelled,
            onProgress = onProgress,
            snapshot = {
                NativeArchiveOp.ProgressSnapshot(
                    bytes = PfsCore.pfsExtractProgressCount(),
                    total = PfsCore.pfsExtractProgressTotal(),
                    fileBytes = PfsCore.pfsExtractProgressFileCount(),
                    fileTotal = PfsCore.pfsExtractProgressFileTotal(),
                    name = PfsCore.pfsExtractProgressName().orEmpty(),
                )
            },
            cancel = { PfsCore.pfsExtractCancel() },
            call = { PfsCore.pfsExtract("", archive.absolutePath, outputDir.absolutePath) },
        )
        // 尾检：取消恰好落在末条目时 Rust 可能已正常返回，必须复检。
        if (isCancelled()) throw ArchiveCancelledException(archive.path)
        return ExtractStats(counts.success, counts.error)
    }

    /** 封包（PF8 格式输出；PF6 包解出后回封恒为 PF8，老引擎兼容性见调用方提示）。输出已存在会被覆盖。 */
    fun pack(
        source: File,
        output: File,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): PackStats {
        if (!source.exists()) throw IOException("PFS pack: source missing: ${source.path}")
        output.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("PFS pack: cannot create output directory: ${parent.path}")
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
                        bytes = PfsCore.pfsCompressProgressCount(),
                        total = PfsCore.pfsCompressProgressTotal(),
                        fileBytes = PfsCore.pfsCompressProgressFileCount(),
                        fileTotal = PfsCore.pfsCompressProgressFileTotal(),
                        name = PfsCore.pfsCompressProgressName().orEmpty(),
                    )
                },
                cancel = { PfsCore.pfsCompressCancel() },
                call = { PfsCore.pfsCreateArchive("", source.absolutePath, output.absolutePath) },
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
            throw IOException("PFS list unparseable: $json")
        }
    }
}
