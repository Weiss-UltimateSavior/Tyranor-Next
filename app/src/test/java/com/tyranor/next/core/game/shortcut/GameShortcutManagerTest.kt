package com.tyranor.next.core.game.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GameShortcutManagerTest {
    @Test
    fun shortcutId_isStableAndDoesNotExposeGameUri() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AGames%2FExample"

        val first = GameShortcutManager.shortcutId(uri)
        val second = GameShortcutManager.shortcutId(uri)

        assertEquals(first, second)
        assertFalse(first.contains("Games"))
        assertEquals(69, first.length)
    }

    @Test
    fun shortcutId_distinguishesDifferentGames() {
        assertNotEquals(
            GameShortcutManager.shortcutId("content://games/one"),
            GameShortcutManager.shortcutId("content://games/two"),
        )
    }
}
