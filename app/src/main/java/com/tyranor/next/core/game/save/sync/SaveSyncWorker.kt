package com.tyranor.next.core.game.save.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tyranor.next.core.game.launch.EngineLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SaveSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            if (!WebDavSettingsStore.isEnabled(applicationContext)) return Result.success()
            val config = WebDavSettingsStore.load(applicationContext)
            if (config.active && config.mode.uploads && SaveSyncRuntime.hasPending(applicationContext)) {
                EngineLauncher.flushPendingSaveSync(applicationContext)
            }
            var report = SaveSyncRuntime.flushPending(applicationContext)
            if (inputData.getBoolean("pull", false) && config.active && config.mode.pulls) {
                report += SaveSyncService(applicationContext).syncAll(SaveSyncDirection.PULL)
            }
            SaveSyncRuntime.recordReport(report)
            if (report.busy > 0 || report.failures.any { it.error in RETRYABLE }) Result.retry()
            else if (report.failures.isNotEmpty()) Result.failure() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = SaveSyncService.errorCode(failure)
            SaveSyncRuntime.recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure("", error))))
            if (error in RETRYABLE) Result.retry() else Result.failure()
        }
    }

    companion object {
        private val RETRYABLE = setOf(SaveSyncError.NETWORK, SaveSyncError.SERVER, SaveSyncError.LOCAL_IO)
        fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork("webdav_save_open")
            manager.cancelUniqueWork("webdav_save_exit")
        }

        fun enqueue(context: Context, pull: Boolean, delayed: Boolean = false) {
            if (!WebDavSettingsStore.isEnabled(context)) return
            val request = OneTimeWorkRequestBuilder<SaveSyncWorker>()
                .setInputData(workDataOf("pull" to pull))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(if (delayed) 30 else 1, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                if (pull) "webdav_save_open" else "webdav_save_exit",
                // Serialize non-delayed transfers so concurrent work cannot race on remote state.
                if (delayed) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}

/** Explicit, non-exported callback wakes only the app's targeted pending-session queue. */
class SaveSyncExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!WebDavSettingsStore.isEnabled(context)) return@launch
                val config = WebDavSettingsStore.load(context)
                if (config.active && config.mode.uploads && SaveSyncRuntime.hasPending(context)) {
                    SaveSyncWorker.enqueue(context, pull = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SaveSyncRuntime.recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure("", SaveSyncService.errorCode(failure)))))
            } finally {
                pending.finish()
            }
        }
    }
}
