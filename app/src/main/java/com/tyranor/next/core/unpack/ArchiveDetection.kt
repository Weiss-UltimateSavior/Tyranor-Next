package com.tyranor.next.core.unpack

import java.util.Locale

/** 受支持封包的扩展名（含 `.` 前缀，小写）。 */
val ARCHIVE_EXTENSIONS = listOf(".xp3")

/** 仅按文件名判定（扫描期用，不读内容）。非封包名返回 false。 */
fun isArchiveFileName(name: String): Boolean {
    return name.lowercase(Locale.ROOT).run {
        ARCHIVE_EXTENSIONS.any { endsWith(it) }
    }
}

/** 去掉最后一个扩展名的基名：解包输出文件夹、封包展示名共用。 */
fun baseNameWithoutExtension(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) fileName.substring(0, dot) else fileName
}
