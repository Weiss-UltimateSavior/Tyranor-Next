package com.tyranor.next.core.unpack

import java.io.IOException

/** 同名产物冲突（解压输出文件夹 / 封包输出文件已存在）：拒绝执行，提示用户先备份或改名。 */
class ArchiveConflictException(message: String) : IOException(message)
