package com.core.web

/** Shared script identity and buffering policy for the two RPG Maker web hosts. */
internal object DkToolsCompat {
    const val SCRIPT_PATH = "js/plugins/dktools.js"
    const val ASSET_PATH = "rpgmaker/__dktools_compat.js"
    const val MAX_SCRIPT_BYTES = 8L * 1024 * 1024
}
