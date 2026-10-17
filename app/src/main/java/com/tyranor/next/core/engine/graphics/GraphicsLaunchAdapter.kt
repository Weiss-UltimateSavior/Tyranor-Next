package com.tyranor.next.core.engine.graphics

import android.content.Intent

/** Transport-only adapter, shared by built-in and external launchers. It never selects another family's defaults. */
internal fun Intent.putDisabledGraphicsRequest(onlyMissing: Boolean = false) {
    putGraphicsExtras(if (onlyMissing) GraphicsLaunchPolicy.missingDisabledExtras(extras?.keySet().orEmpty())
        else GraphicsLaunchPolicy.disabledExtras())
}

internal fun Intent.putGraphicsExtras(extras: Map<String, Any>) {
    for ((key, value) in extras) {
        when (value) {
            is Int -> putExtra(key, value)
            is Boolean -> putExtra(key, value)
            is String -> putExtra(key, value)
            else -> error("Unsupported graphics extra type: ${value::class.java.name}")
        }
    }
}
