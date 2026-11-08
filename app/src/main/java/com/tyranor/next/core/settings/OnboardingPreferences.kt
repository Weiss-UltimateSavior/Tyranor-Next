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
    private val ownsSettings: () -> Boolean = { true },
) {
    private var pendingInitialValue: Boolean? = null
    private var initialStatePersisted = false

    /** Existing install data, a saved marker, or a recovery snapshot makes later infrastructure writes safe. */
    @Synchronized
    fun hasPersistedInitialState(): Boolean = initialStatePersisted

    @Synchronized
    fun initialize(hasExistingData: () -> Boolean): OnboardingWriteResult {
        if (!ownsSettings()) {
            // SharedPreferences commits rewrite the process's whole map. A child may inspect durable
            // evidence for engine migration, but must never write its possibly stale app_settings map.
            val saved = if (settings.contains(AppSettingsStore.KEY_ONBOARDING_COMPLETED)) {
                settings.getBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false)
            } else {
                readPendingInitialValue()
            }
            val existing = saved == null && hasExistingData()
            pendingInitialValue = saved ?: existing
            initialStatePersisted = saved != null || existing
            return if (initialStatePersisted) OnboardingWriteResult.SAVED else OnboardingWriteResult.STORAGE_UNAVAILABLE
        }
        if (pendingInitialValue == null && settings.contains(AppSettingsStore.KEY_ONBOARDING_COMPLETED)) {
            initialStatePersisted = true
            clearPendingInitialValue()
            return OnboardingWriteResult.SAVED
        }
        if (pendingInitialValue == null) {
            pendingInitialValue = readPendingInitialValue()
            if (pendingInitialValue != null) initialStatePersisted = true
        }
        val completed = pendingInitialValue ?: hasExistingData().also { pendingInitialValue = it }
        // Existing data is already durable evidence; only a fresh-install decision needs a new record.
        if (completed) initialStatePersisted = true
        if (!settings.edit().putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, completed).commit()) {
            // A failed commit already changed memory. Keep that original marker there: removing it
            // with another apply could persist pending ordinary settings without their install decision.
            // pendingInitialValue prevents us from treating this in-memory marker as a saved commit.
            initialStatePersisted = persistPendingInitialValue(completed) || initialStatePersisted
            return OnboardingWriteResult.STORAGE_UNAVAILABLE
        }
        pendingInitialValue = null
        initialStatePersisted = true
        clearPendingInitialValue()
        return OnboardingWriteResult.SAVED
    }

    @Synchronized
    fun isCompleted(): Boolean {
        if (!ownsSettings()) {
            return if (settings.contains(AppSettingsStore.KEY_ONBOARDING_COMPLETED)) {
                settings.getBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false)
            } else {
                readPendingInitialValue() ?: pendingInitialValue ?: false
            }
        }
        if (pendingInitialValue == null && settings.contains(AppSettingsStore.KEY_ONBOARDING_COMPLETED)) {
            initialStatePersisted = true
            pendingInitialValue = null
            clearPendingInitialValue()
            return settings.getBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false)
        }
        if (pendingInitialValue == null) {
            pendingInitialValue = readPendingInitialValue()
            if (pendingInitialValue != null) initialStatePersisted = true
        }
        return pendingInitialValue ?: settings.getBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false)
    }

    @Synchronized
    fun complete(): OnboardingWriteResult {
        if (!ownsSettings()) return OnboardingWriteResult.STORAGE_UNAVAILABLE
        if (pendingInitialValue == null) {
            pendingInitialValue = readPendingInitialValue()
            if (pendingInitialValue != null) initialStatePersisted = true
        }
        if (pendingInitialValue == null &&
            settings.getBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, false)
        ) {
            initialStatePersisted = true
            return OnboardingWriteResult.SAVED
        }
        if (!settings.edit().putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, true).commit()) {
            // 重建 Activity/onStop 不能把失败写入留下的内存 true 当成完成。
            settings.edit().putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, pendingInitialValue ?: false).apply()
            return OnboardingWriteResult.STORAGE_UNAVAILABLE
        }
        pendingInitialValue = null
        initialStatePersisted = true
        clearPendingInitialValue()
        return OnboardingWriteResult.SAVED
    }

    /** Keeps the original install decision in the same transaction as any ordinary settings write. */
    @Synchronized
    fun updateSettings(update: SharedPreferences.Editor.() -> Unit) {
        if (!ownsSettings()) return
        val editor = settings.edit()
        if (!initialStatePersisted) {
            // A failed commit updates memory. If a later apply succeeds, it must not persist just
            // the user's setting and leave the fresh install without its authoritative false marker.
            pendingInitialValue?.let { editor.putBoolean(AppSettingsStore.KEY_ONBOARDING_COMPLETED, it) }
        }
        editor.update()
        editor.apply()
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

    private fun persistPendingInitialValue(value: Boolean): Boolean {
        val file = pendingStateFile ?: return false
        return runCatching {
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
            true
        }.getOrDefault(false)
    }

    private fun clearPendingInitialValue() {
        pendingStateFile?.let { file -> runCatching { file.delete() } }
    }
}
