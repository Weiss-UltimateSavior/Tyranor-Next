package com.tyranor.next.core.game.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

internal object AllFilesAccess {
    fun isMissing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    fun openSettings(
        context: Context,
        launch: (Intent) -> Unit = { intent ->
            context.applicationContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val appSettings = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        return tryLaunch(appSettings, launch) ||
            tryLaunch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), launch)
    }

    private fun tryLaunch(intent: Intent, launch: (Intent) -> Unit): Boolean = try {
        launch(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
