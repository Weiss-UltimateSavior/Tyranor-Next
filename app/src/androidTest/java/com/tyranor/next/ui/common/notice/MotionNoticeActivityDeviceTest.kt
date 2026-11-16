package com.tyranor.next.ui.common.notice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.PageTransitions
import com.tyranor.next.ui.settings.AboutAppActivity
import com.tyranor.next.ui.settings.AppSettingsActivity
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production page roots and real Activity pause/resume, with real (not Compose test) time. */
@RunWith(AndroidJUnit4::class)
class MotionNoticeActivityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val handler = Handler(Looper.getMainLooper())
    private var scenario: ActivityScenario<AppSettingsActivity>? = null
    private lateinit var settings: AppSettingsActivity
    private var about: AboutAppActivity? = null
    private var pushRequested = false
    private val childLifecycle = ActivityLifecycleCallback { activity, stage ->
        if (stage == Stage.CREATED && activity is AboutAppActivity &&
            ::settings.isInitialized && activity.taskId == settings.taskId
        ) about = activity
    }
    private var savedPreference: Boolean? = null
    private var savedMode = false
    private var savedFlow = false

    @Before fun openSettings() {
        MotionDeviceAcceptance.require(enhanced = null)
        onMain {
            savedMode = MotionMode.enhanced
            savedFlow = AppSettingsStore.motionEnhancedState.value
            savedPreference = AppSettingsStore.isMotionEnhanced(context)
            // An absent preference already means enhanced. Do not turn it into an explicit setting.
            if (savedPreference == false) AppSettingsStore.setMotionEnhanced(context, true)
            MotionMode.sync(true)
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(childLifecycle)
        }
        scenario = ActivityScenario.launch<AppSettingsActivity>(AppSettingsActivity.createIntent(context))
        checkNotNull(scenario).onActivity { settings = it }
        awaitPage { settings }
    }

    @After fun closePagesAndRestoreMode() {
        val previous = savedPreference ?: return // Includes the package/argument assumption failing.
        try {
            // Even a failed timing assertion can return before the requested child reaches CREATED.
            val child = if (onMain { pushRequested }) await("requested About created") { about } else null
            onMain { if (child != null && !child.isFinishing && !child.isDestroyed) child.finish() }
            if (child != null) await("About destroyed") { true.takeIf { child.isDestroyed } }
        } finally {
            try {
                scenario?.close()
            } finally {
                onMain {
                    ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(childLifecycle)
                    if (NoticeCenter.displayed?.text?.startsWith(PREFIX) == true) NoticeCenter.clear()
                    if (!previous) AppSettingsStore.setMotionEnhanced(context, false)
                    AppSettingsStore.motionEnhancedState.value = savedFlow
                    MotionMode.sync(savedMode)
                }
            }
        }
    }

    @Test fun freshNoticeMovesFromSettingsToAboutWithSameIdentity() {
        crossPage(settings, fresh = true, destination = { about }, navigate = ::openAbout)
    }

    @Test fun alreadyVisibleNoticeDoesNotFollowPushToAbout() {
        crossPage(settings, fresh = false, destination = { about }, navigate = ::openAbout)
    }

    @Test fun alreadyVisibleNoticeDoesNotFollowPopToSettings() {
        onMain { openAbout() }
        val child = awaitPage { about }
        crossPage(child, fresh = false, destination = { settings }, navigate = { child.finish() })
    }

    private fun openAbout() {
        PageTransitions.startPush(settings, AboutAppActivity.createIntent(settings))
        pushRequested = true
    }

    private fun crossPage(
        source: AppScreenActivity,
        fresh: Boolean,
        destination: () -> AppScreenActivity?,
        navigate: () -> Unit,
    ) {
        val message = "$PREFIX${source.javaClass.simpleName}/${if (fresh) "fresh" else "aged"}"
        lateinit var sent: Sent
        val paused = CompletableFuture<Pause>()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val entering = SystemClock.uptimeMillis()
                // A later-added observer sees downward events before the host. Read after this
                // dispatch, never treating the startActivity()/finish() call as the handoff time.
                handler.postAtFrontOfQueue {
                    val displayed = NoticeCenter.displayed
                    // A null drawing host with displayed still set means "waiting for handoff",
                    // not "retired"; that state must fail the aged case.
                    val retired = displayed == null || (displayed.id == sent.notice.id && NoticeCenter.exiting &&
                        NoticeCenter.drawing?.page === source)
                    paused.complete(Pause(entering, SystemClock.uptimeMillis(), retired))
                }
            }
        }
        try {
            val presentedBy = if (fresh) {
                // Prove this exact source window can render a notice and let it finish naturally.
                // First composition, text measurement, and semantics polling are outside the timed action.
                val probe = onMain { sendOnSource(source, "$PREFIX source control", Toast.LENGTH_SHORT).notice }
                await("local notice on source") { true.takeIf { visibleNotice(source, probe) } }
                await("source control retired") { true.takeIf { NoticeCenter.displayed == null } }
                onMain {
                    source.lifecycle.addObserver(observer)
                    sent = sendOnSource(source, message, Toast.LENGTH_LONG)
                    val accepted = SystemClock.uptimeMillis()
                    // A user action may post feedback and immediately open the next Activity.
                    // Do not insert a second main-thread dispatch or wait for a source frame here.
                    navigate()
                    accepted
                }
            } else {
                sent = onMain { sendOnSource(source, message, Toast.LENGTH_LONG) }
                // Age the notice from evidence of visible text in the actual source window.
                val seen = await("source notice text") {
                    if (visibleNotice(source, sent.notice)) SystemClock.uptimeMillis() else null
                }
                SystemClock.sleep((seen + 450L - SystemClock.uptimeMillis()).coerceAtLeast(0))
                onMain {
                    assertTrue("Notice must still be visible immediately before navigation", visibleNotice(source, sent.notice))
                    source.lifecycle.addObserver(observer)
                    navigate()
                }
                seen
            }
            val notice = sent.notice
            val pause = paused.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val lower = pause.enter - presentedBy
            val upper = pause.afterDispatch - sent.before
            val timing = "${source.javaClass.simpleName} id=${notice.id}, age at ON_PAUSE in [$lower, $upper] ms"
            Log.i("MotionNoticeActivityTest", timing)
            if (fresh) assertTrue("Fresh boundary missed: $timing", upper < CARRY_MS)
            else {
                assertTrue("Aged boundary missed: $timing", lower > CARRY_MS)
                assertTrue("Natural expiry would invalidate this case: $timing", upper < notice.dwellMillis)
                assertTrue("Old notice was not cleared or exiting on its original host: $timing", pause.retired)
            }
            val target = awaitPage(destination)
            if (fresh) {
                await("same notice on destination: $timing") { true.takeIf { visibleNotice(target, notice) } }
            } else {
                onMain {
                    assertFalse("Old notice present in destination window", hasText(target.window.decorView, message))
                    assertFalse("Destination claimed old notice", NoticeCenter.displayed?.id == notice.id &&
                        NoticeCenter.drawing?.page === target)
                    // Positive control: absence of the old text must not pass because the
                    // destination has no working host. Issue this only after checking absence.
                    AppNotice.makeText(target, "$PREFIX local", Toast.LENGTH_LONG, AppNotice.Kind.Info).show()
                }
                val probe = onMain { checkNotNull(NoticeCenter.displayed).also { assertEquals("$PREFIX local", it.text) } }
                await("local notice on destination") { true.takeIf { visibleNotice(target, probe) } }
            }
        } finally {
            onMain { source.lifecycle.removeObserver(observer) }
        }
    }

    private data class Sent(val before: Long, val notice: Notice)

    // Main thread. The real NoticeState.present timestamp falls between before and return.
    private fun sendOnSource(source: AppScreenActivity, text: String, duration: Int): Sent {
        val handle = AppNotice.makeText(source, text, duration, AppNotice.Kind.Info)
        val before = SystemClock.uptimeMillis()
        handle.show()
        val notice = checkNotNull(NoticeCenter.displayed) { "Notice did not reach the page host" }
        assertEquals(text, notice.text)
        assertSame(source, NoticeCenter.drawing?.page)
        return Sent(before, notice)
    }

    private data class Pause(val enter: Long, val afterDispatch: Long, val retired: Boolean)

    private fun visibleNotice(page: AppScreenActivity, notice: Notice): Boolean =
        NoticeCenter.drawing?.page === page && NoticeCenter.displayed?.id == notice.id &&
            !NoticeCenter.exiting && NoticeCenter.appear > 0.2f && hasText(page.window.decorView, notice.text)

    private fun awaitPage(page: () -> AppScreenActivity?): AppScreenActivity = await("resumed page with visible title") {
        page()?.takeIf {
            val title = if (it is AboutAppActivity) R.string.settings_about_app else R.string.settings_app_title
            it.lifecycle.currentState == Lifecycle.State.RESUMED && it.hasWindowFocus() && NoticeCenter.canPresent &&
                hasText(it.window.decorView, AppLocaleController.wrap(it).getString(title))
        }
    }

    // Read semantics from this Activity's decor only: the outgoing window can still be drawn
    // during a transition. A global matching-text query could pass on that old window.
    private fun hasText(view: View, text: String): Boolean {
        if (!view.isShown || !view.isAttachedToWindow) return false
        if (view is ViewRootForTest) return hasText(view.semanticsOwner.unmergedRootSemanticsNode, text)
        return view is ViewGroup && (0 until view.childCount).any { hasText(view.getChildAt(it), text) }
    }

    private fun hasText(node: SemanticsNode, text: String): Boolean =
        (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
            !node.boundsInWindow.isEmpty) || node.children.any { hasText(it, text) }

    private fun <T : Any> await(label: String, read: () -> T?): T {
        val until = SystemClock.uptimeMillis() + TIMEOUT_MS
        do {
            onMain(read)?.let { return it }
            SystemClock.sleep(10)
        } while (SystemClock.uptimeMillis() < until)
        throw AssertionError("Timed out waiting for $label")
    }

    // Assertions thrown on main must be reported on the instrumentation thread.
    private fun <T> onMain(block: () -> T): T =
        FutureTask(Callable { block() }).also { instrumentation.runOnMainSync(it) }.get()

    private companion object {
        const val PREFIX = "Lifecycle test: "
        const val CARRY_MS = 350L
        const val TIMEOUT_MS = 8_000L
    }
}
