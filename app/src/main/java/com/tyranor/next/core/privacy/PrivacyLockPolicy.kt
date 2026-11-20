package com.tyranor.next.core.privacy

/**
 * 应用锁解锁延迟档位（纯 JVM，便于单测）：启动器界面离开前台（退后台 / 熄屏）超过所选时长后，
 * 回前台需要先解锁。存储档位与 `AppSettingsStore.KEY_PRIVACY_APP_LOCK_TIMEOUT` 的 Int 档位对齐。
 */
enum class PrivacyLockTimeout(
    val storageIndex: Int,
    val timeoutMillis: Long,
) {
    /** 立即：每次回到启动器界面都要解锁。 */
    IMMEDIATE(0, 0L),

    /** 离开 1 分钟内回来免解锁。 */
    ONE_MINUTE(1, 60_000L),

    /** 离开 5 分钟内回来免解锁。 */
    FIVE_MINUTES(2, 5 * 60_000L),

    /** 离开 15 分钟内回来免解锁。 */
    FIFTEEN_MINUTES(3, 15 * 60_000L),
    ;

    companion object {
        /** 下拉选项顺序（与 [storageIndex] 一致）。 */
        val ordered: List<PrivacyLockTimeout> = entries.toList()

        /** 按存储档位取枚举；越界 / 非法档位一律回退立即档（与存储层的钳制互为兜底）。 */
        fun fromStorageIndex(index: Int): PrivacyLockTimeout =
            entries.firstOrNull { it.storageIndex == index } ?: IMMEDIATE
    }
}

/**
 * 判定离开时长是否需要解锁（纯函数，便于单测）。
 * [elapsedMillis] 为 `SystemClock.elapsedRealtime()` 差值；恰好等于延迟档也视为需要解锁
 * （边界取严），负值（时钟异常）同样按需要解锁处理。
 */
fun shouldRequireUnlock(elapsedMillis: Long, timeout: PrivacyLockTimeout): Boolean =
    elapsedMillis < 0L || elapsedMillis >= timeout.timeoutMillis
