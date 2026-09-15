package com.tyranor.next.core.engine.external

import androidx.annotation.StringRes
import com.tyranor.next.core.engine.EngineType

/**
 * 外置主机模拟器跳转目标（PPSSPP / Eden）。
 *
 * 与 [ExternalEngineModule] 不同：目标不是随 App 分发的引擎运行时，而是用户自行安装的独立
 * 模拟器 APK。主 App 只做识别、安装探测与 `ACTION_VIEW` 显式组件跳转，不接管其存档与设置。
 */
data class EmulatorTarget(
    val engine: EngineType,
    @get:StringRes val displayNameRes: Int,
    val packageName: String,
    val activityName: String,
    // ACTION_VIEW 的 MIME：PPSSPP 用 */*，Eden 用 application/octet-stream
    val mime: String,
    // 是否附带 FLAG_GRANT_WRITE_URI_PERMISSION（PPSSPP 需要，Eden 只读）
    val grantWrite: Boolean,
    val installUrl: String,
)
