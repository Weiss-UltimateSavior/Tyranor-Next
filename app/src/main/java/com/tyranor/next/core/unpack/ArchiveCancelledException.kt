package com.tyranor.next.core.unpack

import java.io.IOException

/**
 * 用户取消封包/解包操作时的哨兵异常（[IOException] 子类型）。
 *
 * 与普通 IO 失败区分：调用方不得将其按失败处理或上报错误，必须落“已取消”提示。
 */
class ArchiveCancelledException(what: String) : IOException("Archive operation cancelled: $what")
