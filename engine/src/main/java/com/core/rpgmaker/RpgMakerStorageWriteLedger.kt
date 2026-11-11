package com.core.rpgmaker

import java.util.concurrent.ConcurrentHashMap

/**
 * 跨会话的「谁最后写过这个路径」账本。
 *
 * 场景：同一游戏快速重启时，旧会话在 `onDestroy` 才异步刷 localStorage，而新会话可能
 * 已经写过同一个 `.dat`——`dataDir` 是 `<gameDir>/AppData`，两个会话共用一份存储。
 * 旧刷盘若晚到，会把新值覆盖成旧值；[RpgMakerSessionOwnership] 的期号能挡住「旧任务
 * 杀进程」，挡不住文件写入。
 *
 * 解法不是让新会话等待（会阻塞主线程或渲染线程），而是让旧会话**不覆盖更新的一侧**：
 * 每次成功写入记录「路径 → 会话期号」，旧会话刷盘前查询，若该路径已被更高期号写过就跳过。
 * 新会话还没写过的键照常落盘，因此不会丢待写数据。
 */
internal object RpgMakerStorageWriteLedger {

    /** 路径 → 最后写入它的会话期号。 */
    private val lastWriter = ConcurrentHashMap<String, Long>()

    /** 记录一次成功写入（路径为规范化后的绝对路径）。 */
    fun record(path: String, session: Long) {
        lastWriter[path] = session
    }

    /** 该路径是否已被**更新**的会话写过：旧会话据此跳过，避免用旧值覆盖新值。 */
    fun isSuperseded(path: String, session: Long): Boolean {
        val writer = lastWriter[path] ?: return false
        return writer > session
    }

    /**
     * 丢弃比 [keepFrom] 更旧会话的记录。
     *
     * 只有「上一期」的刷盘可能还在飞（看门狗 1.2s 上限），更早的会话不可能还有待写数据，
     * 留着只会让表随会话数无界增长。
     */
    fun pruneBelow(keepFrom: Long) {
        lastWriter.entries.removeIf { it.value < keepFrom }
    }

    /** 仅测试使用：清空账本（静态状态在进程内共享，用例之间必须重置）。 */
    fun reset() {
        lastWriter.clear()
    }
}
