package com.core.archive

/**
 * Kirikiri XP3 打包/解包 native 桥（engine/rust/crates/xp3-core，产物 libarchive_xp3_core.so）。
 *
 * 库加载失败不抛在 init（避免 ExceptionInInitializerError），由 [ensureLoaded]
 * 在每次操作入口显式检查并转为 [IOException]。
 */
object Xp3Core {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary("archive_xp3_core")
            } catch (error: UnsatisfiedLinkError) {
                throw NativeMissingException("archive_xp3_core (${error.message})")
            }
            loaded = true
        }
    }

    external fun xp3Extract(tool: String, input: String, output: String): String?
    external fun xp3ListEntries(input: String): String?
    external fun xp3ExtractProgressCount(): Long
    external fun xp3ExtractProgressTotal(): Long
    external fun xp3ExtractProgressFileCount(): Long
    external fun xp3ExtractProgressFileTotal(): Long
    external fun xp3ExtractProgressName(): String?
    external fun xp3ExtractCancel()
    external fun xp3CreateArchive(tool: String, input: String, output: String, level: String): String?
    external fun xp3CompressProgressCount(): Long
    external fun xp3CompressProgressTotal(): Long
    external fun xp3CompressProgressFileCount(): Long
    external fun xp3CompressProgressFileTotal(): Long
    external fun xp3CompressProgressName(): String?
    external fun xp3CompressCancel()
}
