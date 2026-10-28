package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveDirectoryIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun overlappingSafRootsShareWriterAdmissionButKeepSeparateHistoryKeys() = runBlocking {
        val root = temp.newFolder("Demo")
        val parentRoot = game("content://storage/tree/primary%3AGames/document/primary%3AGames%2FDemo")
        val childRoot = game("content://storage/tree/primary%3AGames%2FDemo/document/primary%3AGames%2FDemo")
        val unrelated = game("content://storage/tree/other")
        fun directory(game: ScanGame) = when (game.uri) {
            parentRoot.uri -> root
            childRoot.uri -> File(root, "../Demo")
            else -> File(temp.root, "other")
        }
        val related = relatedSaveUris(childRoot, listOf(parentRoot, childRoot, unrelated), ::directory)
        assertEquals(setOf(parentRoot.uri, childRoot.uri), related)
        val firstKey = saveDirectoryIdentity(parentRoot, ::directory)
        val secondKey = saveDirectoryIdentity(childRoot, ::directory)
        assertEquals(firstKey, secondKey)
        assertFalse(SaveContentHash.text(parentRoot.uri) == SaveContentHash.text(childRoot.uri))

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            SaveSyncLocks.active(firstKey) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val secondEntered = CompletableDeferred<Unit>()
        val second = async {
            SaveSyncLocks.active(secondKey) { secondEntered.complete(Unit) }
        }
        kotlinx.coroutines.yield()
        assertFalse(secondEntered.isCompleted)
        release.complete(Unit)
        withTimeout(5_000) { first.await(); second.await() }
        Unit
    }

    private fun game(uri: String) = ScanGame("Demo", uri, EngineType.ONS, "__game_dir__")
}
