package com.tyranor.next.core.unpack

import java.io.File
import java.io.FileInputStream
import java.util.Locale

enum class ArchiveBackend { PFS, XP3 }

/** 受支持封包的扩展名（含 `.` 前缀，小写）。 */
val ARCHIVE_EXTENSIONS = listOf(".xp3", ".pfs", ".pf6", ".pf8")

/** 仅按文件名判定后端（扫描期用，不读内容）。非封包名返回 null。 */
fun detectArchiveBackendByName(name: String): ArchiveBackend? {
    val lower = name.lowercase(Locale.ROOT)
    return when {
        lower.endsWith(".xp3") -> ArchiveBackend.XP3
        lower.endsWith(".pfs") || lower.endsWith(".pf6") || lower.endsWith(".pf8") -> ArchiveBackend.PFS
        else -> null
    }
}

/**
 * 判定封包后端：扩展名先行（`.xp3`→XP3，`.pfs/.pf6/.pf8`→PFS），
 * 未知/无扩展名时读头字节嗅探（XP3=`58 50 33`，PFS=`70 66`），都认不出返回 null。
 *
 * SAF 下 `content://` 的 lastPathSegment 常无扩展名，中转后的真实文件名同样适用本函数。
 */
fun detectArchiveBackend(file: File): ArchiveBackend? {
    detectArchiveBackendByName(file.name)?.let { return it }
    return try {
        FileInputStream(file).use { input ->
            val head = ByteArray(3)
            if (input.read(head) < 3) return null
            when {
                head[0] == 0x58.toByte() && head[1] == 0x50.toByte() && head[2] == 0x33.toByte() ->
                    ArchiveBackend.XP3
                head[0] == 0x70.toByte() && head[1] == 0x66.toByte() -> ArchiveBackend.PFS
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }
}
