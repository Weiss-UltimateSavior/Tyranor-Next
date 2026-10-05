package com.tyranor.next.core.unpack

import java.io.IOException

/**
 * Native 封包组件缺失（so 未打包或 ABI 不匹配）：UI 层据此出本地化提示，
 * 而不是把 engine 的技术栈信息直接展示给用户。
 */
class ArchiveNativeMissingException : IOException("native archive component missing")
