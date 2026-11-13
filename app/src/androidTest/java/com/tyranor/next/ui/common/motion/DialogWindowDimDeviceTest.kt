package com.tyranor.next.ui.common.motion

import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.Dialog
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real Window attribute/lifetime checks; display-pixel regression lives in NestedWindowScrimDeviceTest. */
class DialogWindowDimDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val fixtureVisible = mutableStateOf(true)

    @Test fun threeWindowsKeepOneLowerMaskAndRestoreTheirOwnRawDimWhenAlone() = withMode(true) {
        val parent = Slot("parent", 0.6f)
        val middle = Slot("middle", 0.3f)
        val top = Slot("top", 0.1f)
        compose.setContent { MaterialTheme { Scene(parent, middle, top) } }

        show(parent)
        show(middle)
        show(top)
        awaitDims(parent to 0.6f, middle to 0.6f, top to 0.6f)
        val parentWindow = parent.window.get()
        val middleWindow = middle.window.get()
        val topWindow = top.window.get()

        compose.runOnIdle { parent.dim.floatValue = 0.2f }
        // Even the middle window's deeper 0.3 request must not darken an existing 0.2 mask.
        awaitDims(parent to 0.2f, middle to 0.2f, top to 0.2f)
        remove(middle)
        awaitDims(parent to 0.2f, top to 0.2f)

        // Updating a lower window through ordinary recomposition must not move it above top.
        compose.runOnIdle { parent.dim.floatValue = 0.7f }
        awaitDims(parent to 0.7f, top to 0.7f)
        compose.runOnIdle {
            assertSame(parentWindow, parent.window.get())
            assertSame(middleWindow, middle.window.get())
            assertSame(topWindow, top.window.get())
        }
        remove(parent)
        // Inheritance changes only the applied dim: top still owns its original 0.1 request.
        awaitDims(top to 0.1f)
        remove(top)
    }

    @Test fun aDeeperUpperRequestIsIgnoredWhileReleaseAndModeChangesKeepTheSameLowerMask() = withMode(false) {
        val parent = Slot("parent", 0.4f)
        val child = Slot("child", 0.7f)
        compose.setContent { MaterialTheme { Scene(parent, child) } }
        show(parent)
        show(child)
        // A second window must keep one 0.4 mask even when its own raw dim is deeper (0.7).
        awaitDims(parent to 0.4f, child to 0.4f)
        val originalChild = child.window.get()

        compose.runOnIdle {
            originalChild.passThroughTouches()
            originalChild.releaseFocusForExit()
            originalChild.releaseDialogDim()
            originalChild.releaseDialogDim() // Repeated release cannot remove the parent's floor.
            assertEquals("The parent must brighten before any exit animation frame", 0,
                checkNotNull(originalChild).attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            assertEquals(0f, originalChild.attributes.dimAmount, 0f)
        }
        awaitDims(parent to 0.4f)
        compose.runOnIdle { child.dim.floatValue = 0.2f }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.runOnIdle {
            val flags = checkNotNull(originalChild).attributes.flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertEquals("A raw update must not re-enable a closing window", 0,
                flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            originalChild.restoreInteraction()
            // Change the requested base dim as the same live Window switches motion mode.
            // The dim host must update rather than disposing/re-registering it in a new order.
            MotionMode.sync(true)
            child.dim.floatValue = 0.1f
            parent.dim.floatValue = 0.6f
        }
        awaitDims(parent to 0.6f, child to 0.6f)
        compose.runOnIdle {
            assertSame(originalChild, child.window.get())
            val flags = checkNotNull(originalChild).attributes.flags
            assertEquals(0, flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
            parent.dim.floatValue = 0.2f
        }
        awaitDims(parent to 0.2f, child to 0.2f)
        remove(child)
        awaitDims(parent to 0.2f)
        remove(parent)
    }

    private class Slot(val name: String, initialDim: Float) {
        val visible = mutableStateOf(false)
        val dim = mutableFloatStateOf(initialDim)
        val window = AtomicReference<Window?>()
    }

    @Composable
    private fun Scene(vararg slots: Slot) {
        Box(Modifier.fillMaxSize()) {
            if (fixtureVisible.value) for (slot in slots) {
                key(slot) {
                    if (slot.visible.value) {
                        Dialog(onDismissRequest = {}) {
                            // Reading mode makes the test exercise an actual regular recomposition.
                            val enhanced = MotionMode.enhanced
                            val window = syncDialogWindow(baseDimOverride = slot.dim.floatValue) { 1f }
                            SideEffect { slot.window.set(window) }
                            Text("${slot.name} $enhanced", Modifier.testTag("dim-${slot.name}"))
                        }
                    }
                }
            }
        }
    }

    private fun show(slot: Slot) {
        compose.runOnIdle { slot.visible.value = true }
        await("${slot.name} must attach and measure") {
            val decor = slot.window.get()?.decorView
            decor?.isAttachedToWindow == true && decor.width > 0 && decor.height > 0
        }
    }

    private fun remove(slot: Slot) {
        val window = compose.runOnIdle { checkNotNull(slot.window.get()) }
        compose.runOnIdle { slot.visible.value = false }
        await("${slot.name} must detach and release its dim eligibility") {
            !window.decorView.isAttachedToWindow &&
                window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND == 0 &&
                window.attributes.dimAmount == 0f
        }
        compose.runOnIdle {
            assertFalse(window.decorView.isAttachedToWindow)
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            assertEquals(0f, window.attributes.dimAmount, 0f)
        }
    }

    private fun awaitDims(vararg expected: Pair<Slot, Float>) {
        await("expected ${expected.map { "${it.first.name}=${it.second}" }}") {
            expected.all { (slot, dim) ->
                val window = slot.window.get()
                window != null && window.decorView.isAttachedToWindow &&
                    window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 &&
                    abs(window.attributes.dimAmount - dim) <= DIM_TOLERANCE
            }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                SystemClock.sleep(16)
                compose.runOnIdle(condition)
            }
        } catch (failure: Throwable) {
            throw AssertionError("$label; virtual clock=${compose.mainClock.currentTime}", failure)
        }
    }

    private fun withMode(enhanced: Boolean, test: () -> Unit) {
        MotionDeviceAcceptance.require(enhanced = null)
        val originalMode = compose.runOnIdle { MotionMode.enhanced }
        try {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { MotionMode.sync(enhanced) }
            test()
        } finally {
            // Dispose every Dialog even on a failed assertion before restoring the shared mode.
            try {
                compose.runOnIdle { fixtureVisible.value = false }
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
            } finally {
                compose.runOnIdle { MotionMode.sync(originalMode) }
                compose.mainClock.autoAdvance = true
            }
        }
    }

    private companion object {
        const val DIM_TOLERANCE = 0.005f
    }
}
