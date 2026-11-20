package com.tyranor.next.core.game.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsResolver
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineSettingsPersistenceDeviceTest {
    @Test
    fun concurrentFieldWritesPreserveEveryOverrideAndResolveWebNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val game = ScanGame("Settings fixture", "file:///settings-test/${UUID.randomUUID()}", EngineType.TYRANO, "")
        val global = EngineSettingsStore.isTyranoExternalNetwork(context)
        val start = CompletableDeferred<Unit>()
        try {
            GameOverridesRepository.ensureSynced(context)
            val writes = (1..12).map { field ->
                async(Dispatchers.IO) {
                    start.await()
                    PerGameSettingsStore.setStr(context, game.uri, "fixture_$field", field.toString())
                }
            }
            start.complete(Unit)
            PerGameSettingsStore.setBool(context, game.uri, PerGameSettingsStore.F_TY_EXTERNAL, !global)
            writes.awaitAll()
            assertEquals(!global, EngineSettingsResolver.resolve(context, game, null).webExternalNetwork)
            GameLibraryRepository.awaitPendingWrites()
            val dao = GameLibraryDatabase.get(context).gameLibraryDao()
            val saved = GameOverridePartitions.assemble(requireNotNull(dao.getOverrideRow(game.uri)))
            for (field in 1..12) assertEquals(field.toString(), saved.getString("fixture_$field"))
            assertEquals(!global, saved.getBoolean(PerGameSettingsStore.F_TY_EXTERNAL))

            PerGameSettingsStore.setBool(context, game.uri, PerGameSettingsStore.F_TY_EXTERNAL, null)
            assertEquals(global, EngineSettingsResolver.resolve(context, game, null).webExternalNetwork)
        } finally {
            PerGameSettingsStore.clear(context, game.uri)
            GameLibraryRepository.awaitPendingWrites()
        }
    }
}
