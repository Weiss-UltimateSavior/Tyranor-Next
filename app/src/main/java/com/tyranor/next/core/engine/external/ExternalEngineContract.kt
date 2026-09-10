package com.tyranor.next.core.engine.external

/**
 * 外置 APK 引擎模块（Joiplay runtime 系）的 Intent extras 键单一来源（P1-7）。
 * 协议由外置 APK 固定，App 侧只负责按契约写入；两侧不再散落裸字符串。
 */
object ExternalEngineContract {
    const val GAME = "game"
    const val SETTINGS = "settings"
    const val ORIENTATION = "orientation"
    const val ROOT_URI = "rootUri"
    const val LAUNCH_TARGET = "launchTarget"
}
