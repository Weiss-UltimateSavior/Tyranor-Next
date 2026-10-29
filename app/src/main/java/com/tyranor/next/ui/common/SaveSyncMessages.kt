package com.tyranor.next.ui.common

import android.content.Context
import com.tyranor.next.R
import com.tyranor.next.core.game.save.sync.SaveSyncError
import com.tyranor.next.core.game.save.sync.SaveSyncException
import com.tyranor.next.core.game.save.sync.SaveSyncReport
import com.tyranor.next.core.game.save.sync.WebDavSettingsStore
import com.tyranor.next.core.i18n.AppLocaleController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun shouldShowSaveSyncFeedback(context: Context): Boolean = withContext(Dispatchers.IO) {
    try {
        if (!WebDavSettingsStore.isEnabled(context)) return@withContext false
        val config = WebDavSettingsStore.load(context)
        config.active && config.showSyncFeedback
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { false }
}

internal fun SaveSyncError.syncErrorResource(): Int = when (this) {
    SaveSyncError.NOT_CONFIGURED -> R.string.webdav_error_not_configured
    SaveSyncError.DISABLED -> R.string.webdav_error_disabled
    SaveSyncError.INVALID_CONFIG -> R.string.webdav_error_invalid_config
    SaveSyncError.CREDENTIALS_UNAVAILABLE -> R.string.webdav_error_credentials
    SaveSyncError.AUTHENTICATION -> R.string.webdav_error_authentication
    SaveSyncError.NETWORK -> R.string.webdav_error_network
    SaveSyncError.SERVER -> R.string.webdav_error_server
    SaveSyncError.INVALID_RESPONSE -> R.string.webdav_error_response
    SaveSyncError.CORRUPT_ARCHIVE -> R.string.webdav_error_archive
    SaveSyncError.LOCAL_IO -> R.string.webdav_error_local_io
    SaveSyncError.STORAGE_FULL -> R.string.webdav_error_storage_full
    SaveSyncError.SAVE_UNAVAILABLE -> R.string.webdav_error_no_save
    SaveSyncError.SAVE_LOCATION_CHANGED -> R.string.webdav_error_save_location_changed
    SaveSyncError.GAME_RUNNING -> R.string.webdav_error_game_running
    SaveSyncError.IDENTITY_CONFLICT -> R.string.webdav_error_identity_conflict
    SaveSyncError.SELECTION_INCOMPLETE -> R.string.save_error_selection_incomplete
    SaveSyncError.CLOUD_REPAIR_REQUIRED -> R.string.webdav_error_cloud_repair
    SaveSyncError.LOCAL_STATE_CHANGED -> R.string.webdav_error_local_state_changed
}

internal fun SaveSyncError.toSaveSyncMessage(context: Context): String =
    AppLocaleController.wrap(context).getString(syncErrorResource())

/** Preserve a typed sync failure; unrelated cleanup failures use the existing LOCAL_IO code. */
internal fun Exception.toSaveSyncCleanupException(): SaveSyncException {
    if (this is CancellationException) throw this
    return this as? SaveSyncException ?: SaveSyncException(SaveSyncError.LOCAL_IO, cause = this)
}

internal fun SaveSyncReport.syncMessageResource(): Int {
    val transferred = uploaded + downloaded > 0
    return when {
        failures.isNotEmpty() -> if (transferred) {
            R.string.webdav_sync_partial_failure
        } else {
            R.string.webdav_sync_failure
        }
        cleanupPending > 0 -> if (transferred) {
            R.string.webdav_sync_partial_cleanup_pending
        } else {
            R.string.webdav_sync_cleanup_pending
        }
        transferred && busy == 0 -> R.string.webdav_sync_success
        transferred -> R.string.webdav_sync_busy_skipped
        busy > 0 -> R.string.webdav_error_game_running
        else -> R.string.webdav_sync_unchanged
    }
}

internal fun SaveSyncReport.toSaveSyncMessage(context: Context): String {
    val localized = AppLocaleController.wrap(context)
    val template = syncMessageResource()
    val firstFailure = failures.firstOrNull() ?: return localized.getString(template)
    return localized.getString(template, localized.getString(firstFailure.error.syncErrorResource()))
}
