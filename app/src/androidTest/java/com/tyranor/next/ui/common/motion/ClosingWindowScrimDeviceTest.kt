package com.tyranor.next.ui.common.motion

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.AppBottomSheet
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Keeps the child surface alive: only the accepted close can brighten the parent probe. */
class ClosingWindowScrimDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enhancedClosingWindowBrightensItsParentWithoutUncoveringTheRoot() = checkClosingWindow(true)

    @Test fun ordinaryClosingWindowBrightensItsParentWithoutUncoveringTheRoot() = checkClosingWindow(false)

    @Test fun enhancedDialogDimsTheRealSheetAndReturnsItsMaskOnClose() = checkClosingWindow(true, sheetParent = true)

    @Test fun ordinaryDialogDimsTheRealSheetAndReturnsItsMaskOnClose() = checkClosingWindow(false, sheetParent = true)

    private fun checkClosingWindow(enhanced: Boolean, sheetParent: Boolean = false) {
        MotionDeviceAcceptance.require(enhanced = null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = compose.runOnIdle { MotionMode.enhanced }
        val originalAutoAdvance = compose.mainClock.autoAdvance
        val parentVisible = mutableStateOf(false)
        val childVisible = mutableStateOf(false)
        val childRaw = mutableFloatStateOf(1f)
        val observedRaw = AtomicReference<Float>()
        val parentWindow = AtomicReference<Window?>()
        val childWindow = AtomicReference<Window?>()
        val rootProbe = AtomicReference<IntOffset?>()
        val parentProbe = AtomicReference<IntOffset?>()
        val movingParentProbe = AtomicReference<(() -> IntOffset?)?>()
        val mode = "${if (sheetParent) "sheet" else "dialog"}-${if (enhanced) "enhanced" else "ordinary"}"
        val context = instrumentation.targetContext
        val evidence = File(context.externalCacheDir ?: context.cacheDir,
            "closing-window-scrim/$mode-${SystemClock.uptimeMillis()}")
        val samples = mutableListOf<String>()
        var sequence = 0

        fun screenshot(label: String): Brightness {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            // The real sheet enters through a graphicsLayer. Re-read its live coordinates:
            // an initial onGloballyPositioned callback alone can retain the offscreen point.
            compose.runOnIdle { movingParentProbe.get()?.invoke()?.let { parentProbe.set(it) } }
            SystemClock.sleep(32) // WMS and SurfaceFlinger run independently of the Compose clock.
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull("The test requires a screenshot of actual Android windows", bitmap)
            bitmap!!
            try {
                evidence.mkdirs()
                File(evidence, "${sequence++}-$label.png").outputStream().use {
                    assertTrue("Cannot save display evidence", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val root = brightnessAt(bitmap, checkNotNull(rootProbe.get()))
                val parent = parentProbe.get()?.let { brightnessAt(bitmap, it) }
                val state = compose.runOnIdle {
                    val window = childWindow.get()
                    listOf(childRaw.floatValue, childVisible.value, window?.attributes?.flags,
                        window?.attributes?.dimAmount, window.dialogScrimAlpha(),
                        window?.decorView?.isAttachedToWindow, window?.decorView?.isShown).joinToString("\t")
                }
                samples += "$label\t${compose.mainClock.currentTime}\t${SystemClock.uptimeMillis()}\t$root\t$parent\t$state"
                return Brightness(root, parent)
            } finally {
                bitmap.recycle()
            }
        }

        fun awaitPixels(label: String, accepts: (Brightness) -> Boolean): Brightness {
            var latest: Brightness? = null
            var stable = 0
            try {
                compose.waitUntil(timeoutMillis = 5_000) {
                    val sample = screenshot(label)
                    latest = sample
                    stable = if (accepts(sample)) stable + 1 else 0
                    stable >= 3
                }
            } catch (failure: Throwable) {
                throw AssertionError("$label did not settle: $latest; evidence=${evidence.absolutePath}", failure)
            }
            return checkNotNull(latest)
        }

        fun awaitLayout(tag: String, window: AtomicReference<Window?>) {
            compose.waitUntil(timeoutMillis = 5_000) {
                // Initial Dialog measurement is allowed to advance time; the close checks below are not.
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                instrumentation.waitForIdleSync()
                SystemClock.sleep(16)
                runCatching {
                    compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                    compose.runOnIdle {
                        window.get()?.decorView?.let { it.isAttachedToWindow && it.isShown && it.width > 0 } == true
                    }
                }.getOrDefault(false)
            }
        }

        fun assertFrozen(window: Window, time: Long, raw: Float) {
            assertEquals("Closing must not advance the panel animation", time, compose.mainClock.currentTime)
            assertTrue("The Compose animation clock must remain frozen", !compose.mainClock.autoAdvance)
            compose.runOnIdle {
                assertSame("The original child Window must stay alive", window, childWindow.get())
                assertTrue("The child must stay in the composition", childVisible.value)
                assertTrue("The child Window must remain attached", window.decorView.isAttachedToWindow)
                assertTrue("The child surface must remain shown", window.decorView.isShown)
                assertEquals("Closing must not depend on decreasing progress", raw, childRaw.floatValue, 0f)
            }
        }

        try {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { MotionMode.sync(enhanced) }
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                            Box(Modifier.size(16.dp).then(screenCenter(rootProbe)))
                        }
                        if (parentVisible.value) {
                            val child: @Composable () -> Unit = {
                                if (childVisible.value) {
                                    FixtureDialog(enhanced, {
                                        childRaw.floatValue.also { observedRaw.set(it) }
                                    }, childWindow, "closing-child")
                                }
                            }
                            if (sheetParent) FixtureSheet(parentWindow, parentProbe, movingParentProbe, child)
                            else FixtureDialog(enhanced, { 1f }, parentWindow, "closing-parent", parentProbe, child)
                        }
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                rootProbe.get() != null
            }
            val white = screenshot("root-white")
            assertTrue("Positive control: the root probe must be white: $white", white.root >= 245)

            compose.runOnIdle { parentVisible.value = true }
            awaitLayout("closing-parent", parentWindow)
            if (sheetParent) {
                // Unlike the fixed-progress native parent, the actual M3 sheet has a real
                // entry/cover animation. Finish it before establishing the mask baseline.
                repeat(96) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                }
                awaitLayout("closing-parent", parentWindow)
                compose.runOnIdle {
                    val sheet = checkNotNull(parentWindow.get())
                    assertEquals("The parent must use the production sheet's self-drawn mask, not a fake native dim", 0,
                        sheet.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    assertTrue("The production sheet must actually draw its own mask before a child opens",
                        sheet.dialogScrimAlpha() > 0f)
                }
            }
            val baseline = awaitPixels("parent-alone") {
                it.parent != null && it.parent >= 245 && it.root <= white.root - 32
            }
            val parentWhite = checkNotNull(baseline.parent)
            assertTrue("The root and parent probes must sample different surfaces",
                rootProbe.get() != parentProbe.get())

            compose.runOnIdle { childVisible.value = true }
            awaitLayout("closing-child", childWindow)
            awaitPixels("child-open") {
                it.parent != null && it.parent <= parentWhite - 32 && abs(it.root - baseline.root) <= TOLERANCE
            }
            val originalChild = checkNotNull(childWindow.get())
            val frozenTime = compose.mainClock.currentTime
            assertFrozen(originalChild, frozenTime, 1f)

            compose.runOnIdle { originalChild.passThroughTouches() }
            compose.runOnIdle {
                assertEquals(0, originalChild.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                assertEquals(0f, originalChild.attributes.dimAmount, 0f)
                assertEquals(0f, originalChild.dialogScrimAlpha(), 0f)
            }
            awaitPixels("accepted-close") {
                it.parent != null && abs(it.parent - parentWhite) <= TOLERANCE &&
                    abs(it.root - baseline.root) <= TOLERANCE
            }
            assertFrozen(originalChild, frozenTime, 1f)

            compose.runOnIdle {
                childRaw.floatValue = 0.75f
                // Publish a real progress update without an animation frame or window removal.
                Snapshot.sendApplyNotifications()
            }
            compose.waitUntil(timeoutMillis = 5_000) { observedRaw.get() == 0.75f }
            awaitPixels("released-progress-update") {
                it.parent != null && abs(it.parent - parentWhite) <= TOLERANCE &&
                    abs(it.root - baseline.root) <= TOLERANCE
            }
            assertFrozen(originalChild, frozenTime, 0.75f)
            compose.runOnIdle {
                assertEquals(0, originalChild.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                assertEquals(0f, originalChild.dialogScrimAlpha(), 0f)
                originalChild.restoreInteraction()
            }
            awaitPixels("explicit-reopen") {
                it.parent != null && it.parent <= parentWhite - 32 && abs(it.root - baseline.root) <= TOLERANCE
            }
            assertFrozen(originalChild, frozenTime, 0.75f)
            compose.runOnIdle {
                assertEquals(0, originalChild.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }
        } catch (failure: Throwable) {
            runCatching { screenshot("failure") }.onFailure { failure.addSuppressed(it) }
            throw failure
        } finally {
            try {
                evidence.mkdirs()
                File(evidence, "samples.tsv").writeText(
                    "label\tcomposeMillis\tuptimeMillis\trootBrightness\tparentBrightness\trawProgress\tchildVisible\tflags\tnativeDim\tselfScrim\tattached\tshown\n" +
                        samples.joinToString("\n") + "\n",
                )
            } finally {
                try {
                    compose.runOnIdle {
                        childVisible.value = false
                        parentVisible.value = false
                    }
                    compose.mainClock.advanceTimeBy(32)
                    compose.waitForIdle()
                    instrumentation.waitForIdleSync()
                    compose.waitUntil(timeoutMillis = 5_000) {
                        compose.runOnIdle {
                            parentWindow.get()?.decorView?.isAttachedToWindow != true &&
                                childWindow.get()?.decorView?.isAttachedToWindow != true
                        }
                    }
                } finally {
                    try {
                        compose.runOnIdle { MotionMode.sync(originalMode) }
                    } finally {
                        compose.mainClock.autoAdvance = originalAutoAdvance
                    }
                }
            }
        }
    }

    /** Exercise the actual M3 window, motionSheetSurface, BindWindowScrim and BindBackdrop path. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FixtureSheet(
        window: AtomicReference<Window?>,
        parentProbe: AtomicReference<IntOffset?>,
        movingParentProbe: AtomicReference<(() -> IntOffset?)?>,
        content: @Composable () -> Unit,
    ) {
        val backdrop = rememberSheetBackdrop()
        CompositionLocalProvider(LocalSheetBackdrop provides backdrop) {
            AppBottomSheet(onDismissRequest = {}) {
                val view = LocalView.current
                SideEffect { window.set((view.parent as DialogWindowProvider).window) }
                // The child panel occupies the bottom-right. This parent probe lies inside
                // opaque sheet content near its top-left, separately from the root probe.
                Box(Modifier.fillMaxWidth().height(240.dp).background(Color.White).testTag("closing-parent")) {
                    Box(Modifier.padding(20.dp).size(16.dp).then(screenCenter(parentProbe, movingParentProbe)))
                    content()
                }
            }
        }
    }

    @Composable
    private fun FixtureDialog(
        enhanced: Boolean,
        progress: () -> Float,
        window: AtomicReference<Window?>,
        tag: String,
        parentProbe: AtomicReference<IntOffset?>? = null,
        content: @Composable () -> Unit = {},
    ) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            DialogWindowScrim(enhanced, progress, { window.set(it) }, {}, dismissEnabled = false) {
                if (parentProbe != null) {
                    Box(Modifier.align(Alignment.Center).fillMaxWidth(0.72f).fillMaxHeight(0.62f)
                        .nestedPanelDim(window = { window.get() })
                        .background(Color.White).testTag(tag)) {
                        Box(Modifier.padding(20.dp).size(16.dp).then(screenCenter(parentProbe)))
                    }
                } else {
                    // This opaque panel cannot cover either the root's or the parent's top-left probe.
                    Box(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp).fillMaxWidth(0.25f).fillMaxHeight(0.2f)
                        .background(Color.White).testTag(tag))
                }
                content()
            }
        }
    }

    @Composable
    private fun screenCenter(
        target: AtomicReference<IntOffset?>,
        refresh: AtomicReference<(() -> IntOffset?)?>? = null,
    ): Modifier {
        val view = LocalView.current
        return Modifier.onGloballyPositioned { coordinates ->
            val locate: () -> IntOffset? = {
                if (!coordinates.isAttached) null else {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val center = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
                    IntOffset(location[0] + center.x.roundToInt(), location[1] + center.y.roundToInt())
                }
            }
            refresh?.set(locate)
            locate()?.let { target.set(it) }
        }
    }

    private fun brightnessAt(bitmap: Bitmap, point: IntOffset): Int {
        assertTrue("probe x is outside the display", point.x >= 3 && point.x + 3 < bitmap.width)
        assertTrue("probe y is outside the display", point.y >= 3 && point.y + 3 < bitmap.height)
        var total = 0
        for (y in point.y - 3..point.y + 3) for (x in point.x - 3..point.x + 3) {
            val pixel = bitmap.getPixel(x, y)
            total += android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
        }
        return (total / (49f * 3f)).roundToInt()
    }

    private data class Brightness(val root: Int, val parent: Int?)

    private companion object {
        const val TOLERANCE = 8
    }
}
