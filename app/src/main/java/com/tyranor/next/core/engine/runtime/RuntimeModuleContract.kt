package com.tyranor.next.core.engine.runtime

import com.core.engine.LaunchContract

/**
 * 第三方 APK 运行时模块的 v1 协议常量。
 *
 * 模块通过 exported Activity 声明 [ACTION_LAUNCH] 被主 App 发现；包名前缀只是命名约定，
 * action 才是发现键。扫描描述放在模块自己的 `res/raw/`，由 [META_MANIFEST] 指向。
 */
object RuntimeModuleContract {
    /** 模块启动入口声明的 action（同时用于发现查询）。 */
    const val ACTION_LAUNCH = LaunchContract.RUNTIME_MODULE_ACTION

    /** 模块包名命名约定；不是安全边界。 */
    const val PACKAGE_PREFIX = "com.tyranor.runtime."

    /** Activity meta-data：指向模块 `res/raw/` 下的扫描描述资源。 */
    const val META_MANIFEST = "com.tyranor.runtime.MANIFEST"

    /** 启动 extra：游戏文件夹绝对路径。 */
    const val EXTRA_FOLDER_PATH = LaunchContract.RUNTIME_MODULE_FOLDER_PATH

    /** 启动 extra：游戏标题（便于模块展示，可不读）。 */
    const val EXTRA_GAME_TITLE = LaunchContract.RUNTIME_MODULE_GAME_TITLE

    /** 启动 extra：主 App 协议版本。 */
    const val EXTRA_PROTOCOL_VERSION = LaunchContract.RUNTIME_MODULE_PROTOCOL_VERSION

    /** 当前主 App 支持的协议版本。 */
    const val PROTOCOL_VERSION = LaunchContract.RUNTIME_MODULE_VERSION
}
