package com.tyranor.next.core.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tyranor.next.testing.InMemoryPreferencesContext
import com.tyranor.next.testing.InMemorySharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
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
        AppSettingsStore.gameCardStyleState.value = originalStyle
        AppSettingsStore.gameGridPortraitColumnsState.value = originalPortraitColumns
        AppSettingsStore.gameGridLandscapeColumnsState.value = originalLandscapeColumns
        AppSettingsStore.gameWaterfallPortraitColumnsState.value = originalWaterfallPortraitColumns
        AppSettingsStore.gameWaterfallLandscapeColumnsState.value = originalWaterfallLandscapeColumns
    }

    @Test
    fun gridColumnsDefaultToFourPortraitAndSixLandscape() {
        val context = InMemoryPreferencesContext()

        assertEquals(4, AppSettingsStore.DEFAULT_GAME_GRID_PORTRAIT_COLUMNS)
        assertEquals(6, AppSettingsStore.DEFAULT_GAME_GRID_LANDSCAPE_COLUMNS)
        assertEquals(4, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(6, AppSettingsStore.getGameGridLandscapeColumns(context))

        AppSettingsStore.gameCardStyleState.value = AppSettingsStore.GAME_CARD_STYLE_GRID
        AppSettingsStore.gameGridPortraitColumnsState.value = 9
        AppSettingsStore.gameGridLandscapeColumnsState.value = 3
        AppSettingsStore.initGameCardStyle(context)

        assertEquals(AppSettingsStore.DEFAULT_GAME_CARD_STYLE, AppSettingsStore.gameCardStyleState.value)
        assertEquals(4, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(6, AppSettingsStore.gameGridLandscapeColumnsState.value)
    }

    @Test
    fun gridColumnsClampToTwoThroughTen() {
        assertEquals(2, AppSettingsStore.MIN_GAME_GRID_COLUMNS)
        assertEquals(10, AppSettingsStore.MAX_GAME_GRID_COLUMNS)
        for (columns in listOf(Int.MIN_VALUE, -1, 0, 1)) {
            assertEquals("columns=$columns", 2, AppSettingsStore.normalizeGameGridColumns(columns))
        }
        for (columns in 2..10) {
            assertEquals("columns=$columns", columns, AppSettingsStore.normalizeGameGridColumns(columns))
        }
        for (columns in listOf(11, Int.MAX_VALUE)) {
            assertEquals("columns=$columns", 10, AppSettingsStore.normalizeGameGridColumns(columns))
        }
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
        assertEquals(10, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(2, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(10, AppSettingsStore.gameGridLandscapeColumnsState.value)
        val stored = context.prefSnapshot(AppStorageNames.APP_SETTINGS_PREFS)
        assertEquals(2, stored[AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS])
        assertEquals(10, stored[AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS])
    }

    @Test
    fun persistedOutOfRangeColumnsAreNormalizedOnReadAndInitialization() {
        val context = InMemoryPreferencesContext()
        context.getSharedPreferences(AppStorageNames.APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS, Int.MAX_VALUE)
            .putInt(AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS, Int.MIN_VALUE)
            .apply()

        assertEquals(10, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(2, AppSettingsStore.getGameGridLandscapeColumns(context))

        AppSettingsStore.initGameCardStyle(context)

        assertEquals(10, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(2, AppSettingsStore.gameGridLandscapeColumnsState.value)
    }

    @Test
    fun waterfallColumnsDefaultToFourPortraitAndSixLandscape() {
        val context = InMemoryPreferencesContext()

        assertEquals(4, AppSettingsStore.DEFAULT_GAME_WATERFALL_PORTRAIT_COLUMNS)
        assertEquals(6, AppSettingsStore.DEFAULT_GAME_WATERFALL_LANDSCAPE_COLUMNS)
        assertEquals(4, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(6, AppSettingsStore.getGameWaterfallLandscapeColumns(context))

        AppSettingsStore.gameWaterfallPortraitColumnsState.value = 9
        AppSettingsStore.gameWaterfallLandscapeColumnsState.value = 3
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
        assertEquals(10, AppSettingsStore.getGameWaterfallLandscapeColumns(context))
        assertEquals(2, AppSettingsStore.gameWaterfallPortraitColumnsState.value)
        assertEquals(10, AppSettingsStore.gameWaterfallLandscapeColumnsState.value)
        val stored = context.prefSnapshot(AppStorageNames.APP_SETTINGS_PREFS)
        assertEquals(2, stored[AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS])
        assertEquals(10, stored[AppSettingsStore.KEY_GAME_WATERFALL_LANDSCAPE_COLUMNS])

        AppSettingsStore.setGameWaterfallPortraitColumns(context, 1)
        AppSettingsStore.setGameWaterfallLandscapeColumns(context, 11)
        assertEquals(2, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(10, AppSettingsStore.getGameWaterfallLandscapeColumns(context))
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
        AppSettingsStore.setGameWaterfallPortraitColumns(context, 9)
        AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW)
        AppSettingsStore.gameGridPortraitColumnsState.value = 2
        AppSettingsStore.gameGridLandscapeColumnsState.value = 2
        AppSettingsStore.gameWaterfallPortraitColumnsState.value = 2
        AppSettingsStore.gameWaterfallLandscapeColumnsState.value = 2
        AppSettingsStore.initGameCardStyle(context)

        assertEquals(AppSettingsStore.GAME_CARD_STYLE_COVER_FLOW, AppSettingsStore.gameCardStyleState.value)
        assertEquals(4, AppSettingsStore.gameGridPortraitColumnsState.value)
        assertEquals(8, AppSettingsStore.gameGridLandscapeColumnsState.value)
        assertEquals(9, AppSettingsStore.gameWaterfallPortraitColumnsState.value)
        assertEquals(7, AppSettingsStore.gameWaterfallLandscapeColumnsState.value)
        assertEquals(4, AppSettingsStore.getGameGridPortraitColumns(context))
        assertEquals(8, AppSettingsStore.getGameGridLandscapeColumns(context))
        assertEquals(9, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(7, AppSettingsStore.getGameWaterfallLandscapeColumns(context))

        val stored = context.prefSnapshot(AppStorageNames.APP_SETTINGS_PREFS)
        assertEquals(4, stored[AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS])
        assertEquals(8, stored[AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS])
        assertEquals(9, stored[AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS])
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

        assertEquals(10, AppSettingsStore.getGameWaterfallPortraitColumns(context))
        assertEquals(2, AppSettingsStore.getGameWaterfallLandscapeColumns(context))

        AppSettingsStore.initGameCardStyle(context)

        assertEquals(10, AppSettingsStore.gameWaterfallPortraitColumnsState.value)
        assertEquals(2, AppSettingsStore.gameWaterfallLandscapeColumnsState.value)
    }

    @Test
    fun initializationKeepsColumnReadAndPublicationSerializedWithSetters() {
        for (setting in columnSettings()) {
            assertInitializationAndSetterAreSerialized(setting, pauseInitialization = true)
        }
    }

    @Test
    fun columnSettersKeepApplyAndPublicationSerializedWithInitialization() {
        for (setting in columnSettings()) {
            assertInitializationAndSetterAreSerialized(setting, pauseInitialization = false)
        }
    }

    private data class ColumnSetting(
        val key: String,
        val state: MutableStateFlow<Int>,
        val setter: (Context, Int) -> Unit,
    )

    private fun columnSettings() = listOf(
        ColumnSetting(
            AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS,
            AppSettingsStore.gameGridPortraitColumnsState,
            AppSettingsStore::setGameGridPortraitColumns,
        ),
        ColumnSetting(
            AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS,
            AppSettingsStore.gameGridLandscapeColumnsState,
            AppSettingsStore::setGameGridLandscapeColumns,
        ),
        ColumnSetting(
            AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS,
            AppSettingsStore.gameWaterfallPortraitColumnsState,
            AppSettingsStore::setGameWaterfallPortraitColumns,
        ),
        ColumnSetting(
            AppSettingsStore.KEY_GAME_WATERFALL_LANDSCAPE_COLUMNS,
            AppSettingsStore.gameWaterfallLandscapeColumnsState,
            AppSettingsStore::setGameWaterfallLandscapeColumns,
        ),
    )

    private fun assertInitializationAndSetterAreSerialized(
        setting: ColumnSetting,
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
            backing = mutableMapOf(setting.key to 4),
            onCommit = { if (!pauseInitialization) pause() },
        )
        val preferences = object : SharedPreferences by backing {
            override fun getInt(key: String, defValue: Int): Int {
                val value = backing.getInt(key, defValue)
                if (pauseInitialization && key == setting.key) pause()
                return value
            }
        }
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        val initialize = { AppSettingsStore.initGameCardStyle(context) }
        val update = { setting.setter(context, 8) }
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
            setting.state.value = 2
            first.start()
            assertTrue("${setting.key}: first operation did not pause", paused.await(5, TimeUnit.SECONDS))
            second.start()
            assertTrue(competitorStarted.await(5, TimeUnit.SECONDS))
            // Wait for actual monitor contention, rather than assuming a scheduled thread has run.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (second.isAlive && second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("${setting.key}: competing operation must wait", Thread.State.BLOCKED, second.state)
            assertEquals(2, setting.state.value)
            assertEquals(if (pauseInitialization) 4 else 8, backing.getInt(setting.key, 0))

            release.countDown()
            first.join(5_000)
            second.join(5_000)
            assertFalse("${setting.key}: first operation did not finish", first.isAlive)
            assertFalse("${setting.key}: competing operation did not finish", second.isAlive)
            failure.get()?.let { throw AssertionError("${setting.key}: worker failed", it) }
            assertEquals(8, backing.getInt(setting.key, 0))
            assertEquals(8, setting.state.value)
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
