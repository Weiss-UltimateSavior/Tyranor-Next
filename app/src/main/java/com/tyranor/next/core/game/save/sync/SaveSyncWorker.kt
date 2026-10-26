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
        val pull = inputData.getBoolean(INPUT_PULL, false)
        val fallback = inputData.getBoolean(INPUT_FALLBACK, false)
        return try {
            if (!WebDavSettingsStore.isEnabled(applicationContext)) return Result.success()
            if (SaveSyncWorkPolicy.fallbackAttemptsExhausted(fallback, runAttemptCount)) {
                // Persisted sessions remain available to the next foreground or manual schedule.
                return Result.failure()
            }
            val config = WebDavSettingsStore.load(applicationContext)
            if (config.active && config.mode.uploads && SaveSyncRuntime.hasPending(applicationContext)) {
                EngineLauncher.flushPendingSaveSync(applicationContext)
            }
            var report = SaveSyncRuntime.flushPending(applicationContext)
            if (pull && config.active && config.mode.pulls) {
                report += SaveSyncService(applicationContext).syncAll(SaveSyncDirection.PULL)
            }
            complete(report, pull, fallback)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!WebDavSettingsStore.isEnabled(applicationContext)) return Result.success()
            val error = SaveSyncService.errorCode(failure)
            if (error == SaveSyncError.DISABLED) return Result.success()
            complete(SaveSyncReport(failures = listOf(SaveSyncFailure("", error))), pull, fallback)
        }
    }

    private fun complete(report: SaveSyncReport, pull: Boolean, fallback: Boolean): Result {
        if (!WebDavSettingsStore.isEnabled(applicationContext) || SaveSyncWorkPolicy.isDisabled(report)) {
            return Result.success()
        }
        if (!fallback && SaveSyncWorkPolicy.shouldScheduleFallback(report)) {
            enqueue(applicationContext, pull = pull, delayed = true)
        }
        SaveSyncRuntime.recordReport(report)
        return when (SaveSyncWorkPolicy.outcome(report, fallback)) {
            SaveSyncWorkOutcome.SUCCESS -> Result.success()
            SaveSyncWorkOutcome.FAILURE -> Result.failure()
            SaveSyncWorkOutcome.RETRY -> if (WebDavSettingsStore.isEnabled(applicationContext)) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }

    companion object {
        internal const val INPUT_PULL = "pull"
        internal const val INPUT_FALLBACK = "fallback"
        internal const val WORK_NAME_OPEN_NOW = "webdav_save_open_now"
        internal const val WORK_NAME_EXIT_NOW = "webdav_save_exit_now"
        internal const val WORK_NAME_OPEN_FALLBACK = "webdav_save_open_fallback"
        internal const val WORK_NAME_EXIT_FALLBACK = "webdav_save_exit_fallback"
        private const val LEGACY_WORK_NAME_OPEN = "webdav_save_open"
        private const val LEGACY_WORK_NAME_EXIT = "webdav_save_exit"
        private const val LEGACY_WORK_NAME_FALLBACK = "webdav_save_fallback"

        fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context.applicationContext)
            // Enqueue leaves legacy chains alone; disabling the feature cancels both generations.
            manager.cancelUniqueWork(LEGACY_WORK_NAME_OPEN)
            manager.cancelUniqueWork(LEGACY_WORK_NAME_EXIT)
            manager.cancelUniqueWork(LEGACY_WORK_NAME_FALLBACK)
            manager.cancelUniqueWork(WORK_NAME_OPEN_NOW)
            manager.cancelUniqueWork(WORK_NAME_EXIT_NOW)
            manager.cancelUniqueWork(WORK_NAME_OPEN_FALLBACK)
            manager.cancelUniqueWork(WORK_NAME_EXIT_FALLBACK)
        }

        fun enqueue(context: Context, pull: Boolean, delayed: Boolean = false) {
            if (!WebDavSettingsStore.isEnabled(context)) return
            val request = OneTimeWorkRequestBuilder<SaveSyncWorker>()
                .setInputData(workDataOf(INPUT_PULL to pull, INPUT_FALLBACK to delayed))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(SaveSyncWorkPolicy.AUTOMATIC_NETWORK_TYPE)
                        .build(),
                )
                .setInitialDelay(if (delayed) 30 else 1, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                when {
                    delayed && pull -> WORK_NAME_OPEN_FALLBACK
                    delayed -> WORK_NAME_EXIT_FALLBACK
                    pull -> WORK_NAME_OPEN_NOW
                    else -> WORK_NAME_EXIT_NOW
                },
                // Immediate chains stay short; only the separate delayed chain may remain in retry.
                if (delayed) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}

internal enum class SaveSyncWorkOutcome { SUCCESS, FAILURE, RETRY }

internal object SaveSyncWorkPolicy {
    /**
     * Every automatic worker may flush a pending upload even when pulling, so none should spend metered data.
     * WorkManager's zero-based runAttemptCount therefore permits five fallback executions before failing.
     */
    internal val AUTOMATIC_NETWORK_TYPE = NetworkType.UNMETERED
    internal const val MAX_FALLBACK_RUN_ATTEMPT_COUNT = 5

    private val retryable = setOf(SaveSyncError.NETWORK, SaveSyncError.SERVER, SaveSyncError.LOCAL_IO)

    fun fallbackAttemptsExhausted(fallback: Boolean, runAttemptCount: Int): Boolean =
        fallback && runAttemptCount >= MAX_FALLBACK_RUN_ATTEMPT_COUNT

    fun isDisabled(report: SaveSyncReport): Boolean =
        report.failures.any { it.error == SaveSyncError.DISABLED }

    fun shouldScheduleFallback(report: SaveSyncReport): Boolean =
        !isDisabled(report) && (report.busy > 0 || report.failures.any { it.error in retryable })

    fun outcome(report: SaveSyncReport, fallback: Boolean): SaveSyncWorkOutcome {
        if (isDisabled(report)) return SaveSyncWorkOutcome.SUCCESS
        if (report.failures.any { it.error !in retryable }) return SaveSyncWorkOutcome.FAILURE
        val retryableFailure = report.failures.any { it.error in retryable }
        if (report.busy == 0 && !retryableFailure) return SaveSyncWorkOutcome.SUCCESS
        if (fallback) return SaveSyncWorkOutcome.RETRY
        return if (report.failures.isEmpty()) SaveSyncWorkOutcome.SUCCESS else SaveSyncWorkOutcome.FAILURE
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
