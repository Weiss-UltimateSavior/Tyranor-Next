package com.core.engine

/**
 * engine 模块共享偏好文件名/键常量。
 * 主源在 app 模块（APP_PREFS → com.apps.LauncherPreferences.APP_PREFS；
 * KEY_TYRANO_EXTERNAL_NETWORK → com.core.launcher.EngineSaveKeys.KEY_TYRANO_EXTERNAL_NETWORK）。
 * engine 不得反向依赖 app，故在 engine 侧镜像常量作为单一来源，避免各引擎类各自持有字面量副本。
 */
object EnginePrefs {
    const val APP_PREFS = "yukihub_prefs"
    const val NATIVE_PLUGIN_OVERRIDE_PREFS = "rinne_native_plugin_overrides"

    /**
     * 单游戏引擎设置覆盖层 prefs 文件名。
     * 由 app 侧 PerGameSettingsStore 与引擎 TyranoActivity / TouchPadSaveBridge 共同读写；
     * 引擎不得反向依赖 app，故契约锚点定在 engine、app 直接引用本常量，避免两侧字面量漂移。
     */
    const val GAME_OVERRIDES_PREFS = "tyranor_game_overrides"

    /** Tyrano 外部网络开关偏好键（镜像 app 模块 EngineSaveKeys.KEY_TYRANO_EXTERNAL_NETWORK）。 */
    const val KEY_TYRANO_EXTERNAL_NETWORK = "tyrano_external_network"

    const val KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED = "native_plugin.kirikiroid2.enabled"
    const val KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED = "native_plugin.kirikiroid2.installed"
    const val KEY_NATIVE_PLUGIN_KIRIKIROID2_VERSION = "native_plugin.kirikiroid2.version"
    const val KEY_NATIVE_PLUGIN_KIRIKIROID2_ABI = "native_plugin.kirikiroid2.abi"
    const val KEY_NATIVE_PLUGIN_KIRIKIROID2_ZIP_SHA256 = "native_plugin.kirikiroid2.zip_sha256"
    const val KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED_AT = "native_plugin.kirikiroid2.installed_at"
    const val KEY_NATIVE_PLUGIN_KIRIKIROID2_BRIDGE_ABI = "native_plugin.kirikiroid2.bridge_abi"
    const val KEY_NATIVE_PLUGIN_KIRIKIROID2_EXPECTED_ZIP_SHA256 =
        "native_plugin.kirikiroid2.expected_zip_sha256"

    const val KEY_NATIVE_PLUGIN_ONS_ENABLED = "native_plugin.ons.enabled"
    const val KEY_NATIVE_PLUGIN_ONS_INSTALLED = "native_plugin.ons.installed"
    const val KEY_NATIVE_PLUGIN_ONS_VERSION = "native_plugin.ons.version"
    const val KEY_NATIVE_PLUGIN_ONS_ABI = "native_plugin.ons.abi"
    const val KEY_NATIVE_PLUGIN_ONS_ZIP_SHA256 = "native_plugin.ons.zip_sha256"
    const val KEY_NATIVE_PLUGIN_ONS_INSTALLED_AT = "native_plugin.ons.installed_at"
    const val KEY_NATIVE_PLUGIN_ONS_BRIDGE_ABI = "native_plugin.ons.bridge_abi"
    const val KEY_NATIVE_PLUGIN_ONS_EXPECTED_ZIP_SHA256 =
        "native_plugin.ons.expected_zip_sha256"

    const val KEY_NATIVE_PLUGIN_ARTEMIS_ENABLED = "native_plugin.artemis.enabled"
    const val KEY_NATIVE_PLUGIN_ARTEMIS_INSTALLED = "native_plugin.artemis.installed"
    const val KEY_NATIVE_PLUGIN_ARTEMIS_VERSION = "native_plugin.artemis.version"
    const val KEY_NATIVE_PLUGIN_ARTEMIS_ABI = "native_plugin.artemis.abi"
    const val KEY_NATIVE_PLUGIN_ARTEMIS_ZIP_SHA256 = "native_plugin.artemis.zip_sha256"
    const val KEY_NATIVE_PLUGIN_ARTEMIS_INSTALLED_AT = "native_plugin.artemis.installed_at"
    const val KEY_NATIVE_PLUGIN_ARTEMIS_BRIDGE_ABI = "native_plugin.artemis.bridge_abi"
    const val KEY_NATIVE_PLUGIN_ARTEMIS_EXPECTED_ZIP_SHA256 =
        "native_plugin.artemis.expected_zip_sha256"
}
