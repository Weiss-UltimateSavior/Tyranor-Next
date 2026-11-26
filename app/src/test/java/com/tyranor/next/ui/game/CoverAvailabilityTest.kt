package com.tyranor.next.ui.game

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面可用标记的可观察语义：`isKnown` 在组合期读取的是快照状态，
 * 写入（markKnown / forget）必须能被快照观察者看到，否则 forget 后卡片不会立即重组。
 */
class CoverAvailabilityTest {

    @Test
    fun `读取已登记状态会登记到快照观察者`() {
        val uri = "content://cover/test-observable"
        CoverAvailability.markKnown(uri)
        val reads = mutableListOf<Any>()
        val known = Snapshot.observe(readObserver = { reads.add(it) }) {
            CoverAvailability.isKnown(uri)
        }
        assertTrue(known)
        assertTrue("isKnown 必须在快照观察者下读取到状态", reads.isNotEmpty())
    }

    @Test
    fun `forget 后状态为未登记`() {
        val uri = "content://cover/test-forget"
        CoverAvailability.markKnown(uri)
        assertTrue(CoverAvailability.isKnown(uri))
        CoverAvailability.forget(uri)
        assertFalse(CoverAvailability.isKnown(uri))
    }

    @Test
    fun `失败后重新登记恢复为已登记`() {
        val uri = "content://cover/test-recover"
        CoverAvailability.markKnown(uri)
        CoverAvailability.forget(uri)
        CoverAvailability.markKnown(uri)
        assertTrue(CoverAvailability.isKnown(uri))
    }
}
