package com.tyranor.next.core.updater

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * 应用切入后台后执行的更新检查。
 *
 * WorkManager 让检查不依赖 Activity 生命周期，并在系统暂时没有网络时等待到网络恢复。
 */
class BackgroundUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return when (val update = GitHubUpdateChecker.check(applicationContext)) {
            is UpdateCheckResult.UpdateAvailable -> {
                val notified = UpdateNotificationManager.notifyIfNeeded(applicationContext, update.primary)
                Log.d(TAG, "Background update check found ${update.primary.latestVersion}; notified=$notified")
                Result.success()
            }

            is UpdateCheckResult.UpToDate -> {
                Log.d(TAG, "Background update check is up to date: ${update.currentVersion}")
                Result.success()
            }

            is UpdateCheckResult.Failed -> {
                // 保持静默；下一次进入后台时会再次尝试，避免无限重试 GitHub 限流等非瞬时错误。
                Log.w(TAG, "Background update check failed: ${update.message}")
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "BackgroundUpdate"
        private const val UNIQUE_WORK_NAME = "background_update_check"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackgroundUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
