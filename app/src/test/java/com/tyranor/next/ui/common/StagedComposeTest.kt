package com.tyranor.next.ui.common

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.tyranor.next.ui.common.motion.PageEnter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StagedComposeTest {
    @Test fun choosingFreshEntryDoesNotSubscribeThePageToItsAnimation() = runBlocking {
        val enter = PageEnter(initial = 0f)
        var invalidations = 0
        lateinit var staged: StagedCompose
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Unit, { invalidations++ }) {
                staged = StagedCompose.forPage(3, enhanced = true, enter = enter)
            }
            // Read group state outside the page observation: only entry detection is under test.
            assertTrue(staged.isVisible(0))
            assertFalse(staged.isVisible(1))
            for (progress in listOf(0.2f, 0.6f, 1f)) {
                enter.progress.snapTo(progress)
                Snapshot.sendApplyNotifications()
            }
            assertEquals("Entry progress must not invalidate the page's composition", 0, invalidations)
        } finally {
            observer.stop()
        }
    }

    @Test fun freshEntryStillRevealsExactlyOneGroupPerFrame() = runTest {
        val staged = StagedCompose.forPage(3, enhanced = true, PageEnter(initial = 0f))
        val clock = BroadcastFrameClock()
        val reveal = launch(clock) { staged.revealGroups() }
        runCurrent()
        for (frame in 0..3) {
            for (group in 0..3) assertEquals("frame=$frame group=$group", group <= frame, staged.isVisible(group))
            if (frame < 3) {
                clock.sendFrame((frame + 1) * 16_000_000L)
                runCurrent()
            }
        }
        reveal.join()
    }

    @Test fun restoredOrdinaryAndUnhostedPagesRemainFullyComposed() {
        val pages = listOf(
            StagedCompose.forPage(3, enhanced = true, PageEnter(initial = 1f)),
            StagedCompose.forPage(3, enhanced = false, PageEnter(initial = 0f)),
            StagedCompose.forPage(3, enhanced = true, enter = null),
        )
        for (page in pages) {
            for (group in 0..3) assertTrue(page.isVisible(group))
        }
        assertFalse(StagedCompose.forPage(3, enhanced = true, PageEnter(initial = 0f)).isVisible(1))
    }
}
