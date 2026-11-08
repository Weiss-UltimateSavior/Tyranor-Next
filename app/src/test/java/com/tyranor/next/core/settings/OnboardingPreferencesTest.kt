package com.tyranor.next.core.settings

import android.app.OnboardingActivityManager
import android.content.SharedPreferences
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.util.Log
import com.tyranor.next.core.game.storage.GameLibraryDatabase
import com.tyranor.next.core.game.storage.GameLibraryFacade
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
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

    /** Android commit changes memory even when the disk write fails; keep both snapshots separate. */
    private class MemoryPreferences(
        initial: Map<String, Any> = emptyMap(),
        val disk: MutableMap<String, Any> = initial.toMutableMap(),
    ) {
        private val memory = initial.toMutableMap()
        var writeSucceeds = true
        var applyWritesSucceed: Boolean? = null
        var commits = 0
        var edits = 0
        val writes = mutableListOf<Map<String, Any>>()
        val preferences: SharedPreferences = proxy { method, args ->
            when (method) {
                "contains" -> memory.containsKey(args[0])
                "getBoolean", "getInt", "getString", "getStringSet" -> memory[args[0]] ?: args[1]
                "getAll" -> memory.toMap()
                "edit" -> editor()
                else -> error("Unexpected preferences method: $method")
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
                    "commit", "apply" -> {
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
