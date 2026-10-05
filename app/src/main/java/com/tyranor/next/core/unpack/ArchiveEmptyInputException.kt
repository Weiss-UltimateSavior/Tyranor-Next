package com.tyranor.next.core.unpack

import java.io.IOException

/** 封包输入为空（目录里没有任何普通文件）：core 层类型化错误，文案由 UI 层格式化。 */
class ArchiveEmptyInputException(subject: String) : IOException("archive input empty: $subject")
