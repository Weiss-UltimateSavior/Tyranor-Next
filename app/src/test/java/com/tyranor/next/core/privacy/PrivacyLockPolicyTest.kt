package com.tyranor.next.core.privacy

import com.tyranor.next.core.settings.AppSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用锁解锁延迟档位与判定钉住：档位与存储索引错位会让老用户的延迟档静默变成另一档；
 * 边界语义（恰好等于延迟 = 需要解锁、负值 = 需要解锁）取严不放宽。
 */
class PrivacyLockPolicyTest {

    @Test
    fun `storageIndex 对齐枚举顺序`() {
        PrivacyLockTimeout.ordered.forEachIndexed { index, timeout ->
            assertEquals("档位 $index 的 storageIndex 必须与下拉顺序一致", index, timeout.storageIndex)
        }
        // 存储层的档位总数与枚举保持同步（AppSettingsStore 只做范围钳制）
        assertEquals(PrivacyLockTimeout.entries.size, AppSettingsStore.PRIVACY_APP_LOCK_TIMEOUT_COUNT)
    }

    @Test
    fun `fromStorageIndex 已知档位原样取回`() {
        assertEquals(PrivacyLockTimeout.IMMEDIATE, PrivacyLockTimeout.fromStorageIndex(0))
        assertEquals(PrivacyLockTimeout.ONE_MINUTE, PrivacyLockTimeout.fromStorageIndex(1))
        assertEquals(PrivacyLockTimeout.FIVE_MINUTES, PrivacyLockTimeout.fromStorageIndex(2))
        assertEquals(PrivacyLockTimeout.FIFTEEN_MINUTES, PrivacyLockTimeout.fromStorageIndex(3))
    }

    @Test
    fun `fromStorageIndex 越界档位回退立即`() {
        assertEquals(PrivacyLockTimeout.IMMEDIATE, PrivacyLockTimeout.fromStorageIndex(-1))
        assertEquals(PrivacyLockTimeout.IMMEDIATE, PrivacyLockTimeout.fromStorageIndex(4))
    }

    @Test
    fun `延迟档位时长换算`() {
        assertEquals(0L, PrivacyLockTimeout.IMMEDIATE.timeoutMillis)
        assertEquals(60_000L, PrivacyLockTimeout.ONE_MINUTE.timeoutMillis)
        assertEquals(5 * 60_000L, PrivacyLockTimeout.FIVE_MINUTES.timeoutMillis)
        assertEquals(15 * 60_000L, PrivacyLockTimeout.FIFTEEN_MINUTES.timeoutMillis)
    }

    @Test
    fun `立即档任何离开时长都要解锁`() {
        assertTrue(shouldRequireUnlock(0L, PrivacyLockTimeout.IMMEDIATE))
        assertTrue(shouldRequireUnlock(1L, PrivacyLockTimeout.IMMEDIATE))
    }

    @Test
    fun `恰好等于延迟档视为需要解锁（边界取严）`() {
        assertTrue(shouldRequireUnlock(60_000L, PrivacyLockTimeout.ONE_MINUTE))
        assertFalse(shouldRequireUnlock(59_999L, PrivacyLockTimeout.ONE_MINUTE))
        assertTrue(shouldRequireUnlock(5 * 60_000L, PrivacyLockTimeout.FIVE_MINUTES))
        assertFalse(shouldRequireUnlock(5 * 60_000L - 1, PrivacyLockTimeout.FIVE_MINUTES))
        assertTrue(shouldRequireUnlock(15 * 60_000L, PrivacyLockTimeout.FIFTEEN_MINUTES))
        assertFalse(shouldRequireUnlock(15 * 60_000L - 1, PrivacyLockTimeout.FIFTEEN_MINUTES))
    }

    @Test
    fun `延迟档位归一：越界值回退立即档而不是钳到最宽松档`() {
        // 存量数据 / 备份恢复可能带来越界索引：钳到 (count-1) 会静默放宽到 15 分钟档
        assertEquals(0, AppSettingsStore.normalizePrivacyLockTimeoutIndex(4))
        assertEquals(0, AppSettingsStore.normalizePrivacyLockTimeoutIndex(99))
        assertEquals(0, AppSettingsStore.normalizePrivacyLockTimeoutIndex(-1))
        // 合法档位保持原值
        for (index in 0 until AppSettingsStore.PRIVACY_APP_LOCK_TIMEOUT_COUNT) {
            assertEquals(index, AppSettingsStore.normalizePrivacyLockTimeoutIndex(index))
        }
    }

    @Test
    fun `负值时长按需要解锁处理（时钟异常不放宽）`() {
        assertTrue(shouldRequireUnlock(-1L, PrivacyLockTimeout.FIFTEEN_MINUTES))
    }
}
