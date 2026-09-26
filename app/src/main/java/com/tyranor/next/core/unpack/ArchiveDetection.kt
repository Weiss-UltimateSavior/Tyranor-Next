package com.tyranor.next.core.unpack

import java.io.File
import java.io.FileInputStream
import java.util.Locale

/** 受支持封包的扩展名（含 `.` 前缀，小写）。 */
val ARCHIVE_EXTENSIONS = listOf(".xp3")

/** 仅按文件名判定（扫描期用，不读内容）。非封包名返回 false。 */
fun isArchiveFileName(name: String): Boolean {
    return name.lowercase(Locale.ROOT).run {
        ARCHIVE_EXTENSIONS.any { endsWith(it) }
    }
}

/**
 * 判定封包文件：扩展名先行；未知/无扩展名时读头字节嗅探（XP3=`58 50 33`）。
 *
 * SAF 下 `content://` 的 lastPathSegment 常无扩展名，中转后的真实文件名同样适用本函数。
 */
fun isArchiveFile(file: File): Boolean {
    if (isArchiveFileName(file.name)) return true
    return try {
        FileInputStream(file).use { input ->
            val head = ByteArray(3)
            if (input.read(head) < 3) return false
            head[0] == 0x58.toByte() && head[1] == 0x50.toByte() && head[2] == 0x33.toByte()
        }
    } catch (_: Exception) {
        false
    }
}
