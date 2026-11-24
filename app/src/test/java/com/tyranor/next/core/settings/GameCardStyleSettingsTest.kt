package com.tyranor.next.core.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import com.tyranor.next.testing.InMemoryPreferencesContext
import com.tyranor.next.testing.InMemorySharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 游戏卡片风格归一：接受 waterfall / uniform_grid / cover_flow；
 * 历史值 grid（当时渲染瀑布流）、空值、未知值与大小写/空白差异按默认瀑布处理。
 */
class GameCardStyleSettingsTest {

    private val originalStyle = AppSettingsStore.gameCardStyleState.value
    private val originalPortraitColumns = AppSettingsStore.gameGridPortraitColumnsState.value
    private val originalLandscapeColumns = AppSettingsStore.gameGridLandscapeColumnsState.value
    private val originalWaterfallPortraitColumns = AppSettingsStore.gameWaterfallPortraitColumnsState.value
    private val originalWaterfallLandscapeColumns = AppSettingsStore.gameWaterfallLandscapeColumnsState.value

    @After
    fun restoreSettingsState() {
        // 列数流对外只读，恢复也必须经 setter（顺带经过同一把锁与 2..8 钳制）。
        val context = InMemoryPreferencesContext()
        AppSettingsStore.gameCardStyleState.value = originalStyle
        AppSettingsStore.setGameGridPortraitColumns(context, originalPortraitColumns)
        AppSettingsStore.setGameGridLandscapeColumns(context, originalLandscapeColumns)
        AppSettingsStore.setGameWaterfallPortraitColumns(context, originalWaterfallPortraitColumns)
        AppSettingsStore.setGameWaterfallLandscapeColumns(context, originalWaterfallLandscapeColumns)
    }

    @Test
    fun gridColumnsDefaultToFourPortraitAndSixLandscape() {
        val context = InMemoryPreferencesContext()

        assertEquals(4, AppSettingsStore.DEFAULT_GAME_GRID_PORTRAIT_COLUMNS)
        assertEquals(6, AppSettingsStore.DEFAULT_GAME_GRID_LANDSCAPE_COLUMNS)
        assertEquals(4, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(6, AppSettingsStore.getGameGridLandscapeColumns(context))

        AppSettingsStore.gameCardStyleState.value = AppSettingsStore.GAME_CARD_STYLE_GRID
        AppSettingsStore.initGameCardStyle(context)

        assertEquals(AppSettingsStore.DEFAULT_GAME_CARD_STYLE, AppSettingsStore.gameCardStyleState.value)
        assertEquals(4, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(6, AppSettingsStore.gameGridLandscapeColumnsState.value)
    }

    @Test
    fun gridColumnsClampToTwoThroughEight() {
        assertEquals(2, AppSettingsStore.MIN_GAME_CARD_COLUMNS)
        assertEquals(8, AppSettingsStore.MAX_GAME_CARD_COLUMNS)
        for (columns in listOf(Int.MIN_VALUE, -1, 0, 1)) {
            assertEquals("columns=$columns", 2, AppSettingsStore.normalizeGameCardColumns(columns))
        }
        for (columns in 2..8) {
            assertEquals("columns=$columns", columns, AppSettingsStore.normalizeGameCardColumns(columns))
        }
        for (columns in listOf(9, Int.MAX_VALUE)) {
            assertEquals("columns=$columns", 8, AppSettingsStore.normalizeGameCardColumns(columns))
        }
    }

    @Test
    fun legacyNarrowAndWideColumnsMigrateIntoAllFourKeys() {
        val context = InMemoryPreferencesContext()
        context.getSharedPreferences(AppStorageNames.APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(AppSettingsStore.KEY_GAME_CARD_COLUMNS_NARROW_LEGACY, 5)
            .putInt(AppSettingsStore.KEY_GAME_CARD_COLUMNS_WIDE_LEGACY, 7)
            .apply()

        AppSettingsStore.initGameCardStyle(context)

        // 旧版只有「窄屏 / 宽屏」一对键，升级后网格与瀑布各自按方向继承同一份值。
        assertEquals(5, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(7, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(5, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(7, AppSettingsStore.getGameWaterfallLandscapeColumns(context))
        val stored = context.prefSnapshot(AppStorageNames.APP_SETTINGS_PREFS)
        assertEquals(5, stored[AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS])
        assertEquals(7, stored[AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS])
        assertEquals(5, stored[AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS])
        assertEquals(7, stored[AppSettingsStore.KEY_GAME_WATERFALL_LANDSCAPE_COLUMNS])
    }

    @Test
    fun legacyMigrationClampsOutOfRangeAndKeepsExistingKeys() {
        val context = InMemoryPreferencesContext()
        context.getSharedPreferences(AppStorageNames.APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(AppSettingsStore.KEY_GAME_CARD_COLUMNS_NARROW_LEGACY, 99)
            .putInt(AppSettingsStore.KEY_GAME_CARD_COLUMNS_WIDE_LEGACY, 1)
            .putInt(AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS, 6)
            .apply()

        AppSettingsStore.initGameCardStyle(context)

        // 已存在的新键不迁移、不被旧值覆盖；其余缺失键补上并夹到 2–8。
        assertEquals(6, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(2, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(8, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(2, AppSettingsStore.getGameWaterfallLandscapeColumns(context))
    }

    @Test
    fun legacyWideOnlyKeepsPortraitDefault() {
        val context = InMemoryPreferencesContext()
        context.getSharedPreferences(AppStorageNames.APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(AppSettingsStore.KEY_GAME_CARD_COLUMNS_WIDE_LEGACY, 7)
            .apply()

        AppSettingsStore.initGameCardStyle(context)

        // 旧版只在用户拖动后落盘：只调过宽屏的用户没有窄屏值，
        // 竖屏应保持本次的默认 4 列，而不是沿用旧版窄屏默认 3 列。
        assertEquals(4, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(4, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(7, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(7, AppSettingsStore.getGameWaterfallLandscapeColumns(context))
    }

    @Test
    fun gridColumnsPersistIndependentlyAndReloadWithStyle() {
        val context = InMemoryPreferencesContext()
        AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW)
        AppSettingsStore.setGameGridPortraitColumns(context, 3)
        AppSettingsStore.setGameGridLandscapeColumns(context, 8)
        AppSettingsStore.setGameGridPortraitColumns(context, 5)

        assertEquals(5, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(8, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(5, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(8, AppSettingsStore.gameGridLandscapeColumnsState.value)

        AppSettingsStore.setGameGridLandscapeColumns(context, 7)
        assertEquals(5, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(7, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(5, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(7, AppSettingsStore.gameGridLandscapeColumnsState.value)
        val stored = context.prefSnapshot(AppStorageNames.APP_SETTINGS_PREFS)
        assertEquals(5, stored[AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS])
        assertEquals(7, stored[AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS])

        AppSettingsStore.initGameCardStyle(InMemoryPreferencesContext())
        AppSettingsStore.initGameCardStyle(context)

        assertEquals(AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW, AppSettingsStore.gameCardStyleState.value)
        assertEquals(5, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(7, AppSettingsStore.gameGridLandscapeColumnsState.value)
    }

    @Test
    fun gridColumnSettersPersistNormalizedValues() {
        val context = InMemoryPreferencesContext()
        AppSettingsStore.setGameGridPortraitColumns(context, Int.MIN_VALUE)
        AppSettingsStore.setGameGridLandscapeColumns(context, Int.MAX_VALUE)

        assertEquals(2, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(8, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(2, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(8, AppSettingsStore.gameGridLandscapeColumnsState.value)
        val stored = context.prefSnapshot(AppStorageNames.APP_SETTINGS_PREFS)
        assertEquals(2, stored[AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS])
        assertEquals(8, stored[AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS])
    }

    @Test
    fun persistedOutOfRangeColumnsAreNormalizedOnReadAndInitialization() {
        val context = InMemoryPreferencesContext()
        context.getSharedPreferences(AppStorageNames.APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS, Int.MAX_VALUE)
            .putInt(AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS, Int.MIN_VALUE)
            .apply()

        assertEquals(8, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(2, AppSettingsStore.getGameGridLandscapeColumns(context))

        AppSettingsStore.initGameCardStyle(context)

        assertEquals(8, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(2, AppSettingsStore.gameGridLandscapeColumnsState.value)
    }

    @Test
    fun waterfallColumnsDefaultToFourPortraitAndSixLandscape() {
        val context = InMemoryPreferencesContext()

        assertEquals(4, AppSettingsStore.DEFAULT_GAME_WATERFALL_PORTRAIT_COLUMNS)
        assertEquals(6, AppSettingsStore.DEFAULT_GAME_WATERFALL_LANDSCAPE_COLUMNS)
        assertEquals(4, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(6, AppSettingsStore.getGameWaterfallLandscapeColumns(context))

        AppSettingsStore.initGameCardStyle(context)

        assertEquals(4, AppSettingsStore.gameWaterfallPortraitColumnsState.value)
        assertEquals(6, AppSettingsStore.gameWaterfallLandscapeColumnsState.value)
    }

    @Test
    fun waterfallSettersClampAndPersistNormalizedValues() {
        val context = InMemoryPreferencesContext()
        AppSettingsStore.setGameWaterfallPortraitColumns(context, Int.MIN_VALUE)
        AppSettingsStore.setGameWaterfallLandscapeColumns(context, Int.MAX_VALUE)

        assertEquals(2, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(8, AppSettingsStore.getGameWaterfallLandscapeColumns(context))
        assertEquals(2, AppSettingsStore.gameWaterfallPortraitColumnsState.value)
        assertEquals(8, AppSettingsStore.gameWaterfallLandscapeColumnsState.value)
        val stored = context.prefSnapshot(AppStorageNames.APP_SETTINGS_PREFS)
        assertEquals(2, stored[AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS])
        assertEquals(8, stored[AppSettingsStore.KEY_GAME_WATERFALL_LANDSCAPE_COLUMNS])

        AppSettingsStore.setGameWaterfallPortraitColumns(context, 1)
        AppSettingsStore.setGameWaterfallLandscapeColumns(context, 11)
        assertEquals(2, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(8, AppSettingsStore.getGameWaterfallLandscapeColumns(context))
    }

    @Test
    fun waterfallAndGridColumnsPersistIndependentlyAcrossStylesAndInitialization() {
        val context = InMemoryPreferencesContext()
        AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_WATERFALL)
        AppSettingsStore.setGameGridPortraitColumns(context, 3)
        AppSettingsStore.setGameGridLandscapeColumns(context, 8)
        AppSettingsStore.setGameWaterfallPortraitColumns(context, 5)
        AppSettingsStore.setGameWaterfallLandscapeColumns(context, 7)

        AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_GRID)
        AppSettingsStore.setGameGridPortraitColumns(context, 4)
        AppSettingsStore.setGameWaterfallPortraitColumns(context, 8)
        AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW)
        AppSettingsStore.initGameCardStyle(context)

        assertEquals(AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW, AppSettingsStore.gameCardStyleState.value)
        assertEquals(4, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(8, AppSettingsStore.gameGridLandscapeColumnsState.value)
        assertEquals(8, AppSettingsStore.gameWaterfallPortraitColumnsState.value)
        assertEquals(7, AppSettingsStore.gameWaterfallLandscapeColumnsState.value)
        assertEquals(4, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(8, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(8, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(7, AppSettingsStore.getGameWaterfallLandscapeColumns(context))

        val stored = context.prefSnapshot(AppStorageNames.APP_SETTINGS_PREFS)
        assertEquals(4, stored[AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS])
        assertEquals(8, stored[AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS])
        assertEquals(8, stored[AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS])
        assertEquals(7, stored[AppSettingsStore.KEY_GAME_WATERFALL_LANDSCAPE_COLUMNS])
    }

    @Test
    fun legacyPortraitSourceDependsOnSmallestWidthOnly() {
        // 上一版竖屏平板读的是「宽屏」键，手机读「窄屏」键；判据只看最小宽度。
        assertEquals(false, AppSettingsStore.wideKeyAppliesToPortrait(599))
        assertEquals(true, AppSettingsStore.wideKeyAppliesToPortrait(600))
        // 没有可信宽度（未定义哨兵）与负值时都按手机处理，不会把手机误判成平板。
        assertEquals(false, AppSettingsStore.wideKeyAppliesToPortrait(Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED))
        assertEquals(false, AppSettingsStore.wideKeyAppliesToPortrait(-1))
        assertEquals(7, AppSettingsStore.legacyPortraitValue(narrow = 5, wide = 7, wideAppliesToPortrait = true))
        assertEquals(5, AppSettingsStore.legacyPortraitValue(narrow = 5, wide = 7, wideAppliesToPortrait = false))
        // 只有宽屏值可用时，平板取宽屏值、手机保持默认（null 表示不迁移）。
        assertEquals(7, AppSettingsStore.legacyPortraitValue(narrow = null, wide = 7, wideAppliesToPortrait = true))
        assertEquals(null, AppSettingsStore.legacyPortraitValue(narrow = null, wide = 7, wideAppliesToPortrait = false))
        assertEquals(5, AppSettingsStore.legacyPortraitValue(narrow = 5, wide = null, wideAppliesToPortrait = true))
        assertEquals(null, AppSettingsStore.legacyPortraitValue(narrow = null, wide = null, wideAppliesToPortrait = false))
    }

    @Test
    fun resetGameConditionsKeepsCardColumns() {
        val context = InMemoryPreferencesContext()
        AppSettingsStore.setGameGridPortraitColumns(context, 3)
        AppSettingsStore.setGameGridLandscapeColumns(context, 8)
        AppSettingsStore.setGameWaterfallPortraitColumns(context, 5)
        AppSettingsStore.setGameWaterfallLandscapeColumns(context, 7)

        AppSettingsStore.resetGameConditions(context)

        assertEquals(3, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(8, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(5, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(7, AppSettingsStore.getGameWaterfallLandscapeColumns(context))
        val stored = context.prefSnapshot(AppStorageNames.APP_SETTINGS_PREFS)
        assertEquals(3, stored[AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS])
        assertEquals(8, stored[AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS])
        assertEquals(5, stored[AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS])
        assertEquals(7, stored[AppSettingsStore.KEY_GAME_WATERFALL_LANDSCAPE_COLUMNS])
    }

    @Test
    fun persistedOutOfRangeWaterfallColumnsAreNormalizedOnReadAndInitialization() {
        val context = InMemoryPreferencesContext()
        context.getSharedPreferences(AppStorageNames.APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS, Int.MAX_VALUE)
            .putInt(AppSettingsStore.KEY_GAME_WATERFALL_LANDSCAPE_COLUMNS, Int.MIN_VALUE)
            .apply()

        assertEquals(8, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(2, AppSettingsStore.getGameWaterfallLandscapeColumns(context))

        AppSettingsStore.initGameCardStyle(context)

        assertEquals(8, AppSettingsStore.gameWaterfallPortraitColumnsState.value)
        assertEquals(2, AppSettingsStore.gameWaterfallLandscapeColumnsState.value)
    }

    @Test
    fun initializationKeepsColumnReadAndPublicationSerializedWithSetters() {
        for (setting in columnSettings()) {
            assertInitializationAndSetterAreSerialized(setting, 2, 4, 8, pauseInitialization = true)
        }
    }

    @Test
    fun columnSettersKeepApplyAndPublicationSerializedWithInitialization() {
        for (setting in columnSettings()) {
            assertInitializationAndSetterAreSerialized(setting, 2, 4, 8, pauseInitialization = false)
        }
    }

    @Test
    fun styleInitializationAndSetterKeepPreferencesAndFlowInSync() {
        val setting = CardSetting(
            AppSettingsStore.KEY_GAME_CARD_STYLE,
            AppSettingsStore.gameCardStyleState,
            AppSettingsStore::setGameCardStyle,
            seed = { AppSettingsStore.gameCardStyleState.value = it },
        )
        for (pauseInitialization in listOf(true, false)) {
            assertInitializationAndSetterAreSerialized(
                setting,
                initialState = AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW,
                savedValue = AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
                updatedValue = AppSettingsStore.GAME_CARD_STYLE_GRID,
                pauseInitialization = pauseInitialization,
            )
        }
    }

    /**
     * 列数流对外只读（发布统一在 [AppSettingsStore] 内部加锁完成），所以预置状态也要走各自的正规入口：
     * 列数用 setter 写进一个独立的内存偏好，不会污染用例自己构造的 backing。
     */
    private data class CardSetting<T : Any>(
        val key: String,
        val state: StateFlow<T>,
        val setter: (Context, T) -> Unit,
        val seed: (T) -> Unit,
    )

    private fun columnSettings() = listOf(
        CardSetting(
            AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS,
            AppSettingsStore.gameGridPortraitColumnsState,
            AppSettingsStore::setGameGridPortraitColumns,
            seed = { AppSettingsStore.setGameGridPortraitColumns(InMemoryPreferencesContext(), it) },
        ),
        CardSetting(
            AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS,
            AppSettingsStore.gameGridLandscapeColumnsState,
            AppSettingsStore::setGameGridLandscapeColumns,
            seed = { AppSettingsStore.setGameGridLandscapeColumns(InMemoryPreferencesContext(), it) },
        ),
        CardSetting(
            AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS,
            AppSettingsStore.gameWaterfallPortraitColumnsState,
            AppSettingsStore::setGameWaterfallPortraitColumns,
            seed = { AppSettingsStore.setGameWaterfallPortraitColumns(InMemoryPreferencesContext(), it) },
        ),
        CardSetting(
            AppSettingsStore.KEY_GAME_WATERFALL_LANDSCAPE_COLUMNS,
            AppSettingsStore.gameWaterfallLandscapeColumnsState,
            AppSettingsStore::setGameWaterfallLandscapeColumns,
            seed = { AppSettingsStore.setGameWaterfallLandscapeColumns(InMemoryPreferencesContext(), it) },
        ),
    )

    private fun <T : Any> assertInitializationAndSetterAreSerialized(
        setting: CardSetting<T>,
        initialState: T,
        savedValue: T,
        updatedValue: T,
        pauseInitialization: Boolean,
    ) {
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val competitorStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val pause = {
            paused.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Timed out releasing ${setting.key}" }
        }
        val backing = InMemorySharedPreferences(
            backing = mutableMapOf(setting.key to savedValue),
            onCommit = { if (!pauseInitialization) pause() },
        )
        val preferences = object : SharedPreferences by backing {
            override fun getInt(key: String, defValue: Int): Int {
                val value = backing.getInt(key, defValue)
                if (pauseInitialization && key == setting.key) pause()
                return value
            }

            override fun getString(key: String, defValue: String?): String? {
                val value = backing.getString(key, defValue)
                if (pauseInitialization && key == setting.key) pause()
                return value
            }
        }
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        val initialize = { AppSettingsStore.initGameCardStyle(context) }
        val update = { setting.setter(context, updatedValue) }
        val firstAction = if (pauseInitialization) initialize else update
        val secondAction = if (pauseInitialization) update else initialize
        val first = Thread({
            try {
                firstAction()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }, "card-columns-first")
        val second = Thread({
            competitorStarted.countDown()
            try {
                secondAction()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }, "card-columns-second")

        try {
            setting.seed(initialState)
            first.start()
            assertTrue("${setting.key}: first operation did not pause", paused.await(5, TimeUnit.SECONDS))
            second.start()
            assertTrue(competitorStarted.await(5, TimeUnit.SECONDS))
            // 等待实际发生监视器竞争，而不是假设已调度线程一定运行过。
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (second.isAlive && second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("${setting.key}: competing operation must wait", Thread.State.BLOCKED, second.state)
            assertEquals(initialState, setting.state.value)
            assertEquals(if (pauseInitialization) savedValue else updatedValue, backing.snapshot()[setting.key])

            release.countDown()
            first.join(5_000)
            second.join(5_000)
            assertFalse("${setting.key}: first operation did not finish", first.isAlive)
            assertFalse("${setting.key}: competing operation did not finish", second.isAlive)
            failure.get()?.let { throw AssertionError("${setting.key}: worker failed", it) }
            assertEquals(updatedValue, backing.snapshot()[setting.key])
            assertEquals(updatedValue, setting.state.value)
        } finally {
            release.countDown()
            try {
                for (thread in listOf(first, second)) {
                    thread.interrupt()
                    thread.join(5_000)
                }
            } finally {
                restoreSettingsState()
            }
            assertFalse("${setting.key}: first worker leaked", first.isAlive)
            assertFalse("${setting.key}: competing worker leaked", second.isAlive)
        }
    }

    @Test
    fun explicitStylesAreAccepted() {
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle("waterfall"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_GRID,
            AppSettingsStore.normalizeGameCardStyle("uniform_grid"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW,
            AppSettingsStore.normalizeGameCardStyle("cover_flow"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW,
            AppSettingsStore.normalizeGameCardStyle(" COVER_FLOW "),
        )
    }

    @Test
    fun legacyGridAndInvalidValuesFallBackToWaterfall() {
        // 历史持久化值 "grid" 在当时渲染瀑布流；升级后必须保持瀑布，不能跳变为网格。
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle("grid"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle(null),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle(""),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.normalizeGameCardStyle("unknown"),
        )
        assertEquals(
            AppSettingsStore.GAME_CARD_STYLE_WATERFALL,
            AppSettingsStore.DEFAULT_GAME_CARD_STYLE,
        )
    }
}
