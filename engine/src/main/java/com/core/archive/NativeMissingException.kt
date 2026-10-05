package com.core.archive

import java.io.IOException

/** Native 封包库缺失（so 未打包/ABI 不匹配）：engine 层哨兵，core 层转译为本地化异常。 */
class NativeMissingException(component: String) :
    IOException("native library missing: $component, rebuild engine/rust")
