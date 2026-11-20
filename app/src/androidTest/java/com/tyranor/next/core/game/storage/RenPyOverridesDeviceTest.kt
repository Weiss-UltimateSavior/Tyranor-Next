package com.tyranor.next.core.game.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsResolver
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.core.settings.RenPyOverride
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenPyOverridesDeviceTest {
    @Test
    fun launchReadsLatestOverridesWhileOlderWriteFinishes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val game = ScanGame(
            title = "Ren'Py settings fixture",
            uri = "file:///renpy-settings-test/${UUID.randomUUID()}",
            engine = EngineType.RENPY,
            launchTarget = "",
        )
        val global = EngineSettingsStore.loadRenPy(context)
        val newOverrides = RenPyOverride(hwVideo = false, vsync = true, dontUseGl2 = true)
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldCommitted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        try {
            GameOverridesRepository.ensureSynced(context)
            PerGameSettingsStore.setBool(context, game.uri, PerGameSettingsStore.F_INPUT_GAMEPAD_ENABLED, true)
            GameLibraryRepository.post(context) {
                oldStarted.complete(Unit)
                withTimeout(10_000) { releaseOld.await() }
            }
            withTimeout(10_000) { oldStarted.await() }
            PerGameSettingsStore.setRenPyOverrides(
                context, game.uri, EngineSettingsStore.RENPY_77, RenPyOverride(hwVideo = true),
            )
            GameLibraryRepository.post(context) {
                oldCommitted.complete(Unit)
                withTimeout(10_000) { releaseNew.await() }
            }
            PerGameSettingsStore.setRenPyOverrides(context, game.uri, EngineSettingsStore.RENPY_853, newOverrides)
            releaseOld.complete(Unit)
            withTimeout(10_000) { oldCommitted.await() }

            val settings = EngineSettingsResolver.resolve(context, game, null)
            assertEquals(EngineSettingsStore.RENPY_853, settings.renpyVersion)
            assertEquals(global.copy(hwVideo = false, vsync = true, dontUseGl2 = true), settings.renpy)

            releaseNew.complete(Unit)
            GameLibraryRepository.awaitPendingWrites()
            val dao = GameLibraryDatabase.get(context).gameLibraryDao()
            val saved = GameOverridePartitions.assemble(requireNotNull(dao.getOverrideRow(game.uri)))
            assertEquals(newOverrides, PerGameSettingsStore.toRenPyOverride(saved))
            assertEquals(true, saved.getBoolean(PerGameSettingsStore.F_INPUT_GAMEPAD_ENABLED))

            PerGameSettingsStore.setRenPyOverrides(context, game.uri, null, RenPyOverride())
            GameLibraryRepository.awaitPendingWrites()
            assertEquals(null, requireNotNull(dao.getOverrideRow(game.uri)).renpyJson)
            val inherited = EngineSettingsResolver.resolve(context, game, null)
            assertEquals(EngineSettingsStore.getRenpyVersion(context), inherited.renpyVersion)
            assertEquals(global, inherited.renpy)
        } finally {
            releaseOld.complete(Unit)
            releaseNew.complete(Unit)
            PerGameSettingsStore.clear(context, game.uri)
            GameLibraryRepository.awaitPendingWrites()
        }
    }
}
