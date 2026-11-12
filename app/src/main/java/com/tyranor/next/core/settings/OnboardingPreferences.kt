package com.tyranor.next.core.settings

import android.content.SharedPreferences
import java.io.File
import java.nio.charset.StandardCharsets

enum class OnboardingWriteResult { SAVED, STORAGE_UNAVAILABLE }

/**
 * 首次判定失败后保留原始快照，重试不能把初始化期间生成的设置当作旧安装证据。
 *
 * [pendingStateFile] 不属于任何用于识别存量安装的 prefs；它只用于跨进程重启恢复一次
 * 未落盘的判定结果，提交成功后立即清除。
 */
internal class OnboardingPreferences(
    private val settings: SharedPreferences,
    private val pendingStateFile: File? = null,
) {
    private var pendingInitialValue: Boolean? = null

    @Synchronized
    fun initialize(hasExistingData: () -> Boolean): OnboardingWriteResult {
        if (pendingInitialValue == null && settings.contains(AppSettingsStore.KEY_ONBOARDING_COMPLETED)) {
            clearPendingInitialValue()
            return OnboardingWriteResult.SAVED
        }
        if (pendingInitialValue == null) {
            pendingInitialValue = readPendingInitialValue()
        }
        val completed = pendingInitialValue ?: hasExistingData().also { pendingInitialValue = it }
        if (!settings.edit().putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, completed).commit()) {
            // commit 失败也已改写内存；移除未落盘标记，且保留 pending 快照以便重试。
            settings.edit().remove(AppSettingsStore.KEY_ONBOARDING_COMPLETED).apply()
            persistPendingInitialValue(completed)
            return OnboardingWriteResult.STORAGE_UNAVAILABLE
        }
        pendingInitialValue = null
        clearPendingInitialValue()
        return OnboardingWriteResult.SAVED
    }

    @Synchronized
    fun isCompleted(): Boolean {
        if (settings.contains(AppSettingsStore.KEY_ONBOARDING_COMPLETED)) {
            pendingInitialValue = null
            clearPendingInitialValue()
            return settings.getBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false)
        }
        if (pendingInitialValue == null) {
            pendingInitialValue = readPendingInitialValue()
        }
        return pendingInitialValue ?: settings.getBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false)
    }

    @Synchronized
    fun complete(): OnboardingWriteResult {
        if (pendingInitialValue == null) {
            pendingInitialValue = readPendingInitialValue()
        }
        if (pendingInitialValue == null &&
            settings.getBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false)
        ) {
            return OnboardingWriteResult.SAVED
        }
        if (!settings.edit().putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, true).commit()) {
            // 重建 Activity/onStop 不能把失败写入留下的内存 true 当成完成。
            settings.edit().putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false).apply()
            return OnboardingWriteResult.STORAGE_UNAVAILABLE
        }
        pendingInitialValue = null
        clearPendingInitialValue()
        return OnboardingWriteResult.SAVED
    }

    private fun readPendingInitialValue(): Boolean? = pendingStateFile?.let { file ->
        runCatching {
            if (!file.isFile) return@runCatching null
            when (file.readText(StandardCharsets.UTF_8).trim()) {
                "1" -> true
                "0" -> false
                else -> null
            }
        }.getOrNull()
    }

    private fun persistPendingInitialValue(value: Boolean) {
        val file = pendingStateFile ?: return
        runCatching {
            val parent = file.parentFile ?: File(".")
            parent.mkdirs()
            val temporary = File.createTempFile("${file.name}.", ".tmp", parent)
            try {
                temporary.writeText(if (value) "1" else "0", StandardCharsets.UTF_8)
                if (!temporary.renameTo(file)) {
                    file.writeText(if (value) "1" else "0", StandardCharsets.UTF_8)
                }
            } finally {
                temporary.delete()
            }
        }
    }

    private fun clearPendingInitialValue() {
        pendingStateFile?.let { file -> runCatching { file.delete() } }
    }
}
