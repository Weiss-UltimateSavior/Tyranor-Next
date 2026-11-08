package com.tyranor.next.core.settings

import android.app.OnboardingActivityManager
import android.content.SharedPreferences
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.util.Log
import androidx.room.InvalidationTracker
import com.core.engine.EnginePrefs
import com.tyranor.next.core.game.storage.GameLibraryDao
import com.tyranor.next.core.game.storage.GameLibraryDatabase
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.game.storage.GameOverrideEntity
import com.tyranor.next.core.game.storage.GameOverridePartitions
import com.tyranor.next.core.game.storage.GameOverridesRepository
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnboardingPreferencesTest {
    private var processFixture: AutoCloseable? = null
    private var loggingFixture: AutoCloseable? = null

    @Before
    fun resetAppSettingsOnboardingCache() {
        processFixture = Process.fixtureScope(37_001)
        loggingFixture = Log.fixtureScope()
        AppSettingsStore.resetOnboardingForTests()
    }

    @After
    fun restorePlatformFixtures() {
        try {
            AppSettingsStore.resetOnboardingForTests()
        } finally {
            loggingFixture?.close()
            processFixture?.close()
        }
    }

    @Test
    fun `application initialization uses persisted install evidence`() {
        val appSettings = MemoryPreferences()
        val legacySettings = MemoryPreferences(mapOf("legacy_key" to true))
        val context = OnboardingContext(
            appSettings = appSettings.preferences,
            stores = mapOf("tyranor_prefs" to legacySettings.preferences),
            gameLibrary = File("missing-game-library.db"),
        )
        assertEquals(OnboardingWriteResult.SAVED, AppSettingsStore.initializeOnboarding(context))
        assertTrue(AppSettingsStore.isOnboardingCompleted(context))
    }

    @Test
    fun `application initialization keeps a fresh install in onboarding`() {
        val context = OnboardingContext(
            appSettings = MemoryPreferences().preferences,
            stores = emptyMap(),
            gameLibrary = File("missing-game-library.db"),
        )
        assertEquals(OnboardingWriteResult.SAVED, AppSettingsStore.initializeOnboarding(context))
        assertFalse(AppSettingsStore.isOnboardingCompleted(context))
    }

    @Test
    fun `application initialization predicate requires any persisted evidence`() {
        assertTrue(AppSettingsStore.hasExistingOnboardingData(true, false, false))
        assertTrue(AppSettingsStore.hasExistingOnboardingData(false, true, false))
        assertTrue(AppSettingsStore.hasExistingOnboardingData(false, false, true))
        assertFalse(AppSettingsStore.hasExistingOnboardingData(false, false, false))
    }

    @Test
    fun `fresh install remains incomplete after initialization and a later restart`() {
        val storage = MemoryPreferences()
        val state = OnboardingPreferences(storage.preferences)
        assertFalse(state.isCompleted())
        assertEquals(OnboardingWriteResult.SAVED, state.initialize { false })
        val restarted = OnboardingPreferences(MemoryPreferences(storage.disk).preferences)
        restarted.initialize { error("New migration data must not override explicit false") }
        assertFalse(restarted.isCompleted())
    }

    @Test
    fun `existing data skips onboarding even without games`() {
        val state = OnboardingPreferences(MemoryPreferences().preferences)
        assertEquals(OnboardingWriteResult.SAVED, state.initialize { true })
        assertTrue(state.isCompleted())
    }

    @Test
    fun `restored explicit states take priority over legacy data`() {
        for (completed in listOf(false, true)) {
            val storage = MemoryPreferences(mapOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED to completed))
            val state = OnboardingPreferences(storage.preferences)
            state.initialize { error("Existing marker must be authoritative") }
            assertEquals(completed, state.isCompleted())
            assertEquals(0, storage.commits)
        }
    }

    @Test
    fun `persisted marker wins over a stale pending snapshot`() {
        val pending = Files.createTempFile("onboarding-stale-state", ".tmp").toFile()
        try {
            pending.writeText("0")
            val storage = MemoryPreferences(mapOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED to true))
            val state = OnboardingPreferences(storage.preferences, pending)
            assertTrue(state.isCompleted())
            assertFalse(pending.exists())
        } finally {
            pending.delete()
        }
    }

    @Test
    fun `completion survives a new preferences instance and repeated completion is harmless`() {
        val storage = MemoryPreferences()
        val state = OnboardingPreferences(storage.preferences)
        state.initialize { false }
        assertEquals(OnboardingWriteResult.SAVED, state.complete())
        val writes = storage.commits
        assertEquals(OnboardingWriteResult.SAVED, state.complete())
        assertEquals(writes, storage.commits)
        assertTrue(OnboardingPreferences(MemoryPreferences(storage.disk).preferences).isCompleted())
    }

    @Test
    fun `failed initial write can retry without reclassifying a new install`() {
        val storage = MemoryPreferences().apply { writeSucceeds = false }
        val state = OnboardingPreferences(storage.preferences)
        assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, state.initialize { false })
        assertFalse(state.isCompleted())
        storage.writeSucceeds = true
        assertEquals(OnboardingWriteResult.SAVED, state.initialize { error("Must reuse the initial snapshot") })
        assertFalse(state.isCompleted())
    }

    @Test
    fun `failed initial write survives process restart without reclassifying install`() {
        val pending = Files.createTempFile("onboarding-initial-state", ".tmp").toFile().apply { delete() }
        try {
            val storage = MemoryPreferences().apply { writeSucceeds = false }
            val state = OnboardingPreferences(storage.preferences, pending)
            assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, state.initialize { false })
            assertFalse(state.isCompleted())
            assertTrue(pending.isFile)

            val restarted = OnboardingPreferences(MemoryPreferences(storage.disk).preferences, pending)
            assertEquals(
                OnboardingWriteResult.SAVED,
                restarted.initialize { true },
            )
            assertFalse(restarted.isCompleted())
            assertFalse(pending.exists())
        } finally {
            pending.delete()
        }
    }

    @Test
    fun `failed initialization keeps fresh install after restart with infrastructure data`() {
        val gameLibrary = Files.createTempFile("onboarding-game-library", ".db").toFile()
        gameLibrary.delete()
        val appSettings = MemoryPreferences().apply { writeSucceeds = false }
        val infrastructure = MemoryPreferences(mapOf("prefs_rename_migrated_v1" to true))
        val scanner = MemoryPreferences(mapOf("storage_migration_v1_done" to true))
        val firstContext = OnboardingContext(
            appSettings = appSettings.preferences,
            stores = emptyMap(),
            gameLibrary = gameLibrary,
        )
        val pending = firstContext.filesDir.resolve("onboarding_initial_state")
        pending.delete()
        try {
            assertEquals(
                OnboardingWriteResult.STORAGE_UNAVAILABLE,
                AppSettingsStore.initializeOnboarding(firstContext),
            )
            assertFalse(AppSettingsStore.isOnboardingCompleted(firstContext))

            gameLibrary.createNewFile()
            AppSettingsStore.resetOnboardingForTests()
            appSettings.writeSucceeds = true
            val restarted = OnboardingContext(
                appSettings = appSettings.preferences,
                stores = mapOf(
                    "tyranor_prefs" to infrastructure.preferences,
                    "game_scanner" to scanner.preferences,
                ),
                gameLibrary = gameLibrary,
            )
            assertEquals(OnboardingWriteResult.SAVED, AppSettingsStore.initializeOnboarding(restarted))
            assertFalse(AppSettingsStore.isOnboardingCompleted(restarted))
        } finally {
            AppSettingsStore.resetOnboardingForTests()
            pending.delete()
            gameLibrary.delete()
        }
    }

    @Test
    fun `double persistence failure defers migration until the initial decision can be saved`() {
        // A regular file where filesDir should be makes the independent snapshot unwritable.
        val blockedFiles = Files.createTempFile("onboarding-blocked-files", ".tmp").toFile()
        val database = Files.createTempFile("onboarding-deferred-library", ".db").toFile().apply { delete() }
        val settings = MemoryPreferences().apply { writeSucceeds = false }
        val infrastructure = MemoryPreferences()
        val context = OnboardingContext(
            appSettings = settings.preferences,
            stores = mapOf("tyranor_prefs" to infrastructure.preferences),
            gameLibrary = database,
            appFiles = blockedFiles,
        )
        try {
            assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, AppSettingsStore.initializeOnboarding(context))
            assertFalse(AppSettingsStore.prepareInstallationData(context))
            assertFalse(AppSettingsStore.markXs4RescanMigrationDone(context))
            PrefsRenameMigration.migrate(context)
            assertTrue(settings.disk.isEmpty())
            assertTrue(infrastructure.disk.isEmpty())

            val readers = listOf<(Context) -> List<*>>(
                GameLibraryFacade::loadGames,
                GameLibraryFacade::loadRecentGames,
                GameLibraryFacade::loadQuickLaunch,
                GameLibraryFacade::loadRoots,
            )
            for (read in readers) {
                assertTrue(read(context).isEmpty())
                val attempts = settings.commits
                assertTrue(read(context).isEmpty())
                assertTrue("An empty read must retry persistence instead of becoming cached", settings.commits > attempts)
            }
            val gameId = "content://onboarding-fixture/unavailable-game"
            val beforeOverrideRead = settings.commits
            assertNull(PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))
            assertNull(PerGameSettingsStore.getBool(context, gameId, PerGameSettingsStore.F_SCOPED_SAVE_DIR))
            assertFalse(PerGameSettingsStore.hasOverride(context, gameId))
            assertTrue("Unavailable override reads must retry without caching a missing row", settings.commits >= beforeOverrideRead + 3)
            assertThrows(IOException::class.java) { GameLibraryDatabase.get(context) }
            assertFalse("Reading an unavailable fresh library must not create upgrade evidence", database.exists())

            settings.writeSucceeds = true
            assertTrue(AppSettingsStore.markXs4RescanMigrationDone(context))
            assertEquals(false, settings.disk[AppSettingsStore.KEY_ONBOARDING_COMPLETED])
            PrefsRenameMigration.migrate(context)
            assertEquals(true, infrastructure.disk["prefs_rename_migrated_v1"])

            AppSettingsStore.resetOnboardingForTests()
            val restarted = OnboardingContext(
                appSettings = MemoryPreferences(settings.disk).preferences,
                stores = mapOf("tyranor_prefs" to MemoryPreferences(infrastructure.disk).preferences),
                gameLibrary = database,
                appFiles = blockedFiles,
            )
            assertEquals(OnboardingWriteResult.SAVED, AppSettingsStore.initializeOnboarding(restarted))
            assertFalse(AppSettingsStore.isOnboardingCompleted(restarted))
        } finally {
            AppSettingsStore.resetOnboardingForTests()
            blockedFiles.delete()
            database.delete()
        }
    }

    @Test
    fun `saved recovery snapshot permits infrastructure while marker writes remain unavailable`() {
        val files = Files.createTempDirectory("onboarding-recovery-gate").toFile()
        val settings = MemoryPreferences().apply { writeSucceeds = false }
        val context = OnboardingContext(
            appSettings = settings.preferences,
            stores = emptyMap(),
            gameLibrary = File("missing-game-library.db"),
            appFiles = files,
        )
        val pending = files.resolve("onboarding_initial_state")
        try {
            assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, AppSettingsStore.initializeOnboarding(context))
            assertTrue(pending.isFile)
            val commits = settings.commits
            assertTrue(AppSettingsStore.prepareInstallationData(context))
            assertEquals("The saved decision must allow the normal fast path", commits, settings.commits)
            assertFalse(AppSettingsStore.isOnboardingCompleted(context))
        } finally {
            AppSettingsStore.resetOnboardingForTests()
            pending.delete()
            files.delete()
        }
    }

    @Test
    fun `failed completion cannot discard an initial decision that was never persisted`() {
        val storage = MemoryPreferences().apply { writeSucceeds = false }
        val state = OnboardingPreferences(storage.preferences)
        assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, state.initialize { false })
        assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, state.complete())
        assertFalse(state.isCompleted())
        assertFalse(state.hasPersistedInitialState())
        storage.writeSucceeds = true
        assertEquals(OnboardingWriteResult.SAVED, state.initialize { error("Must retain the initial decision") })
        assertFalse(state.isCompleted())
    }

    @Test
    fun `scan depth stays editable and successful apply preserves the original install decision atomically`() {
        val blockedFiles = Files.createTempFile("onboarding-setting-blocked", ".tmp").toFile()
        val storage = MemoryPreferences().apply { writeSucceeds = false }
        val context = OnboardingContext(storage.preferences, emptyMap(), File("missing-game-library.db"), blockedFiles)
        try {
            assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, AppSettingsStore.initializeOnboarding(context))
            AppSettingsStore.setScanDepth(context, 2)
            assertEquals(2, AppSettingsStore.getScanDepth(context))
            assertTrue(storage.disk.isEmpty())

            // Model storage recovering after the separate synchronous marker retry has failed.
            storage.applyWritesSucceed = true
            AppSettingsStore.setScanDepth(context, 4)
            assertEquals(4, storage.disk[AppSettingsStore.KEY_SCAN_DEPTH])
            assertEquals(false, storage.disk[AppSettingsStore.KEY_ONBOARDING_COMPLETED])
            assertTrue(storage.writes.filter { AppSettingsStore.KEY_SCAN_DEPTH in it }
                .all { it[AppSettingsStore.KEY_ONBOARDING_COMPLETED] == false })

            AppSettingsStore.resetOnboardingForTests()
            val restarted = OnboardingContext(
                MemoryPreferences(storage.disk).preferences, emptyMap(), File("missing-game-library.db"), blockedFiles,
            )
            assertEquals(OnboardingWriteResult.SAVED, AppSettingsStore.initializeOnboarding(restarted))
            assertFalse(AppSettingsStore.isOnboardingCompleted(restarted))
            assertEquals(4, AppSettingsStore.getScanDepth(restarted))
        } finally {
            AppSettingsStore.resetOnboardingForTests()
            blockedFiles.delete()
        }
    }

    @Test
    fun `ordinary writes initialize the decision before settings become upgrade evidence`() {
        val blockedFiles = Files.createTempFile("onboarding-first-setting", ".tmp").toFile()
        val storage = MemoryPreferences().apply { writeSucceeds = false; applyWritesSucceed = true }
        val context = OnboardingContext(storage.preferences, emptyMap(), File("missing-game-library.db"), blockedFiles)
        val previousLanguage = AppSettingsStore.languageState.value
        val previousMotion = AppSettingsStore.motionEnhancedState.value
        try {
            AppSettingsStore.setLanguage(context, AppSettingsStore.LANGUAGE_EN)
            AppSettingsStore.setMotionEnhanced(context, false)
            AppSettingsStore.setThemeMode(context, AppSettingsStore.THEME_MODE_DARK)
            assertEquals(false, storage.disk[AppSettingsStore.KEY_ONBOARDING_COMPLETED])
            assertEquals(AppSettingsStore.LANGUAGE_EN, storage.disk[AppSettingsStore.KEY_LANGUAGE])
            assertEquals(false, storage.disk[AppSettingsStore.KEY_MOTION_ENHANCED])
            assertEquals(AppSettingsStore.THEME_MODE_DARK, storage.disk[AppSettingsStore.KEY_THEME_MODE])
            assertFalse(AppSettingsStore.isOnboardingCompleted(context))
        } finally {
            AppSettingsStore.resetOnboardingForTests()
            AppSettingsStore.languageState.value = previousLanguage
            AppSettingsStore.motionEnhancedState.value = previousMotion
            blockedFiles.delete()
        }
    }

    @Test
    fun `ordinary writes after completion cannot restore the old incomplete decision`() {
        val storage = MemoryPreferences().apply { writeSucceeds = false; applyWritesSucceed = true }
        val state = OnboardingPreferences(storage.preferences)
        assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, state.initialize { false })
        state.updateSettings { putInt(AppSettingsStore.KEY_SCAN_DEPTH, 4) }
        assertEquals(false, storage.disk[AppSettingsStore.KEY_ONBOARDING_COMPLETED])
        storage.writeSucceeds = true
        assertEquals(OnboardingWriteResult.SAVED, state.complete())
        state.updateSettings { putInt(AppSettingsStore.KEY_SCAN_DEPTH, 5) }
        assertEquals(true, storage.disk[AppSettingsStore.KEY_ONBOARDING_COMPLETED])
        assertEquals(5, storage.disk[AppSettingsStore.KEY_SCAN_DEPTH])
    }

    @Test
    fun `accepted overrides remain readable and cumulative after actual database writes fail`() = withUnavailableOverrides {
        val gameId = "content://onboarding-fixture/accepted-overrides"
        withPausedWrites {
            PerGameSettingsStore.setStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION, "1.3.4")
            assertEquals("1.3.4", PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))
            assertTrue(PerGameSettingsStore.hasOverride(context, gameId))
            PerGameSettingsStore.setBool(context, gameId, PerGameSettingsStore.F_SCOPED_SAVE_DIR, true)
            assertEquals("1.3.4", PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))
            assertEquals(true, PerGameSettingsStore.getBool(context, gameId, PerGameSettingsStore.F_SCOPED_SAVE_DIR))
        }

        assertEquals("Both actual post failures must run the production invalidation callback", 2, failures.get())
        assertFalse(hasCachedRow(gameId))
        assertEquals("1.3.4", PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))
        assertEquals(true, PerGameSettingsStore.getBool(context, gameId, PerGameSettingsStore.F_SCOPED_SAVE_DIR))
        assertFalse("A mirror read must not become an authoritative database cache entry", hasCachedRow(gameId))
        assertFalse(database.exists())
        assertTrue(settings.disk.isEmpty())
        assertFalse(AppSettingsStore.isOnboardingCompleted(context))
    }

    @Test
    fun `accepted clear tombstone wins over an old mirror and stays cleared after failure`() = withUnavailableOverrides {
        val gameId = "content://onboarding-fixture/cleared-overrides"
        overrides.preferences.edit().putString(gameId, "{\"engine_version\":\"old\"}").apply()
        withPausedWrites {
            GameOverridesRepository.clearRow(context, gameId)
            assertTrue("The old mirror still exists before the caller removes it", overrides.preferences.contains(gameId))
            assertTrue(hasCachedRow(gameId))
            assertFalse(PerGameSettingsStore.hasOverride(context, gameId))
            assertNull(PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))

            PerGameSettingsStore.clear(context, gameId)
            assertFalse(overrides.preferences.contains(gameId))
            assertFalse(PerGameSettingsStore.hasOverride(context, gameId))
        }
        assertEquals(2, failures.get())
        assertFalse(hasCachedRow(gameId))
        assertFalse(PerGameSettingsStore.hasOverride(context, gameId))
        assertFalse(database.exists())
    }

    @Test
    fun `missing and corrupt mirrors remain uncached retry installation and propagate cancellation`() = withUnavailableOverrides {
        val gameId = "content://onboarding-fixture/retry-overrides"
        for (raw in listOf(null, "{")) {
            if (raw == null) overrides.preferences.edit().remove(gameId).apply()
            else overrides.preferences.edit().putString(gameId, raw).apply()
            val attempts = settings.commits
            repeat(2) {
                assertNull(PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))
                assertFalse(PerGameSettingsStore.hasOverride(context, gameId))
                assertFalse(hasCachedRow(gameId))
            }
            assertTrue("Every unavailable read must retry installation persistence", settings.commits >= attempts + 4)
        }
        overrides.preferences.edit().putString(gameId, "{\"engine_version\":\"repaired\"}").apply()
        assertEquals("repaired", PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))
        assertFalse(hasCachedRow(gameId))
        overrides.afterStringRead = { throw IOException("Unavailable mirror read") }
        try {
            assertNull(PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))
            assertFalse(hasCachedRow(gameId))
        } finally {
            overrides.afterStringRead = null
        }
        overrides.afterStringRead = { throw CancellationException("Cancelled mirror read") }
        try {
            assertThrows(CancellationException::class.java) {
                PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION)
            }
        } finally {
            overrides.afterStringRead = null
        }
        assertFalse(database.exists())
        assertFalse(AppSettingsStore.isOnboardingCompleted(context))
    }

    @Test
    fun `mirror read retries when an accepted update clear or invalidation changes its generation`() = withUnavailableOverrides {
        val gameId = "content://onboarding-fixture/concurrent-overrides"
        val reader = Executors.newSingleThreadExecutor()
        try {
            withPausedWrites {
                for (operation in listOf("update", "clear", "invalidate")) {
                    GameOverridesRepository.invalidateRowCache()
                    overrides.preferences.edit().putString(gameId, "{\"engine_version\":\"old\"}").apply()
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val paused = AtomicBoolean()
                    overrides.afterStringRead = { key ->
                        if (key == gameId && paused.compareAndSet(false, true)) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "Timed out releasing the mirror read" }
                        }
                    }
                    try {
                        val result = reader.submit<String?> {
                            PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION)
                        }
                        assertTrue("The reader never captured the old mirror", entered.await(10, TimeUnit.SECONDS))
                        when (operation) {
                            "update" -> GameOverridesRepository.updateRecord(
                                context, gameId, JSONObject().put(PerGameSettingsStore.F_ENGINE_VERSION, "new"),
                            )
                            "clear" -> GameOverridesRepository.clearRow(context, gameId)
                            "invalidate" -> {
                                overrides.preferences.edit().putString(gameId, "{\"engine_version\":\"new\"}").apply()
                                GameOverridesRepository.invalidateRowCache()
                            }
                        }
                        release.countDown()
                        assertEquals(if (operation == "clear") null else "new", result.get(10, TimeUnit.SECONDS))
                    } finally {
                        release.countDown()
                        overrides.afterStringRead = null
                    }
                }
            }
        } finally {
            reader.shutdownNow()
            assertTrue("The owned mirror reader must stop before restoring repository state", reader.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertFalse(database.exists())
    }

    @Test
    fun `override mirror survives restart without skipping onboarding and imports after storage recovers`() = withUnavailableOverrides {
        val gameId = "content://onboarding-fixture/restarted-overrides"
        PerGameSettingsStore.setStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION, "1.3.4")
        drainWrites()
        PerGameSettingsStore.setBool(context, gameId, PerGameSettingsStore.F_SCOPED_SAVE_DIR, true)
        drainWrites()
        assertEquals(2, failures.get())
        restart()
        assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, AppSettingsStore.initializeOnboarding(context))
        assertFalse(AppSettingsStore.isOnboardingCompleted(context))
        assertEquals("1.3.4", PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))
        assertEquals(true, PerGameSettingsStore.getBool(context, gameId, PerGameSettingsStore.F_SCOPED_SAVE_DIR))
        assertFalse(database.exists())

        settings.writeSucceeds = true
        assertTrue(AppSettingsStore.prepareInstallationData(context))
        assertEquals(false, settings.disk[AppSettingsStore.KEY_ONBOARDING_COMPLETED])
        withRecoveredDatabase { rows ->
            runBlocking { GameOverridesRepository.ensureSynced(context) }
            val imported = GameOverridePartitions.assemble(checkNotNull(rows[gameId]))
            assertEquals("1.3.4", imported.getString(PerGameSettingsStore.F_ENGINE_VERSION))
            assertTrue(imported.getBoolean(PerGameSettingsStore.F_SCOPED_SAVE_DIR))
            assertEquals("1.3.4", PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_ENGINE_VERSION))
            assertEquals(true, PerGameSettingsStore.getBool(context, gameId, PerGameSettingsStore.F_SCOPED_SAVE_DIR))
            assertFalse(AppSettingsStore.isOnboardingCompleted(context))
        }
    }

    @Test
    fun `stale engine process never edits app settings or deletes the main recovery snapshot`() {
        val files = Files.createTempDirectory("onboarding-child-reader").toFile()
        val sharedDisk = mutableMapOf<String, Any>(AppSettingsStore.KEY_THEME_COLOR to "old-color")
        val child = MemoryPreferences(sharedDisk, sharedDisk)
        val main = MemoryPreferences(sharedDisk, sharedDisk)
        val engineSettings = MemoryPreferences()
        val mainContext = OnboardingContext(main.preferences, emptyMap(), File("missing-game-library.db"), files)
        val childContext = OnboardingContext(
            child.preferences,
            mapOf("tyranor_prefs" to engineSettings.preferences),
            File("missing-game-library.db"), files, "$PACKAGE:tyrano",
        )
        val pending = files.resolve("onboarding_initial_state")
        try {
            AppSettingsStore.initializeOnboarding(mainContext)
            AppSettingsStore.setThemeColorHex(mainContext, "new-color")
            pending.writeText("0")

            AppSettingsStore.resetOnboardingForTests() // A child has its own singleton and an old prefs map.
            assertEquals(OnboardingWriteResult.SAVED, AppSettingsStore.initializeOnboarding(childContext))
            assertTrue(AppSettingsStore.prepareInstallationData(childContext))
            assertFalse(AppSettingsStore.isOnboardingCompleted(childContext))
            AppSettingsStore.setScanDepth(childContext, 5)
            AppSettingsStore.setThemeColorHex(childContext, "stale-child-color")
            assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, AppSettingsStore.setOnboardingCompleted(childContext))
            assertFalse(AppSettingsStore.markXs4RescanMigrationDone(childContext))
            PrefsRenameMigration.migrate(childContext)

            assertEquals("new-color", sharedDisk[AppSettingsStore.KEY_THEME_COLOR])
            assertEquals(true, sharedDisk[AppSettingsStore.KEY_ONBOARDING_COMPLETED])
            assertEquals(0, child.edits)
            assertTrue("The main process owns recovery snapshot cleanup", pending.isFile)
            assertEquals(true, engineSettings.disk["prefs_rename_migrated_v1"])
        } finally {
            AppSettingsStore.resetOnboardingForTests()
            pending.delete()
            files.delete()
        }
    }

    @Test
    fun `failed upgrade marker keeps existing users out of onboarding and can be repaired`() {
        val storage = MemoryPreferences().apply { writeSucceeds = false }
        val state = OnboardingPreferences(storage.preferences)
        assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, state.initialize { true })
        assertTrue(state.isCompleted())
        storage.writeSucceeds = true
        assertEquals(OnboardingWriteResult.SAVED, state.complete())
        assertTrue(state.isCompleted())
    }

    @Test
    fun `failed completion restores memory before recreation or background observers read it`() {
        val storage = MemoryPreferences()
        val state = OnboardingPreferences(storage.preferences)
        state.initialize { false }
        storage.writeSucceeds = false
        assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, state.complete())
        assertFalse(state.isCompleted())
        assertFalse(OnboardingPreferences(storage.preferences).isCompleted())
        assertFalse(OnboardingPreferences(MemoryPreferences(storage.disk).preferences).isCompleted())
        storage.writeSucceeds = true
        assertEquals(OnboardingWriteResult.SAVED, state.complete())
        assertTrue(state.isCompleted())
    }

    @Test
    fun `clearing data restores the first launch flow`() {
        val oldStorage = MemoryPreferences()
        OnboardingPreferences(oldStorage.preferences).apply { initialize { false }; complete() }
        val cleared = OnboardingPreferences(MemoryPreferences().preferences)
        cleared.initialize { false }
        assertFalse(cleared.isCompleted())
    }

    private fun withUnavailableOverrides(test: OverrideFixture.() -> Unit) {
        val fixture = OverrideFixture()
        try {
            fixture.initialize()
            fixture.test()
        } finally {
            fixture.close()
        }
    }

    /** Owns the real post queue and snapshots globals while only installation persistence is blocked. */
    private class OverrideFixture : AutoCloseable {
        val database = Files.createTempFile("onboarding-overrides-library", ".db").toFile().apply { delete() }
        private val blockedFiles = Files.createTempFile("onboarding-overrides-files", ".tmp").toFile()
        var settings = MemoryPreferences().apply { writeSucceeds = false }
        var overrides = MemoryPreferences()
        val failures = AtomicInteger()
        val context: Context
            get() = OnboardingContext(
                settings.preferences,
                mapOf(EnginePrefs.GAME_OVERRIDES_PREFS to overrides.preferences),
                database,
                blockedFiles,
            )

        private val databaseField = GameLibraryDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        private val mirrorField = GameLibraryRepository::class.java.getDeclaredField("mirrorJob").apply { isAccessible = true }
        private val writer = (GameLibraryRepository::class.java.getDeclaredField("writeScope").apply {
            isAccessible = true
        }.get(GameLibraryRepository) as CoroutineScope).coroutineContext[Job]!!
        private val cacheLock = GameOverridesRepository::class.java.getDeclaredField("cacheLock").apply {
            isAccessible = true
        }.get(GameOverridesRepository)
        private val rowsField = GameOverridesRepository::class.java.getDeclaredField("rowCache").apply { isAccessible = true }
        private val generationField = GameOverridesRepository::class.java.getDeclaredField("cacheGeneration").apply { isAccessible = true }
        private val syncField = GameOverridesRepository::class.java.getDeclaredField("syncDone").apply { isAccessible = true }
        private val listenerField = GameLibraryFacade::class.java.getDeclaredField("persistFailureListenerInstalled").apply { isAccessible = true }
        private val facadeCaches = listOf("gamesCache", "recentGamesCache", "quickLaunchCache", "rootsCache").associate { name ->
            GameLibraryFacade::class.java.getDeclaredField(name).apply { isAccessible = true }.let { it to it.get(GameLibraryFacade) }
        }
        private val previousDatabase = databaseField.get(null)
        private val previousMirror = mirrorField.get(GameLibraryRepository)
        private val previousListener = GameLibraryRepository.onPersistFailure
        private val previousListenerInstalled = listenerField.getBoolean(GameLibraryFacade)
        private val previousRows = synchronized(cacheLock) { cachedRows().toMap() }
        private val previousGeneration = generationField.getLong(GameOverridesRepository)
        private val previousSync = syncField.getBoolean(GameOverridesRepository)
        private var started = false

        /** Installs the real failure listener while database creation is blocked by the install gate. */
        fun initialize() {
            check(writer.children.none()) { "Override fixture requires an idle repository" }
            started = true
            databaseField.set(null, null)
            GameOverridesRepository.invalidateRowCache()
            facadeCaches.keys.forEach { it.set(GameLibraryFacade, null) }
            listenerField.setBoolean(GameLibraryFacade, false)
            assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, AppSettingsStore.initializeOnboarding(context))
            // This real uncached entry installs the production failure callback without opening Room.
            assertTrue(GameLibraryFacade.loadRoots(context).isEmpty())
            val productionListener = checkNotNull(GameLibraryRepository.onPersistFailure)
            GameLibraryRepository.onPersistFailure = {
                productionListener()
                failures.incrementAndGet()
            }
        }

        /** Distinguishes an optimistic row/tombstone from an uncached mirror read. */
        fun hasCachedRow(gameId: String): Boolean = synchronized(cacheLock) { cachedRows().containsKey(gameId) }

        /** Holds the real FIFO so optimistic rows and tombstones can be inspected before persistence. */
        fun withPausedWrites(test: () -> Unit) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            GameLibraryRepository.post(context) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Timed out releasing the override queue" }
            }
            try {
                assertTrue("The fixture barrier never entered the real queue", entered.await(10, TimeUnit.SECONDS))
                test()
            } finally {
                release.countDown()
                drainWrites()
            }
        }

        /** Waits for all owned posts, cancelling delayed mirror jobs before restoring any singleton. */
        fun drainWrites() = runBlocking {
            withTimeout(10_000) {
                // A finishing post can replace mirrorJob. Recheck it until every owned child is done.
                while (writer.children.any()) {
                    (mirrorField.get(GameLibraryRepository) as Job?)?.cancelAndJoin()
                    delay(1)
                }
            }
        }

        /** Recreates preference memory from durable snapshots after all previous posts have finished. */
        fun restart() {
            drainWrites()
            AppSettingsStore.resetOnboardingForTests()
            GameOverridesRepository.invalidateRowCache()
            settings = MemoryPreferences(settings.disk).apply { writeSucceeds = false }
            overrides = MemoryPreferences(overrides.disk)
        }

        /** Replaces only DAO storage after the install marker is saved; no SQLite claim is implied. */
        fun withRecoveredDatabase(test: (Map<String, GameOverrideEntity>) -> Unit) {
            drainWrites()
            check(AppSettingsStore.prepareInstallationData(context))
            val rows = linkedMapOf<String, GameOverrideEntity>()
            val dao: GameLibraryDao = proxy { method, args ->
                synchronized(rows) {
                    when (method) {
                        "getOverrideRow" -> rows[args[0]]
                        "getAllOverrideRows" -> rows.values.toList()
                        "upsertOverrideRows" -> {
                            @Suppress("UNCHECKED_CAST")
                            (args[0] as List<GameOverrideEntity>).forEach { rows[it.gameUri] = it }
                            Unit
                        }
                        "deleteOverrideRowsByUris" -> {
                            @Suppress("UNCHECKED_CAST")
                            (args[0] as Collection<String>).forEach { rows.remove(it) }
                            Unit
                        }
                        "deleteOverrideRow" -> { rows.remove(args[0]); Unit }
                        else -> error("Unexpected override DAO method: $method")
                    }
                }
            }
            val replacement = object : GameLibraryDatabase() {
                override fun gameLibraryDao(): GameLibraryDao = dao
                override fun createInvalidationTracker(): InvalidationTracker = error("Not used by override synchronization")
                override fun clearAllTables() = error("Not used by override synchronization")
            }
            val previous = databaseField.get(null)
            databaseField.set(null, replacement)
            try {
                test(rows)
            } finally {
                try {
                    drainWrites()
                } finally {
                    databaseField.set(null, previous)
                }
            }
        }

        /** Restores database, callbacks, caches and files before the outer Android scopes close. */
        override fun close() {
            try {
                if (started) {
                    try {
                        drainWrites()
                    } finally {
                        try {
                            runBlocking {
                                withTimeout(10_000) { writer.children.toList().forEach { it.cancelAndJoin() } }
                            }
                        } finally {
                            databaseField.set(null, previousDatabase)
                            mirrorField.set(GameLibraryRepository, previousMirror)
                            GameLibraryRepository.onPersistFailure = previousListener
                            listenerField.setBoolean(GameLibraryFacade, previousListenerInstalled)
                            facadeCaches.forEach { (field, value) -> field.set(GameLibraryFacade, value) }
                            synchronized(cacheLock) {
                                cachedRows().clear()
                                cachedRows().putAll(previousRows)
                                generationField.setLong(GameOverridesRepository, previousGeneration)
                                syncField.setBoolean(GameOverridesRepository, previousSync)
                            }
                            AppSettingsStore.resetOnboardingForTests()
                        }
                    }
                }
            } finally {
                blockedFiles.delete()
                database.delete()
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun cachedRows(): MutableMap<String, GameOverrideEntity?> =
            rowsField.get(GameOverridesRepository) as MutableMap<String, GameOverrideEntity?>
    }

    /** Android commit changes memory even when the disk write fails; keep both snapshots separate. */
    private class MemoryPreferences(
        initial: Map<String, Any> = emptyMap(),
        val disk: MutableMap<String, Any> = initial.toMutableMap(),
    ) {
        private val memory = initial.toMutableMap()
        @Volatile
        var writeSucceeds = true
        @Volatile
        var applyWritesSucceed: Boolean? = null
        var commits = 0
        var edits = 0
        @Volatile
        var afterStringRead: ((String) -> Unit)? = null
        val writes = mutableListOf<Map<String, Any>>()
        val preferences: SharedPreferences = proxy { method, args ->
            if (method == "getString") {
                val value = synchronized(memory) { memory[args[0]] ?: args[1] }
                // Capture first, then let concurrency tests pause without holding a prefs lock.
                afterStringRead?.invoke(args[0] as String)
                value
            } else {
                synchronized(memory) {
                    when (method) {
                        "contains" -> memory.containsKey(args[0])
                        "getBoolean", "getInt", "getStringSet" -> memory[args[0]] ?: args[1]
                        "getAll" -> memory.toMap()
                        "edit" -> editor()
                        else -> error("Unexpected preferences method: $method")
                    }
                }
            }
        }

        private fun editor(): SharedPreferences.Editor {
            edits++
            val changes = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy { method, args ->
                when (method) {
                    "putBoolean", "putInt", "putString", "putStringSet" -> { changes[args[0] as String] = args[1]; editor }
                    "remove" -> { changes[args[0] as String] = null; editor }
                    "commit", "apply" -> synchronized(memory) {
                        for ((key, value) in changes) {
                            if (value == null) memory.remove(key) else memory[key] = value
                        }
                        if (method == "commit") commits++
                        val saved = if (method == "apply") applyWritesSucceed ?: writeSucceeds else writeSucceeds
                        if (saved) { disk.clear(); disk.putAll(memory); writes += disk.toMap() }
                        if (method == "commit") writeSucceeds else null
                    }
                    else -> error("Unexpected editor method: $method")
                }
            }
            return editor
        }
    }

    private class OnboardingContext(
        appSettings: SharedPreferences,
        private val stores: Map<String, SharedPreferences>,
        private val gameLibrary: File,
        private val appFiles: File = File("build/tmp/onboarding-preferences-test"),
        private val processName: String = PACKAGE,
    ) : ContextWrapper(null) {
        private val appSettings = appSettings

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = PACKAGE

        override fun getSystemService(name: String): Any? =
            if (name == Context.ACTIVITY_SERVICE) OnboardingActivityManager(processName) else null

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "app_settings") appSettings else stores[name] ?: MemoryPreferences().preferences

        override fun getDatabasePath(name: String): File = gameLibrary

        override fun getFilesDir(): File = appFiles
    }

    companion object {
        private const val PACKAGE = "com.tyranor.next.motionv2"
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args ?: emptyArray())
            } as T
    }
}
