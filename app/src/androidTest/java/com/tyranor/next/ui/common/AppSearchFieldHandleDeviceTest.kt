@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.tyranor.next.ui.common

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.Handle
import androidx.compose.foundation.text.selection.SelectionHandleInfoKey
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.syncDialogWindow
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Checks actual handle Popups independently of the blinking cursor stroke.
 * The internal semantic probe is tied to Foundation 1.11.2; recheck it when upgrading Compose.
 */
class AppSearchFieldHandleDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enhancedDefaultInputKeepsItsHandlesWhileFocused() = checkHandles(true, false)

    @Test fun ordinaryDefaultInputKeepsItsHandlesWhileFocused() = checkHandles(false, false)

    @Test fun enhancedEmbeddedInputKeepsItsHandlesWhileFocused() = checkHandles(true, true)

    @Test fun ordinaryEmbeddedInputKeepsItsHandlesWhileFocused() = checkHandles(false, true)

    private fun checkHandles(enhanced: Boolean, embedded: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = compose.runOnIdle { MotionMode.enhanced }
        val originalAutoAdvance = compose.mainClock.autoAdvance
        val mounted = mutableStateOf(true)
        val query = mutableStateOf(TEXT)
        val window = AtomicReference<Window?>()
        val case = "${if (enhanced) "enhanced" else "ordinary"}-${if (embedded) "embedded" else "default"}"
        val context = instrumentation.targetContext
        val evidence = File(context.externalCacheDir ?: context.cacheDir,
            "search-field-handles/$case-${SystemClock.uptimeMillis()}")
        val samples = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val field = compose.onNode(hasSetTextAction(), useUnmergedTree = true)
        var originalFieldId: Int? = null
        var originalWindow: Window? = null

        fun nextFrame() {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            // Popup registration and Android placement must catch up with this virtual frame.
            SystemClock.sleep(16)
            instrumentation.waitForIdleSync()
        }

        fun visibleHandles(handle: Handle) = compose.onAllNodes(handleMatcher(handle), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.config[SelectionHandleInfoKey].visible }

        fun selection(): TextRange = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

        fun sample(label: String, expected: List<Handle>, required: Boolean): Boolean {
            val node = field.fetchSemanticsNode()
            val focused = node.config.getOrNull(SemanticsProperties.Focused) == true
            val range = node.config[SemanticsProperties.TextSelectionRange]
            val handles = expected.associateWith { visibleHandles(it) }
            val present = handles.values.all { it.size == 1 }
            samples += "$label\t${compose.mainClock.currentTime}\t${SystemClock.uptimeMillis()}\t${node.id}\t$focused\t$range\t" +
                handles.entries.joinToString(";") { (handle, nodes) ->
                    "$handle=${nodes.map { "${it.id}:${it.config[SelectionHandleInfoKey]}" }}"
                }
            assertTrue("$case/$label: input lost focus; evidence=${evidence.absolutePath}", focused)
            originalFieldId?.let { assertEquals("The text field must not be recreated", it, node.id) }
            assertEquals("The gesture must not clear or rewrite the query", TEXT,
                node.config[SemanticsProperties.EditableText].text)
            compose.runOnIdle {
                assertEquals(TEXT, query.value)
                assertSame("The original Dialog must stay alive", originalWindow, window.get())
                assertTrue(checkNotNull(window.get()).decorView.isAttachedToWindow)
            }
            if (required && !present) missing += "$label@${compose.mainClock.currentTime}: ${handles.mapValues { it.value.size }}"
            return present
        }

        fun positiveControl(label: String, handles: List<Handle>) {
            // The test cannot pass by never producing a handle. Only allow initial Popup placement
            // here; once it has really appeared, every following animation frame must retain it.
            var appeared = false
            repeat(12) {
                if (!appeared) {
                    nextFrame()
                    appeared = sample("$label-control-$it", handles, required = false)
                }
            }
            assertTrue("$case/$label: real touch did not produce $handles within 192 ms; evidence=${evidence.absolutePath}",
                appeared)
        }

        fun retainHandles(label: String, handles: List<Handle>, frames: Int) {
            repeat(frames) {
                nextFrame()
                sample("$label-$it", handles, required = true)
            }
        }

        fun saveFailure(failure: Throwable) {
            runCatching {
                evidence.mkdirs()
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                if (bitmap != null) {
                    try {
                        File(evidence, "failure.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            }.onFailure { failure.addSuppressed(it) }
        }

        try {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { MotionMode.sync(enhanced) }
            compose.setContent {
                TyranorNextTheme {
                    if (mounted.value) {
                        Dialog(
                            onDismissRequest = {},
                            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                        ) {
                            val currentWindow = syncDialogWindow { 1f }
                            SideEffect { window.set(currentWindow) }
                            Box(Modifier.fillMaxSize().background(Color.White)) {
                                AppSearchField(
                                    query = query.value,
                                    onQueryChange = { query.value = it },
                                    imeAction = if (embedded) ImeAction.Done else ImeAction.Search,
                                    iconContentDescription = "Search handle regression input",
                                    modifier = Modifier.align(Alignment.TopCenter)
                                        .windowInsetsPadding(WindowInsets.safeDrawing)
                                        .padding(24.dp).widthIn(max = 420.dp).fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                nextFrame()
                window.get()?.decorView?.isAttachedToWindow == true &&
                    compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            // Settle initial Android Dialog measurement before testing a new focus transition.
            repeat(4) { nextFrame() }
            originalFieldId = field.fetchSemanticsNode().id
            originalWindow = checkNotNull(window.get())
            assertFalse("The fixture must start unfocused",
                field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)

            // Use a real touch inside nonempty text. RequestFocus/SetSelection would not exercise
            // insertion-handle creation or its visible-bounds updates during the focus animation.
            field.performTouchInput { click(Offset(center.x * 0.76f, center.y)) }
            positiveControl("focus", listOf(Handle.Cursor))
            retainHandles("focus", listOf(Handle.Cursor), frames = 64)
            val firstCursor = selection()
            assertTrue("A tap must place a collapsed caret", firstCursor.collapsed)

            field.performTouchInput { click(Offset(center.x * 1.24f, center.y)) }
            positiveControl("move-cursor", listOf(Handle.Cursor))
            retainHandles("move-cursor", listOf(Handle.Cursor), frames = 16)
            val movedCursor = selection()
            assertTrue(movedCursor.collapsed)
            assertNotEquals("A second text tap must actually move the caret", firstCursor, movedCursor)

            field.performTouchInput { longClick(Offset(center.x * 0.76f, center.y)) }
            positiveControl("select-word", listOf(Handle.SelectionStart, Handle.SelectionEnd))
            retainHandles("select-word", listOf(Handle.SelectionStart, Handle.SelectionEnd), frames = 12)
            val beforeDrag = selection()
            assertFalse("Long press must select nonempty text", beforeDrag.collapsed)

            val dragDistance = 36f * context.resources.displayMetrics.density
            compose.onNode(handleMatcher(Handle.SelectionEnd), useUnmergedTree = true).performTouchInput {
                swipe(start = center, end = center + Offset(dragDistance, 0f), durationMillis = 240)
            }
            positiveControl("drag-selection", listOf(Handle.SelectionStart, Handle.SelectionEnd))
            retainHandles("drag-selection", listOf(Handle.SelectionStart, Handle.SelectionEnd), frames = 12)
            assertNotEquals("Dragging the real Popup handle must change selection", beforeDrag, selection())
            assertFalse(selection().collapsed)

            assertTrue("$case: a focused text field lost a real selection handle during animation: " +
                "${missing.take(12)} (${missing.size} missing frames); evidence=${evidence.absolutePath}", missing.isEmpty())
        } catch (failure: Throwable) {
            saveFailure(failure)
            throw failure
        } finally {
            try {
                evidence.mkdirs()
                File(evidence, "samples.tsv").writeText(
                    "label\tcomposeMillis\tuptimeMillis\tfieldId\tfocused\tselection\thandles\n" + samples.joinToString("\n") + "\n",
                )
            } finally {
                try {
                    compose.runOnIdle { mounted.value = false }
                    compose.mainClock.advanceTimeBy(32)
                    compose.waitForIdle()
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

    private fun handleMatcher(handle: Handle) = SemanticsMatcher("SelectionHandleInfo($handle)") {
        val info = it.config.getOrNull(SelectionHandleInfoKey)
        info?.handle == handle && info.visible
    }

    private companion object {
        const val TEXT = "alpha bravo charlie delta"
    }
}
