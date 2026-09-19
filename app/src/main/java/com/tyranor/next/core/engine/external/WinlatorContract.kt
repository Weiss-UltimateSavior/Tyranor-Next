package com.tyranor.next.core.engine.external

/**
 * Winlator（winlator-cn）外置启动协议常量（唯一字面量来源）。
 *
 * 协议来源：winlator-cn `docs/external-launch.md` 与 `ExternalLaunchActivity`。
 * 入口为导出的 `com.winlator.ExternalLaunchActivity`，安装包名与 Java 包名同为 `com.winlator`
 * （与 `/Users/weiss/github- engine/winlator-cn` 的 applicationId 一致）；`exe_path` 支持相对
 * `dir_path` 的文件名，未映射的 `dir_path` 由 Winlator 从 `W:` 递减分配空闲盘符**临时挂载**
 * （`save=false` 不落盘）。
 */
object WinlatorContract {

    /** 安装包名（applicationId）。 */
    const val PACKAGE_NAME = "com.winlator"
    const val ACTIVITY_NAME = "com.winlator.ExternalLaunchActivity"
    /** 待挂载并启动的目录（Unix 绝对路径）。 */
    const val EXTRA_DIR_PATH = "dir_path"

    /** 可执行文件：绝对路径（DOS/Unix/file://）或相对 [EXTRA_DIR_PATH] 的文件名。 */
    const val EXTRA_EXE_PATH = "exe_path"

    /** 是否弹 Winlator 侧确认框（默认 true；`false` 仅在对方全局设置允许时生效）。 */
    const val EXTRA_CONFIRM = "confirm"

    /** 调用方自定义标识：用于日志与会话内防抖。 */
    const val EXTRA_LAUNCH_ID = "launch_id"
}
