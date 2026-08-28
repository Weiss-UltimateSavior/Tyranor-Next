package com.tyranor.next.ui.main

import android.app.Application
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.core.cover.CoverScrapeTaskManager
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.ui.game.cleanupDeletedGame
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainLibraryUiState(
    val games: List<ScanGame> = emptyList(),
    val recentGames: List<ScanGame> = emptyList(),
    val quickLaunch: List<ScanGame> = emptyList(),
    val loaded: Boolean = false,
    val scanning: Boolean = false,
    val scrapeEventId: Long = 0L,
    val scrapeMessage: String? = null,
)

/**
 * MainActivity 四个 Tab 共用的游戏库状态。
 *
 * 页面只持有搜索词、弹窗等瞬时 UI 状态；持久数据统一进入一个 FIFO 队列并在 IO dispatcher
 * 处理，避免组合期磁盘读取、离页取消后台任务，以及快速操作时后发写入被先发任务覆盖。
 */
class MainLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val stateRevision = AtomicLong(0L)
    private val commands = Channel<PersistenceCommand>(Channel.UNLIMITED)
    private val _uiState = MutableStateFlow(MainLibraryUiState())
    val uiState: StateFlow<MainLibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (command in commands) {
                try {
                    command.action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    Log.e(TAG, "Library persistence command failed", throwable)
                } finally {
                    publishStorageSnapshot(command.revision, command.finishesScan)
                }
            }
        }
        // 每张封面成功持久化后立即更新所有应用层列表，批量任务无需等到全部游戏完成。
        viewModelScope.launch {
            CoverScrapeTaskManager.gameUpdates.collect { updated ->
                _uiState.update { MainLibraryStateReducer.replaceGame(it, updated) }
            }
        }
        // 设置页删除扫描目录时会同步清理该目录下的持久游戏缓存；
        // 主库订阅 core 层修订号，避免跨 Tab 常驻组合下游戏页仍显示旧游戏。
        viewModelScope.launch {
            EngineScanner.libraryRevision.drop(1).collect {
                refreshFromStorage()
            }
        }
        // 刮削任务属于应用级长任务，即使 GameScreen 已离开组合，也要立即发布给首页与游戏页。
        viewModelScope.launch {
            var handledEventId = 0L
            snapshotFlow { CoverScrapeTaskManager.state.value }.collect { task ->
                if (task.running || task.eventId == 0L || task.eventId == handledEventId) return@collect
                handledEventId = task.eventId
                val result = task.result
                val message = if (result != null) {
                    "批量刮削完成：更新 ${result.updatedCount}，跳过 ${result.skippedCount}，失败 ${result.failedCount}"
                } else {
                    task.error
                }
                if (result != null) {
                    acceptPersistedGames(result.games, task.eventId, message)
                } else {
                    _uiState.update { it.copy(scrapeEventId = task.eventId, scrapeMessage = message) }
                }
                CoverScrapeTaskManager.clearFinished(task.eventId)
            }
        }
        refreshFromStorage()
    }

    fun refreshFromStorage() {
        enqueuePersistence(revision = stateRevision.get()) { }
    }

    /** 扫描/刮削已经完成持久化时，立即发布结果，再由 FIFO 队列校准派生列表。 */
    private fun acceptPersistedGames(games: List<ScanGame>, eventId: Long = 0L, message: String? = null) {
        val revision = stateRevision.incrementAndGet()
        _uiState.update {
            MainLibraryStateReducer.acceptGames(it, games).copy(
                scrapeEventId = eventId.takeIf { id -> id != 0L } ?: it.scrapeEventId,
                scrapeMessage = message,
            )
        }
        enqueuePersistence(revision) { }
    }

    fun replaceGame(updated: ScanGame) {
        val before = _uiState.value.games.firstOrNull { it.uri == updated.uri } ?: updated
        val revision = stateRevision.incrementAndGet()
        _uiState.update { MainLibraryStateReducer.replaceGame(it, updated) }
        enqueuePersistence(revision) {
            var persisted = updated
            EngineScanner.updateGames(appContext) { games ->
                games.map { current ->
                    if (current.uri == updated.uri) {
                        mergeChangedGameFields(current, before, updated).also { persisted = it }
                    } else {
                        current
                    }
                }
            }
            EngineScanner.updateRecentGames(appContext) { recent ->
                recent.map {
                    if (it.uri == persisted.uri) persisted.copy(openTime = it.openTime) else it
                }
            }
            EngineScanner.saveQuickLaunch(
                appContext,
                EngineScanner.loadQuickLaunch(appContext).map {
                    if (it.uri == persisted.uri) persisted else it
                },
            )
        }
    }

    fun deleteGame(target: ScanGame) {
        val revision = stateRevision.incrementAndGet()
        _uiState.update { MainLibraryStateReducer.deleteGame(it, target.uri) }
        enqueuePersistence(revision) {
            EngineScanner.removeGame(appContext, target.uri)
            cleanupDeletedGame(appContext, target)
        }
    }

    fun removeRecentGame(target: ScanGame) {
        val revision = stateRevision.incrementAndGet()
        _uiState.update { MainLibraryStateReducer.removeRecent(it, target.uri) }
        enqueuePersistence(revision) {
            EngineScanner.removeRecentGame(appContext, target.uri)
        }
    }

    /** 返回 false 表示三个快捷启动槽位均已占用。 */
    fun toggleQuickLaunch(game: ScanGame): Boolean {
        val current = _uiState.value
        val shouldAdd = current.quickLaunch.none { it.uri == game.uri }
        if (shouldAdd && current.quickLaunch.size >= 3) return false
        val revision = stateRevision.incrementAndGet()
        _uiState.update { MainLibraryStateReducer.toggleQuickLaunch(it, game) }
        enqueuePersistence(revision) {
            if (shouldAdd) {
                EngineScanner.addQuickLaunch(appContext, game)
            } else {
                EngineScanner.removeQuickLaunch(appContext, game.uri)
            }
        }
        return true
    }

    /** 扫描放在 viewModelScope/FIFO 队列中，离开游戏页或 Activity 重建时不会由页面 Scope 取消。 */
    fun scanLibrary() {
        if (_uiState.value.scanning || CoverScrapeTaskManager.state.value.running) return
        val revision = stateRevision.incrementAndGet()
        _uiState.update { it.copy(scanning = true) }
        enqueuePersistence(revision = revision, finishesScan = true) {
            if (EngineScanner.loadRoots(appContext).isNotEmpty()) {
                EngineScanner.rescanLibrary(appContext)
            }
        }
    }

    fun acknowledgeScrapeEvent(eventId: Long) {
        _uiState.update { state ->
            if (state.scrapeEventId == eventId) state.copy(scrapeMessage = null) else state
        }
    }

    private fun enqueuePersistence(
        revision: Long,
        finishesScan: Boolean = false,
        action: suspend () -> Unit,
    ) {
        check(commands.trySend(PersistenceCommand(revision, finishesScan, action)).isSuccess)
    }

    private fun publishStorageSnapshot(commandRevision: Long, finishesScan: Boolean) {
        val games = EngineScanner.loadGames(appContext)
        val byUri = games.associateBy { it.uri }
        val storedQuick = EngineScanner.loadQuickLaunch(appContext)
        val normalizedRecent = EngineScanner.updateRecentGames(appContext) { current ->
            current.mapNotNull { stored -> byUri[stored.uri]?.copy(openTime = stored.openTime) }
        }
        val quick = storedQuick.mapNotNull { stored -> byUri[stored.uri] }.take(3)
        if (quick != storedQuick) EngineScanner.saveQuickLaunch(appContext, quick)

        _uiState.update { current ->
            if (commandRevision == stateRevision.get()) {
                current.copy(
                    games = games,
                    recentGames = normalizedRecent.take(10),
                    quickLaunch = quick,
                    loaded = true,
                    scanning = if (finishesScan) false else current.scanning,
                )
            } else if (finishesScan) {
                current.copy(scanning = false)
            } else {
                current
            }
        }
    }

    private data class PersistenceCommand(
        val revision: Long,
        val finishesScan: Boolean,
        val action: suspend () -> Unit,
    )

    companion object {
        private const val TAG = "MainLibraryVM"
    }
}

/** 将用户实际改动的字段合并到最新磁盘对象，避免重命名覆盖同时完成的刮削封面。 */
internal fun mergeChangedGameFields(base: ScanGame, before: ScanGame, updated: ScanGame): ScanGame = base.copy(
    title = if (updated.title != before.title) updated.title else base.title,
    engine = if (updated.engine != before.engine) updated.engine else base.engine,
    launchTarget = if (updated.launchTarget != before.launchTarget) updated.launchTarget else base.launchTarget,
    coverUri = if (updated.coverUri != before.coverUri) updated.coverUri else base.coverUri,
    coverSource = if (updated.coverSource != before.coverSource) updated.coverSource else base.coverSource,
    vndbId = if (updated.vndbId != before.vndbId) updated.vndbId else base.vndbId,
    metadataTitle = if (updated.metadataTitle != before.metadataTitle) updated.metadataTitle else base.metadataTitle,
    externalModuleAlias = if (updated.externalModuleAlias != before.externalModuleAlias) {
        updated.externalModuleAlias
    } else {
        base.externalModuleAlias
    },
    detectedRenpyVersion = if (updated.detectedRenpyVersion != before.detectedRenpyVersion) {
        updated.detectedRenpyVersion
    } else {
        base.detectedRenpyVersion
    },
    launchFile = if (updated.launchFile != before.launchFile) updated.launchFile else base.launchFile,
    openTime = if (updated.openTime != before.openTime) updated.openTime else base.openTime,
)

/** 纯状态变换单独收口，便于在本地单测覆盖页面间同步规则。 */
internal object MainLibraryStateReducer {
    fun acceptGames(state: MainLibraryUiState, games: List<ScanGame>): MainLibraryUiState {
        val snapshot = games.toList()
        val byUri = snapshot.associateBy { it.uri }
        return state.copy(
            games = snapshot,
            recentGames = state.recentGames.mapNotNull { old ->
                byUri[old.uri]?.copy(openTime = old.openTime)
            },
            quickLaunch = state.quickLaunch.mapNotNull { byUri[it.uri] },
            loaded = true,
        )
    }

    fun replaceGame(state: MainLibraryUiState, updated: ScanGame): MainLibraryUiState = state.copy(
        games = state.games.map { if (it.uri == updated.uri) updated else it },
        recentGames = state.recentGames.map {
            if (it.uri == updated.uri) updated.copy(openTime = it.openTime) else it
        },
        quickLaunch = state.quickLaunch.map { if (it.uri == updated.uri) updated else it },
    )

    fun deleteGame(state: MainLibraryUiState, uri: String): MainLibraryUiState = state.copy(
        games = state.games.filterNot { it.uri == uri },
        recentGames = state.recentGames.filterNot { it.uri == uri },
        quickLaunch = state.quickLaunch.filterNot { it.uri == uri },
    )

    fun removeRecent(state: MainLibraryUiState, uri: String): MainLibraryUiState = state.copy(
        recentGames = state.recentGames.filterNot { it.uri == uri },
    )

    fun toggleQuickLaunch(state: MainLibraryUiState, game: ScanGame): MainLibraryUiState {
        val exists = state.quickLaunch.any { it.uri == game.uri }
        val next = if (exists) {
            state.quickLaunch.filterNot { it.uri == game.uri }
        } else {
            state.quickLaunch + game
        }
        return state.copy(quickLaunch = next)
    }
}
