package com.core.rpgmaker

import java.io.File
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
 *
 * 四类记录缺一不可：
 *  - [record] 成功写入；
 *  - [record] 删除（文件本来就不存在时也要记：旧会话可能正准备创建它）；
 *  - [recordDirectory] 目录级清除（`Storage.clear()` 看不到「旧会话尚未落盘的键」，
 *    只能按目录记版本，否则旧会话的合并写会在 clear 之后把整批键写回来）；
 *  - [withPathLock] 复核与落盘/删除必须在同一把「按路径」锁里，否则两个 WebView 的
 *    桥线程交错时仍能删掉刚刚写入的新值。
 */
internal object RpgMakerStorageWriteLedger {

    /** 路径 → 最后写入/删除它的会话期号。 */
    private val lastWriter = ConcurrentHashMap<String, Long>()

    /** 目录 → 最后**清除**它的会话期号（`Storage.clear()` 的目录级墓碑）。 */
    private val lastClear = ConcurrentHashMap<String, Long>()

    /** 按路径分条的锁：同一路径的「复核 + 落盘/删除 + 记账」必须原子。 */
    @PublishedApi
    internal val pathLocks = Array(64) { Any() }

    /**
     * 记录一次成功的写入或删除（路径为规范化后的绝对路径）。
     *
     * 期号只增不减：`remove` 失败后重试、旧会话晚到的记账都不能把已记录的新会话期号
     * 写回更小的值，否则新值会被旧会话的刷盘重新覆盖。
     */
    fun record(path: String, session: Long) {
        lastWriter.merge(path, session) { current, incoming -> if (incoming > current) incoming else current }
    }

    /** 记录一次目录级清除：该目录下所有更旧会话的写入/删除都要让位。 */
    fun recordDirectory(dir: String, session: Long) {
        lastClear.merge(dir, session) { current, incoming -> if (incoming > current) incoming else current }
    }

    /** 该路径是否已被**更新**的会话写过或删过：旧会话据此跳过，避免用旧值覆盖新值。 */
    fun isSuperseded(path: String, session: Long): Boolean {
        val writer = lastWriter[path]
        if (writer != null && writer > session) return true
        if (lastClear.isEmpty()) return false
        // 目录条目很少（每个会话至多一次 clear），直接遍历比对前缀即可
        for ((dir, clearedBy) in lastClear) {
            if (clearedBy <= session) continue
            if (path == dir || path.startsWith(dir + File.separator)) return true
        }
        return false
    }

    /**
     * 在同一路径的锁内执行「版本复核 + 落盘/删除」。
     *
     * 两个会话的桥线程可能并发（旧 WebView 的退出刷盘与新会话的首次写入），
     * 只靠 `isSuperseded()` 的返回值无法阻止「复核通过 → 另一方写入 → 我方删除」。
     * 锁按路径哈希分 64 条：同路径串行，不同路径几乎不互相阻塞。
     */
    inline fun <T> withPathLock(path: String, block: () -> T): T {
        val lock = pathLocks[(path.hashCode() and 0x7fffffff) % pathLocks.size]
        return synchronized(lock) { block() }
    }

    /**
     * 丢弃比 [keepFrom] 更旧会话的记录。
     *
     * 只有「上一期」的刷盘可能还在飞（看门狗 2.5s 上限），更早的会话不可能还有待写数据，
     * 留着只会让表随会话数无界增长。
     */
    fun pruneBelow(keepFrom: Long) {
        lastWriter.entries.removeIf { it.value < keepFrom }
        lastClear.entries.removeIf { it.value < keepFrom }
    }

    /** 仅测试使用：清空账本（静态状态在进程内共享，用例之间必须重置）。 */
    fun reset() {
        lastWriter.clear()
        lastClear.clear()
    }
}
