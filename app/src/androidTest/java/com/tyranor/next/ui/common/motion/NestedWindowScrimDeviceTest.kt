package com.tyranor.next.ui.common.motion

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Samples the composed Android display: Compose captureToImage cannot observe a Window dim layer. */
class NestedWindowScrimDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enhancedCancelNeverUncoversTheParentMask() = checkNestedExit(enhanced = true, back = false)

    @Test fun enhancedBackNeverUncoversTheParentMask() = checkNestedExit(enhanced = true, back = true)

    @Test fun ordinaryCancelNeverUncoversTheParentMask() = checkNestedExit(enhanced = false, back = false)

    @Test fun ordinaryBackNeverUncoversTheParentMask() = checkNestedExit(enhanced = false, back = true)

    private fun checkNestedExit(enhanced: Boolean, back: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = compose.runOnIdle { MotionMode.enhanced }
        val parentVisible = mutableStateOf(false)
        val childVisible = mutableStateOf(false)
        val parentActions = mutableIntStateOf(0)
        val childActions = mutableIntStateOf(0)
        val parentWindow = AtomicReference<Window?>()
        val childWindow = AtomicReference<Window?>()
        val probe = AtomicReference<IntOffset?>()
        val parentButton = AtomicReference<IntOffset?>()
        val childCancelButton = AtomicReference<IntOffset?>()
        val case = "${if (enhanced) "enhanced" else "ordinary"}-${if (back) "back" else "cancel"}"
        val context = instrumentation.targetContext
        val evidence = File(context.externalCacheDir ?: context.cacheDir, "nested-window-scrim/$case")
        val samples = mutableListOf<String>()
        var baseline = 0
        var brightest = 0
        var darkest = 255
        var sampledFrames = 0
        var mountedChild = false
        var childMounts = 0
        var childDisposals = 0

        fun screenshot(label: String, compare: Boolean = true): Int {
            // The Compose clock drives the real AppAlertDialog animations; the short real wait
            // lets WMS/SurfaceFlinger commit their separate Window dim transaction as well.
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(16)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull("UiAutomation must capture actual platform windows", bitmap)
            bitmap!!
            try {
                val point = checkNotNull(probe.get()) { "The white display probe has not been placed" }
                assertTrue("probe x is outside display", point.x >= 3 && point.x + 3 < bitmap.width)
                assertTrue("probe y is outside display", point.y >= 3 && point.y + 3 < bitmap.height)
                var peak = 0
                for (y in point.y - 3..point.y + 3) {
                    for (x in point.x - 3..point.x + 3) {
                        val pixel = bitmap.getPixel(x, y)
                        val luminance = (android.graphics.Color.red(pixel) +
                            android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3
                        peak = maxOf(peak, luminance)
                    }
                }
                samples += "$label\t${compose.mainClock.currentTime}\t${SystemClock.uptimeMillis()}\t$peak\t${childVisible.value}"
                if (!compare || peak > brightest) {
                    evidence.mkdirs()
                    val name = if (compare) "brightest.png" else "$label.png"
                    File(evidence, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                if (compare) {
                    sampledFrames++
                    brightest = maxOf(brightest, peak)
                    darkest = minOf(darkest, peak)
                }
                return peak
            } finally {
                bitmap.recycle()
            }
        }

        fun frames(label: String, count: Int) {
            repeat(count) { frame ->
                compose.mainClock.advanceTimeByFrame()
                screenshot("$label-$frame")
            }
        }

        fun tap(target: AtomicReference<IntOffset?>) {
            val point = checkNotNull(target.get()) { "The real button must be placed before input injection" }
            val downAt = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                    point.x.toFloat(), point.y.toFloat(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    assertTrue("The real display must accept the button event",
                        instrumentation.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
                if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(32)
            }
            instrumentation.waitForIdleSync()
        }

        fun saveSynchronizationFailure(label: String, failure: Throwable) {
            runCatching {
                evidence.mkdirs()
                val state = buildString {
                    appendLine("$label clock=${compose.mainClock.currentTime} uptime=${SystemClock.uptimeMillis()}")
                    for (tag in listOf("scrim-parent", "scrim-open", "scrim-child", "scrim-cancel")) {
                        val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
                        appendLine("$tag=${nodes.map { "root=${it.boundsInRoot} window=${it.boundsInWindow}" }}")
                    }
                    compose.runOnIdle {
                        for ((name, reference) in listOf("parent" to parentWindow, "child" to childWindow)) {
                            val decor = reference.get()?.decorView
                            appendLine("$name attached=${decor?.isAttachedToWindow} size=${decor?.width}x${decor?.height} " +
                                "visible=${decor?.visibility} focus=${decor?.hasWindowFocus()}")
                        }
                    }
                    appendLine("parentVisible=${parentVisible.value} childVisible=${childVisible.value} " +
                        "mounts=$childMounts disposals=$childDisposals probe=${probe.get()} " +
                        "parentButton=${parentButton.get()} childButton=${childCancelButton.get()}")
                }
                File(evidence, "$label-layout.txt").writeText(state)
                Log.e("NestedWindowScrimTest", state)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                if (bitmap != null) {
                    try {
                        File(evidence, "$label-layout.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }.onFailure { failure.addSuppressed(it) }
        }

        fun awaitDisplayed(tag: String, window: AtomicReference<Window?>, sample: Boolean = false) {
            var previousBounds: Rect? = null
            var stableSamples = 0
            var attempts = 0
            try {
                // An Android Dialog is measured by its separate ViewRoot. Advancing virtual time
                // alone does not prove that first layout/placement has happened. Yield to the real
                // UI and wait for visible, nonempty, stable bounds instead of increasing a big tick.
                compose.waitUntil(timeoutMillis = 8_000) {
                    attempts++
                    compose.mainClock.advanceTimeBy(32)
                    compose.waitForIdle()
                    instrumentation.waitForIdleSync()
                    if (sample) screenshot("await-$tag-$attempts") else SystemClock.sleep(32)
                    try {
                        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                        val bounds = node.fetchSemanticsNode().boundsInRoot
                        val attached = compose.runOnIdle { window.get()?.decorView?.isAttachedToWindow == true }
                        stableSamples = if (attached && !bounds.isEmpty && bounds == previousBounds) stableSamples + 1 else 0
                        previousBounds = bounds
                        stableSamples >= 3
                    } catch (_: AssertionError) {
                        previousBounds = null
                        stableSamples = 0
                        false
                    }
                }
            } catch (failure: Throwable) {
                saveSynchronizationFailure(tag, failure)
                throw failure
            }
            Log.i("NestedWindowScrimTest", "Displayed $tag after $attempts attempts at clock=${compose.mainClock.currentTime}")
        }

        fun awaitRemoved(tag: String, window: AtomicReference<Window?>, sample: Boolean = false) {
            var attempts = 0
            try {
                compose.waitUntil(timeoutMillis = 5_000) {
                    attempts++
                    compose.mainClock.advanceTimeBy(32)
                    compose.waitForIdle()
                    instrumentation.waitForIdleSync()
                    if (sample) screenshot("remove-$tag-$attempts") else SystemClock.sleep(32)
                    compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                        compose.runOnIdle { window.get()?.decorView?.isAttachedToWindow != true }
                }
            } catch (failure: Throwable) {
                saveSynchronizationFailure("remove-$tag", failure)
                throw failure
            }
        }

        try {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { MotionMode.sync(enhanced) }
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                            Box(Modifier.size(16.dp).then(screenCenter(probe)))
                        }
                        if (parentVisible.value) {
                            AppAlertDialog(
                                onDismissRequest = { parentVisible.value = false },
                                title = {
                                    ObserveWindow(parentWindow)
                                    Text("Parent dialog", Modifier.testTag("scrim-parent"))
                                },
                                text = {
                                    Text("Parent body")
                                    if (childVisible.value) {
                                        AppAlertDialog(
                                            onDismissRequest = { childActions.intValue++; childVisible.value = false },
                                            title = {
                                                ObserveWindow(childWindow)
                                                DisposableEffect(Unit) {
                                                    mountedChild = true
                                                    childMounts++
                                                    onDispose { mountedChild = false; childDisposals++ }
                                                }
                                                Text("Child dialog", Modifier.testTag("scrim-child"))
                                            },
                                            text = { Text("Cancel this confirmation") },
                                            dismissButton = {
                                                DialogTextButton("Cancel", Modifier.testTag("scrim-cancel")
                                                    .then(screenCenter(childCancelButton))) {
                                                    childActions.intValue++
                                                    childVisible.value = false
                                                }
                                            },
                                            confirmButton = { Text("Confirm") },
                                        )
                                    }
                                },
                                dismissButton = { Text("Parent stays open") },
                                confirmButton = {
                                    DialogTextButton("Open child", Modifier.testTag("scrim-open")
                                        .then(screenCenter(parentButton))) {
                                        parentActions.intValue++
                                        childVisible.value = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                instrumentation.waitForIdleSync()
                probe.get() != null
            }
            val undimmed = screenshot("undimmed", compare = false)
            assertTrue("The screen probe must really be white before any window opens: $undimmed", undimmed >= 245)
            compose.runOnIdle { parentVisible.value = true }
            awaitDisplayed("scrim-parent", parentWindow)
            compose.onNodeWithTag("scrim-open").assertIsDisplayed()
            baseline = screenshot("parent-mask", compare = false)
            assertTrue("Positive control: the parent's real Window must darken the white root: $baseline/$undimmed",
                baseline <= undimmed - 32)

            tap(parentButton)
            screenshot("child-added")
            frames("child-enter", 64)
            awaitDisplayed("scrim-child", childWindow, sample = true)
            val originalChild = checkNotNull(childWindow.get())
            compose.runOnIdle {
                assertTrue("The real child Window must be attached", originalChild.decorView.isAttachedToWindow)
                assertTrue(mountedChild)
                assertEquals(1, childMounts)
            }

            if (back) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            else tap(childCancelButton)
            screenshot("exit-requested")
            frames("child-exit", 80)
            awaitRemoved("scrim-child", childWindow, sample = true)
            compose.onNodeWithTag("scrim-child").assertDoesNotExist()
            awaitDisplayed("scrim-parent", parentWindow, sample = true)
            compose.runOnIdle {
                assertFalse("The child must leave the composition", mountedChild)
                assertFalse("The child Android Window must actually be removed", originalChild.decorView.isAttachedToWindow)
                assertEquals(1, childDisposals)
                assertEquals(1, childActions.intValue)
            }

            // Reopen via the visible parent button: a retained scrim or focus/touch blocker must
            // not leave a visually restored parent unusable. The second Window is then disposed.
            tap(parentButton)
            awaitDisplayed("scrim-child", childWindow)
            compose.runOnIdle {
                assertEquals(2, parentActions.intValue)
                assertEquals(2, childMounts)
                parentVisible.value = false
            }
            awaitRemoved("scrim-parent", parentWindow)
            awaitRemoved("scrim-child", childWindow)
            compose.onNodeWithTag("scrim-child").assertDoesNotExist()
            compose.onNodeWithTag("scrim-parent").assertDoesNotExist()
            val restored = screenshot("all-windows-disposed", compare = false)
            assertTrue("Removing the parent must not retain a leaked dim layer: $restored/$undimmed",
                restored >= undimmed - BRIGHTNESS_TOLERANCE)

            assertTrue("Both entry and exit must produce sampled display frames", sampledFrames >= 144)
            assertTrue("$case: background flashed above parent mask; baseline=$baseline, peak=$brightest, " +
                "tolerance=$BRIGHTNESS_TOLERANCE; evidence=${evidence.absolutePath}",
                brightest <= baseline + BRIGHTNESS_TOLERANCE)
            assertTrue("$case: child deepened the parent mask; baseline=$baseline, minimum=$darkest, " +
                "tolerance=$BRIGHTNESS_TOLERANCE; evidence=${evidence.absolutePath}",
                darkest >= baseline - BRIGHTNESS_TOLERANCE)
        } finally {
            try {
                evidence.mkdirs()
                File(evidence, "samples.tsv").writeText(
                    "label\tcomposeMillis\tuptimeMillis\tpeakBrightness\tchildVisible\n" + samples.joinToString("\n") + "\n",
                )
            } finally {
                compose.runOnIdle {
                    parentVisible.value = false
                    childVisible.value = false
                    MotionMode.sync(originalMode)
                }
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Composable
    private fun ObserveWindow(target: AtomicReference<Window?>) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect { target.set(window) }
    }

    @Composable
    private fun screenCenter(target: AtomicReference<IntOffset?>): Modifier {
        val view = LocalView.current
        return Modifier.onGloballyPositioned { coordinates ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val center = coordinates.localToRoot(Offset(
                coordinates.size.width / 2f,
                coordinates.size.height / 2f,
            ))
            target.set(IntOffset(location[0] + center.x.roundToInt(), location[1] + center.y.roundToInt()))
        }
    }

    private companion object {
        const val BRIGHTNESS_TOLERANCE = 8
    }
}
