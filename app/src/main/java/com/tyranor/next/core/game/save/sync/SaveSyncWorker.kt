package com.tyranor.next.core.game.save.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tyranor.next.core.game.launch.EngineLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class SaveSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val fallbackStateKey: String
        get() = inputData.getString(INPUT_FALLBACK_CHAIN) ?: id.toString()

    override suspend fun doWork(): Result {
        val pull = inputData.getBoolean(INPUT_PULL, false)
        val fallback = inputData.getBoolean(INPUT_FALLBACK, false)
        val stateStore = fallbackStates(applicationContext)
        var excludedUris: Set<String> = emptySet()
        var exclusionStateFailure = false
        val report = try {
            if (!WebDavSettingsStore.isEnabled(applicationContext)) {
                removeFallbackState(stateStore, fallbackStateKey, id.toString())
                return Result.success()
            }
            if (fallback && inputData.getString(INPUT_FALLBACK_CHAIN) != null &&
                stateStore.isSuperseded(fallbackStateKey, id.toString())) {
                return Result.success()
            }
            if (SaveSyncWorkPolicy.fallbackAttemptsExhausted(fallback, runAttemptCount)) {
                // Persisted sessions remain available to the next foreground or manual schedule.
                removeFallbackState(stateStore, fallbackStateKey, id.toString())
                // A terminal failed predecessor would also fail an already queued successor.
                return Result.success()
            }
            if (fallback) {
                val persisted = loadFallbackState(stateStore, fallbackStateKey)
                if (persisted == null) {
                    exclusionStateFailure = true
                } else {
                    excludedUris = persisted
                }
            }
            if (exclusionStateFailure) {
                SaveSyncReport()
            } else {
                val config = WebDavSettingsStore.load(applicationContext)
                if (config.active && config.mode.uploads && SaveSyncRuntime.hasPending(applicationContext)) {
                    EngineLauncher.flushPendingSaveSync(applicationContext)
                }
                var flushReport = SaveSyncRuntime.flushPending(applicationContext, excludedUris = excludedUris)
                val mergedExclusions = excludedUris + SaveSyncWorkPolicy.storageFullUris(flushReport)
                if (fallback && mergedExclusions != excludedUris) {
                    exclusionStateFailure = !saveFallbackState(
                        stateStore,
                        fallbackStateKey,
                        mergedExclusions,
                        sharedChain = inputData.getString(INPUT_FALLBACK_CHAIN) != null,
                    )
                }
                excludedUris = mergedExclusions
                if (!exclusionStateFailure && pull && config.active && config.mode.pulls) {
                    flushReport += SaveSyncService(applicationContext).syncAll(
                        SaveSyncDirection.PULL,
                        excludedUris = excludedUris,
                    )
                }
                flushReport
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = SaveSyncService.errorCode(failure)
            SaveSyncReport(failures = listOf(SaveSyncFailure("", error)))
        }
        return complete(report, pull, fallback, excludedUris, exclusionStateFailure)
    }

    private fun complete(
        report: SaveSyncReport,
        pull: Boolean,
        fallback: Boolean,
        excludedUris: Set<String>,
        exclusionStateFailure: Boolean,
    ): Result {
        val stateStore = fallbackStates(applicationContext)
        val workerId = fallbackStateKey
        if (!WebDavSettingsStore.isEnabled(applicationContext) || SaveSyncWorkPolicy.isDisabled(report)) {
            removeFallbackState(stateStore, workerId, id.toString())
            return Result.success()
        }
        if (exclusionStateFailure) return failForFallbackState(report, stateStore, workerId)
        val outcome = SaveSyncWorkPolicy.outcome(report, fallback)
        val mergedExclusions = excludedUris + SaveSyncWorkPolicy.storageFullUris(report)
        if (fallback) {
            try {
                stateStore.complete(workerId, id.toString(), outcome == SaveSyncWorkOutcome.RETRY,
                    mergedExclusions, sharedChain = inputData.getString(INPUT_FALLBACK_CHAIN) != null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Fallback outcome persistence failed", failure)
                return failForFallbackState(report, stateStore, workerId)
            }
        } else {
            if (SaveSyncWorkPolicy.shouldScheduleFallback(report)) {
                try {
                    enqueue(
                        applicationContext,
                        pull = pull,
                        delayed = true,
                        initialExcludedUris = mergedExclusions,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w(TAG, "Fallback work scheduling failed", failure)
                    return failForFallbackState(report, stateStore, workerId)
                }
            }
            removeFallbackState(stateStore, workerId, id.toString())
        }
        SaveSyncRuntime.recordReport(report)
        return when (outcome) {
            SaveSyncWorkOutcome.SUCCESS -> Result.success()
            SaveSyncWorkOutcome.FAILURE -> if (fallback) Result.success() else Result.failure()
            SaveSyncWorkOutcome.RETRY -> if (WebDavSettingsStore.isEnabled(applicationContext)) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }

    private fun failForFallbackState(
        report: SaveSyncReport,
        stateStore: SaveSyncFallbackStateStore,
        workerId: String,
    ): Result {
        removeFallbackState(stateStore, workerId, id.toString())
        val failed = report + SaveSyncReport(
            failures = listOf(SaveSyncFailure("", SaveSyncError.LOCAL_IO)),
        )
        SaveSyncRuntime.recordReport(failed)
        return if (inputData.getBoolean(INPUT_FALLBACK, false)) Result.success() else Result.failure()
    }

    companion object {
        private const val TAG = "SaveSyncWorker"
        internal const val INPUT_PULL = "pull"
        internal const val INPUT_FALLBACK = "fallback"
        internal const val INPUT_FALLBACK_CHAIN = "fallback_chain"
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
            try {
                fallbackStates(context).removeAll()
            } catch (failure: Exception) {
                Log.w(TAG, "Fallback state cleanup failed", failure)
            }
        }

        fun enqueue(
            context: Context,
            pull: Boolean,
            delayed: Boolean = false,
            initialExcludedUris: Set<String> = emptySet(),
        ) {
            if (!WebDavSettingsStore.isEnabled(context)) return
            val excludedUris = SaveSyncFallbackStateStore.normalize(initialExcludedUris)
            val workName = when {
                delayed && pull -> WORK_NAME_OPEN_FALLBACK
                delayed -> WORK_NAME_EXIT_FALLBACK
                pull -> WORK_NAME_OPEN_NOW
                else -> WORK_NAME_EXIT_NOW
            }
            val input = Data.Builder()
                .putBoolean(INPUT_PULL, pull)
                .putBoolean(INPUT_FALLBACK, delayed)
            if (delayed) input.putString(INPUT_FALLBACK_CHAIN, workName)
            val request = OneTimeWorkRequestBuilder<SaveSyncWorker>()
                .setInputData(input.build())
                .setConstraints(automaticWorkConstraints())
                .setInitialDelay(if (delayed) 30 else 1, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
            val manager = WorkManager.getInstance(context.applicationContext)
            if (delayed) {
                fallbackStates(context).schedule(workName, request.id.toString(), excludedUris) {
                    // KEEP can silently discard this UUID at the completion boundary. Append
                    // without canceling a running transfer; superseded queued workers skip IO.
                    manager.enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                }
            } else {
                manager.enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }

        internal fun automaticWorkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(SaveSyncWorkPolicy.AUTOMATIC_NETWORK_TYPE)
            .build()

        private fun fallbackStates(context: Context) = SaveSyncFallbackStateStore(
            File(context.noBackupFilesDir, "save_sync/workers"),
        )

        private fun loadFallbackState(
            stateStore: SaveSyncFallbackStateStore,
            workerId: String,
        ): Set<String>? = try {
            stateStore.pruneExpired()
            stateStore.load(workerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Fallback state could not be loaded", failure)
            null
        }

        private fun saveFallbackState(
            stateStore: SaveSyncFallbackStateStore,
            workerId: String,
            excludedUris: Set<String>,
            sharedChain: Boolean,
        ): Boolean = try {
            stateStore.merge(workerId, excludedUris, sharedChain)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Fallback state could not be saved", failure)
            false
        }

        private fun removeFallbackState(
            stateStore: SaveSyncFallbackStateStore,
            workerId: String,
            owner: String,
        ) {
            try {
                stateStore.remove(workerId, owner)
            } catch (failure: Exception) {
                Log.w(TAG, "Fallback state could not be removed", failure)
            }
        }
    }
}

internal enum class SaveSyncWorkOutcome { SUCCESS, FAILURE, RETRY }

internal object SaveSyncWorkPolicy {
    /**
     * Every automatic worker may flush a pending upload even when pulling, so none should spend metered data.
     * Five zero-based fallback attempts are allowed; exhaustion completes successfully so a queued
     * successor remains runnable, while persisted sessions wait for the next scheduling event.
     */
    internal val AUTOMATIC_NETWORK_TYPE = NetworkType.UNMETERED
    internal const val MAX_FALLBACK_RUN_ATTEMPT_COUNT = 5

    private val retryable = setOf(SaveSyncError.NETWORK, SaveSyncError.SERVER, SaveSyncError.LOCAL_IO,
        SaveSyncError.LOCAL_STATE_CHANGED)

    fun fallbackAttemptsExhausted(fallback: Boolean, runAttemptCount: Int): Boolean =
        fallback && runAttemptCount >= MAX_FALLBACK_RUN_ATTEMPT_COUNT

    fun isDisabled(report: SaveSyncReport): Boolean =
        report.failures.any { it.error == SaveSyncError.DISABLED }

    fun storageFullUris(report: SaveSyncReport): Set<String> = report.failures
        .asSequence()
        .filter { it.error == SaveSyncError.STORAGE_FULL }
        .map { it.gameUri }
        .filter { it.isNotBlank() }
        .toSet()

    // One game may be out of space while another still has a cleanup round to finish; only skip
    // the storage-full game, not the whole aggregate report.
    private fun cleanupCanRetry(report: SaveSyncReport): Boolean {
        val pending = report.cleanupPendingGames.filter { it.isNotBlank() }
        if (pending.isEmpty()) return report.cleanupPending > 0
        val storageFull = storageFullUris(report)
        return pending.any { it !in storageFull }
    }

    fun shouldScheduleFallback(report: SaveSyncReport): Boolean =
        !isDisabled(report) && (report.busy > 0 || cleanupCanRetry(report) || report.failures.any { it.error in retryable })

    fun outcome(report: SaveSyncReport, fallback: Boolean): SaveSyncWorkOutcome {
        if (isDisabled(report)) return SaveSyncWorkOutcome.SUCCESS
        val retryableFailure = report.failures.any { it.error in retryable }
        if (fallback && (report.busy > 0 || cleanupCanRetry(report) || retryableFailure)) return SaveSyncWorkOutcome.RETRY
        if (report.failures.any { it.error !in retryable }) return SaveSyncWorkOutcome.FAILURE
        if (report.busy == 0 && !retryableFailure) return SaveSyncWorkOutcome.SUCCESS
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
