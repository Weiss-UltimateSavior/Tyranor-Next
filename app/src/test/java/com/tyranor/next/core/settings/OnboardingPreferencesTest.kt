package com.tyranor.next.core.settings

import android.content.SharedPreferences
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnboardingPreferencesTest {
    @Before
    fun resetAppSettingsOnboardingCache() {
        AppSettingsStore.resetOnboardingForTests()
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
    fun `failed upgrade marker cannot be observed as completion`() {
        val storage = MemoryPreferences().apply { writeSucceeds = false }
        val state = OnboardingPreferences(storage.preferences)
        assertEquals(OnboardingWriteResult.STORAGE_UNAVAILABLE, state.initialize { true })
        assertFalse(state.isCompleted())
        storage.writeSucceeds = true
        state.initialize { false }
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
    private class MemoryPreferences(initial: Map<String, Boolean> = emptyMap()) {
        val disk = initial.toMutableMap()
        private val memory = initial.toMutableMap()
        var writeSucceeds = true
        var commits = 0
        val preferences: SharedPreferences = proxy { method, args ->
            when (method) {
                "contains" -> memory.containsKey(args[0])
                "getBoolean" -> memory[args[0]] ?: args[1]
                "getAll" -> memory.toMap()
                "edit" -> editor()
                else -> error("Unexpected preferences method: $method")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Boolean?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy { method, args ->
                when (method) {
                    "putBoolean" -> { changes[args[0] as String] = args[1] as Boolean; editor }
                    "remove" -> { changes[args[0] as String] = null; editor }
                    "commit", "apply" -> {
                        for ((key, value) in changes) {
                            if (value == null) memory.remove(key) else memory[key] = value
                        }
                        if (method == "commit") commits++
                        if (writeSucceeds) { disk.clear(); disk.putAll(memory) }
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
    ) : ContextWrapper(null) {
        private val appSettings = appSettings

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "app_settings") appSettings else stores[name] ?: MemoryPreferences().preferences

        override fun getDatabasePath(name: String): File = gameLibrary
    }

    companion object {
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args ?: emptyArray())
            } as T
    }
}
