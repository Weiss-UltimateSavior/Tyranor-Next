package com.tyranor.next.core.updater

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController

object UpdateNotificationManager {
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 0x54594E
    private const val PREFS_NAME = "background_update"
    private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    private const val KEY_PERMISSION_REQUESTED = "notification_permission_requested"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val localizedContext = AppLocaleController.wrap(context)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedContext.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = localizedContext.getString(R.string.update_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 同一版本只发一次；没有通知权限时不记录版本，授权后仍可在下次后台检查时提醒。 */
    fun notifyIfNeeded(context: Context, update: UpdateCheckResult.UpdateAvailable): Boolean {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) return false

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVersion = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)
        if (!shouldNotifyUpdate(update.latestVersion, lastVersion)) return false

        val releaseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            update.latestVersion.hashCode(),
            releaseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val localizedContext = AppLocaleController.wrap(appContext)
        val fallbackText = localizedContext.getString(R.string.update_notification_fallback_text)
        val releaseName = update.releaseName.ifBlank { fallbackText }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(localizedContext.getString(R.string.update_notification_title, update.latestVersion))
            .setContentText(releaseName)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    localizedContext.getString(R.string.update_notification_big_text, releaseName),
                ),
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        return runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
            prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, update.latestVersion).apply()
            true
        }.getOrDefault(false)
    }

    fun shouldRequestPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PERMISSION_REQUESTED, false)
    }

    fun markPermissionRequested(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PERMISSION_REQUESTED, true)
            .apply()
    }

    private fun canPostNotifications(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

internal fun shouldNotifyUpdate(latestVersion: String, lastNotifiedVersion: String?): Boolean =
    latestVersion.isNotBlank() && latestVersion != lastNotifiedVersion
