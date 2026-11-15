package com.tyranor.next.ui.common.motion

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DrawPhaseLayersBindingTest {
    // Equality excludes the progress held by this particular instance.
    private data class Source(val name: String) : LayerSource {
        var progress = 0f
        override fun compute(p: LayerParams, density: Float) { p.translationX = progress * p.width }
    }

    @Test fun equalSourcesAndBoundReferencesDoNotHideAReplacement() {
        val a = Source("page")
        val b = Source("page").apply { progress = 0.5f }
        assertEquals(a, b)
        assertEquals(a::compute, b::compute)
        fun element(vararg sources: LayerSource) = Modifier.drawPhaseLayersInOwnedLayer("page", *sources)
        assertNotEquals(element(a), element(b))
        assertNotEquals(element(LayerSource(a::compute)), element(LayerSource(b::compute)))
        assertNotEquals(element(a, b), element(b, a))
        assertEquals(element(a, b), element(a, b))
        assertEquals(element(a, b).hashCode(), element(a, b).hashCode())
    }

    @Test fun sameSnapshotRebindAndEmptyLeaveNoOldOrOuterDrawSubscription() {
        val pending = ArrayDeque<() -> Unit>()
        val hub = LayerSourceReads { pending.addLast(it) }
        val oldState = mutableIntStateOf(0)
        val newState = mutableIntStateOf(0)
        val content = mutableIntStateOf(0)
        var oldCalls = 0
        var newCalls = 0
        var draws = 0
        val draw = SnapshotStateObserver { it() }
        val old = hub.bind { oldCalls++ }
        val next = hub.bind { newCalls++ }
        draw.start()
        try {
            Snapshot.withMutableSnapshot {
                draw.observeReads(Unit, { draws++ }) {
                    content.intValue
                    old.observe { oldState.intValue }
                    old.close()
                    next.observe { newState.intValue }
                }
            }
            Snapshot.withMutableSnapshot { oldState.intValue++ }
            assertEquals("No scheduler wakeup for the replaced source", 0, pending.size)
            Snapshot.withMutableSnapshot { newState.intValue++ }
            assertEquals(1, pending.size)
            pending.removeFirst().invoke()
            assertEquals(1, newCalls)
            assertEquals(0, oldCalls)
            assertEquals("Progress leaked into draw", 0, draws)
            Snapshot.withMutableSnapshot {
                next.observe { newState.intValue }
                next.close()
            }
            Snapshot.withMutableSnapshot { newState.intValue++ }
            assertEquals("Empty binding must not even enqueue a notification", 0, pending.size)
            Snapshot.withMutableSnapshot { content.intValue++ }
            assertEquals("Content must remain observed by draw", 1, draws)
        } finally {
            old.close()
            next.close()
            draw.stop()
            draw.clear()
        }
    }

    @Test fun queuedNotificationCannotCrossBindingsOrCancelAnotherPage() {
        val pending = ArrayDeque<() -> Unit>()
        val hub = LayerSourceReads { pending.addLast(it) }
        val progress = mutableIntStateOf(0)
        var stale = 0
        var fresh = 0
        var peer = 0
        val old = hub.bind { stale++ }
        val otherPage = hub.bind { peer++ }
        val replacement = hub.bind { fresh++ }
        try {
            old.observe { progress.intValue }
            otherPage.observe { progress.intValue }
            Snapshot.withMutableSnapshot { progress.intValue++ }
            assertEquals(1, pending.size)
            old.close()
            replacement.observe { progress.intValue }
            pending.removeFirst().invoke()
            assertEquals(0, stale)
            assertEquals(0, fresh)
            assertEquals(1, peer)
            Snapshot.withMutableSnapshot { progress.intValue++ }
            pending.removeFirst().invoke()
            assertEquals(1, fresh)
            assertEquals(2, peer)
        } finally {
            old.close()
            replacement.close()
            otherPage.close()
        }
    }
}
