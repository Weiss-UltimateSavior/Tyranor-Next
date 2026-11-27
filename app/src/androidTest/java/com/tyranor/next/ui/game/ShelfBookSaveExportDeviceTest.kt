package com.tyranor.next.ui.game

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShelfBookSaveExportDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val cleanup = mutableListOf<File>()

    @After
    fun cleanUp() {
        cleanup.asReversed().forEach { it.deleteRecursively() }
    }

    @Test
    fun standardExportFromLiveBookIsDeliveredOnce() = verifyStandardExport(restore = false)

    @Test
    fun standardExportFromRestoredBookIsDeliveredOnce() = verifyStandardExport(restore = true)

    private fun verifyStandardExport(restore: Boolean) {
        MotionDeviceAcceptance.require(enhanced = null)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "book-export-").toFile()
        cleanup.add(root)
        val game = createGame(root, "Original")
        var picker by mutableStateOf(DocumentPicker())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                TyranorNextTheme {
                    key(game.uri) {
                        ShelfOpenBook(
                            game = game.copy(),
                            stage = remember { ShelfBookStage() },
                            quickLaunched = false,
                            onDismiss = { error("Export must not close the book") },
                            onGameUpdated = {},
                            onGameCoverUpdated = { _, _ -> },
                            onGameLaunchFileUpdated = null,
                            onGameBindingUpdated = { _, _ -> },
                            onGameRenamed = null,
                            onDeleteGame = {},
                            onQuickLaunchToggle = { false },
                        )
                    }
                }
            }
        }
        button(R.string.game_save_management).performScrollTo().performClick()
        val export = hasText(context.getString(R.string.save_export_zip)) and hasClickAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(export)
        compose.waitUntil(5_000) { compose.onAllNodes(export and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(export).performClick()
        button(R.string.save_export_format_standard).performClick()
        val archive = File(root, "standard.zip")
        val duplicate = File(root, "duplicate.zip")
        val requestCode = compose.runOnIdle { picker.requests.single() }
        val savedRegistry = compose.runOnIdle { Bundle().also(picker.activityResultRegistry::onSaveInstanceState) }
        val resultPicker = if (restore) DocumentPicker(savedRegistry) else picker
        compose.runOnIdle { assertTrue(resultPicker.deliver(requestCode, archive)) }
        if (restore) {
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { picker = resultPicker }
        }
        compose.waitUntil(5_000) {
            runCatching {
                ZipFile(archive).use { zip ->
                    val entry = zip.getEntry("file1.rpgsave") ?: return@use false
                    zip.size() == 1 && zip.getInputStream(entry).bufferedReader().use { it.readText() } == game.title
                }
            }.getOrDefault(false)
        }
        compose.runOnIdle {
            assertEquals(if (restore) 0 else 1, picker.requests.size)
            picker.deliver(requestCode, duplicate)
        }
        val close = hasText(context.getString(R.string.common_close)) and hasClickAction() and isEnabled()
        compose.waitUntil(5_000) { compose.onAllNodes(close).fetchSemanticsNodes().isNotEmpty() }

        val replayPicker = DocumentPicker(savedRegistry)
        compose.runOnIdle { assertTrue(replayPicker.deliver(requestCode, duplicate)) }
        compose.runOnIdle { picker = replayPicker }
        compose.waitForIdle()
        compose.onNode(close).performClick()
        compose.onNode(close).assertDoesNotExist()
        button(R.string.save_export_zip).assertDoesNotExist()
        button(R.string.game_save_management).assertExists()
        assertFalse(duplicate.exists())
    }

    private fun createGame(root: File, title: String): ScanGame {
        val directory = Files.createDirectory(root.toPath().resolve(root.name)).toFile()
        val game = ScanGame(title, Uri.fromFile(directory).toString(), EngineType.RPG_MV, "index.html")
        val saves = checkNotNull(GameSaveManager(context).resolveSaveLocation(game).directory)
        Files.createDirectories(checkNotNull(saves.toPath().parent))
        Files.createDirectory(saves.toPath())
        if (!saves.toPath().startsWith(root.toPath())) cleanup.add(saves)
        File(saves, "RPG File1.bin").writeText(title)
        return game
    }

    private fun button(id: Int) = compose.onNodeWithText(context.getString(id))

    private class DocumentPicker(savedState: Bundle? = null) : ActivityResultRegistryOwner {
        val requests = mutableListOf<Int>()

        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                assertTrue(contract is ActivityResultContracts.CreateDocument)
                requests.add(requestCode)
            }
        }

        init {
            activityResultRegistry.onRestoreInstanceState(savedState?.let(::Bundle))
        }

        fun deliver(requestCode: Int, destination: File): Boolean =
            activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))
    }
}
