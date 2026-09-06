package com.core.rpgmaker

/**
 * rpgmaker 模块用到的共享偏好文件名/键常量镜像。
 *
 * 主源在 engine 模块（com.core.engine.EnginePrefs，其自身镜像 app 模块常量）；
 * 本模块不依赖 :engine，按 engine 既有“跨模块镜像常量、锚点单一”的约定在此镜像
 * 本模块实际读取的三个键，修改任一侧时必须同步另一侧。
 */
object EnginePrefs {
    /** 全局引擎设置/状态共享 prefs 文件名，与主进程/引擎子进程共用（主源：EnginePrefs.APP_PREFS）。 */
    const val APP_PREFS = "tyranor_prefs"

    /** 单游戏引擎设置覆盖层 prefs 文件名（主源：EnginePrefs.GAME_OVERRIDES_PREFS）。 */
    const val GAME_OVERRIDES_PREFS = "tyranor_game_overrides"

    /** Tyrano 外部网络开关偏好键，MV/MZ 宿主沿用同一键（主源：EnginePrefs.KEY_TYRANO_EXTERNAL_NETWORK）。 */
    const val KEY_TYRANO_EXTERNAL_NETWORK = "tyrano_external_network"
}
