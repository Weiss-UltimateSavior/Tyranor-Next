package com.tyranor.next.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.R
import com.tyranor.next.core.cover.CoverScrapeTaskManager
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.game.scan.SiglusTitleFeedback
import com.tyranor.next.core.game.scan.Xs35TitleFeedback
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.sync.SaveSyncError
import com.tyranor.next.core.game.save.sync.SaveSyncException
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.cover.coverErrorMessage
import com.tyranor.next.ui.game.cleanupDeletedGame
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainLibraryUiState(
    val games: List<ScanGame> = emptyList(),
    val recentGames: List<ScanGame> = emptyList(),
    val quickLaunch: List<ScanGame> = emptyList(),
    val loaded: Boolean = false,
    val scanning: Boolean = false,
    val scrapeEventId: Long = 0L,
    val scrapeMessage: String? = null,
    val cleanupError: SaveSyncError? = null,
    val cleanupGameTitle: String? = null,
    /** 最新搜索词；与页面当前搜索词一致时才可采用 [searchHitUris]。 */
    val searchQuery: String = "",
    /** 当前游戏标题快照的 Room 命中集；null 表示尚无结果，空集合表示没有命中。 */
    val searchHitUris: Set<String>? = null,
    /** 已从 Room 确认的引擎类型；null 表示尚不能据此清除已失效的筛选条件。 */
    val confirmedEngineTypes: Set<EngineType>? = null,
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

    /** 搜索防抖任务；请求实例区分快速回到相同搜索词时仍在执行的旧查询。 */
    private var searchJob: Job? = null
    @Volatile
    private var activeSearchRequest: SearchRequest? = null

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
                    // 发布失败也不能杀死 FIFO 队列协程，否则后续所有命令（扫描/删除/搜索）全部停摆。
                    try {
                        publishStorageSnapshot(command.revision, command.finishesScan)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        Log.e(TAG, "Publish storage snapshot failed", throwable)
                    }
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
            GameLibraryFacade.libraryRevision.drop(1).collect {
                refreshFromStorage()
            }
        }
        // 刮削任务属于应用级长任务，即使 GameScreen 已离开组合，也要立即发布给首页与游戏页。
        viewModelScope.launch {
            var handledEventId = 0L
            CoverScrapeTaskManager.state.collect { task ->
                if (task.running || task.eventId == 0L || task.eventId == handledEventId) return@collect
                handledEventId = task.eventId
                val result = task.result
                val message = if (result != null) {
                    AppLocaleController.wrap(appContext).getString(
                        R.string.cover_scrape_done,
                        result.updatedCount,
                        result.skippedCount,
                        result.failedCount,
                    )
                } else {
                    task.error?.coverErrorMessage(appContext)
                }
                if (result != null) {
                    acceptPersistedGames(result.games, task.eventId, message)
                } else {
                    _uiState.update { it.copy(scrapeEventId = task.eventId, scrapeMessage = message) }
                }
                // 不在此清空任务态：刮削页/游戏页各自展示完成后经 acknowledgeScrapeEvent 清空，
                // 避免 Main.immediate 收集器先于 UI 帧清空导致页面结果提示被吞（H1）。
            }
        }
        refreshFromStorage()
        // System4 一次性迁移：旧版本把 System4 游戏存成了 XSYS35（增量扫描不改判）。
        // 只对库中 XSYS35 条目定点重检测（不整库重扫，不重置标题/手动改动、不删除游戏）。
        // 所有目标都得到确定结果（或没有目标）才置位；存在暂不可达 / 读取失败的条目时不置位，
        // 下次启动仅重试仍为 XSYS35 的条目（成本低）。
        viewModelScope.launch(Dispatchers.IO) {
            if (AppSettingsStore.isXs4RescanMigrationDone(appContext)) return@launch
            try {
                val report = EngineScanner.redetectXs35Games(appContext)
                if (report.changed > 0) refreshFromStorage()
                if (report.complete) AppSettingsStore.markXs4RescanMigrationDone(appContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.e(TAG, "Xs4 redetect migration failed", throwable)
            }
        }
        // 标题回写导入：引擎宿主首次启动成功后写入解析出的标题，此处消费并差量落库
        viewModelScope.launch(Dispatchers.IO) {
            SiglusTitleFeedback.import(appContext)
            Xs35TitleFeedback.import(appContext)
            refreshFromStorage()
        }
    }

    fun refreshFromStorage() {
        enqueuePersistence(revision = stateRevision.get()) { }
    }

    /**
     * 回到前台时的库刷新（与 [refreshFromStorage] 同一入口）：先补一次标题回写导入
     * （引擎子进程在后台写入 prefs，幂等、无待处理键时快速返回），再刷新列表。
     * 单次 launch 内顺序执行，导入先于发布，避免「刷新已完成、标题仍旧」的竞态。
     */
    fun onForeground() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Xs35TitleFeedback.import(appContext)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    Log.e(TAG, "Xs35 title feedback import failed", throwable)
                }
            }
            refreshFromStorage()
        }
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
            GameLibraryFacade.updateGames(appContext) { games ->
                games.map { current ->
                    if (current.uri == updated.uri) {
                        mergeChangedGameFields(current, before, updated).also { persisted = it }
                    } else {
                        current
                    }
                }
            }
            GameLibraryFacade.updateRecentGames(appContext) { recent ->
                recent.map {
                    if (it.uri == persisted.uri) persisted.copy(openTime = it.openTime) else it
                }
            }
            GameLibraryFacade.updateQuickLaunch(appContext) { quick ->
                quick.map { if (it.uri == persisted.uri) persisted else it }
            }
        }
    }

    /**
     * 手动添加游戏（PC / 安卓游戏，不参与扫描）：同 uri 已存在时返回 false；
     * 与扫描/删除共用 FIFO 命令队列落库，避免「添加后立刻重扫」互相覆盖。
     * 立即发布采用「现有列表 + 新条目」而非整表替换，避免短暂清空派生列表（最近/快捷启动）。
     */
    fun addManualGame(game: ScanGame): Boolean {
        if (_uiState.value.games.any { it.uri == game.uri }) return false
        val revision = stateRevision.incrementAndGet()
        _uiState.update { MainLibraryStateReducer.acceptGames(it, it.games + game) }
        enqueuePersistence(revision) {
            GameLibraryFacade.registerGames(appContext) { games ->
                if (games.any { it.uri == game.uri }) games else games + game
            }
        }
        return true
    }

    fun deleteGame(target: ScanGame) {
        val revision = stateRevision.incrementAndGet()
        _uiState.update { MainLibraryStateReducer.deleteGame(it, target.uri) }
        enqueuePersistence(revision) {
            try {
                cleanupDeletedGame(appContext, target)
                withContext(NonCancellable) {
                    GameLibraryFacade.removeGame(appContext, target.uri)
                }
            } catch (failure: SaveSyncException) {
                // Keep the persistent library entry as the retry route when cleanup is blocked.
                _uiState.update { it.copy(cleanupError = failure.code, cleanupGameTitle = target.title) }
            }
        }
    }

    fun dismissCleanupError() {
        _uiState.update { it.copy(cleanupError = null, cleanupGameTitle = null) }
    }

    fun removeRecentGame(target: ScanGame) {
        val revision = stateRevision.incrementAndGet()
        _uiState.update { MainLibraryStateReducer.removeRecent(it, target) }
        enqueuePersistence(revision) {
            GameLibraryFacade.removeRecentGame(appContext, target.uri, target.openTime)
        }
    }

    /** 返回 false 表示快捷启动槽位均已占用。 */
    fun toggleQuickLaunch(game: ScanGame): Boolean {
        val current = _uiState.value
        val shouldAdd = current.quickLaunch.none { it.uri == game.uri }
        if (shouldAdd && current.quickLaunch.size >= GameLibraryFacade.MAX_QUICK_LAUNCH) return false
        val revision = stateRevision.incrementAndGet()
        _uiState.update { MainLibraryStateReducer.toggleQuickLaunch(it, game) }
        enqueuePersistence(revision) {
            if (shouldAdd) {
                GameLibraryFacade.addQuickLaunch(appContext, game)
            } else {
                GameLibraryFacade.removeQuickLaunch(appContext, game.uri)
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
            if (GameLibraryFacade.loadRoots(appContext).isNotEmpty()) {
                EngineScanner.rescanLibrary(appContext)
            }
        }
    }

    fun acknowledgeScrapeEvent(eventId: Long) {
        _uiState.update { state ->
            if (state.scrapeEventId == eventId) state.copy(scrapeMessage = null) else state
        }
        // 主界面已展示结果：清空共享任务态，避免后续进入刮削页重复弹同一结果
        CoverScrapeTaskManager.clearFinished(eventId)
    }

    /**
     * 游戏页搜索：防抖后经 FIFO 队列查询 Room 的标题命中集，与待落库写互斥。
     * 新搜索词立即使旧命中集失效，等待期间页面用内存快照过滤。
     */
    fun onSearchQueryChanged(query: String) {
        searchJob?.cancel()
        activeSearchRequest = null
        _uiState.update { it.copy(searchQuery = query, searchHitUris = null) }
        if (query.isBlank()) return
        val request = SearchRequest(query)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            activeSearchRequest = request
            enqueuePersistence(revision = stateRevision.get()) { }
        }
    }

    private fun enqueuePersistence(
        revision: Long,
        finishesScan: Boolean = false,
        action: suspend () -> Unit,
    ) {
        check(commands.trySend(PersistenceCommand(revision, finishesScan, action)).isSuccess)
    }

    private suspend fun publishStorageSnapshot(commandRevision: Long, finishesScan: Boolean) {
        GameLibraryRepository.awaitPendingWrites()
        val games = GameLibraryFacade.loadGames(appContext)
        val byUri = games.associateBy { it.uri }
        val storedQuick = GameLibraryFacade.loadQuickLaunch(appContext)
        val normalizedRecent = GameLibraryFacade.updateRecentGames(appContext) { current ->
            current.mapNotNull { stored -> byUri[stored.uri]?.copy(openTime = stored.openTime) }
        }
        val quick = storedQuick.mapNotNull { stored -> byUri[stored.uri] }.take(GameLibraryFacade.MAX_QUICK_LAUNCH)
        if (quick != storedQuick) GameLibraryFacade.saveQuickLaunch(appContext, quick)

        val confirmedEngines = if (commandRevision == stateRevision.get()) {
            confirmEngineTypes(games)
        } else {
            null
        }
        // 引擎确认可能等待并发写完成，期间门面缓存可能更新或因写失败而回源。
        if (commandRevision == stateRevision.get() && GameLibraryFacade.loadGames(appContext) !== games) {
            enqueuePersistence(commandRevision, finishesScan) { }
            return
        }
        _uiState.update { current ->
            if (commandRevision == stateRevision.get()) {
                MainLibraryStateReducer.acceptStorageSnapshot(
                    current, games, normalizedRecent, quick, finishesScan, confirmedEngines,
                )
            } else if (finishesScan) {
                current.copy(scanning = false)
            } else {
                current
            }
        }

        // 搜索激活时随库更新刷新命中集，例如重命名后标题可能不再匹配。
        val request = activeSearchRequest ?: return
        if (commandRevision != stateRevision.get()) return
        val hits = GameLibraryRepository.searchGameUrisConsistent(appContext, request.query)
        // 查询等待落库时，写失败可能使门面缓存回源到另一份标题快照。
        if (!sameSearchTitles(games, GameLibraryFacade.loadGames(appContext))) {
            // 用回源后的库刷新页面；较新的命令会自行发布快照。
            if (commandRevision == stateRevision.get()) enqueuePersistence(commandRevision) { }
            return
        }
        _uiState.update { current ->
            if (activeSearchRequest === request && commandRevision == stateRevision.get() &&
                current.searchQuery == request.query && sameSearchTitles(current.games, games)
            ) {
                current.copy(searchHitUris = hits)
            } else {
                current
            }
        }
    }

    private suspend fun confirmEngineTypes(games: List<ScanGame>): Set<EngineType>? {
        val previous = _uiState.value.confirmedEngineTypes
        val cached = games.mapTo(mutableSetOf()) { it.engine }
        if (cached == previous) return previous
        return try {
            // 门面的库快照先于异步写入更新。Room 确认后才允许自动清除已选引擎。
            GameLibraryRepository.loadEngineTypesConsistent(appContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Confirm library engine types failed", failure)
            previous
        }
    }

    private class SearchRequest(val query: String)

    private data class PersistenceCommand(
        val revision: Long,
        val finishesScan: Boolean,
        val action: suspend () -> Unit,
    )

    companion object {
        private const val TAG = "MainLibraryVM"
        private const val SEARCH_DEBOUNCE_MS = 150L
    }
}

/** URI 和标题按快照顺序比较，忽略封面、打开时间等元数据。 */
private fun sameSearchTitles(first: List<ScanGame>, second: List<ScanGame>): Boolean =
    first === second || (first.size == second.size && first.indices.all { index ->
        first[index].uri == second[index].uri && first[index].title == second[index].title
    })

/** 将用户改动的游戏元数据合并到最新磁盘对象；最近打开时间由游玩记录入口管理。 */
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
    runtimeModulePackage = if (updated.runtimeModulePackage != before.runtimeModulePackage) {
        updated.runtimeModulePackage
    } else {
        base.runtimeModulePackage
    },
    launchFile = if (updated.launchFile != before.launchFile) updated.launchFile else base.launchFile,
)

/** 纯状态变换单独收口，便于在本地单测覆盖页面间同步规则。 */
internal object MainLibraryStateReducer {
    fun acceptStorageSnapshot(
        state: MainLibraryUiState,
        games: List<ScanGame>,
        recentGames: List<ScanGame>,
        quickLaunch: List<ScanGame>,
        finishesScan: Boolean,
        confirmedEngineTypes: Set<EngineType>?,
    ): MainLibraryUiState = state.copy(
        games = games,
        // 完整最近列表（存储侧 RECENT_LIMIT = 20 条）；展示数量由首页按布局决定。
        recentGames = recentGames,
        quickLaunch = quickLaunch,
        loaded = true,
        scanning = if (finishesScan) false else state.scanning,
        searchHitUris = state.searchHitUris?.takeIf { sameSearchTitles(state.games, games) },
        confirmedEngineTypes = confirmedEngineTypes,
    )

    fun acceptGames(state: MainLibraryUiState, games: List<ScanGame>): MainLibraryUiState {
        // 刮削结果可能在清除记录落库之前拍下快照；游玩时间由后续库快照校准。
        val currentTimes = state.games.associate { it.uri to it.openTime }
        val snapshot = games.map { game ->
            val openTime = currentTimes[game.uri]
            if (openTime != null && openTime != game.openTime) game.copy(openTime = openTime) else game
        }
        val byUri = snapshot.associateBy { it.uri }
        return state.copy(
            games = snapshot,
            recentGames = state.recentGames.mapNotNull { old ->
                byUri[old.uri]?.copy(openTime = old.openTime)
            },
            quickLaunch = state.quickLaunch.mapNotNull { byUri[it.uri] },
            loaded = true,
            searchHitUris = state.searchHitUris?.takeIf { sameSearchTitles(state.games, snapshot) },
        )
    }

    fun replaceGame(state: MainLibraryUiState, updated: ScanGame): MainLibraryUiState {
        fun replaceIn(games: List<ScanGame>) = games.map { game ->
            if (game.uri == updated.uri) updated.copy(openTime = game.openTime) else game
        }
        val games = replaceIn(state.games)
        return state.copy(
            games = games,
            recentGames = replaceIn(state.recentGames),
            quickLaunch = state.quickLaunch.map { if (it.uri == updated.uri) updated else it },
            searchHitUris = state.searchHitUris?.takeIf { sameSearchTitles(state.games, games) },
        )
    }

    fun deleteGame(state: MainLibraryUiState, uri: String): MainLibraryUiState {
        val engine = state.games.firstOrNull { it.uri == uri }?.engine
        return state.copy(
            games = state.games.filterNot { it.uri == uri },
            recentGames = state.recentGames.filterNot { it.uri == uri },
            quickLaunch = state.quickLaunch.filterNot { it.uri == uri },
            searchHitUris = state.searchHitUris?.minus(uri),
            // 用户可在新条目第一次落库前移除它。这时等下一次 Room 确认再决定该引擎是否消失。
            confirmedEngineTypes = state.confirmedEngineTypes?.takeUnless { engine != null && engine !in it },
        )
    }

    fun removeRecent(state: MainLibraryUiState, target: ScanGame): MainLibraryUiState {
        fun matches(game: ScanGame) = game.uri == target.uri && game.openTime == target.openTime
        val index = state.games.indexOfFirst { it.openTime != 0L && matches(it) }
        val recent = state.recentGames.filterNot(::matches)
        if (index < 0 && recent.size == state.recentGames.size) return state
        return state.copy(
            games = if (index < 0) state.games else state.games.toMutableList().apply {
                this[index] = this[index].copy(openTime = 0L)
            },
            recentGames = recent,
        )
    }

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
