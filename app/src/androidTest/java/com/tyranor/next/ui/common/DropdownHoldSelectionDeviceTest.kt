package com.tyranor.next.ui.common

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.motion.MotionMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Real touch streams continue across the row and popup; preview and commit are checked separately. */
class DropdownHoldSelectionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enhancedRootPopupCommitsOnlyOnRelease() = checkSelection(enhanced = true, windowHost = false)

    @Test fun ordinaryRootPopupCommitsOnlyOnRelease() = checkSelection(enhanced = false, windowHost = false)

    @Test fun enhancedWindowPopupCommitsOnlyOnRelease() = checkSelection(enhanced = true, windowHost = true)

    @Test fun ordinaryWindowPopupCommitsOnlyOnRelease() = checkSelection(enhanced = false, windowHost = true)

    private fun checkSelection(enhanced: Boolean, windowHost: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = compose.runOnIdle { MotionMode.enhanced }
        val originalAutoAdvance = compose.mainClock.autoAdvance
        val fixture = Fixture()
        val context = instrumentation.targetContext
        val case = "${if (enhanced) "enhanced" else "ordinary"}-${if (windowHost) "window" else "root"}"
        val evidence = File(context.externalCacheDir ?: context.cacheDir,
            "dropdown-hold-selection/$case-${SystemClock.uptimeMillis()}")
        val samples = mutableListOf<String>()
        val touchEvents = mutableListOf<String>()
        var downAt: Long? = null
        var lastPoint = Offset.Zero

        fun settle(millis: Long = 32) {
            compose.mainClock.advanceTimeBy(millis)
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(16)
        }

        fun point(node: SemanticsNodeInteraction): Offset {
            val placed = node.fetchSemanticsNode()
            return placed.positionOnScreen + Offset(placed.boundsInRoot.width / 2f, placed.boundsInRoot.height / 2f)
        }

        fun option(index: Int) = compose.onNodeWithText(LABELS[index])

        fun send(action: Int, target: Offset = lastPoint) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) {
                check(downAt == null) { "The previous touch stream must finish" }
                downAt = now
            }
            val event = MotionEvent.obtain(checkNotNull(downAt), now, action, target.x, target.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("$case: Android rejected touch action $action",
                    instrumentation.uiAutomation.injectInputEvent(event, true))
                touchEvents += "${MotionEvent.actionToString(action)}\t${event.downTime}\t${event.eventTime}\t" +
                    "${compose.mainClock.currentTime}\t${target.x}\t${target.y}"
            } finally {
                event.recycle()
                lastPoint = target
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downAt = null
            }
            instrumentation.waitForIdleSync()
        }

        fun tap(target: Offset) {
            send(MotionEvent.ACTION_DOWN, target)
            SystemClock.sleep(32)
            send(MotionEvent.ACTION_UP, target)
            settle()
        }

        fun assertModel(label: String, callbacks: List<Int> = emptyList(), selected: Int = 0) {
            compose.runOnIdle {
                samples += "$label\t${compose.mainClock.currentTime}\t${SystemClock.uptimeMillis()}\t" +
                    "${fixture.selected}\t${fixture.callbacks}\t${fixture.haptics}\t${fixture.expanded}"
                assertEquals("$case/$label: preview must not commit a value", callbacks, fixture.callbacks.toList())
                assertEquals("$case/$label: committed value changed", selected, fixture.selected)
            }
        }

        fun assertCommittedSemantics(selected: Int = 0) {
            for (index in LABELS.indices) {
                val node = option(index).fetchSemanticsNode()
                assertEquals("A hover candidate must not masquerade as TalkBack's committed selection",
                    index == selected, node.config.getOrNull(SemanticsProperties.Selected))
            }
        }

        fun textColor(index: Int): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            option(index).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue("The production option must expose its real text layout", action(layouts))
            }
            return layouts.single { it.layoutInput.text.text == LABELS[index] }.layoutInput.style.color
        }

        fun candidate(index: Int, expectedHaptics: Int? = null) {
            val node = option(index).fetchSemanticsNode()
            touchEvents += "candidate-$index\t${downAt}\t${SystemClock.uptimeMillis()}\t${compose.mainClock.currentTime}\t" +
                "screen=${node.positionOnScreen}\troot=${node.boundsInRoot}"
            send(MotionEvent.ACTION_MOVE, point(option(index)))
            settle()
            assertModel("preview-$index")
            assertCommittedSemantics()
            assertEquals("The current enabled candidate must use the theme text color; " +
                "holdDeadline=${fixture.longPressMillis}ms; touch=${touchEvents.takeLast(4)}; evidence=${evidence.absolutePath}",
                fixture.primary, textColor(index))
            if (expectedHaptics != null) compose.runOnIdle {
                assertEquals("Only a changed valid candidate may vibrate", expectedHaptics, fixture.haptics.size)
            }
        }

        fun awaitPopup() {
            compose.waitUntil(timeoutMillis = 5_000) {
                settle(32)
                compose.onAllNodesWithText(LABELS.last()).fetchSemanticsNodes().size == 1
            }
            // The popup's real coordinates must settle before injecting a cross-window move.
            settle(400)
            assertTrue(option(1).fetchSemanticsNode().boundsInRoot.height > 0f)
        }

        fun openByClick() {
            tap(point(compose.onNodeWithTag(ROW_TAG)))
            awaitPopup()
            assertModel("open-click")
            compose.runOnIdle { assertTrue("Opening alone must not vibrate", fixture.haptics.isEmpty()) }
        }

        fun holdAt(target: Offset) {
            send(MotionEvent.ACTION_DOWN, target)
            val composeDownAt = compose.mainClock.currentTime
            SystemClock.sleep(fixture.longPressMillis + 64)
            // AwaitPointerEventScope.withTimeout launches delay() in the pointer node's
            // coroutine scope. With autoAdvance=false, real uptime alone does not drain
            // the test dispatcher; advance past that deadline before any MOVE is injected.
            settle(fixture.longPressMillis + 64)
            assertTrue("The pointer timeout must pass on the Compose clock as well",
                compose.mainClock.currentTime - composeDownAt >= fixture.longPressMillis)
            touchEvents += "hold-deadline\t${downAt}\t${SystemClock.uptimeMillis()}\t" +
                "${compose.mainClock.currentTime}\tlongPressMillis=${fixture.longPressMillis}\tcomposeDownAt=$composeDownAt"
        }

        fun reset() {
            if (downAt != null) send(MotionEvent.ACTION_CANCEL)
            compose.runOnIdle {
                fixture.session++
                fixture.selected = 0
                fixture.callbacks.clear()
                fixture.haptics.clear()
                fixture.expanded = false
            }
            settle(500)
        }

        fun outsidePoint(): Offset {
            val first = option(0).fetchSemanticsNode()
            val last = option(LABELS.lastIndex).fetchSemanticsNode()
            val margin = 48f * context.resources.displayMetrics.density
            val above = first.positionOnScreen.y - margin
            val below = last.positionOnScreen.y + last.boundsInRoot.height + margin
            val height = context.resources.displayMetrics.heightPixels.toFloat()
            val y = if (above > margin) above else below
            assertTrue("The fixture must leave a real area outside all option rows", y in 1f..(height - 1f))
            return Offset(point(option(1)).x, y)
        }

        try {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { MotionMode.sync(enhanced) }
            compose.setContent { Scene(fixture, windowHost) }
            settle(500)

            // Moving before the long-press deadline must cancel a press, never select a row.
            val row = point(compose.onNodeWithTag(ROW_TAG))
            send(MotionEvent.ACTION_DOWN, row)
            SystemClock.sleep((fixture.longPressMillis / 4).coerceAtMost(100L))
            send(MotionEvent.ACTION_MOVE, row + Offset(0f, 80f * context.resources.displayMetrics.density))
            send(MotionEvent.ACTION_UP)
            settle(400)
            assertModel("row-short-drag")
            compose.runOnIdle {
                assertFalse("A row scroll before long press must not open the popup", fixture.expanded)
                assertTrue(fixture.haptics.isEmpty())
            }
            // Nothing in this popup scrolls, so the touch itself is the selection: the touched row becomes the
            // candidate at DOWN (no timing), the previously chosen row turns plain, and release commits.
            openByClick()
            assertEquals("Before touching, the committed row owns the theme color", fixture.primary, textColor(0))
            send(MotionEvent.ACTION_DOWN, point(option(1)))
            settle()
            assertModel("down-candidate")
            assertCommittedSemantics()
            assertEquals("The touched row is the candidate at once", fixture.primary, textColor(1))
            assertTrue("The previously chosen row must turn plain", textColor(0) != fixture.primary)
            compose.runOnIdle { assertEquals("Touching a new option vibrates once", 1, fixture.haptics.size) }
            send(MotionEvent.ACTION_MOVE, point(option(3)))
            settle()
            assertModel("immediate-slide-preview")
            assertEquals("The slid-over candidate must use the theme text color", fixture.primary, textColor(3))
            assertTrue("The row left behind must turn plain", textColor(1) != fixture.primary)
            compose.runOnIdle { assertEquals("Entering another candidate vibrates once more", 2, fixture.haptics.size) }
            send(MotionEvent.ACTION_UP)
            settle(400)
            assertModel("immediate-slide-release", listOf(3), selected = 3)
            compose.runOnIdle { assertEquals("Release/commit/close must not add haptics", 2, fixture.haptics.size) }

            // A long press within the popup previews each row; only ACTION_UP delivers once.
            reset()
            openByClick()
            holdAt(point(option(1)))
            assertModel("popup-long-press")
            candidate(3)
            val firstCandidateHaptics = compose.runOnIdle { fixture.haptics.size }
            candidate(1, firstCandidateHaptics + 1)
            val beforeUpHaptics = compose.runOnIdle { fixture.haptics.size }
            send(MotionEvent.ACTION_UP)
            settle(500)
            assertModel("popup-release", listOf(1), selected = 1)
            compose.runOnIdle { assertEquals("Release/commit/close must not add haptics", beforeUpHaptics, fixture.haptics.size) }

            // Hold the original preference row, then continue the same pointer into its popup.
            reset()
            holdAt(point(compose.onNodeWithTag(ROW_TAG)))
            awaitPopup()
            assertModel("row-open-held")
            candidate(1)
            val rowHaptics = compose.runOnIdle { fixture.haptics.size }
            candidate(3, rowHaptics + 1)
            val rowUpHaptics = compose.runOnIdle { fixture.haptics.size }
            send(MotionEvent.ACTION_UP)
            settle(500)
            assertModel("row-release", listOf(3), selected = 3)
            compose.runOnIdle { assertEquals(rowUpHaptics, fixture.haptics.size) }

            // A disabled target is never a candidate and cannot deliver a value on release.
            reset()
            openByClick()
            holdAt(point(option(1)))
            candidate(3)
            val enabledHaptics = compose.runOnIdle { fixture.haptics.size }
            send(MotionEvent.ACTION_MOVE, point(option(2)))
            settle()
            assertModel("disabled-preview")
            assertCommittedSemantics()
            compose.runOnIdle { assertEquals("Entering a disabled item must not vibrate", enabledHaptics, fixture.haptics.size) }
            send(MotionEvent.ACTION_UP)
            settle(400)
            assertModel("disabled-release")

            // Leaving all rows and platform ACTION_CANCEL both discard a valid preview.
            reset()
            openByClick()
            holdAt(point(option(1)))
            candidate(3)
            send(MotionEvent.ACTION_MOVE, outsidePoint())
            settle()
            send(MotionEvent.ACTION_UP)
            settle(400)
            assertModel("outside-release")
            reset()
            openByClick()
            holdAt(point(option(1)))
            candidate(3)
            val cancelHaptics = compose.runOnIdle { fixture.haptics.size }
            send(MotionEvent.ACTION_CANCEL)
            settle(400)
            assertModel("platform-cancel")
            compose.runOnIdle { assertEquals("Cancellation must not vibrate", cancelHaptics, fixture.haptics.size) }

            // Ordinary tap and the actual semantics click remain direct, one-shot actions.
            reset()
            openByClick()
            tap(point(option(3)))
            settle(400)
            assertModel("tap", listOf(3), selected = 3)
            compose.runOnIdle { assertEquals("A tap on a new option is one candidate touch; release adds nothing", 1, fixture.haptics.size) }
            // Press and release in place commits directly, however long the finger rests: no timing rule.
            reset()
            openByClick()
            send(MotionEvent.ACTION_DOWN, point(option(3)))
            SystemClock.sleep(fixture.longPressMillis + 200)
            settle(fixture.longPressMillis + 200)
            assertModel("still-press")
            assertEquals("A resting touch keeps the candidate state", fixture.primary, textColor(3))
            send(MotionEvent.ACTION_UP)
            settle(400)
            assertModel("still-press-release", listOf(3), selected = 3)
            compose.runOnIdle { assertEquals("A resting touch vibrates once on contact, never on release", 1, fixture.haptics.size) }
            reset()
            openByClick()
            // performClick() injects a real touch; TalkBack uses the semantics action itself.
            option(1).performSemanticsAction(SemanticsActions.OnClick) { it() }
            settle(400)
            assertModel("accessibility-click", listOf(1), selected = 1)
            compose.runOnIdle { assertTrue("An accessibility commit must not vibrate", fixture.haptics.isEmpty()) }
        } catch (failure: Throwable) {
            runCatching {
                evidence.mkdirs()
                File(evidence, "failure.txt").writeText(failure.stackTraceToString())
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                if (bitmap != null) {
                    try {
                        File(evidence, "failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }.onFailure { failure.addSuppressed(it) }
            throw failure
        } finally {
            try {
                if (downAt != null) runCatching { send(MotionEvent.ACTION_CANCEL) }
                evidence.mkdirs()
                File(evidence, "samples.tsv").writeText(
                    "label\tcomposeMillis\tuptimeMillis\tselected\tcallbacks\thaptics\texpanded\n" + samples.joinToString("\n") + "\n",
                )
                File(evidence, "touch-events.tsv").writeText(
                    "action\tdownMillis\teventMillis\tcomposeMillis\txOrLayout\tyOrBounds\n" + touchEvents.joinToString("\n") + "\n",
                )
            } finally {
                try {
                    compose.runOnIdle { fixture.mounted = false }
                    settle()
                    compose.runOnIdle { MotionMode.sync(originalMode) }
                } finally {
                    compose.mainClock.autoAdvance = originalAutoAdvance
                }
            }
        }
    }

    private class Fixture {
        var session by mutableIntStateOf(0)
        var selected by mutableIntStateOf(0)
        var mounted by androidx.compose.runtime.mutableStateOf(true)
        var expanded = false
        var primary = Color.Unspecified
        var longPressMillis = 500L
        val callbacks = mutableListOf<Int>()
        val haptics = mutableListOf<HapticFeedbackType>()
        val haptic = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                haptics += hapticFeedbackType
            }
        }
    }

    @Composable
    private fun Scene(fixture: Fixture, windowHost: Boolean) {
        if (!fixture.mounted) return
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalHapticFeedback provides fixture.haptic) {
                MiuixSettingsTheme {
                    val primary = MiuixTheme.colorScheme.primary
                    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
                    SideEffect {
                        fixture.primary = primary
                        fixture.longPressMillis = timeout
                    }
                    Scaffold { contentPadding ->
                        Box(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                            key(fixture.session) {
                                OverlayDropdownPreference(
                                    entry = DropdownEntry(LABELS.mapIndexed { index, text ->
                                        DropdownItem(text = text, enabled = index != 2, selected = fixture.selected == index,
                                            onClick = { fixture.callbacks += index; fixture.selected = index })
                                    }),
                                    title = "Hold selection fixture",
                                    modifier = Modifier.widthIn(max = 360.dp).testTag(ROW_TAG),
                                    showValue = false,
                                    onExpandedChange = { fixture.expanded = it },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (windowHost) {
            Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                CompositionLocalProvider(LocalAppSheetContent provides true) { content() }
            }
        } else content()
    }

    private companion object {
        const val ROW_TAG = "hold-dropdown-row"
        val LABELS = listOf("Committed alpha", "Candidate bravo", "Disabled charlie", "Candidate delta")
    }
}
