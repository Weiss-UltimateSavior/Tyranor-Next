package com.core.archive

/**
 * Artemis PFS/PF6/PF8 打包/解包 native 桥（engine/rust/crates/pfs-core，产物 libarchive_pfs_core.so）。
 *
 * 库加载失败不抛在 init（避免 ExceptionInInitializerError），由 [ensureLoaded]
 * 在每次操作入口显式检查并转为 [IOException]。
 */
object PfsCore {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary("archive_pfs_core")
            } catch (error: UnsatisfiedLinkError) {
                throw NativeMissingException("archive_pfs_core (${error.message})")
            }
            loaded = true
        }
    }

    external fun pfsExtract(tool: String, input: String, output: String): String?
    external fun pfsExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun pfsListEntries(input: String): String?
    external fun pfsExtractProgressCount(): Long
    external fun pfsExtractProgressTotal(): Long
    external fun pfsExtractProgressFileCount(): Long
    external fun pfsExtractProgressFileTotal(): Long
    external fun pfsExtractProgressName(): String?
    external fun pfsExtractCancel()
    external fun pfsCreateArchive(tool: String, input: String, output: String): String?
    external fun pfsCompressProgressCount(): Long
    external fun pfsCompressProgressTotal(): Long
    external fun pfsCompressProgressFileCount(): Long
    external fun pfsCompressProgressFileTotal(): Long
    external fun pfsCompressProgressName(): String?
    external fun pfsCompressCancel()
}
