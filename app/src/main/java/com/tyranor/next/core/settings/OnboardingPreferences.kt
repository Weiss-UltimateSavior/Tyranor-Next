package com.tyranor.next.core.settings

import android.content.SharedPreferences

enum class OnboardingWriteResult { SAVED, STORAGE_UNAVAILABLE }

/** 首次判定失败后保留原始快照，重试不能把初始化期间生成的设置当作旧安装证据。 */
internal class OnboardingPreferences(private val settings: SharedPreferences) {
    private var pendingInitialValue: Boolean? = null

    @Synchronized
    fun initialize(hasExistingData: () -> Boolean): OnboardingWriteResult {
        if (pendingInitialValue == null && settings.contains(AppSettingsStore.KEY_ONBOARDING_COMPLETED)) {
            return OnboardingWriteResult.SAVED
        }
        val completed = pendingInitialValue ?: hasExistingData().also { pendingInitialValue = it }
        if (!settings.edit().putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, completed).commit()) {
            // commit 失败也已改写内存；移除未落盘标记，且保留 pending 快照以便重试。
            settings.edit().remove(AppSettingsStore.KEY_ONBOARDING_COMPLETED).apply()
            return OnboardingWriteResult.STORAGE_UNAVAILABLE
        }
        pendingInitialValue = null
        return OnboardingWriteResult.SAVED
    }

    @Synchronized
    fun isCompleted(): Boolean = pendingInitialValue == null &&
        settings.getBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false)

    @Synchronized
    fun complete(): OnboardingWriteResult {
        if (isCompleted()) return OnboardingWriteResult.SAVED
        if (!settings.edit().putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, true).commit()) {
            // 重建 Activity/onStop 不能把失败写入留下的内存 true 当成完成。
            settings.edit().putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false).apply()
            return OnboardingWriteResult.STORAGE_UNAVAILABLE
        }
        return OnboardingWriteResult.SAVED
    }
}
