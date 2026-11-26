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
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 验证「显示首页」默认关闭仅作用于全新安装，老用户升级与显式开关保持原有状态。 */
class HomePageMigrationSettingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun resetCachesBefore() {
        AppSettingsStore.resetOnboardingForTests()
        AppSettingsStore.resetHomePageMigrationForTests()
        AppSettingsStore.homePageEnabledState.value = AppSettingsStore.DEFAULT_HOME_PAGE_ENABLED
    }

    @After
    fun resetCachesAfter() {
        AppSettingsStore.resetOnboardingForTests()
        AppSettingsStore.resetHomePageMigrationForTests()
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
            AppSettingsStore.hasAppSettingsForHomePageMigration(
                onboardingCompleted = false,
                appSettingsKeys = setOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED, AppSettingsStore.KEY_HOME_PAGE_ENABLED),
            ),
        )
        assertTrue(
            AppSettingsStore.hasAppSettingsForHomePageMigration(
                onboardingCompleted = true,
                appSettingsKeys = setOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED),
            ),
        )
        assertTrue(
            AppSettingsStore.hasAppSettingsForHomePageMigration(
                onboardingCompleted = false,
                appSettingsKeys = setOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED, AppSettingsStore.KEY_HOME_STYLE),
            ),
        )
    }

    @Test
    fun freshInstallPersistsFalseEvenAfterSubsequentSettingsWrites() {
        val appSettings = MemoryPreferences(dropAsyncWrites = true)
        val context = MigrationContext(
            appSettings = appSettings.preferences,
            stores = emptyMap(),
            gameLibrary = File("missing-game-library.db"),
            files = temp.root,
        )
        AppSettingsStore.initializeOnboarding(context)
        AppSettingsStore.initHomePageEnabled(context)

        assertFalse(AppSettingsStore.homePageEnabledState.value)
        assertFalse(AppSettingsStore.isHomePageEnabled(context))
        assertEquals(false, appSettings.disk[AppSettingsStore.KEY_HOME_PAGE_ENABLED])

        // 后续用户调整其它设置或完成引导后再重启，不应将新安装误判为老用户升级
        appSettings.preferences.edit().putBoolean(AppSettingsStore.KEY_HOME_STYLE, true).apply()
        val afterRestart = MemoryPreferences(appSettings.disk)
        val restarted = MigrationContext(afterRestart.preferences, emptyMap(), temp.newFile(), temp.root)
        AppSettingsStore.initHomePageEnabled(restarted)
        assertFalse(AppSettingsStore.homePageEnabledState.value)
        assertFalse(AppSettingsStore.isHomePageEnabled(restarted))
    }

    @Test
    fun startupPublishesTheDecisionBeforeDiskWriteAndBeforeGameLibraryCreation() {
        val appSettings = MemoryPreferences()
        val library = File(temp.root, AppStorageNames.GAME_LIBRARY_DATABASE)
        val context = MigrationContext(appSettings.preferences, emptyMap(), library, temp.root)

        AppSettingsStore.prepareHomePageEnabled(context)
        assertFalse(AppSettingsStore.homePageEnabledState.value)
        assertFalse(appSettings.disk.containsKey(AppSettingsStore.KEY_HOME_PAGE_ENABLED))

        assertTrue(library.createNewFile())
        AppSettingsStore.resetHomePageMigrationForTests()
        AppSettingsStore.initHomePageEnabled(context)
        assertEquals(false, appSettings.disk[AppSettingsStore.KEY_HOME_PAGE_ENABLED])
    }

    @Test
    fun failedFirstCommitRetainsFreshInstallDecisionAfterLibraryAppearsAndProcessRestarts() {
        val beforeDisk = MemoryPreferences(failedCommits = 1)
        val library = File(temp.root, AppStorageNames.GAME_LIBRARY_DATABASE)
        AppSettingsStore.initHomePageEnabled(MigrationContext(beforeDisk.preferences, emptyMap(), library, temp.root))
        assertFalse(AppSettingsStore.homePageEnabledState.value)
        assertFalse(beforeDisk.disk.containsKey(AppSettingsStore.KEY_HOME_PAGE_ENABLED))

        assertTrue(library.createNewFile())
        AppSettingsStore.resetHomePageMigrationForTests()
        val afterDisk = MemoryPreferences(beforeDisk.disk)
        AppSettingsStore.initHomePageEnabled(MigrationContext(afterDisk.preferences, emptyMap(), library, temp.root))
        assertFalse(AppSettingsStore.homePageEnabledState.value)
        assertEquals(false, afterDisk.disk[AppSettingsStore.KEY_HOME_PAGE_ENABLED])
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

    private class MemoryPreferences(
        initial: Map<String, Boolean> = emptyMap(),
        private val dropAsyncWrites: Boolean = false,
        private var failedCommits: Int = 0,
    ) {
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
                        if (method == "commit" && failedCommits > 0) {
                            failedCommits--
                            return@proxy false
                        }
                        if (method == "apply" && dropAsyncWrites) return@proxy null
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
        private val files: File = gameLibrary.absoluteFile.parentFile,
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == AppStorageNames.APP_SETTINGS_PREFS) appSettings else stores[name] ?: MemoryPreferences().preferences

        override fun getDatabasePath(name: String): File = gameLibrary

        override fun getFilesDir(): File = files
    }

    companion object {
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args ?: emptyArray())
            } as T
    }
}
