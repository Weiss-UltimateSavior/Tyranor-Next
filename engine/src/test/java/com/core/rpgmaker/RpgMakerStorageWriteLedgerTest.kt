package com.core.rpgmaker

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：同一游戏快速重启时，旧会话的退出刷盘不得覆盖新会话已写入的值。
 *
 * 两个会话共用 `<gameDir>/AppData/Local Storage`，而旧 WebView 的刷盘是异步的
 * （`onDestroy` 里 `evaluateJavascript` 排队）。会话期号只挡「旧任务杀进程」，
 * 挡不住文件写入——所以用写入记录让旧会话让位。
 */
class RpgMakerStorageWriteLedgerTest {

    private val keyA = "/game/AppData/Local Storage/61.dat"
    private val keyB = "/game/AppData/Local Storage/62.dat"
    private val keyC = "/game/AppData/Local Storage/63.dat"

    @After
    fun tearDown() {
        RpgMakerStorageWriteLedger.reset()
    }

    @Test
    fun writeFromANewerSessionSupersedesTheOlderFlush() {
        val older = 1L
        val newer = 2L

        assertFalse("还没人写过时，旧会话可以照常落盘", RpgMakerStorageWriteLedger.isSuperseded(keyA, older))

        RpgMakerStorageWriteLedger.record(keyA, newer)
        assertTrue("新会话写过之后，旧会话必须让位", RpgMakerStorageWriteLedger.isSuperseded(keyA, older))
        assertFalse("新会话自己不算被覆盖", RpgMakerStorageWriteLedger.isSuperseded(keyA, newer))
    }

    @Test
    fun keysTheNewerSessionNeverTouchedStillFlush() {
        val older = 3L
        val newer = 4L
        RpgMakerStorageWriteLedger.record(keyA, newer)

        // 新会话没写过的键：旧会话仍要落盘，否则待写数据就丢了
        assertFalse(RpgMakerStorageWriteLedger.isSuperseded(keyB, older))
    }

    @Test
    fun pruningKeepsOnlyTheImmediatelyPreviousSession() {
        val oldest = 1L
        val previous = 2L
        val current = 3L
        RpgMakerStorageWriteLedger.record(keyA, oldest)
        RpgMakerStorageWriteLedger.record(keyB, previous)
        RpgMakerStorageWriteLedger.record(keyC, current)

        // beginSession 用 current - 1 作为下界：上一期的刷盘可能还在飞，必须保留
        RpgMakerStorageWriteLedger.pruneBelow(current - 1)
        assertFalse("更早会话的记录被清掉", RpgMakerStorageWriteLedger.isSuperseded(keyA, oldest - 1))
        assertTrue("上一期记录保留", RpgMakerStorageWriteLedger.isSuperseded(keyB, previous - 1))
        assertTrue("当前期记录保留", RpgMakerStorageWriteLedger.isSuperseded(keyC, current - 1))
    }

    @Test
    fun recordNeverDowngradesTheEpoch() {
        RpgMakerStorageWriteLedger.record(keyA, 5L)
        // 旧会话在删除失败/重试时晚到的记账：期号只增不减，不能被写回更小的值
        RpgMakerStorageWriteLedger.record(keyA, 3L)
        assertTrue("迟到的旧会话记账不能把记录改小", RpgMakerStorageWriteLedger.isSuperseded(keyA, 4L))
        assertFalse(RpgMakerStorageWriteLedger.isSuperseded(keyA, 5L))
    }

    @Test
    fun directoryClearTombstoneIsAlsoMonotonic() {
        // 账本里的路径来自 resolve()，分隔符随平台（Android 是 `/`，Windows 测试宿主是 `\`）
        val dir = listOf("game", "AppData", "Local Storage").joinToString(java.io.File.separator)
        val child = dir + java.io.File.separator + "61.dat"
        RpgMakerStorageWriteLedger.recordDirectory(dir, 5L)
        RpgMakerStorageWriteLedger.recordDirectory(dir, 2L)
        assertTrue(
            "目录级墓碑同样只增不减",
            RpgMakerStorageWriteLedger.isSuperseded(child, 4L),
        )
        assertFalse(
            "发起清除的会话自己不受影响",
            RpgMakerStorageWriteLedger.isSuperseded(child, 5L),
        )
    }

    @Test
    fun beginSessionPrunesTheLedger() {
        val ownership = RpgMakerSessionOwnership()
        val first = ownership.beginSession()
        RpgMakerStorageWriteLedger.record(keyA, first)

        val second = ownership.beginSession()
        RpgMakerStorageWriteLedger.record(keyB, second)

        // 第三期启动后，只保留第二期（上一期）
        ownership.beginSession()
        assertFalse("第一期已被清理", RpgMakerStorageWriteLedger.isSuperseded(keyA, first - 1))
        assertTrue("第二期仍保留", RpgMakerStorageWriteLedger.isSuperseded(keyB, second - 1))
    }
}
