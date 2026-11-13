package com.tyranor.next.ui.common

import android.os.SystemClock
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.motion.EntranceState
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.rememberAfterPushStarts
import com.tyranor.next.ui.common.motion.rememberEntranceState
import com.tyranor.next.ui.main.MainTabMotion
import com.tyranor.next.ui.main.MainPanelNavigation
import com.tyranor.next.ui.main.rememberMainTabMotion
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Tests only the isolated motion target; animation scaling never changes device settings. */
class MotionLifecycleDeviceTest {
    private val durationScale = object : MotionDurationScale {
        override val scaleFactor: Float = 4f
    }

    @get:Rule val compose = createComposeRule(effectContext = durationScale)

    /** Every failed start propagates its original throwable and permits a subsequent successful request. */
    @Test
    fun failedNavigationPropagatesEveryThrowableAndAllowsRetry() {
        MotionDeviceAcceptance.require()
        for (failure in listOf(RuntimeException("start"), AssertionError("start"), IOException("start"))) {
            val navigation = MainPanelNavigation()
            var pending: (() -> Unit)? = null
            var closes = 0
            val afterPush: (() -> Unit) -> Unit = { pending = it }
            val thrown = try {
                navigation.navigate({ throw failure }, afterPush) { closes++ }
                null
            } catch (error: Throwable) {
                error
            }
            assertSame("A failed start must propagate the original throwable", failure, thrown)
            assertNull("A failed start must not queue a close callback", pending)
            assertEquals(0, closes)
            var starts = 0
            navigation.navigate({ starts++ }, afterPush) { closes++ }
            assertEquals("A failed start must not lock the next navigation", 1, starts)
            assertEquals("A successful retry must still wait for the push", 0, closes)
            requireNotNull(pending).invoke()
            assertEquals(1, closes)
        }
    }

    /** Keeps the overlay open during a slow launch and consumes the latest close only on a real pause. */
    @Test
    fun slowNavigationStaysLockedUntilPauseAndConsumesOnlyTheLatestClose() {
        MotionDeviceAcceptance.require()
        val owner = resumedOwner()
        val staleCloses = AtomicInteger()
        val closes = AtomicInteger()
        val starts = AtomicInteger()
        val navigation = MainPanelNavigation()
        lateinit var afterPush: (() -> Unit) -> Unit
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val deferred = rememberAfterPushStarts()
                SideEffect { afterPush = deferred }
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                afterPush { staleCloses.incrementAndGet() }
                navigation.navigate({ starts.incrementAndGet() }, afterPush) { closes.incrementAndGet() }
            }
            compose.mainClock.advanceTimeBy(600)
            val observeUntil = SystemClock.uptimeMillis() + 600L
            compose.waitUntil(timeoutMillis = 2_000) { SystemClock.uptimeMillis() >= observeUntil }
            compose.runOnIdle {
                assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
                navigation.navigate({ starts.incrementAndGet() }, afterPush) { closes.incrementAndGet() }
                assertEquals("Slow startup must not permit a second Activity launch", 1, starts.get())
                assertEquals("Elapsed time must leave the overlay visible while this page is resumed", 0, closes.get())
                assertEquals("Replacing a request must discard its close action", 0, staleCloses.get())
                owner.event(Lifecycle.Event.ON_PAUSE)
                assertEquals("The real pause must close the latest overlay once", 1, closes.get())
                owner.event(Lifecycle.Event.ON_RESUME)
                owner.event(Lifecycle.Event.ON_PAUSE)
                assertEquals("A later pause must not execute the consumed action again", 1, closes.get())
                assertEquals(0, staleCloses.get())
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** Reopens navigation and verifies that the cancelled callback cannot close or unlock the new request. */
    @Test
    fun cancellingNavigationDiscardsItsOldCloseWithoutUnlockingTheNewRequest() {
        MotionDeviceAcceptance.require()
        val navigation = MainPanelNavigation()
        var pending: (() -> Unit)? = null
        var starts = 0
        var closes = 0
        val afterPush: (() -> Unit) -> Unit = { pending = it }
        navigation.navigate({ starts++ }, afterPush) { closes++ }
        val oldClose = requireNotNull(pending)
        navigation.cancel()
        navigation.navigate({ starts++ }, afterPush) { closes++ }
        oldClose()
        assertEquals("A cancelled request must not close a reopened panel", 0, closes)
        navigation.navigate({ starts++ }, afterPush) { closes++ }
        assertEquals("An old close must not unlock the new navigation request", 2, starts)
        requireNotNull(pending).invoke()
        assertEquals(1, closes)
    }

    /** Disposes an overlay before pause and checks that its pending close never executes. */
    @Test
    fun disposingTheCompositionCancelsPendingClose() {
        MotionDeviceAcceptance.require()
        val owner = resumedOwner()
        val closes = AtomicInteger()
        var mounted by mutableStateOf(true)
        lateinit var afterPush: (() -> Unit) -> Unit
        compose.setContent {
            if (mounted) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val deferred = rememberAfterPushStarts()
                    SideEffect { afterPush = deferred }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                afterPush { closes.incrementAndGet() }
                mounted = false
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                owner.event(Lifecycle.Event.ON_PAUSE)
                owner.event(Lifecycle.Event.ON_STOP)
                owner.event(Lifecycle.Event.ON_DESTROY)
            }
            compose.mainClock.advanceTimeBy(600)
            // Coroutine delays may use wall time instead of the frame clock on a device.
            val observeUntil = SystemClock.uptimeMillis() + 600L
            compose.waitUntil(timeoutMillis = 2_000) { SystemClock.uptimeMillis() >= observeUntil }
            assertEquals("A disposed overlay must never execute its pending close", 0, closes.get())
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** Checks the original immediate-close behavior and ensures a later pause does not duplicate it. */
    @Test
    fun ordinaryModeClosesImmediatelyWithoutWaitingForPause() {
        MotionDeviceAcceptance.require()
        val owner = resumedOwner()
        val closes = AtomicInteger()
        lateinit var afterPush: (() -> Unit) -> Unit
        InstrumentationRegistry.getInstrumentation().runOnMainSync { MotionMode.sync(false) }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val deferred = rememberAfterPushStarts()
                    SideEffect { afterPush = deferred }
                }
            }
            compose.runOnIdle {
                afterPush { closes.incrementAndGet() }
                assertEquals("Ordinary mode must preserve the original immediate close", 1, closes.get())
                assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
                owner.event(Lifecycle.Event.ON_PAUSE)
                assertEquals(1, closes.get())
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { MotionMode.sync(true) }
        }
    }

    /** Keeps upstream's shorter ordinary onboarding transition without shortening normal tab changes. */
    @Test
    fun ordinaryOnboardingKeepsItsShortTransitionWhileNormalTabsKeepTheirDuration() {
        MotionDeviceAcceptance.require(enhanced = false)
        var selectedPage by mutableIntStateOf(0)
        var onboarding by mutableStateOf(true)
        lateinit var tabMotions: List<MainTabMotion>
        compose.setContent {
            val transition = updateTransition(selectedPage, label = "ordinaryCoachTabs")
            val motions = rememberMainTabMotion(transition, pageCount = 2, onboarding = onboarding)
            SideEffect { tabMotions = motions }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { selectedPage = 1 }
            compose.mainClock.advanceTimeBy(512)
            compose.runOnIdle {
                assertEquals("Onboarding must settle within the upstream short transition", 0f, tabMotions[1].offset.value, 0f)
                assertEquals(1f, tabMotions[1].alpha.value, 0f)
                onboarding = false
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { selectedPage = 0 }
            compose.mainClock.advanceTimeBy(512)
            compose.runOnIdle {
                assertTrue("Normal ordinary tabs must still be moving at the same observation time", abs(tabMotions[0].offset.value) > 0f)
            }
            compose.mainClock.advanceTimeBy(1_500)
            compose.runOnIdle { assertEquals(0f, tabMotions[0].offset.value, 0f) }
        } finally {
            compose.mainClock.autoAdvance = true
            InstrumentationRegistry.getInstrumentation().runOnMainSync { MotionMode.sync(true) }
        }
    }

    /** Exercises slow tab motion so reselecting a still-visible page cannot restart its entrance. */
    @Test
    fun tabEntranceReplaysOnlyAfterTheLeavingPageIsActuallyHidden() {
        MotionDeviceAcceptance.require()
        var selectedPage by mutableIntStateOf(1)
        lateinit var tabMotions: List<MainTabMotion>
        lateinit var entrance: EntranceState
        compose.setContent {
            val transition = updateTransition(selectedPage, label = "mainTabLifecycle")
            val motions = rememberMainTabMotion(transition, pageCount = 2)
            val state = rememberEntranceState(playOnStart = false)
            SideEffect {
                tabMotions = motions
                entrance = state
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { selectedPage = 0 }
            compose.mainClock.advanceTimeBy(704)
            compose.runOnIdle {
                val leaving = tabMotions[1]
                assertTrue(
                    "At four times normal duration the leaving page must remain partly visible after 704ms",
                    leaving.alpha.value > 0f && abs(leaving.offset.value) < 1f,
                )
                val generation = entrance.generation
                leaving.replayEntranceIfHidden(entrance, timeSinceLeft = 704L)
                assertEquals("Re-selecting a visible page must not restart its entrance", generation, entrance.generation)
            }
            compose.mainClock.advanceTimeBy(8_000)
            compose.runOnIdle {
                val leaving = tabMotions[1]
                assertTrue(
                    "The leaving page must eventually settle offscreen",
                    leaving.alpha.value <= 0f || abs(leaving.offset.value) >= 1f,
                )
                val generation = entrance.generation
                leaving.replayEntranceIfHidden(entrance, timeSinceLeft = 400L)
                assertEquals("The minimum replay gap must still apply to a hidden page", generation, entrance.generation)
                leaving.replayEntranceIfHidden(entrance, timeSinceLeft = 401L)
                assertEquals("A hidden page may replay once the minimum gap has elapsed", generation + 1, entrance.generation)
                assertTrue("Returning to a previously shown tab must use its quick entrance", entrance.quick)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** Builds a resumed test lifecycle on the main thread for deterministic pause and disposal checks. */
    private fun resumedOwner(): MotionTestLifecycleOwner {
        lateinit var owner: MotionTestLifecycleOwner
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = MotionTestLifecycleOwner().apply {
                event(Lifecycle.Event.ON_CREATE)
                event(Lifecycle.Event.ON_START)
                event(Lifecycle.Event.ON_RESUME)
            }
        }
        return owner
    }

    private class MotionTestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        /** Dispatches a lifecycle event through the registry used by the tested composition. */
        fun event(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }
}
