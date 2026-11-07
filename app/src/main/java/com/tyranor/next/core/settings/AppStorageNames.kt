package com.tyranor.next.core.settings

/** 应用级持久化容器名称，集中维护跨域迁移与首次启动判断所需的存储边界。 */
internal object AppStorageNames {
    const val APP_SETTINGS_PREFS = "app_settings"
    const val ENGINE_PREFS = "tyranor_prefs"
    const val LEGACY_ENGINE_PREFS = "yukihub_prefs"
    const val GAME_SCANNER_PREFS = "game_scanner"
    const val GAME_LIBRARY_DATABASE = "game_library.db"
}
