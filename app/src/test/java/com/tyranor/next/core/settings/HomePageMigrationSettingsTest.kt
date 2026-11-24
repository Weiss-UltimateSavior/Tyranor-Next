package com.tyranor.next.core.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 验证「显示首页」默认关闭仅作用于全新安装，老用户升级与显式开关保持原有状态。 */
class HomePageMigrationSettingsTest {

    @Before
    fun resetCachesBefore() {
        AppSettingsStore.resetOnboardingForTests()
        AppSettingsStore.homePageEnabledState.value = AppSettingsStore.DEFAULT_HOME_PAGE_ENABLED
    }

    @After
    fun resetCachesAfter() {
        AppSettingsStore.resetOnboardingForTests()
        AppSettingsStore.homePageEnabledState.value = AppSettingsStore.DEFAULT_HOME_PAGE_ENABLED
    }

    @Test
    fun resolveInitialHomePageEnabledHonorsStoredAndExistingInstall() {
        assertFalse(AppSettingsStore.resolveInitialHomePageEnabled(storedValue = null, existingInstallDetected = false))
        assertTrue(AppSettingsStore.resolveInitialHomePageEnabled(storedValue = null, existingInstallDetected = true))
        assertTrue(AppSettingsStore.resolveInitialHomePageEnabled(storedValue = true, existingInstallDetected = false))
        assertFalse(AppSettingsStore.resolveInitialHomePageEnabled(storedValue = false, existingInstallDetected = true))
    }

    @Test
    fun hasExistingInstallIgnoresOnboardingAndHomeKeysAlone() {
        assertFalse(
            AppSettingsStore.hasExistingInstallForHomePageMigration(
                onboardingCompleted = false,
                appSettingsKeys = setOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED, AppSettingsStore.KEY_HOME_PAGE_ENABLED),
                legacyPreferencesHaveData = false,
                gameLibraryExists = false,
            ),
        )
        assertTrue(
            AppSettingsStore.hasExistingInstallForHomePageMigration(
                onboardingCompleted = true,
                appSettingsKeys = setOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED),
                legacyPreferencesHaveData = false,
                gameLibraryExists = false,
            ),
        )
        assertTrue(
            AppSettingsStore.hasExistingInstallForHomePageMigration(
                onboardingCompleted = false,
                appSettingsKeys = setOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED, AppSettingsStore.KEY_HOME_STYLE),
                legacyPreferencesHaveData = false,
                gameLibraryExists = false,
            ),
        )
        assertTrue(
            AppSettingsStore.hasExistingInstallForHomePageMigration(
                onboardingCompleted = false,
                appSettingsKeys = emptySet(),
                legacyPreferencesHaveData = true,
                gameLibraryExists = false,
            ),
        )
        assertTrue(
            AppSettingsStore.hasExistingInstallForHomePageMigration(
                onboardingCompleted = false,
                appSettingsKeys = emptySet(),
                legacyPreferencesHaveData = false,
                gameLibraryExists = true,
            ),
        )
    }

    @Test
    fun freshInstallPersistsFalseEvenAfterSubsequentSettingsWrites() {
        val appSettings = MemoryPreferences()
        val context = MigrationContext(
            appSettings = appSettings.preferences,
            stores = emptyMap(),
            gameLibrary = File("missing-game-library.db"),
        )
        AppSettingsStore.initializeOnboarding(context)
        AppSettingsStore.initHomePageEnabled(context)

        assertFalse(AppSettingsStore.homePageEnabledState.value)
        assertFalse(AppSettingsStore.isHomePageEnabled(context))
        assertEquals(false, appSettings.disk[AppSettingsStore.KEY_HOME_PAGE_ENABLED])

        // 后续用户调整其它设置或完成引导后再重启，不应将新安装误判为老用户升级
        appSettings.preferences.edit().putBoolean(AppSettingsStore.KEY_HOME_STYLE, true).apply()
        AppSettingsStore.initHomePageEnabled(context)
        assertFalse(AppSettingsStore.homePageEnabledState.value)
        assertFalse(AppSettingsStore.isHomePageEnabled(context))
    }

    @Test
    fun upgradingUsersPreserveHomeEnabledAcrossAllEvidenceSources() {
        // 1. 仅有老版本已完成引导记录
        val onboardingUpgrade = MemoryPreferences(mapOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED to true))
        val onboardingCtx = MigrationContext(onboardingUpgrade.preferences, emptyMap(), File("missing.db"))
        AppSettingsStore.initHomePageEnabled(onboardingCtx)
        assertTrue(AppSettingsStore.homePageEnabledState.value)
        assertTrue(AppSettingsStore.isHomePageEnabled(onboardingCtx))

        // 2. 仅存在其它应用设置键
        val settingsUpgrade = MemoryPreferences(mapOf(AppSettingsStore.KEY_GAME_CARD_BADGE to true))
        val settingsCtx = MigrationContext(settingsUpgrade.preferences, emptyMap(), File("missing.db"))
        AppSettingsStore.initHomePageEnabled(settingsCtx)
        assertTrue(AppSettingsStore.homePageEnabledState.value)

        // 3. 仅存在旧版引擎/扫描偏好
        val legacyStore = MemoryPreferences(mapOf("legacy_key" to true))
        val legacyCtx = MigrationContext(
            MemoryPreferences().preferences,
            mapOf(AppStorageNames.LEGACY_ENGINE_PREFS to legacyStore.preferences),
            File("missing.db"),
        )
        AppSettingsStore.initHomePageEnabled(legacyCtx)
        assertTrue(AppSettingsStore.homePageEnabledState.value)

        // 4. 仅存在既有游戏库数据库
        val dbFile = Files.createTempFile("home-migration-db", ".db").toFile()
        try {
            val dbCtx = MigrationContext(MemoryPreferences().preferences, emptyMap(), dbFile)
            AppSettingsStore.initHomePageEnabled(dbCtx)
            assertTrue(AppSettingsStore.homePageEnabledState.value)
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun explicitFalseStoredBeforeUpgradeRemainsFalse() {
        val explicitOff = MemoryPreferences(
            mapOf(
                AppSettingsStore.KEY_ONBOARDING_COMPLETED to true,
                AppSettingsStore.KEY_HOME_PAGE_ENABLED to false,
            ),
        )
        val ctx = MigrationContext(explicitOff.preferences, emptyMap(), File("missing.db"))
        AppSettingsStore.initHomePageEnabled(ctx)
        assertFalse(AppSettingsStore.homePageEnabledState.value)
        assertFalse(AppSettingsStore.isHomePageEnabled(ctx))
    }

    private class MemoryPreferences(initial: Map<String, Boolean> = emptyMap()) {
        val disk = initial.toMutableMap()
        private val memory = initial.toMutableMap()
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
                        disk.clear()
                        disk.putAll(memory)
                        if (method == "commit") true else null
                    }
                    else -> error("Unexpected editor method: $method")
                }
            }
            return editor
        }
    }

    private class MigrationContext(
        private val appSettings: SharedPreferences,
        private val stores: Map<String, SharedPreferences>,
        private val gameLibrary: File,
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "app_settings") appSettings else stores[name] ?: MemoryPreferences().preferences

        override fun getDatabasePath(name: String): File = gameLibrary

        override fun getFilesDir(): File = File("build/tmp/home-migration-settings-test")
    }

    companion object {
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args ?: emptyArray())
            } as T
    }
}
