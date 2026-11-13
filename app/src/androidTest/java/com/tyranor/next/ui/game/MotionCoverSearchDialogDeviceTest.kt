package com.tyranor.next.ui.game

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.cover.persistentCoverDir
import com.tyranor.next.core.cover.stableKey
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises real search-window cleanup using empty searches and a restored private cache fixture. */
class MotionCoverSearchDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** Ordinary mode keeps the matching platform window policy and clears dim on close and disposal. */
    @Test
    fun ordinarySearchPreservesPlatformExitAndClearsWindowOnEveryRemoval() = verifyWindow(enhanced = false)

    /** Enhanced mode keeps its existing zero-animation policy and performs the same ownership cleanup. */
    @Test
    fun enhancedSearchRetainsWindowPolicyAndClearsWindowOnEveryRemoval() = verifyWindow(enhanced = true)

    /** A successful ordinary-mode binding releases its live window before the update callback runs. */
    @Test
    fun ordinarySuccessfulBindingReleasesWindowBeforeDeliveringUpdate() = verifySuccessfulBinding(enhanced = false)

    /** Enhanced mode follows the same binding callback order without changing its window policy. */
    @Test
    fun enhancedSuccessfulBindingReleasesWindowBeforeDeliveringUpdate() = verifySuccessfulBinding(enhanced = true)

    /** Restores a real candidate through its production saver, then binds a pre-existing JPEG cache entry. */
    private fun verifySuccessfulBinding(enhanced: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = AppSettingsStore.COVER_SOURCE_VNDB
        val sourceEnabled = AppSettingsStore.isCoverScraperSourceEnabled(context, source)
        val id = UUID.randomUUID().toString()
        val game = fixtureGame.copy(uri = "content://motion-review-fixture/cover-binding/$id")
        val candidateTitle = "Restored motion cover $id"
        val downloadUrl = "https://fixture.invalid/$id.jpg"
        val bindPrefix = "${source}_${stableKey(game.uri)}"
        val cacheFile = File(persistentCoverDir(context), "${bindPrefix}_${stableKey(downloadUrl)}.jpg")
        val expectedCover = Uri.fromFile(cacheFile).toString()
        val encoded = JSONObject()
            .put("source", source)
            .put("id", id)
            .put("title", candidateTitle)
            .put("subtitle", "Private cached fixture")
            .put("detail", "")
            // An empty preview takes the real no-preview branch, so no preview request can use the network.
            .put("coverUrl", "")
            .put("downloadUrl", downloadUrl)
            .toString()
        var previousMode = true
        instrumentation.runOnMainSync { previousMode = MotionMode.enhanced }
        var mounted by mutableStateOf(true)
        var registry = RecordingSaveableRegistry()
        var bindingWindow: Window? = null
        val updates = AtomicInteger()
        var delivered: ScanGame? = null
        var contentInstalled = false
        var ownsCacheFile = false
        try {
            assertTrue(cacheFile.parentFile!!.isDirectory || cacheFile.parentFile!!.mkdirs())
            assertTrue("The unique fixture must not overwrite an existing cover", cacheFile.createNewFile())
            ownsCacheFile = true
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.WHITE)
                cacheFile.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            assertTrue(cacheFile.isFile && cacheFile.length() > 0)
            instrumentation.runOnMainSync {
                MotionMode.sync(enhanced)
                if (!sourceEnabled) AppSettingsStore.setCoverScraperSourceEnabled(context, source, true)
            }
            contentInstalled = true
            compose.setContent {
                if (mounted) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        TyranorNextTheme {
                            GameActionsSheet(
                                game = game,
                                onDismiss = { mounted = false },
                                onGameUpdated = { updated ->
                                    val window = requireNotNull(bindingWindow)
                                    assertTrue("Disposal must not hide a missing beforeClose call", window.decorView.isAttachedToWindow)
                                    assertReleased(window)
                                    assertEquals(expectedCover, updated.coverUri)
                                    assertEquals(source, updated.coverSource)
                                    delivered = updated
                                    updates.incrementAndGet()
                                },
                                onEngineSettings = { error("This fixture must not navigate") },
                            )
                        }
                    }
                }
            }
            val closeLabel = context.getString(R.string.common_close)
            openSearch(context.getString(R.string.game_search_cover), closeLabel)
            val oldWindow = requireNotNull(findDialogWindow())
            var candidateKey = ""
            val restoredCandidates = mutableStateOf(arrayListOf(encoded))
            var saved: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle {
                val providers = registry.valuesInRegistrationOrder()
                val keywordIndex = providers.indices.single { (providers[it].value as? State<*>)?.value == game.title }
                val searchState = providers.drop(keywordIndex).take(4)
                assertEquals("The production dialog declares keyword, noMatch, error and candidates in order", 4, searchState.size)
                assertEquals(false, (searchState[1].value as? State<*>)?.value)
                // The nullable error code uses the default MutableState saver. The wrapper exists
                // even when its value is null; the empty candidate list below uses a listSaver.
                assertNull((searchState[2].value as State<*>).value)
                assertNull("The actual empty-list saver omits candidates", searchState[3].value)
                candidateKey = searchState[3].key
                val slot = providers.take(keywordIndex + 3).count { it.key == candidateKey }
                saved = registry.performSave().toMutableMap().also { values ->
                    // Compose allows multiple providers under one key and restores their values in order.
                    val slots = values[candidateKey]?.toMutableList() ?: mutableListOf<Any?>(null)
                    assertNull(slots[slot])
                    slots[slot] = restoredCandidates
                    values[candidateKey] = slots
                }
                mounted = false
            }
            compose.waitForIdle()
            compose.runOnIdle {
                registry = RecordingSaveableRegistry(saved)
                mounted = true
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(candidateTitle).fetchSemanticsNodes().size == 1
            }
            compose.runOnIdle {
                assertEquals("The real saver must consume the restored candidate state", 1,
                    registry.consumed.count { (key, value) -> key == candidateKey && value === restoredCandidates })
                bindingWindow = requireNotNull(findDialogWindow())
                assertNotSame(oldWindow, bindingWindow)
                val window = requireNotNull(bindingWindow)
                assertTrue(window.decorView.isAttachedToWindow)
                assertTrue("The window must be touchable before binding", window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
                // A settled bottom sheet already draws the shared mask. Search legitimately
                // requests zero native dim above it, while retaining ownership until release.
                assertTrue("The live window must still submit its coordinated dim before binding",
                    window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            }
            compose.onNodeWithText(candidateTitle).performClick()
            compose.waitUntil(timeoutMillis = 10_000) { updates.get() > 0 }
            compose.runOnIdle {
                assertEquals("Binding must deliver exactly one update", 1, updates.get())
                assertEquals(game.copy(coverUri = expectedCover, coverSource = source), delivered)
            }
        } finally {
            try {
                if (contentInstalled) {
                    compose.runOnIdle { mounted = false }
                    compose.waitForIdle()
                }
            } finally {
                try {
                    instrumentation.runOnMainSync {
                        try {
                            if (!sourceEnabled) AppSettingsStore.setCoverScraperSourceEnabled(context, source, false)
                        } finally {
                            MotionMode.sync(previousMode)
                        }
                    }
                } finally {
                    if (ownsCacheFile) {
                        assertTrue("Only the known private cache fixture is removed", !cacheFile.exists() || cacheFile.delete())
                    }
                }
            }
        }
    }

    /** Compares a matching fullscreen platform reference, then closes and externally removes the real UI. */
    private fun verifyWindow(enhanced: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = AppSettingsStore.COVER_SOURCE_VNDB
        val sourceEnabled = AppSettingsStore.isCoverScraperSourceEnabled(context, source)
        var previousMode = true
        var reference by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var referenceWindow: Window? = null
        var originalAnimations = 0
        try {
            instrumentation.runOnMainSync {
                previousMode = MotionMode.enhanced
                MotionMode.sync(enhanced)
                AppSettingsStore.setCoverScraperSourceEnabled(context, source, true)
            }
            compose.setContent {
                TyranorNextTheme {
                    if (reference) {
                        Dialog(
                            onDismissRequest = {},
                            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                        ) {
                            val window = (LocalView.current.parent as DialogWindowProvider).window
                            SideEffect {
                                referenceWindow = window
                                originalAnimations = window.attributes.windowAnimations
                            }
                            Text("Platform search-window reference")
                        }
                    } else if (mounted) {
                        GameActionsSheet(
                            game = fixtureGame,
                            onDismiss = { mounted = false },
                            onGameUpdated = {},
                            onEngineSettings = {},
                        )
                    }
                }
            }
            compose.runOnIdle {
                requireNotNull(referenceWindow).setWindowAnimations(0)
                reference = false
            }
            val searchLabel = context.getString(R.string.game_search_cover)
            val closeLabel = context.getString(R.string.common_close)
            openSearch(searchLabel, closeLabel)
            val searchWindow = requireNotNull(findDialogWindow())
            compose.runOnIdle {
                assertEquals(if (enhanced) 0 else originalAnimations, searchWindow.attributes.windowAnimations)
            }
            val close = requireNotNull(
                compose.onNodeWithText(closeLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action,
            )
            compose.runOnIdle {
                close()
                // Enhanced mode hands off touch at once, then fades the window dim with the card.
                assertTrue(searchWindow.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                if (!enhanced) assertReleased(searchWindow)
            }
            compose.waitForIdle()
            compose.runOnIdle { assertReleased(searchWindow) }
            openSearch(searchLabel, closeLabel)
            val removedWindow = requireNotNull(findDialogWindow())
            compose.runOnIdle { mounted = false }
            compose.runOnIdle { assertReleased(removedWindow) }
        } finally {
            instrumentation.runOnMainSync {
                AppSettingsStore.setCoverScraperSourceEnabled(context, source, sourceEnabled)
                MotionMode.sync(previousMode)
            }
        }
    }

    /** Uses real UI entry points without invoking the dialog's network-backed search action. */
    private fun openSearch(searchLabel: String, closeLabel: String) {
        compose.onNodeWithText(searchLabel).performClick()
        compose.onNodeWithText("VNDB").performClick()
        assertTrue("The real search panel must draw", compose.onNodeWithText(closeLabel).captureToImage().width > 0)
    }

    /** Checks cleanup on the captured window so a later replacement cannot mask an ownership error. */
    private fun assertReleased(window: Window) {
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(0f, window.attributes.dimAmount, 0f)
    }

    /** Finds an attached Compose dialog within this explicitly gated instrumentation process. */
    private fun findDialogWindow(): Window? = WindowInspector.getGlobalWindowViews().asSequence()
        .filter { it.isAttachedToWindow }
        .flatMap { descendants(it) }
        .filterIsInstance<DialogWindowProvider>()
        .map { it.window }
        .lastOrNull()

    /** Traverses nested view groups while retaining their owning window references. */
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private val fixtureGame = ScanGame(
        title = "Motion cover-window fixture",
        uri = "content://motion-review-fixture/cover-window",
        engine = EngineType.UNKNOWN,
        launchTarget = "",
    )

    /** Observes real saver registrations while delegating all saving and restoration to Compose. */
    private class RecordingSaveableRegistry(restoredValues: Map<String, List<Any?>>? = null) : SaveableStateRegistry {
        private val delegate = SaveableStateRegistry(restoredValues, canBeSaved = { true })
        private val providers = ArrayList<Pair<String, () -> Any?>>()
        val consumed = ArrayList<Pair<String, Any>>()

        override fun consumeRestored(key: String): Any? = delegate.consumeRestored(key).also {
            if (it != null) consumed += key to it
        }

        override fun registerProvider(key: String, valueProvider: () -> Any?): SaveableStateRegistry.Entry {
            val registration = key to valueProvider
            val entry = delegate.registerProvider(key, valueProvider)
            providers += registration
            return object : SaveableStateRegistry.Entry {
                override fun unregister() {
                    providers.remove(registration)
                    entry.unregister()
                }
            }
        }

        override fun canBeSaved(value: Any): Boolean = delegate.canBeSaved(value)

        override fun performSave(): Map<String, List<Any?>> = delegate.performSave()

        /** Includes null-returning providers, which performSave deliberately omits for empty list savers. */
        fun valuesInRegistrationOrder(): List<ProviderValue> = providers.map { (key, save) -> ProviderValue(key, save()) }
    }

    private data class ProviderValue(val key: String, val value: Any?)
}
