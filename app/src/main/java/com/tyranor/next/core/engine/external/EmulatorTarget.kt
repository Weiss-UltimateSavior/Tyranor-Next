package com.tyranor.next.core.engine.external

import androidx.annotation.StringRes
import com.tyranor.next.core.engine.EngineType

/**
 * 外置跳转目标的启动形态。
 *
 * - [VIEW_FILE]：`ACTION_VIEW` + 显式组件，数据为 ROM 文件 URI（PPSSPP / Eden）。
 * - [WINLATOR_EXTERNAL]：Winlator 外置启动协议（显式组件 + `dir_path`/`exe_path` extras，
 *   目录由 Winlator 自动分配空闲盘符临时挂载），用于 YU-RIS 等 Windows 游戏。
 */
enum class EmulatorLaunchStyle { VIEW_FILE, WINLATOR_EXTERNAL }

/**
 * 外置主机/模拟器跳转目标（PPSSPP / Eden / Winlator）。
 *
 * 与 [ExternalEngineModule] 不同：目标不是随 App 分发的引擎运行时，而是用户自行安装的独立
 * 模拟器 APK。主 App 只做识别、安装探测与显式组件跳转，不接管其存档与设置。
 */
data class EmulatorTarget(
    val engine: EngineType,
    @get:StringRes val displayNameRes: Int,
    val packageName: String,
    val activityName: String,
    // ACTION_VIEW 的 MIME（仅 VIEW_FILE 使用）：PPSSPP 用 */*，Eden 用 application/octet-stream
    val mime: String,
    // 是否附带 FLAG_GRANT_WRITE_URI_PERMISSION（PPSSPP 需要，Eden 只读）
    val grantWrite: Boolean,
    val installUrl: String,
    val launchStyle: EmulatorLaunchStyle = EmulatorLaunchStyle.VIEW_FILE,
)
