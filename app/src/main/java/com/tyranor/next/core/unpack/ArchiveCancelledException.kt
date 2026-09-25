package com.tyranor.next.core.unpack

import java.io.IOException

/**
 * 用户取消封包/解包操作时的哨兵异常（[IOException] 子类型）。
 *
 * 与普通 IO 失败区分：调用方不得将其按失败处理或上报错误，必须落“已取消”提示。
 * 归属本文件而非 [ArtemisPfsFormat]——取消是跨格式（XP3/PFS/PF6）的通用语义，
 * 放在 Artemis 专属对象里会让 XP3 路径的异常信息撒谎。
 */
class ArchiveCancelledException(what: String) : IOException("Archive operation cancelled: $what")
